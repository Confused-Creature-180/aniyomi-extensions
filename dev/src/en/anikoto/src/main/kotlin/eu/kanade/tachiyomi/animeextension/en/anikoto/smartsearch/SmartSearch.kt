package eu.kanade.tachiyomi.animeextension.en.anikoto.smartsearch

import eu.kanade.tachiyomi.animeextension.en.anikoto.AnikotoLog
import eu.kanade.tachiyomi.animeextension.en.anikoto.video.WebViewFetcher
import java.net.URLEncoder

/**
 * ★ session 51: Smart Search module — AI-powered anime search via Google AI Search.
 *
 * This module is self-contained and can be easily removed or transferred.
 * To remove smart search:
 * 1. Delete this file and [SmartSearchSettings]
 * 2. Remove the `smartSearch` field and `getSearchAnime()` override from Anikoto.kt
 * 3. Remove the smart search settings from AnikotoSettings.kt
 *
 * ## What it does
 * Resolves descriptive queries or misspelled titles to a concrete anime title
 * using Google AI Search (udm=50), then returns that title for normal search.
 *
 * ## Two modes
 * 1. Descriptive: "the anime with a russian girl" → AI returns "Alya Sometimes..."
 * 2. Correction: "narutp" → AI corrects to "Naruto"
 *
 * ## Triggering
 * Smart search triggers when:
 * - Toggle is ON, AND
 * - Query is non-empty, AND
 * - Either: phrase is empty (ALL searches use AI), OR query starts with phrase + space
 *
 * @property webViewFetcher The WebViewFetcher instance (shared with video pipeline)
 */
