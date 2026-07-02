package eu.kanade.tachiyomi.animeextension.en.anikoto

/**
 * Encodes episode metadata into [SEpisode.url].
 *
 * ## Why a URL-safe format (v16.27, session 43)
 *
 * `SEpisode.url` is persisted in the Aniyomi DB and used by BOTH video pipelines:
 * - **New (ext-lib 16):** `getHosterList(episode)` — our override decodes [EpisodeMeta] directly
 * - **Legacy (pre-16):** `getVideoList(episode)` → default does `GET(baseUrl + episode.url)`
 *
 * If `episode.url` is NOT a valid URL path, the legacy pipeline constructs a malformed URL
 * (`baseUrl + "slug/ep-1|..."` → `https://anikototv.toslug/...`) → **DNS failure** in forks.
 *
 * The fix: store a valid URL path with metadata in the **fragment** (`#...`). HTTP clients
 * strip the fragment before sending requests, so the server only sees the clean path, while
 * our extension can still decode the metadata from the full string.
 *
 * See `EXTENSIONS/anikoto/MEMORY/issues-resolutions/04-episode-url-dns-error-in-forks.md` for the full analysis.
 *
 * ## Format
 *
 * **New (v16.27+):** `/watch/<slug>/ep-<epNum>#<malId>|<timestamp>|<dataIds>|<sub?1:0>|<dub?1:0>|<escapedTitle>`
 * - `baseUrl + episode.url` → `https://anikototv.to/watch/<slug>/ep-<epNum>#...` ✅ valid URL
 * - Fragment is stripped by OkHttp before the request is sent
 *
 * **Old (v16.25 and earlier):** `<slug>/ep-<epNum>|<malId>|<timestamp>|<dataIds>|<sub?1:0>|<dub?1:0>|<escapedTitle>`
 * - NOT a valid URL path → caused DNS errors in forks
 * - `decode()` still handles this for backward compatibility (saved episodes in the user's DB)
 *
 * The `|` in epTitle is escaped to `│` (U+2502) to avoid collisions within the fragment.
 */
data class EpisodeMeta(
    val slug: String,
    val epNum: String,
    val malId: String,
    val timestamp: String,
    val dataIds: String,
    val hasSub: Boolean,
    val hasDub: Boolean,
    val epTitle: String,
) {
    fun encode(): String {
        val escapedTitle = epTitle.replace("|", "│")
        val subFlag = if (hasSub) "1" else "0"
        val dubFlag = if (hasDub) "1" else "0"
        val fragment = "$malId|$timestamp|$dataIds|$subFlag|$dubFlag|$escapedTitle"
        return "/watch/$slug/ep-$epNum#$fragment"
    }

    companion object {
        /**
         * Decode [encoded] (the value of [SEpisode.url]) into an [EpisodeMeta].
         *
         * Handles BOTH formats for backward compatibility:
         * - New (v16.27+): `/watch/<slug>/ep-<epNum>#<fragment>`
         * - Old (v16.25-): `<slug>/ep-<epNum>|<malId>|<timestamp>|<dataIds>|<sub>|<dub>|<title>`
         *
         * Returns null if the input doesn't match either format.
         */
        fun decode(encoded: String): EpisodeMeta? {
            return try {
                if (encoded.startsWith("/watch/") && encoded.contains("#")) {
                    // New format: /watch/<slug>/ep-<epNum>#<fragment>
                    val (path, fragment) = encoded.split("#", limit = 2)
                    val pathBody = path.removePrefix("/watch/")
                    val slugEp = pathBody.split("/ep-", limit = 2)
                    val slug = slugEp.getOrNull(0)?.takeIf { it.isNotEmpty() } ?: return null
                    val epNum = slugEp.getOrNull(1) ?: return null
                    val parts = fragment.split("|")
                    if (parts.size < 6) return null
                    val malId = parts[0]
                    val timestamp = parts[1]
                    val dataIds = parts[2]
                    val hasSub = parts[3] == "1"
                    val hasDub = parts[4] == "1"
                    val epTitle = parts.drop(5).joinToString("|").replace("│", "|")
                    EpisodeMeta(slug, epNum, malId, timestamp, dataIds, hasSub, hasDub, epTitle)
                } else if (encoded.contains("|")) {
                    // Old format: <slug>/ep-<epNum>|<malId>|<timestamp>|<dataIds>|<sub>|<dub>|<title>
                    val parts = encoded.split("|")
                    if (parts.size < 7) return null
                    val slugEp = parts[0].split("/ep-")
                    val slug = slugEp.getOrNull(0) ?: return null
                    val epNum = slugEp.getOrNull(1) ?: return null
                    val malId = parts[1]
                    val timestamp = parts[2]
                    val dataIds = parts[3]
                    val hasSub = parts[4] == "1"
                    val hasDub = parts[5] == "1"
                    val epTitle = parts.drop(6).joinToString("|").replace("│", "|")
                    EpisodeMeta(slug, epNum, malId, timestamp, dataIds, hasSub, hasDub, epTitle)
                } else {
                    null
                }
            } catch (e: Exception) {
                null
            }
        }

        /**
         * Extract a valid URL path (no fragment, no encoded metadata) from the value of
         * [SEpisode.url]. Used by [getEpisodeUrl] and [hosterListRequest] to construct
         * clean request URLs regardless of which encode format was used.
         *
         * - New format `/watch/slug/ep-N#fragment` → `/watch/slug/ep-N`
         * - Old format `slug/ep-N|...` → `/watch/slug/ep-N` (reconstructs the path)
         * - Already a clean path → returned as-is
         */
        fun extractUrlPath(encoded: String): String {
            return when {
                encoded.startsWith("/watch/") && encoded.contains("#") -> {
                    encoded.substringBefore("#")
                }
                encoded.startsWith("/watch/") -> {
                    encoded // already a clean path
                }
                encoded.contains("|") -> {
                    // Old format: reconstruct the path from slug/ep-N
                    val firstPart = encoded.substringBefore("|")
                    if (firstPart.contains("/ep-")) {
                        "/watch/$firstPart"
                    } else {
                        "/watch/$firstPart/ep-1"
                    }
                }
                else -> {
                    // Fallback: treat as a slug
                    "/watch/$encoded/ep-1"
                }
            }
        }
    }
}
