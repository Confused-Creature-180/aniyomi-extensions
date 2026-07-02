package eu.kanade.tachiyomi.animeextension.en.anikoto.video

/**
 * In-memory data models for the video pipeline.
 * Per MEMORY/research/apk-reference/02-video-pipeline-and-proxy.md §5.1 + extraction-flows.md.
 */

/** One unit of work: resolve one (server × audio) combo to an [AudioStream]. */
data class HosterTask(
    val label: String,       // display label e.g. "SUB - VidPlay-1", "H-SUB - Kiwi-Stream"
    val token: String,       // data-link-id (primary) or mapper URL (Kiwi)
    val audioType: String,   // "sub", "hsub", "dub" (primary); "sub"/"dub" (mapper → H-SUB/A-DUB)
    val source: String,      // "primary" or "mapper"
)

/** A resolved audio stream with its variants and subtitles. */
data class AudioStream(
    val audioType: String,       // "sub", "hsub", "dub"
    val audioLabel: String,      // "SUB", "HSUB", "DUB", "H-SUB", "A-DUB"
    val hosterName: String,      // "VidPlay-1", "HD-1", "Vidstream-2", "Kiwi-Stream"
    val variants: List<VariantData>,
    val subtitles: List<SubtitleData>,
    val referer: String,         // ★ per-stream Referer for segment/subtitle fetch (e.g. "https://vidwish.live/")
)

/** One HLS variant (quality) with its parsed segments. */
data class VariantData(
    val quality: String,     // "1080p", "720p", "360p"
    val bandwidth: Int,
    val resolution: Int,     // 1080, 720, 360 (0 if unknown)
    val segments: List<SegmentInfo>,
)

/** One segment URL + its duration. */
data class SegmentInfo(
    val url: String,
    val duration: Double,
)

/** One subtitle track. */
data class SubtitleData(
    val url: String,
    val label: String,
    val lang: String,    // ISO 639-2: "eng", "spa", etc.
)

/** The in-memory playlist loaded into the [LocalProxyServer]. */
data class Playlist(
    val streams: List<AudioStream>,
)