class SmartSearch(
    private val webViewFetcher: WebViewFetcher,
) {

    /** Cache for pagination: last query (phrase stripped) → resolved title. */
    private var cachedQuery: String = ""
    private var cachedTitle: String = ""

    /**
     * Check if smart search should trigger for this query.
     *
     * @param query The raw user query
     * @param enabled Whether the smart search toggle is ON
     * @param phrase The activation phrase (empty = all searches use AI)
     * @return true if smart search should trigger
     */
    fun shouldTrigger(query: String, enabled: Boolean, phrase: String): Boolean {
        if (!enabled) return false
        val queryTrimmed = query.trim()
        if (queryTrimmed.isEmpty()) return false

        val phraseTrimmed = phrase.trim()
        if (phraseTrimmed.isEmpty()) return true // ★ empty phrase = all searches use AI

        // ★ session 51: phrase must be followed by a space (or be the entire query)
        // This prevents "s" from matching "shock" — the phrase must be a separate word.
        if (!queryTrimmed.startsWith(phraseTrimmed, ignoreCase = true)) return false

        // Check what comes after the phrase
        val afterPhrase = queryTrimmed.substring(phraseTrimmed.length)
        // If nothing after phrase → empty query → don't trigger
        // If space after phrase → valid (phrase is a separate word)
        // If non-space after phrase → phrase is part of a word → don't trigger
        return afterPhrase.isEmpty() || afterPhrase.startsWith(" ")
    }

    /**
     * Strip the activation phrase from the start of the query.
     * If phrase is empty, returns the query as-is.
     *
     * @param query The raw user query
     * @param phrase The activation phrase
     * @return The query with phrase removed from start (trimmed)
     */
    fun stripPhrase(query: String, phrase: String): String {
        val phraseTrimmed = phrase.trim()
        if (phraseTrimmed.isEmpty()) return query.trim()

        val queryTrimmed = query.trim()
        if (queryTrimmed.startsWith(phraseTrimmed, ignoreCase = true)) {
            val afterPhrase = queryTrimmed.substring(phraseTrimmed.length)
            return afterPhrase.trim()
        }
        return queryTrimmed
    }

    /**
     * Resolve a query to an anime title via Google AI Search.
     *
     * 1. Craft Google AI URL (udm=50 triggers AI Overview)
     * 2. Scrape rendered page text via WebViewFetcher
     * 3. Extract the anime title using 3 strategies
     *
     * @param query The descriptive query or misspelled title
     * @return The resolved anime title, or null on failure
     */
    fun resolve(query: String): String? {
        if (query.isBlank()) {
            AnikotoLog.w("SmartSearch: empty query")
            return null
        }

        AnikotoLog.i("SmartSearch: resolving query: \"$query\"")

        val searchQuery = buildPrompt(query)
        val encodedQuery = URLEncoder.encode(searchQuery, "UTF-8")
        val googleUrl = "https://www.google.com/search?q=$encodedQuery&udm=50&hl=en"
        AnikotoLog.d("SmartSearch: Google URL: ${AnikotoLog.trunc(googleUrl, 120)}")

        val renderedText = try {
            webViewFetcher.fetchRenderedText(googleUrl, timeoutMs = 20_000)
        } catch (e: Exception) {
            AnikotoLog.e("SmartSearch: scrape failed", e)
            return null
        }

        if (renderedText.isBlank()) {
            AnikotoLog.w("SmartSearch: Google scrape returned empty text")
            return null
        }

        AnikotoLog.d("SmartSearch: Google rendered text (${renderedText.length} chars, first 500: ${AnikotoLog.trunc(renderedText, 500)})")

        val title = extractAnimeTitle(renderedText)
        if (title == null) {
            AnikotoLog.w("SmartSearch: could not extract title from Google AI text")
            return null
        }

        AnikotoLog.i("SmartSearch: extracted title: \"$title\"")
        return title
    }

    /**
     * ★ session 51: Build the AI prompt with smarter instructions.
     *
     * The prompt is wrapped in brackets and includes:
     * - The user's query
     * - Instructions to return only ONE English anime title
     * - Scenario handling: if user gives a genre, return one anime from that genre
     * - Instructions to handle descriptions, misspellings, and vague queries
     */
    private fun buildPrompt(query: String): String {
        return "$query anime. " +
            "[Respond with only the English anime title, nothing else. " +
            "If the query describes an anime, give the title of the anime being described. " +
            "If the query has spelling mistakes, correct them and give the proper title. " +
            "If the query mentions a genre or theme, give one popular anime from that genre. " +
            "If the query is vague, give the most likely anime match. " +
            "Always respond with exactly one anime title, no explanations, no lists.]"
    }

    /**
     * Extract an anime title from Google AI's rendered text.
     * Uses 3 strategies in priority order:
     * 1. "is titled [X]" / "is called [X]" / "is named [X]" — highest confidence
     * 2. Quoted text ("Title" or 'Title' or curly quotes)
     * 3. First capitalized multi-word phrase after "Search Results"
     *
     * Each strategy strips parentheticals and checks word count (2-12 words).
     */
    private fun extractAnimeTitle(text: String): String? {
        // ── Strategy 1: "is titled [X]" (highest priority) ──
        val titledPattern = Regex(
            """(?:is\s+titled|is\s+called|is\s+named|is\s+known\s+as)\s+([A-Z][^\n.!?]{2,80}?)(?:\s*[.\n!?]|$)"""
        )
        for (match in titledPattern.findAll(text)) {
            val raw = match.groupValues[1].trim()
            val title = stripParenthetical(raw)
            val wordCount = title.split(Regex("\\s+")).filter { it.isNotEmpty() }.size
            AnikotoLog.d("SmartSearch: strategy1 match: \"$raw\" → \"$title\" ($wordCount words)")
            if (wordCount in 2..12) return title
        }

        // ── Strategy 2: Quoted text ──
        val quotedPattern = Regex("""[""'']([^"'']{2,80})[""']""")
        for (match in quotedPattern.findAll(text)) {
            val raw = match.groupValues[1].trim()
            if (raw.contains("(") && raw.endsWith(")")) {
                AnikotoLog.d("SmartSearch: strategy2 skip (parenthetical): \"$raw\"")
                continue
            }
            val title = stripParenthetical(raw)
            val wordCount = title.split(Regex("\\s+")).filter { it.isNotEmpty() }.size
            AnikotoLog.d("SmartSearch: strategy2 match: \"$raw\" → \"$title\" ($wordCount words)")
            if (wordCount in 2..12) return title
        }

        // ── Strategy 3: First capitalized multi-word phrase after "Search Results" ──
        val lines = text.lines()
        var inResults = false
        val uiWords = setOf("Sign", "AI", "All", "Images", "Videos", "News", "Books", "Finance",
            "Search", "Results", "Mode", "sites", "Learn")

        for (line in lines) {
            if ("Search Results" in line || "AI Overview" in line) {
                inResults = true
                continue
            }
            if (!inResults) continue
            val trimmed = line.trim()
            if (trimmed.length < 5) continue
            if ("Respond with only" in trimmed || "anime." in trimmed.lowercase()) continue

            val words = trimmed.split(Regex("\\s+"))
            if (words.size < 2) continue
            if (words[0] in uiWords) continue
            if (words[0].firstOrNull()?.isUpperCase() != true) continue

            val phrase = mutableListOf<String>()
            for (w in words) {
                val clean = w.trim()
                if (clean.isEmpty()) continue
                if (clean.endsWith(".") || clean.endsWith("!") || clean.endsWith("?")) {
                    val stripped = clean.trimEnd('.', '!', '?')
                    if (stripped.isNotEmpty()) phrase.add(stripped)
                    break
                }
                if (clean == "—" || clean == "is" || clean == "was") break
                phrase.add(clean)
                if (phrase.size >= 12) break
            }

            if (phrase.size in 2..12) {
                val title = stripParenthetical(phrase.joinToString(" "))
                AnikotoLog.d("SmartSearch: strategy3 match: \"${phrase.joinToString(" ")}\" → \"$title\"")
                return title
            }
        }

        AnikotoLog.d("SmartSearch: all 3 strategies failed")
        return null
    }

    /** Remove trailing and leading parenthetical groups: "Title (suffix)" → "Title" */
    private fun stripParenthetical(s: String): String {
        var result = s.trim()
        result = result.replace(Regex("\\s*\\([^)]*\\)\\s*$"), "").trim()
        result = result.replace(Regex("^\\s*\\([^)]*\\)\\s*"), "").trim()
        return result
    }

    /**
     * Get the cached title for a query (for pagination).
     * Returns null if not cached or if query doesn't match cache.
     */
    fun getCachedTitle(query: String, page: Int): String? {
        if (page > 1 && query == cachedQuery && cachedTitle.isNotEmpty()) {
            AnikotoLog.i("SmartSearch: using cached title \"$cachedTitle\" for page $page")
            return cachedTitle
        }
        return null
    }

    /** Cache a query→title mapping. */
    fun cacheTitle(query: String, title: String) {
        cachedQuery = query
        cachedTitle = title
    }

    /** Pre-warm the Google WebView for smart search. */
    fun warmUp() {
        webViewFetcher.warmUpGoogleWebView()
    }
}
