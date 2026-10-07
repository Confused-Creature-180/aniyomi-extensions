package eu.kanade.tachiyomi.animeextension.en.anikoto.smartsearch

import eu.kanade.tachiyomi.animeextension.en.anikoto.AnikotoLog
import eu.kanade.tachiyomi.animeextension.en.anikoto.video.WebViewFetcher
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * ★ session 51/56: Smart Search module — AI-powered anime search.
 *
 * This module is self-contained and can be easily removed or transferred.
 * To remove smart search:
 * 1. Delete this file
 * 2. Remove the `smartSearch` field and `getSearchAnime()` override from Anikoto.kt
 * 3. Remove the smart search settings from AnikotoSettings.kt
 *
 * ## What it does
 * Resolves descriptive queries or misspelled titles to a concrete anime title,
 * then returns that title for normal search.
 *
 * ## Two engines (user-selectable in settings)
 * 1. **Gemini API** — official Google Generative Language REST API
 *    (generativelanguage.googleapis.com). Needs a free API key from
 *    https://aistudio.google.com/apikey. Robust JSON API — no scraping, no bot walls.
 *    Model is user-selectable (default: Gemini 3.5 Flash-Lite, session 57) plus a
 *    free-text custom model id. Thinking is disabled for speed (with an automatic
 *    retry without the thinking config for models that reject it).
 * 2. **Google AI Search (legacy)** — scrapes the rendered text of
 *    google.com/search?q=…&udm=50 via WebView. Works without a key, but Google
 *    actively fights automated browsers, so it fails intermittently.
 *    ★ session 57 root-cause fix: the old code sent the whole LLM prompt (with the
 *    bracketed instructions) as the literal search query — that hijacked Google into
 *    the "AI Mode conversation" UI whose layout the extractor could not read (the
 *    exact cause of "no anime title could be read" errors). The engine now sends a
 *    CLEAN query, waits for the streamed AI answer to stabilize, uses a much wider
 *    extraction strategy set, and on failure copies the whole rendered response to
 *    the clipboard for debugging.
 *    ★ session 58: BOTH engines now ask the AI to wrap the title in rare
 *    `[{[Title]}]` brackets (Gemini via the prompt, Google via a SHORT suffix
 *    appended to the clean query — verified live 2026-09-13 that AI Mode honors
 *    it). The lenient bracket parser is strategy S0; the sentence strategies were
 *    also widened (the 80-char cap previously missed answers like
 *    "The anime you are looking for is X (Japanese title: …)." — the user's
 *    reported failure).
 *
 * ## Engine selection (settings)
 * - `gemini` (default): Gemini only.
 * - `google`: Google scrape only.
 * - legacy stored `auto`: resolved dynamically — Gemini if a key is set, else Google.
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

    /** ★ session 56: dedicated client for the Gemini REST API (short, predictable timeouts). */
    private val geminiClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // ── Triggering ────────────────────────────────────────────────────────

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

    // ── Resolution (session 56: result-based with classified errors) ─────

    /**
     * ★ session 56: Result of an AI resolution attempt.
     * @property userMessage A specific, user-facing explanation of WHAT failed and WHY
     *   (shown as a toast). Never generic when a specific reason is known.
     */
    sealed class ResolveResult {
        data class Success(val title: String) : ResolveResult()
        data class Failure(val userMessage: String, val detail: String? = null) : ResolveResult()
    }

    /** Engine ids — mirror AnikotoSettings.PREF_SMART_ENGINE_* values. */
    object Engine {
        const val AUTO = "auto"
        const val GEMINI = "gemini"
        const val GOOGLE = "google"
    }

    /**
     * Resolve a query to an anime title using the selected engine.
     *
     * @param query The descriptive query or misspelled title
     * @param engine One of [Engine.GEMINI]/[Engine.GOOGLE] (legacy [Engine.AUTO]
     *        values are still accepted and resolved dynamically)
     * @param geminiApiKey The user's Gemini API key (may be blank)
     * @param geminiModel The Gemini model id (e.g. "gemini-3.5-flash-lite")
     * @return [ResolveResult.Success] with the title, or [ResolveResult.Failure]
     *         with a specific user-facing reason. [ResolveResult.Failure.detail]
     *         carries the raw engine response for clipboard debugging.
     */
    fun resolve(query: String, engine: String, geminiApiKey: String, geminiModel: String): ResolveResult {
        if (query.isBlank()) {
            return ResolveResult.Failure("Smart search: empty query")
        }
        AnikotoLog.i("SmartSearch: resolving (engine=$engine) query: \"$query\"")

        return when (engine) {
            Engine.GEMINI -> resolveWithGemini(query, geminiApiKey, geminiModel)
            Engine.GOOGLE -> resolveWithGoogle(query)
            else -> { // AUTO
                if (geminiApiKey.isNotBlank()) {
                    val geminiResult = resolveWithGemini(query, geminiApiKey, geminiModel)
                    if (geminiResult is ResolveResult.Success) return geminiResult
                    // ★ auto mode: Gemini failed → transparently try the Google path
                    // (still report BOTH reasons so the user knows what happened)
                    val googleResult = resolveWithGoogle(query)
                    if (googleResult is ResolveResult.Success) {
                        AnikotoLog.i("SmartSearch: auto fallback Gemini→Google succeeded")
                        return googleResult
                    }
                    val geminiWhy = (geminiResult as ResolveResult.Failure).userMessage
                    val googleWhy = (googleResult as ResolveResult.Failure).userMessage
                    return ResolveResult.Failure(
                        "Gemini failed ($geminiWhy); Google fallback failed ($googleWhy)",
                    )
                }
                resolveWithGoogle(query)
            }
        }
    }

    // ── Engine 1: Gemini API ──────────────────────────────────────────────

    /**
     * Resolve via the official Gemini REST API.
     * POST https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent
     * Auth: `x-goog-api-key` header.
     *
     * ★ session 57: thinking disabled (fast, deterministic single-title answers);
     * models that reject the thinking config are retried once without it.
     * maxOutputTokens raised 512 → 2048 so thinking-enabled models can still emit text.
     * ★ session 58 FIX (Test Connection HTTP 400): thinkingConfig is ONLY sent to
     * gemini-2.5* models — the 3.x family rejects `thinkingBudget` with a bare
     * "Request contains an invalid argument." (no "thinking" keyword, so the old
     * message-based retry never fired; verified live with a real key). On ANY 400
     * with thinkingConfig attached we now retry once without it.
     */
    private fun resolveWithGemini(query: String, apiKey: String, model: String): ResolveResult {
        if (apiKey.isBlank()) {
            return ResolveResult.Failure(
                "Gemini API key is not set — add one in Settings → Smart Search",
                "blank key",
            )
        }
        val url = "https://generativelanguage.googleapis.com/v1beta/models/${model.trim()}:generateContent"
        AnikotoLog.i("SmartSearch: Gemini resolve via $model")
        return try {
            val prompt = buildPrompt(query)
            val tryThinking = model.trim().startsWith("gemini-2.5")
            var resp = postGemini(geminiClient, url, apiKey, buildGeminiRequestBody(prompt, withThinkingConfig = tryThinking))
            if (resp.first == 400 && tryThinking) {
                AnikotoLog.i("SmartSearch: model rejected thinkingConfig — retrying without it")
                resp = postGemini(geminiClient, url, apiKey, buildGeminiRequestBody(prompt, withThinkingConfig = false))
            }
            val (code, respBody) = resp
            if (code in 200..299) {
                parseGeminiSuccess(respBody)
                    ?.let { return ResolveResult.Success(it) }
                    ?: ResolveResult.Failure(
                        "Gemini replied but no anime title could be read from its answer — try another model in Settings",
                        respBody.take(600),
                    )
            } else {
                ResolveResult.Failure(describeGeminiHttpError(code, respBody, model), respBody.take(600))
            }
        } catch (e: java.io.IOException) {
            AnikotoLog.e("SmartSearch: Gemini network error", e)
            ResolveResult.Failure(
                "Could not reach the Gemini API (network error: ${e.javaClass.simpleName})",
                e.message,
            )
        } catch (e: Exception) {
            AnikotoLog.e("SmartSearch: Gemini unexpected error", e)
            ResolveResult.Failure("Gemini request failed unexpectedly: ${e.message?.take(80)}", null)
        }
    }

    /** Extract the anime title from a successful generateContent response.
     *  ★ session 57: skips model "thought" parts and logs the finish reason. */
    private fun parseGeminiSuccess(body: String): String? {
        return try {
            val root = json.parseToJsonElement(body).jsonObject
            val candidates = root["candidates"] as? JsonArray ?: return null
            val first = candidates.firstOrNull() as? JsonObject ?: return null
            val content = first["content"] as? JsonObject ?: return null
            val parts = content["parts"] as? JsonArray ?: return null
            val text = parts.mapNotNull { p ->
                val o = p as? JsonObject ?: return@mapNotNull null
                val thought = (o["thought"]?.jsonPrimitive?.content) == "true"
                if (thought) null else o["text"]?.jsonPrimitive?.content
            }.joinToString(" ").trim()
            if (text.isBlank()) {
                val finish = try { first["finishReason"]?.jsonPrimitive?.content } catch (_: Exception) { null }
                AnikotoLog.d("SmartSearch: Gemini returned empty text (finishReason=$finish)")
                return null
            }
            AnikotoLog.d("SmartSearch: Gemini raw answer: ${AnikotoLog.trunc(text, 200)}")
            // ★ session 58: the prompt asks for [{[Title]}] — try bracket extraction first
            extractBracketTitle(text)?.let { return it }
            cleanTitle(text)
        } catch (e: Exception) {
            AnikotoLog.e("SmartSearch: Gemini response parse failed", e)
            null
        }
    }

    /**
     * ★ session 57: shared Gemini plumbing (used by both the instance engine and
     * the settings test button). Maps Gemini HTTP errors to SPECIFIC user-facing
     * messages. Error shapes verified live:
     * `{"error":{"code":400,"message":"API key not valid. …","status":"INVALID_ARGUMENT"}}`
     * `{"error":{"code":400,"message":"User location is not supported …","status":"FAILED_PRECONDITION"}}`
     */
    companion object {

        private val testClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
        }

        /** Execute a Gemini generateContent call. @return HTTP code to response body. */
        fun postGemini(client: OkHttpClient, url: String, apiKey: String, body: String): Pair<Int, String> {
            val req = Request.Builder()
                .url(url)
                .header("x-goog-api-key", apiKey.trim())
                .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            client.newCall(req).execute().use { resp ->
                return resp.code to resp.body?.string().orEmpty()
            }
        }

        /** Build the generateContent JSON body (prompt + deterministic generation config).
         *  ★ session 58: thinkingConfig is attached ONLY when [withThinkingConfig] — it is
         *  valid on the gemini-2.5 family and REJECTED by the 3.x family with a bare
         *  "Request contains an invalid argument." (verified live 2026-09-13 with a real
         *  key: thinkingBudget=0 on gemini-3.5-flash-lite → 400 INVALID_ARGUMENT, while
         *  the same payload without thinkingConfig passes validation everywhere).
         *  This was the root cause of the user's "Test connection: HTTP 400" report. */
        fun buildGeminiRequestBody(prompt: String, withThinkingConfig: Boolean): String {
            val payload = buildJsonObject {
                put("contents", buildJsonArray {
                    add(buildJsonObject {
                        put("role", kotlinx.serialization.json.JsonPrimitive("user"))
                        put("parts", buildJsonArray {
                            add(buildJsonObject { put("text", kotlinx.serialization.json.JsonPrimitive(prompt)) })
                        })
                    })
                })
                put("generationConfig", buildJsonObject {
                    put("temperature", kotlinx.serialization.json.JsonPrimitive(0.1))
                    put("maxOutputTokens", kotlinx.serialization.json.JsonPrimitive(2048))
                    if (withThinkingConfig) {
                        put("thinkingConfig", buildJsonObject {
                            put("thinkingBudget", kotlinx.serialization.json.JsonPrimitive(0))
                        })
                    }
                })
            }
            return payload.toString()
        }

        /**
         * ★ session 58: LENIENT `[{[ Title ]}]` bracket extraction (strategy S0, shared by
         * both engines). Tolerates Google AI Mode's imperfect compliance seen live:
         * "[{[Naruto]}]", "[{[\nNaruto , }] :", "[{[ Attack on Titan ]}]".
         * Skips empty/short captures (the query echo contains a literal "[{[ ]}]" from the
         * instruction suffix) and lowercase single words ("[{[yes]}]" junk guard).
         */
        fun extractBracketTitle(text: String): String? {
            val pattern = Regex("""\[\{\[([^\]\}]{2,120}?)[\]\}]""")
            for (match in pattern.findAll(text)) {
                var s = match.groupValues[1].replace(Regex("\\s+"), " ").trim()
                s = s.trim(' ', ',', '.', ':', ';', '!', '?', '"', '\'', '\u201C', '\u201D')
                if (s.length < 2 || s.length > 80) continue
                val words = s.split(Regex("\\s+")).filter { it.isNotEmpty() }
                if (words.isEmpty() || words.size > 12) continue
                if (words.size == 1 && words[0].firstOrNull()?.isUpperCase() != true) continue
                return s
            }
            return null
        }

        /** Pull the human-readable message out of a Gemini error body (null if none). */
        fun geminiApiErrorMessage(body: String): String? = try {
            (Json { ignoreUnknownKeys = true; isLenient = true }
                .parseToJsonElement(body).jsonObject["error"]?.jsonObject?.get("message")
                ?.jsonPrimitive?.content)?.take(160)
        } catch (_: Exception) { null }

        /** Map a Gemini HTTP error to a specific, user-facing message.
         *  ★ session 58: includes the model id in 400/404 messages and hints at the
         *  exact-API-name requirement for custom model ids. */
        fun describeGeminiHttpError(code: Int, body: String, model: String): String {
            val apiMessage = geminiApiErrorMessage(body)
            val m = model.trim()
            val prefix = when (code) {
                400 -> when {
                    apiMessage?.contains("API key not valid", true) == true ->
                        "Gemini API key is invalid (check Settings → Smart Search)"
                    apiMessage?.contains("location is not supported", true) == true ->
                        "Google blocks the Gemini API for this network/region (country or VPN restriction) — try another network"
                    else -> "Gemini rejected the request for model \"$m\" (HTTP 400) — if \"$m\" is a custom model ID it must exactly match Google's API model name"
                }
                401, 403 -> "Gemini API key was rejected (HTTP $code) — invalid, restricted, or Gemini API disabled for this key"
                404 -> "Gemini model \"$m\" not found (HTTP 404) — pick one of the listed models, or enter the exact API model ID for custom models"
                429 -> "Gemini quota exceeded (HTTP 429) — free-tier limit hit, try again later or switch model"
                in 500..599 -> "Gemini server error (HTTP $code) — Google-side problem, try again"
                else -> "Gemini API error (HTTP $code)"
            }
            return if (apiMessage != null) "$prefix — ${apiMessage}" else prefix
        }

        /**
         * Connection test for the settings "Test connection" button.
         * Self-contained (own client). @return null on success, or a user-facing error message.
         */
        fun testGemini(apiKey: String, model: String): String? {
            if (apiKey.isBlank()) return "Gemini API key is not set — paste your key first"
            val url = "https://generativelanguage.googleapis.com/v1beta/models/${model.trim()}:generateContent"
            return try {
                // ★ session 58: same model-aware thinkingConfig logic as the search path
                val tryThinking = model.trim().startsWith("gemini-2.5")
                var resp = postGemini(testClient, url, apiKey, buildGeminiRequestBody("Reply with exactly: OK", withThinkingConfig = tryThinking))
                if (resp.first == 400 && tryThinking) {
                    resp = postGemini(testClient, url, apiKey, buildGeminiRequestBody("Reply with exactly: OK", withThinkingConfig = false))
                }
                if (resp.first in 200..299) null else describeGeminiHttpError(resp.first, resp.second, model)
            } catch (e: java.io.IOException) {
                "Could not reach the Gemini API (network error: ${e.javaClass.simpleName})"
            } catch (e: Exception) {
                "Test request failed: ${e.message?.take(80)}"
            }
        }
    }

    // ── Engine 2: Google AI search scraping (legacy, hardened) ───────────

    /**
     * Resolve via Google AI search scraping — with FAILURE CLASSIFICATION.
     *
     * ★ session 57 root-cause fix: the old code passed the WHOLE LLM prompt
     * (including the bracketed "[Respond with only…]") as the literal search
     * query. That hijacked Google into the "AI Mode conversation" UI whose
     * layout the title extractor could not read → the exact
     * "Google answered but no anime title could be read" failures.
     * Now: a CLEAN query is sent, extraction handles the AI Mode layout
     * (verified live with a stealth-browser probe), and on failure the FULL
     * rendered response is returned in Failure.detail so the app can copy it
     * to the clipboard for debugging.
     * ★ session 58: a SHORT format instruction is appended to the clean query
     * ("(in your answer, wrap the anime title in [{[ ]}] brackets)") so AI Mode
     * self-labels the title — verified live 2026-09-13 that it honors it
     * ("the iconic series [{[\nNaruto , }] :"). The S0 bracket parser tolerates
     * imperfect closing; strategies S1–S7 remain as fallback for when it doesn't.
     */
    private fun resolveWithGoogle(query: String): ResolveResult {
        val cleanQuery = query.trim().let { if (it.endsWith("anime", ignoreCase = true)) it else "$it anime" }
        // ★ session 58: SHORT format instruction (NEVER the whole LLM prompt — that was
        // the v16.12 bug). AI Mode reads it as part of the question and wraps the title.
        val queryWithFormat = "$cleanQuery (in your answer, wrap the anime title in [{[ ]}] brackets)"
        val encodedQuery = URLEncoder.encode(queryWithFormat, "UTF-8")
        val googleUrl = "https://www.google.com/search?q=$encodedQuery&udm=50&hl=en"
        AnikotoLog.d("SmartSearch: Google URL: ${AnikotoLog.trunc(googleUrl, 120)}")

        val renderedText = try {
            webViewFetcher.fetchRenderedText(googleUrl, timeoutMs = 25_000)
        } catch (e: Exception) {
            AnikotoLog.e("SmartSearch: scrape crashed", e)
            return ResolveResult.Failure(
                "Google search could not be opened (${e.javaClass.simpleName})",
                e.message,
            )
        }

        if (renderedText.isBlank()) {
            return ResolveResult.Failure(
                "Google search returned nothing in time — likely a timeout or Google blocking the app's browser",
            )
        }
        AnikotoLog.d("SmartSearch: Google rendered text (${renderedText.length} chars, first 500: ${AnikotoLog.trunc(renderedText, 500)})")

        // Classify BLOCKED states BEFORE trying to parse a title.
        classifyGoogleBlock(renderedText)?.let { return it }

        val title = extractAnimeTitle(renderedText, query)
        if (title == null) {
            val head = renderedText.replace(Regex("\\s+"), " ").trim().take(110)
            return ResolveResult.Failure(
                "Google answered but no anime title could be read from its response (page head: \"$head\") — try rephrasing or use the Gemini engine",
                renderedText.take(20_000),
            )
        }
        AnikotoLog.i("SmartSearch: extracted title: \"$title\"")
        return ResolveResult.Success(title)
    }

    /**
     * ★ session 56: Detect Google's anti-bot / consent walls in the rendered text.
     * @return a Failure with the specific reason, or null if the page looks usable.
     */
    private fun classifyGoogleBlock(text: String): ResolveResult.Failure? {
        val lower = text.lowercase()
        return when {
            listOf("unusual traffic", "not a robot", "captcha").any { it in lower } ->
                ResolveResult.Failure(
                    "Google triggered its bot-check (CAPTCHA) — automated search is blocked right now; the Gemini engine avoids this entirely",
                )
            listOf("before you continue", "consent.google").any { it in lower } ->
                ResolveResult.Failure(
                    "Google showed its cookie-consent page instead of results — the Gemini engine avoids this",
                )
            listOf("enable javascript", "enablejs").any { it in lower } && text.length < 400 ->
                ResolveResult.Failure(
                    "Google demanded JavaScript in a way the app's browser could not satisfy",
                )
            listOf("sign in to confirm", "confirm you're not a bot").any { it in lower } ->
                ResolveResult.Failure(
                    "Google is asking for sign-in verification — automated search is blocked; the Gemini engine avoids this",
                )
            else -> null
        }
    }

    // ── Prompt + title extraction (session 51/58) ────────────────────

    /**
     * Build the AI prompt (★ session 58: `[{[Title]}]` bracket convention).
     *
     * Used by the GEMINI engine. The Google engine gets the same formatting
     * requirement via a SHORT suffix on the clean query (see [resolveWithGoogle]) —
     * never the whole prompt (that was the v16.12 legacy-engine bug).
     *
     * The user's idea (session 58): make the AI self-label the title with rare
     * brackets `[{[…]}]` that our parser can detect unambiguously. Scenario
     * handling (descriptions, misspellings, genre/vague queries) is kept from
     * session 57's LLM-tested prompt, and the exact-title anchoring is preserved.
     */
    private fun buildPrompt(query: String): String {
        return "$query anime. " +
            "[Identify the ONE anime this refers to and reply with ONLY its English title " +
            "wrapped inside these exact brackets: [{[Title]}] — nothing else inside the brackets. " +
            "If the query is already an anime title or close to one, return that title with spelling corrected. " +
            "If the query describes an anime, give the title of the anime being described. " +
            "If the query has spelling mistakes, correct them and give the proper title. " +
            "If the query mentions a genre or theme, give one popular anime from that genre. " +
            "If the query is vague, give the most likely anime match.]"
    }

    /**
     * ★ session 56: Clean an AI answer into a bare title.
     * Handles: surrounding quotes, bracket-wrapped prompt echoes "[...]",
     * leading "Title:" prefixes, trailing punctuation, and multi-line answers
     * (takes the first meaningful line).
     */
    private fun cleanTitle(raw: String): String? {
        var s = raw.trim()
        if (s.isEmpty()) return null
        // Take the first non-empty line (Gemini occasionally adds a preamble line)
        s = s.lines().firstOrNull { it.isNotBlank() }?.trim() ?: return null
        // Strip bracket-wrapped prompt echoes: "[ Respond with only ... ]"
        s = s.replace(Regex("^\\[[^\\]]*]\\s*"), "").trim()
        // Strip leading "Title:" / "Anime:" prefixes
        s = s.replace(Regex("^(title|anime)\\s*:\\s*", RegexOption.IGNORE_CASE), "").trim()
        // Strip surrounding quotes (straight + curly)
        s = s.trim('"', '\'', '\u201C', '\u201D', '\u2018', '\u2019', ' ')
        // Strip trailing period(s) but keep inner punctuation
        s = s.trimEnd('.', ' ')
        if (s.isBlank()) return null
        val wordCount = s.split(Regex("\\s+")).filter { it.isNotEmpty() }.size
        return if (wordCount in 1..12) s else null
    }

    /**
     * ★ session 57/58: Extract an anime title from Google AI's rendered text.
     *
     * Pipeline (designed against a live stealth-browser capture of Google's AI Mode):
     * 0. **S0 bracket strategy (★ session 58)** — `[{[Title]}]` first, on the raw text.
     * 1. Preprocess — strip markdown emphasis, cut Google footers, drop UI-chrome and
     *    query-echo lines (echo lines are only dropped near the top of the page so real
     *    answers that repeat the query words survive).
     * 2. Candidate regions — prefer the text AFTER an AI-answer marker
     *    ("AI Mode reply for", "AI Overview", "AI Mode response", "Search Results").
     * 3. Per region, run pattern strategies strong → weak (S1–S7).
     *
     * @param pageText the rendered page text
     * @param query the user's phrase-stripped query (used to detect the query echo)
     */
    private fun extractAnimeTitle(pageText: String, query: String): String? {
        // ── S0. Bracket convention (★ session 58, strongest signal) ──
        extractBracketTitle(pageText)?.let {
            AnikotoLog.d("SmartSearch: S0 bracket match: \"$it\"")
            return it
        }

        // ── 0. Preprocess ──
        var text = pageText
            .replace(Regex("\\*{1,3}([^*\\n]+)\\*{1,3}"), "$1")
            .replace(Regex("^#{1,6}\\s+", RegexOption.MULTILINE), "")
        for (footer in listOf("AI can make mistakes", "Was this response helpful", "Was this helpful", "Give feedback")) {
            val idx = text.indexOf(footer, ignoreCase = true)
            if (idx >= 0) text = text.substring(0, idx)
        }

        val qWords = query.lowercase().split(Regex("\\s+")).filter { it.length > 2 }.toSet()
        val junkMarkers = listOf(
            "respond with only", "copied to clipboard", "copy edit", "transcribing",
            "add files", "microphone", "ask about", "my ad centre", "google apps",
            "shared public links", "new thread", "search threads", "ai mode history",
            "signed out", "to access history", "open sidebar", "close sidebar",
            "skip to main content", "accessibility help", "quick settings",
            "having trouble accessing google", "please click here if you are not redirected",
            "manage ai mode", "personalization settings", "see your search history",
        )
        val kept = StringBuilder()
        for (line in text.lines()) {
            val lower = line.lowercase()
            val isJunk = junkMarkers.any { it in lower }
            val echoWords = if (qWords.isEmpty()) 0 else lower.split(Regex("\\s+")).count { it in qWords }
            var isEcho = echoWords >= maxOf(2, (qWords.size * 3) / 4)
            if (isEcho) {
                // ★ Real answers may repeat the exact query title — never drop a
                // title-like line (short, Title Case, not a question).
                val rawWords = line.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                val titleLike = rawWords.size in 1..12 &&
                    rawWords.count { it.firstOrNull()?.isUpperCase() == true } * 2 >= rawWords.size &&
                    !line.trim().endsWith("?")
                if (titleLike) isEcho = false
            }
            if (isEcho || isJunk) continue
            kept.appendLine(line)
        }
        val cleaned = kept.toString().trim()
        if (cleaned.isBlank()) return null

        // ── 1. Candidate regions (marker text takes priority) ──
        val markers = listOf("AI Mode reply for", "AI Overview", "AI Mode response", "Search Results")
        val regions = mutableListOf<String>()
        for (m in markers) {
            val idx = cleaned.lastIndexOf(m)
            if (idx >= 0) regions.add(cleaned.substring(idx + m.length))
        }
        regions.add(cleaned)

        // ── 2. Strategies ──
        for (region in regions) {
            extractFromRegion(region)?.let { return it }
        }
        AnikotoLog.d("SmartSearch: all extraction strategies failed")
        return null
    }

    /** Run all pattern strategies over one text region, strongest signal first.
     *  ★ session 58: S1/S2 caps widened 80 → 100 and terminated at "(" so the
     *  parenthetical "(Japanese title: …)" no longer inflates the match past the cap
     *  (the user's reported "no anime title could be read" failure). S2c and S6b added. */
    private fun extractFromRegion(region: String): String? {
        // ── S1 — "is titled / is called / is named / is known as X" (strong) ──
        val titledPattern = Regex(
            """(?:is\s+titled|is\s+called|is\s+named|is\s+known\s+as)\s+([A-Z][^\n.!?(]{1,100}?)(?:\s*[.\n!?]|\(|$)"""
        )
        for (match in titledPattern.findAll(region)) {
            val title = stripParenthetical(match.groupValues[1].trim())
            if (title.split(Regex("\\s+")).filter { it.isNotEmpty() }.size in 1..12) return title
        }

        // ── S2 — "the anime you're describing is X" / "looking for is X" (strong) ──
        // ★ session 58: group stops at "(" — "…looking for is Alya Sometimes Hides Her
        // Feelings in Russian (Japanese title: Tokidoki Bosotto Russia-go de Dereru
        // Tonari no Alya-san)." now yields "Alya Sometimes Hides Her Feelings in Russian"
        // (the old 80-char cap over the WHOLE match including the parenthetical failed).
        val describingPattern = Regex(
            """(?:describing|described|looking\s+for|thinking\s+of|referring\s+to|asking\s+about)\s+is\s+([A-Z][^\n.!?(]{1,100}?)(?:\s*[.\n!?]|\(|$)"""
        )
        for (match in describingPattern.findAll(region)) {
            val title = stripParenthetical(match.groupValues[1].trim())
            if (title.split(Regex("\\s+")).filter { it.isNotEmpty() }.size in 1..12) return title
        }

        // ── S2c — "the anime/series/show is X" (★ session 58) ──
        val animeIsPattern = Regex(
            """\b(?:anime|series|show)\s+is\s+([A-Z][^\n.!?(]{2,100}?)(?:\s*[.\n!?]|\(|$)"""
        )
        for (match in animeIsPattern.findAll(region)) {
            val title = stripParenthetical(match.groupValues[1].trim())
            if (title.split(Regex("\\s+")).filter { it.isNotEmpty() }.size in 1..12) return title
        }

        // ── S3 — AI-Mode follow-up: "Would you like to know more about X" (strong) ──
        val knowMorePattern = Regex(
            """[Ww]ould\s+you\s+like\s+to\s+know\s+more\s+about\s+(.{2,80}?)(?:\s*,|\s*\n|$)"""
        )
        for (match in knowMorePattern.findAll(region)) {
            val title = stripParenthetical(match.groupValues[1].trim())
            if (title.split(Regex("\\s+")).filter { it.isNotEmpty() }.size in 1..12) return title
        }

        // ── S4 — misspellings: "Did you mean: X" (strong) ──
        val didYouMeanPattern = Regex("""[Dd]id\s+you\s+mean\s*:?\s*([^\n?!]{2,80})""")
        for (match in didYouMeanPattern.findAll(region)) {
            val title = stripParenthetical(match.groupValues[1].trim().trimEnd('.', ' '))
            if (title.split(Regex("\\s+")).filter { it.isNotEmpty() }.size in 1..12) return title
        }

        // ── S5 — quoted text (medium) ──
        val quotedPattern = Regex(
            "[\u201C\u201D\"'\u2018\u2019]([^\u201C\u201D\"'\u2018\u2019\\n]{2,80})[\u201C\u201D\"'\u2018\u2019]"
        )
        for (match in quotedPattern.findAll(region)) {
            val raw = match.groupValues[1].trim()
            if (raw.contains("(") && raw.endsWith(")")) continue
            val title = stripParenthetical(raw)
            val n = title.split(Regex("\\s+")).filter { it.isNotEmpty() }.size
            // ★ session 57: single-word quoted titles ("Dandadan", "Naruto") are valid
            val accepted = n in 2..12 || (n == 1 && title.firstOrNull()?.isUpperCase() == true)
            if (accepted) return title
        }

        // ── S6 — database URL slugs: MyAnimeList / AniList (strong-ish) ──
        val urlSlugPattern = Regex("""(?:myanimelist\.net/anime/\d+/|anilist\.co/anime/\d+/)([A-Za-z0-9_\-]+)""")
        for (match in urlSlugPattern.findAll(region)) {
            val slug = try {
                java.net.URLDecoder.decode(match.groupValues[1], "UTF-8")
            } catch (_: Exception) { match.groupValues[1] }
            val words = slug.replace(Regex("[_\\-]+"), " ").trim()
                .split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (words.size in 1..12) {
                return words.joinToString(" ") { w ->
                    w.lowercase().replaceFirstChar { c -> c.uppercase() }
                }
            }
        }

        // ── S6b — "Japanese title: X" parenthetical (★ session 58, late fallback) ──
        // e.g. "The anime you are looking for is X (Japanese title: Tokidoki Bosotto
        // Russia-go de Dereru Tonari no Alya-san)." — if everything above failed, the
        // romaji title is still a far better search term than nothing.
        val japaneseTitlePattern = Regex(
            """Japanese\s+title\s*:?:?\s*([A-Za-z0-9][^\n)\].!?"]{1,100}?)(?:\s*[)\]\n]|\s*[.!?]|$)"""
        )
        for (match in japaneseTitlePattern.findAll(region)) {
            val title = match.groupValues[1].trim().trimEnd('.', ' ')
            if (title.split(Regex("\\s+")).filter { it.isNotEmpty() }.size in 1..12) return title
        }

        // ── S7 — title-like line scan (weakest) ──
        // ★ session 58: parenthetical stripped BEFORE length checks, raw line cap
        // raised 90 → 120 (answers like "… is Alya … (Japanese title: …)." are long).
        val skipStart = setOf(
            "ai", "all", "images", "videos", "news", "shopping", "maps", "books",
            "flights", "finance", "search", "results", "mode", "settings", "history",
            "personalization", "sign", "privacy", "terms", "google", "about",
            "advertising", "how", "why", "what", "where", "when", "would", "could",
            "should", "please", "try", "more", "less", "feedback", "help", "tools",
            "labs", "quick", "accessibility", "skip",
        )
        for (line in region.lines()) {
            val trimmed = line.trim()
            if (trimmed.length < 5 || trimmed.length > 120) continue
            if (trimmed.endsWith("?") || trimmed.endsWith("!") || trimmed.endsWith(":")) continue
            val noParen = stripParenthetical(trimmed)
            val lower = trimmed.lowercase()
            if (lower.contains(" is ") || lower.contains(" are ") || lower.contains(" was ")) continue
            if ("respond with only" in lower || "anime." in lower) continue
            val words = noParen.split(Regex("\\s+"))
            if (words.size < 2 || words.size > 12) continue
            val firstWord = words[0].trimStart('"', '\'', '\u201C', '\u201D').lowercase()
            if (firstWord in skipStart) continue
            val capitalized = words.count { it.firstOrNull()?.isUpperCase() == true }
            if (capitalized * 2 < words.size) continue
            val title = noParen
            val wc = title.split(Regex("\\s+")).filter { it.isNotEmpty() }.size
            if (wc in 2..12) {
                AnikotoLog.d("SmartSearch: line-scan match: \"$trimmed\" → \"$title\"")
                return title
            }
        }
        return null
    }

    /** Remove trailing and leading parenthetical groups: "Title (suffix)" → "Title" */
    private fun stripParenthetical(s: String): String {
        var result = s.trim()
        result = result.replace(Regex("\\s*\\([^)]*\\)\\s*$"), "").trim()
        result = result.replace(Regex("^\\s*\\([^)]*\\)\\s*"), "").trim()
        return result
    }

    // ── Cache + warm-up ───────────────────────────────────────────────────

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

    /** Pre-warm the Google WebView for smart search (legacy engine). */
    fun warmUp() {
        webViewFetcher.warmUpGoogleWebView()
    }
}
