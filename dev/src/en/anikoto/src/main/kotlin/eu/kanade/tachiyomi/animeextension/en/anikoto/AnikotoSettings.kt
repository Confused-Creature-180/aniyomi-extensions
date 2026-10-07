package eu.kanade.tachiyomi.animeextension.en.anikoto

import android.content.SharedPreferences
import android.graphics.Color
import android.text.SpannableStringBuilder
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.graphics.Typeface
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animeextension.en.anikoto.smartsearch.SmartSearch

/**
 * ★ Module: Settings — all preference keys, defaults, typed getters, and the settings UI.
 *
 * Extracted from Anikoto.kt (session 50) so that settings can be managed independently.
 * Modifying the settings UI or adding new preferences does not require touching the main
 * source class — just update this file.
 *
 * ## Preference categories
 * 1. **Playback** — domain, quality, audio, buffer, server
 * 2. **Servers** — Kiwi-Stream toggle
 * 3. **Episode metadata** — thumbnails, titles, descriptions
 * 4. **Smart Search** — AI search toggle, activation phrase, engine/model/key, "Copy response" (session 51/56/58)
 * 5. **Details** — Smart Search usage instructions (session 58)
 *
 * ## Architecture
 * - [AnikotoSettings] wraps a [SharedPreferences] instance and exposes typed getters.
 * - [setupPreferenceScreen] builds the 5-category settings UI.
 * - The main Anikoto.kt class creates an instance and delegates to it.
 *
 * @property prefs The SharedPreferences instance (keyed by source ID)
 */
class AnikotoSettings(private val prefs: SharedPreferences) {

    // ── Typed getters ──────────────────────────────────────────────────

    /** Preferred video quality resolution string (e.g. "720") */
    val preferredQuality: String
        get() = prefs.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT

    /** Preferred audio type label (e.g. "SUB", "A-DUB", "H-SUB") */
    val preferredAudio: String
        get() = prefs.getString(PREF_AUDIO_KEY, PREF_AUDIO_DEFAULT) ?: PREF_AUDIO_DEFAULT

    /** Prefetch buffer percentage (e.g. "10") */
    val prefetchBuffer: String
        get() = prefs.getString(PREF_BUFFER_KEY, PREF_BUFFER_DEFAULT) ?: PREF_BUFFER_DEFAULT

