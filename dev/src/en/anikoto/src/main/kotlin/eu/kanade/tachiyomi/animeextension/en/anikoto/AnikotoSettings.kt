package eu.kanade.tachiyomi.animeextension.en.anikoto

import android.content.SharedPreferences
import android.graphics.Color
import android.text.SpannableStringBuilder
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.graphics.Typeface
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat

/**
 * ★ Module: Settings — all preference keys, defaults, typed getters, and the settings UI.
 *
 * Extracted from Anikoto.kt (session 50) so that settings can be managed independently.
 * Modifying the settings UI or adding new preferences does not require touching the main
 * source class — just update this file.
 *
 * ## Preference categories
 * 1. **Playback** — quality, audio, buffer, server
 * 2. **Servers** — Kiwi-Stream toggle
 * 3. **Episode metadata** — thumbnails, titles, descriptions
 * 4. **Smart Search** — AI-powered search toggle + activation phrase (session 51)
 *
 * ## Architecture
 * - [AnikotoSettings] wraps a [SharedPreferences] instance and exposes typed getters.
 * - [setupPreferenceScreen] builds the 3-category settings UI.
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

    /** The activation phrase that triggers smart search (default: empty — disabled).
     *  When the user types this phrase at the start of their search query,
     *  smart search is triggered. Case-insensitive. */
    val smartSearchPhrase: String
        get() = prefs.getString(PREF_SMART_SEARCH_PHRASE_KEY, PREF_SMART_SEARCH_PHRASE_DEFAULT)
            ?: PREF_SMART_SEARCH_PHRASE_DEFAULT

    // ── Settings UI ────────────────────────────────────────────────────

    /**
     * Build the settings preference screen with 4 categories.
     *
     * Categories:
     * 1. **Playback** — quality, audio, buffer, server (all with "Currently: %s")
     * 2. **Servers** — Kiwi-Stream toggle
     * 3. **Episode metadata** — thumbnails, titles, descriptions toggles
     * 4. **Smart Search** — AI-powered search toggle + activation phrase (session 51)
     *
     * All dropdowns show "Currently: %s" so the user can see the current value.
     */
    fun setupPreferenceScreen(screen: PreferenceScreen) {

        // ── Category 1: Playback ────────────────────────────────────────
        PreferenceCategory(screen.context).apply {
            title = "Playback"
            screen.addPreference(this)

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
                entries = arrayOf("Auto", "VidPlay-1", "HD-1", "Vidstream-2", "VidCloud-1", "Kiwi-Stream")
                entryValues = arrayOf("auto", "VidPlay-1", "HD-1", "Vidstream-2", "VidCloud-1", "Kiwi-Stream")
                setDefaultValue(PREF_SERVER_DEFAULT)
                summary = "Currently: %s"
            }.also(::addPreference)
        }

        // ── Category 2: Servers ─────────────────────────────────────────
        PreferenceCategory(screen.context).apply {
            title = "Servers"
            screen.addPreference(this)

            SwitchPreferenceCompat(context).apply {
                key = PREF_ENABLE_KIWI_KEY
                title = "Enable Kiwi-Stream"
                summaryOn = "Fetching Kiwi-Stream from external sources"
                summaryOff = "Kiwi-Stream disabled"
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
                summaryOn = "Fetching preview images from external sources"
                summaryOff = "Episode thumbnails disabled (faster episode list loading)"
                setDefaultValue(PREF_LOAD_THUMBNAILS_DEFAULT)
            }.also(::addPreference)

            SwitchPreferenceCompat(context).apply {
                key = PREF_LOAD_TITLES_KEY
                title = "Load episode titles"
                summaryOn = "Fetching episode titles from external sources"
                summaryOff = "Using default episode numbers only"
                setDefaultValue(PREF_LOAD_TITLES_DEFAULT)
            }.also(::addPreference)

            SwitchPreferenceCompat(context).apply {
                key = PREF_LOAD_DESCRIPTIONS_KEY
                title = "Load episode descriptions"
                summaryOn = "Fetching episode descriptions from external sources"
                summaryOff = "Episode descriptions disabled"
                setDefaultValue(PREF_LOAD_DESCRIPTIONS_DEFAULT)
            }.also(::addPreference)
        }

        // ── Category 4: Smart Search (session 51) ──────────────────────
        PreferenceCategory(screen.context).apply {
            title = "Smart Search"
            screen.addPreference(this)

            SwitchPreferenceCompat(context).apply {
                key = PREF_SMART_SEARCH_KEY
                title = "Enable smart search"
                summaryOn = "AI resolves descriptive queries and corrects spelling"
                summaryOff = "Smart search disabled (normal keyword search only)"
                setDefaultValue(PREF_SMART_SEARCH_DEFAULT)
            }.also(::addPreference)

            EditTextPreference(context).apply {
                key = PREF_SMART_SEARCH_PHRASE_KEY
                title = "Activation phrase"
                dialogTitle = "Activation phrase"
                dialogMessage = "Type this at the start of your search to trigger AI.\n" +
                    "Case-insensitive. Must be followed by a space.\n" +
                    "Leave empty to use AI for all searches."
                setDefaultValue(PREF_SMART_SEARCH_PHRASE_DEFAULT)
                // ★ session 51: Custom summary that shows the actual phrase (not "%s")
                updatePhraseSummary(this, prefs.getString(PREF_SMART_SEARCH_PHRASE_KEY, PREF_SMART_SEARCH_PHRASE_DEFAULT) ?: PREF_SMART_SEARCH_PHRASE_DEFAULT)
                // Update summary when user changes the phrase
                onPreferenceChangeListener = androidx.preference.Preference.OnPreferenceChangeListener { _, newValue ->
                    updatePhraseSummary(this, newValue as? String ?: "")
                    true
                }
            }.also(::addPreference)

            Preference(context).apply {
                title = "Details"
                val currentPhrase = (prefs.getString(PREF_SMART_SEARCH_PHRASE_KEY, PREF_SMART_SEARCH_PHRASE_DEFAULT) ?: PREF_SMART_SEARCH_PHRASE_DEFAULT).ifBlank { "(empty)" }
                val phraseDisplay = if (currentPhrase == "(empty)") "(empty — AI used for all)" else "\"$currentPhrase\""
                summary = "Type your activation phrase at the start of your search to trigger AI.\n" +
                    "Leave empty to use AI for all searches.\n\n" +
                    "Case-insensitive. Must be followed by a space.\n\n" +
                    "Your phrase: $phraseDisplay\n\n" +
                    "Examples:\n" +
                    "• ${currentPhrase.takeIf { it != "(empty)" } ?: "?"} the anime with a russian girl\n" +
                    "• ${currentPhrase.takeIf { it != "(empty)" } ?: "?"} narutp\n" +
                    "• ${currentPhrase.takeIf { it != "(empty)" } ?: "?"} anime about a spy\n\n" +
                    "Note: ~5-8s latency per AI search."
                isSelectable = false
            }.also(::addPreference)
        }
    }

    // ── Smart Search helpers (session 51) ──────────────────────────────

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

        // Smart Search (session 51)
        internal const val PREF_SMART_SEARCH_KEY = "pref_smart_search"
        internal const val PREF_SMART_SEARCH_DEFAULT = false // ★ OFF by default — user must opt in
        internal const val PREF_SMART_SEARCH_PHRASE_KEY = "pref_smart_search_phrase"
        internal const val PREF_SMART_SEARCH_PHRASE_DEFAULT = "?" // ★ default phrase is question mark
    }
}