    /** Preferred server name (e.g. "auto", "VidPlay-1") */
    val preferredServer: String
        get() = prefs.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT) ?: PREF_SERVER_DEFAULT

    /** ★ session 52: Preferred site domain (e.g. "https://anikototv.to"). */
    val preferredDomain: String
        get() = prefs.getString(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT) ?: PREF_DOMAIN_DEFAULT

    /** Whether Kiwi-Stream server discovery is enabled (default: true) */
    val enableKiwi: Boolean
        get() = prefs.getBoolean(PREF_ENABLE_KIWI_KEY, PREF_ENABLE_KIWI_DEFAULT)

    /** Whether to load episode thumbnails from external sources (default: true) */
    val loadThumbnails: Boolean
        get() = prefs.getBoolean(PREF_LOAD_THUMBNAILS_KEY, PREF_LOAD_THUMBNAILS_DEFAULT)

    /** Whether to load episode titles from external sources (default: true) */
    val loadTitles: Boolean
        get() = prefs.getBoolean(PREF_LOAD_TITLES_KEY, PREF_LOAD_TITLES_DEFAULT)

    /** Whether to load episode descriptions from external sources (default: true) */
    val loadDescriptions: Boolean
        get() = prefs.getBoolean(PREF_LOAD_DESCRIPTIONS_KEY, PREF_LOAD_DESCRIPTIONS_DEFAULT)

    // ── Smart Search (session 51) ─────────────────────────────────────

    /** Whether smart search is enabled (default: false — user must opt in) */
    val smartSearchEnabled: Boolean
        get() = prefs.getBoolean(PREF_SMART_SEARCH_KEY, PREF_SMART_SEARCH_DEFAULT)

    /** The activation phrase that triggers smart search (default: "?" — query must start with it).
     *  When the user types this phrase at the start of their search query,
     *  smart search is triggered. Case-insensitive. */
    val smartSearchPhrase: String
        get() = prefs.getString(PREF_SMART_SEARCH_PHRASE_KEY, PREF_SMART_SEARCH_PHRASE_DEFAULT)
            ?: PREF_SMART_SEARCH_PHRASE_DEFAULT

    /** ★ session 56: Which smart search engine to use ("gemini" | "google").
     *  ★ session 57: legacy stored "auto" (v16.12) resolves dynamically —
     *  Gemini if an API key is set, else Google — so old installs keep working. */
    val smartSearchEngine: String
        get() {
            val stored = prefs.getString(PREF_SMART_ENGINE_KEY, PREF_SMART_ENGINE_DEFAULT)
                ?: PREF_SMART_ENGINE_DEFAULT
            return if (stored == "auto") {
                if (geminiApiKey.isNotBlank()) "gemini" else "google"
            } else {
                stored
            }
        }

    /** ★ session 56: The user's Google Gemini API key (blank = not set). */
    val geminiApiKey: String
        get() = prefs.getString(PREF_GEMINI_KEY_KEY, PREF_GEMINI_KEY_DEFAULT)
            ?: PREF_GEMINI_KEY_DEFAULT

    /** ★ session 57: The Gemini model id (e.g. "gemini-3.1-flash-lite").
     *  "custom" resolves to the user-typed model id (falls back to the default). */
    val geminiModel: String
        get() {
            val stored = prefs.getString(PREF_GEMINI_MODEL_KEY, PREF_GEMINI_MODEL_DEFAULT)
                ?: PREF_GEMINI_MODEL_DEFAULT
            return if (stored == "custom") {
                prefs.getString(PREF_GEMINI_CUSTOM_MODEL_KEY, PREF_GEMINI_CUSTOM_MODEL_DEFAULT)
                    ?.trim()?.ifBlank { null } ?: PREF_GEMINI_MODEL_DEFAULT
            } else {
                stored.ifBlank { PREF_GEMINI_MODEL_DEFAULT }
            }
        }

    /** ★ session 58: whether smart-search results (query + title, or error + raw
     *  response) are automatically copied to the clipboard. Default: OFF. */
    val copyResponse: Boolean
        get() = prefs.getBoolean(PREF_SMART_COPY_RESPONSE_KEY, PREF_SMART_COPY_RESPONSE_DEFAULT)

    // ── Settings UI ────────────────────────────────────────────────────

    /**
     * Build the settings preference screen with 5 categories.
     *
     * Categories:
     * 1. **Playback** — quality, audio, buffer, server (all with "Currently: %s")
     * 2. **Servers** — Kiwi-Stream toggle
     * 3. **Episode metadata** — thumbnails, titles, descriptions toggles
     * 4. **Smart Search** — AI search toggle, phrase, engine, Gemini group, Copy response
     * 5. **Details** — usage instructions (session 58, exact copy per user request)
     *
     * All dropdowns show "Currently: %s" so the user can see the current value.
     */
    fun setupPreferenceScreen(screen: PreferenceScreen) {

        // ── Category 1: Playback ────────────────────────────────────────
        PreferenceCategory(screen.context).apply {
            title = "Playback"
            screen.addPreference(this)

            ListPreference(context).apply {
                key = PREF_DOMAIN_KEY
                title = "Preferred domain"
                // ★ session 52: all 6 official/verified AniKoto domains (from anikoto.site,
                // the site's own domain hub — plus anikototv.com which also serves the site).
                entries = arrayOf(
                    "anikototv.to (Primary)",
                    "anikoto.cz (Regional mirror)",
                    "anikoto.me (Short TLD mirror)",
                    "anikoto.net (Network mirror)",
                    "anikototv.se (Nordic mirror)",
                    "anikototv.com (Legacy mirror)",
                )
                entryValues = arrayOf(
                    "https://anikototv.to",
                    "https://anikoto.cz",
                    "https://anikoto.me",
                    "https://anikoto.net",
                    "https://anikototv.se",
                    "https://anikototv.com",
                )
                setDefaultValue(PREF_DOMAIN_DEFAULT)
                summary = "Currently: %s"
            }.also(::addPreference)

            ListPreference(context).apply {
                key = PREF_QUALITY_KEY
                title = "Preferred quality"
                entries = arrayOf("1080p", "720p", "480p", "360p")
                entryValues = arrayOf("1080", "720", "480", "360")
                setDefaultValue(PREF_QUALITY_DEFAULT)
                summary = "Currently: %s"
            }.also(::addPreference)

            ListPreference(context).apply {
                key = PREF_AUDIO_KEY
                title = "Preferred audio"
                entries = arrayOf("Sub", "Dub", "Hardsub")
                entryValues = arrayOf("SUB", "A-DUB", "H-SUB")
                setDefaultValue(PREF_AUDIO_DEFAULT)
                summary = "Currently: %s"
            }.also(::addPreference)

            ListPreference(context).apply {
                key = PREF_BUFFER_KEY
                title = "Pre-fetch buffer"
                entries = arrayOf("10%", "20%", "30%", "50%", "100%")
                entryValues = arrayOf("10", "20", "30", "50", "100")
                setDefaultValue(PREF_BUFFER_DEFAULT)
                summary = "Currently: %s"
            }.also(::addPreference)

            ListPreference(context).apply {
                key = PREF_SERVER_KEY
                title = "Preferred server"
                // ★ session 60: refreshed for the site's server lineup. ★ session 62: added
                // "Vidstream-1" — the CURRENT live lineup is HD-1 / Vidstream-2 / Vidstream-1
                // (verified 2026-10-07 on Sakamoto Days ep-4). Legacy names stay listed —
                // the site rotates server names over time and old entries still match if a
                // name ever returns (the sorter uses contains(), unknown names are harmless).
                entries = arrayOf(
                    "Auto",
                    "Vidstream-1", "Vidstream-2", "Vidstream-1beta", "HD-1", "HD-2",
                    "VidPlay-1", "VidCloud-1", "Kiwi-Stream",
                )
                entryValues = arrayOf(
                    "auto",
                    "Vidstream-1", "Vidstream-2", "Vidstream-1beta", "HD-1", "HD-2",
                    "VidPlay-1", "VidCloud-1", "Kiwi-Stream",
                )
                setDefaultValue(PREF_SERVER_DEFAULT)
                summary = "Currently: %s"
            }.also(::addPreference)

            // ★ session 62/63: user-facing explanation for the recurring "only one/two
            // resolutions" reports. Live-verified 2026-10-07 (both .to and .cz, every
            // server × CDN candidate × endpoint, with/without the CDN token): several shows
            // (e.g. Sakamoto Days, Beyblade X) ship a SINGLE-variant 1080p-only HLS master —
            // the site's own player shows the same single quality, so nothing was dropped by
            // the extension. The site's other qualities for such shows exist ONLY in the
            // download menu (Kiwi/pahe 360p/720p/1080p file links), which are not streams —
            // verified: those links open a download page, not a video file. Sub and Dub
            // appear as separate entries by design (same file, different audio).
            Preference(context).apply {
                key = "pref_quality_note"
                isSelectable = false
                title = "About missing qualities"
                summary = "Some shows/episodes are single-quality at the source — the site's " +
                    "own player shows the same single quality (e.g. 1080p only). The site's " +
                    "other qualities are in its DOWNLOAD menu only (360p/720p/1080p file " +
                    "links), which cannot be streamed. Sub and Dub count as separate entries. " +
                    "This is a site limitation, not an extension bug."
            }.also(::addPreference)
        }

        // ── Category 2: Servers ─────────────────────────────────────────
        PreferenceCategory(screen.context).apply {
            title = "Servers"
            screen.addPreference(this)

            SwitchPreferenceCompat(context).apply {
                key = PREF_ENABLE_KIWI_KEY
                title = "Enable mapper servers"
                // ★ session 63: the toggle now gates the WHOLE mapper API (it can return
                // Kiwi-Stream plus, on some shows, Vidstream / Vibe-Stream entries).
                summaryOn = "Fetching Kiwi-Stream and other mapper servers from external sources"
                summaryOff = "Mapper servers disabled (primary site servers only)"
                setDefaultValue(PREF_ENABLE_KIWI_DEFAULT)
            }.also(::addPreference)
        }

        // ── Category 3: Episode metadata ───────────────────────────────
        PreferenceCategory(screen.context).apply {
            title = "Episode metadata"
            screen.addPreference(this)

            SwitchPreferenceCompat(context).apply {
                key = PREF_LOAD_THUMBNAILS_KEY
                title = "Load episode thumbnails"
                // ★ session 57: no descriptions — user requested clean minimal toggles
                setDefaultValue(PREF_LOAD_THUMBNAILS_DEFAULT)
            }.also(::addPreference)

            SwitchPreferenceCompat(context).apply {
                key = PREF_LOAD_TITLES_KEY
                title = "Load episode titles"
                setDefaultValue(PREF_LOAD_TITLES_DEFAULT)
            }.also(::addPreference)

            SwitchPreferenceCompat(context).apply {
                key = PREF_LOAD_DESCRIPTIONS_KEY
                title = "Load episode descriptions"
                setDefaultValue(PREF_LOAD_DESCRIPTIONS_DEFAULT)
            }.also(::addPreference)
        }

        // ── Category 4: Smart Search (session 51/56, UI overhaul session 57) ───
        PreferenceCategory(screen.context).apply {
            title = "Smart Search"
            screen.addPreference(this)

            // ★ session 57: one-time migration of v16.12 stored values.
            // "auto" engine → gemini if a key is set, else google (auto is no longer offered).
            val storedEngine = prefs.getString(PREF_SMART_ENGINE_KEY, PREF_SMART_ENGINE_DEFAULT)
                ?: PREF_SMART_ENGINE_DEFAULT
            if (storedEngine == "auto") {
                val hasKey = !prefs.getString(PREF_GEMINI_KEY_KEY, "").isNullOrBlank()
                prefs.edit().putString(PREF_SMART_ENGINE_KEY, if (hasKey) "gemini" else "google").apply()
            }
            // Old 2.x model ids → kept as the user's custom model id so nothing breaks.
            val storedModel = prefs.getString(PREF_GEMINI_MODEL_KEY, PREF_GEMINI_MODEL_DEFAULT)
                ?: PREF_GEMINI_MODEL_DEFAULT
            if (storedModel != "custom" && storedModel !in GEMINI_MODELS) {
                prefs.edit().putString(PREF_GEMINI_MODEL_KEY, "custom")
                    .putString(PREF_GEMINI_CUSTOM_MODEL_KEY, storedModel).apply()
            }

            // ★ session 57: toggle — heading "Smart Search", one-line summary, no on/off variants
            SwitchPreferenceCompat(context).apply {
                key = PREF_SMART_SEARCH_KEY
                title = "Smart Search"
                summary = "Search spelling correction and smarter description searching"
                setDefaultValue(PREF_SMART_SEARCH_DEFAULT)
            }.also(::addPreference)

            // ★ session 57: engine picker — exactly two options, no descriptions
            val enginePref = ListPreference(context).apply {
                key = PREF_SMART_ENGINE_KEY
                title = "AI engine"
                entries = arrayOf("Google Gemini API", "Google AI Search")
                entryValues = arrayOf("gemini", "google")
                setDefaultValue(PREF_SMART_ENGINE_DEFAULT)
                summary = "Currently: %s"
            }.also(::addPreference)

            // ★ session 57: Gemini API key — simple and short (no long dialog text)
            val keyPref = EditTextPreference(context).apply {
                key = PREF_GEMINI_KEY_KEY
                title = "Gemini API key"
                dialogTitle = "Gemini API key"
                setDefaultValue(PREF_GEMINI_KEY_DEFAULT)
                updateGeminiKeySummary(this, null)
                onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, newValue ->
                    updateGeminiKeySummary(this, newValue as? String)
                    true
                }
            }.also(::addPreference)

            // ★ session 58: model picker — Gemini 3.1 Flash Lite on TOP and selected by
            // default; no "(Recommended)" label anywhere (user request). Order = the list
            // the user sees in the dialog.
            val modelPref = ListPreference(context).apply {
                key = PREF_GEMINI_MODEL_KEY
                title = "Gemini model"
                entries = arrayOf(
                    "Gemini 3.1 Flash Lite",
                    "Gemini 3.5 Flash Lite",
                    "Gemini 3.8 Flash",
                    "Custom model ID",
                )
                entryValues = GEMINI_MODELS + arrayOf("custom")
                setDefaultValue(PREF_GEMINI_MODEL_DEFAULT)
                summary = "Currently: %s"
            }.also(::addPreference)

            // ★ session 57: custom model id — visible only when "Custom model ID" is selected
            val customModelPref = EditTextPreference(context).apply {
                key = PREF_GEMINI_CUSTOM_MODEL_KEY
                title = "Custom model ID"
                dialogTitle = "Custom model ID"
                setDefaultValue(PREF_GEMINI_CUSTOM_MODEL_DEFAULT)
                updateCustomModelSummary(this, null)
                onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, newValue ->
                    updateCustomModelSummary(this, newValue as? String)
                    true
                }
            }.also(::addPreference)

            // ★ session 57: connection test (Gemini engine only)
            val testPref = Preference(context).apply {
                key = "pref_gemini_test"
                title = "Test connection"
                summary = "Sends a tiny test request with the key and model above."
                setOnPreferenceClickListener {
                    val appContext = it.context.applicationContext
                    val key = prefs.getString(PREF_GEMINI_KEY_KEY, PREF_GEMINI_KEY_DEFAULT).orEmpty()
                    val model = geminiModel
                    Toast.makeText(appContext, "Testing Gemini $model …", Toast.LENGTH_SHORT).show()
                    Thread {
                        val error = SmartSearch.testGemini(key, model)
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            val msg = if (error == null) "Gemini works! ($model)" else error
                            Toast.makeText(appContext, msg, Toast.LENGTH_LONG).show()
                        }
                    }.start()
                    true
                }
            }.also(::addPreference)

            // ★ session 58: "Copy response" — auto-copy the searched query + the resolved
            // title (or error + raw response) to the clipboard. Default OFF. Sits at the
            // very bottom of the Smart Search section (user request).
            SwitchPreferenceCompat(context).apply {
                key = PREF_SMART_COPY_RESPONSE_KEY
                title = "Copy response"
                setDefaultValue(PREF_SMART_COPY_RESPONSE_DEFAULT)
            }.also(::addPreference)

            EditTextPreference(context).apply {
                key = PREF_SMART_SEARCH_PHRASE_KEY
                title = "Activation phrase"
                dialogTitle = "Activation phrase"
                setDefaultValue(PREF_SMART_SEARCH_PHRASE_DEFAULT)
                // ★ session 51: Custom summary that shows the actual phrase (not "%s")
                updatePhraseSummary(this, prefs.getString(PREF_SMART_SEARCH_PHRASE_KEY, PREF_SMART_SEARCH_PHRASE_DEFAULT) ?: PREF_SMART_SEARCH_PHRASE_DEFAULT)
                // Update summary when user changes the phrase (also refreshes the Details
                // section below, which shows the live phrase + examples)
                onPreferenceChangeListener = androidx.preference.Preference.OnPreferenceChangeListener { _, newValue ->
                    updatePhraseSummary(this, newValue as? String ?: "")
                    smartDetailsPref?.let { updateDetailsSummary(it, newValue as? String) }
                    true
                }
            }.also(::addPreference)

            // ★ session 57: conditional visibility — Google AI Search needs none of the
            // Gemini UI, so those items are hidden while that engine is selected.
            fun applyEngineVisibility() {
                val engine = prefs.getString(PREF_SMART_ENGINE_KEY, PREF_SMART_ENGINE_DEFAULT)
                    ?: PREF_SMART_ENGINE_DEFAULT
                val isGemini = engine != "google"
                keyPref.isVisible = isGemini
                modelPref.isVisible = isGemini
                testPref.isVisible = isGemini
                val model = prefs.getString(PREF_GEMINI_MODEL_KEY, PREF_GEMINI_MODEL_DEFAULT)
                    ?: PREF_GEMINI_MODEL_DEFAULT
                customModelPref.isVisible = isGemini && model == "custom"
            }
            applyEngineVisibility()
            // The change listener fires BEFORE the new value is persisted — re-apply
            // visibility after the value lands via the main-handler queue.
            enginePref.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, _ ->
                android.os.Handler(android.os.Looper.getMainLooper()).post { applyEngineVisibility() }
                true
            }
            modelPref.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, _ ->
                android.os.Handler(android.os.Looper.getMainLooper()).post { applyEngineVisibility() }
                true
            }
        }

        // ── Category 5: Details (★ session 58 — exact copy per user request) ───
        PreferenceCategory(screen.context).apply {
            title = "Details"
            screen.addPreference(this)

            Preference(context).apply {
                key = "pref_smart_details"
                isSelectable = false
                updateDetailsSummary(this)
                smartDetailsPref = this
            }.also(::addPreference)
        }
    }

    // ── Smart Search helpers (session 51/56) ────────────────────────────

    /** ★ session 58: reference to the Details preference so the phrase editor can
     *  refresh its live text. Assigned when the Details category is built. */
    private var smartDetailsPref: Preference? = null

    /**
     * ★ session 58: the Details section — exact copy per the user's request, with the
     * CURRENT activation phrase substituted dynamically (both in "Your phrase" and in
     * the examples).
     */
    private fun updateDetailsSummary(pref: Preference, overridePhrase: String? = null) {
        val phrase = (overridePhrase
            ?: prefs.getString(PREF_SMART_SEARCH_PHRASE_KEY, PREF_SMART_SEARCH_PHRASE_DEFAULT)
            ?: PREF_SMART_SEARCH_PHRASE_DEFAULT).trim()
        val shown = phrase.ifEmpty { "(empty)" }
        val prefix = if (phrase.isEmpty()) "" else "$phrase "
        pref.title = "Details"
        pref.summary = "Type your activation phrase at the start of your search to trigger AI.\n" +
            "Leave empty to use AI for all searches.\n" +
            "Case-insensitive. Must be followed by a space.\n\n" +
            "Your phrase: \"$shown\"\n\n" +
            "Examples:\n" +
            "${prefix}the anime with a russian girl\n" +
            "${prefix}narutp\n" +
            "${prefix}anime about a spy\n\n" +
            "Note: ~5-8s latency per AI search."
    }

    /** ★ session 57: SHORT masked summary for the Gemini API key preference.
     *  @param overrideValue when non-null, shown instead of the stored value (the change
     *  listener fires BEFORE the new value is persisted). */
    private fun updateGeminiKeySummary(pref: EditTextPreference, overrideValue: String?) {
        val key = (overrideValue
            ?: prefs.getString(PREF_GEMINI_KEY_KEY, PREF_GEMINI_KEY_DEFAULT).orEmpty()).trim()
        pref.summary = when {
            key.isEmpty() -> "Not set"
            key.length <= 8 -> "••••"
            else -> "••••${key.takeLast(4)}"
        }
    }

    /** ★ session 57: Short summary for the custom model id preference. */
    private fun updateCustomModelSummary(pref: EditTextPreference, overrideValue: String?) {
        val value = (overrideValue
            ?: prefs.getString(PREF_GEMINI_CUSTOM_MODEL_KEY, PREF_GEMINI_CUSTOM_MODEL_DEFAULT).orEmpty()).trim()
        pref.summary = if (value.isEmpty()) "Not set" else value
    }

    /**
     * ★ session 51: Update the activation phrase preference summary.
     * Shows the actual phrase instead of "%s". Uses red color for the phrase value
     * to make it stand out.
     */
    private fun updatePhraseSummary(pref: EditTextPreference, phrase: String?) {
        val displayPhrase = phrase?.trim()?.ifBlank { "(empty — AI used for all)" } ?: "(empty — AI used for all)"
        val text = "Currently: $displayPhrase"
        // ★ session 51: use SpannableString to color the phrase value red
        val spannable = SpannableString(text)
        val phraseStart = "Currently: ".length
        val phraseEnd = text.length
        spannable.setSpan(
            ForegroundColorSpan(Color.parseColor("#dc2626")),
            phraseStart, phraseEnd,
            SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        spannable.setSpan(
            StyleSpan(Typeface.BOLD),
            phraseStart, phraseEnd,
            SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        pref.summary = spannable
    }

    companion object {
        // ── Preference keys + defaults ──────────────────────────────────
        // All keys are private — access through typed getters only.

        // Playback
        internal const val PREF_DOMAIN_KEY = "pref_domain"
        internal const val PREF_DOMAIN_DEFAULT = "https://anikototv.to"
        internal const val PREF_QUALITY_KEY = "pref_quality"
        internal const val PREF_QUALITY_DEFAULT = "720"
        internal const val PREF_AUDIO_KEY = "pref_audio"
        internal const val PREF_AUDIO_DEFAULT = "SUB"
        internal const val PREF_BUFFER_KEY = "pref_buffer"
        internal const val PREF_BUFFER_DEFAULT = "10"
        internal const val PREF_SERVER_KEY = "pref_server"
        internal const val PREF_SERVER_DEFAULT = "auto"

        // Servers
        internal const val PREF_ENABLE_KIWI_KEY = "pref_enable_kiwi"
        internal const val PREF_ENABLE_KIWI_DEFAULT = true

        // Episode metadata
        internal const val PREF_LOAD_THUMBNAILS_KEY = "pref_load_thumbnails"
        internal const val PREF_LOAD_THUMBNAILS_DEFAULT = true
        internal const val PREF_LOAD_TITLES_KEY = "pref_load_titles"
        internal const val PREF_LOAD_TITLES_DEFAULT = true
        internal const val PREF_LOAD_DESCRIPTIONS_KEY = "pref_load_descriptions"
        internal const val PREF_LOAD_DESCRIPTIONS_DEFAULT = true

        // Smart Search (session 51/56)
        internal const val PREF_SMART_SEARCH_KEY = "pref_smart_search"
        // ★ session 58: ON by default (user request — "by default the smart search will be turned on")
        internal const val PREF_SMART_SEARCH_DEFAULT = true
        internal const val PREF_SMART_SEARCH_PHRASE_KEY = "pref_smart_search_phrase"
        internal const val PREF_SMART_SEARCH_PHRASE_DEFAULT = "?" // ★ default phrase is question mark
        // ★ session 56/57: engine + Gemini settings
        internal const val PREF_SMART_ENGINE_KEY = "pref_smart_engine"
        // ★ session 58: Google AI Search is the default method (user request — no key needed)
        internal const val PREF_SMART_ENGINE_DEFAULT = "google"
        internal const val PREF_GEMINI_KEY_KEY = "pref_gemini_key"
        internal const val PREF_GEMINI_KEY_DEFAULT = ""
        internal const val PREF_GEMINI_MODEL_KEY = "pref_gemini_model"
        // ★ session 58: default = Gemini 3.1 Flash Lite — top of the list, selected by default,
        // NO "Recommended" label shown (user request)
        internal const val PREF_GEMINI_MODEL_DEFAULT = "gemini-3.1-flash-lite"
        internal const val PREF_GEMINI_CUSTOM_MODEL_KEY = "pref_gemini_custom_model"
        internal const val PREF_GEMINI_CUSTOM_MODEL_DEFAULT = ""
        /** ★ session 58: selectable model ids — ORDER MATTERS (top entry = the default).
         *  All three verified against the live v1beta API (2026-09-13): a bogus model id
         *  404s while these three pass model lookup and reach request validation. */
        internal val GEMINI_MODELS = arrayOf(
            "gemini-3.1-flash-lite",
            "gemini-3.5-flash-lite",
            "gemini-3.8-flash",
        )

        // ★ session 58: "Copy response" — auto-copy the searched query + result (default OFF)
        internal const val PREF_SMART_COPY_RESPONSE_KEY = "pref_smart_copy_response"
        internal const val PREF_SMART_COPY_RESPONSE_DEFAULT = false
    }
}
