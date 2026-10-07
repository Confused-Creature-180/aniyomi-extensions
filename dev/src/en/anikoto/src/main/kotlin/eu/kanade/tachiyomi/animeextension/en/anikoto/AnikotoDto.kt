package eu.kanade.tachiyomi.animeextension.en.anikoto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * All serializable DTOs for the Anikoto site API.
 * Per MEMORY/research/apk-reference/03-catalog-and-dtos.md §4.
 */

// ── Episode list: /ajax/episode/list/{animeId}?vrf=..&style=default ──────────
@Serializable
data class EpisodeListResponse(
    val status: Int = 0,
    val result: String = "",
)

// ── Server list: /ajax/server/list?servers=<data-ids> ────────────────────────
@Serializable
data class ServerListResponse(
    val status: Int = 0,
    val result: String = "",
)

// ── Server resolve: /ajax/server?get=<link-id> ───────────────────────────────
@Serializable
data class ServerResponse(
    val status: Int = 0,
    val result: ServerResult? = null,
)

@Serializable
data class ServerResult(
    val url: String = "",
    @SerialName("skip_data") val skipData: SkipData? = null,
)

@Serializable
data class SkipData(
    val intro: List<Float> = emptyList(),
    val outro: List<Float> = emptyList(),
)

// ── VidTube sources: /stream/getSources(New)?id=<data-id>&type=<audio> ───────
// ★ session 52: megaplay.buzz now ENCRYPTS the video URL — the response carries an
// `enc` blob (AES-256-CBC, see video/MegaPlayDecrypt.kt) instead of plaintext
// sources.file. Both shapes are handled by the parser in AnikotoExtractors.
@Serializable
data class VidTubeSourcesResponse(
    val sources: VidTubeSources? = null,
    val tracks: List<VidTubeTrack> = emptyList(),
    val enc: String? = null,
)

@Serializable
data class VidTubeSources(
    val file: String? = null,
)

@Serializable
data class VidTubeTrack(
    val file: String = "",
    val label: String = "",
    val kind: String = "",
)

// ── Mapper API: mapper.nekostream.site/api/mal/<mal>/<ep>/<ts> ───────────────
// ★ session 63: LIVE-VERIFIED 2026-10-07 response shape (Sakamoto Days ep-4 and 8
// latest-updated shows):
//   {"Kiwi":{"sub":{"download":{"360p":"https://pahe…","720p":…,"1080p":…}},"dub":{…}},
//    "status":{"time":…,"cache_expires_in":…}}
// - Server keys have NO trailing dash ("Kiwi", not "Kiwi-Stream-") — the old parser
//   required `endsWith("-")` and therefore matched NOTHING; the whole mapper path was
//   silently dead.
// - Streaming servers carry {"sub":{"url":…}} / {"dub":{"url":…}} — download-only
//   entries (pahe) carry {"sub":{"download":{…}}} and are correctly skipped.
// - Historical/other keys (yuzono reference): "gogoanime", "anivibe", "animepahe".

data class MapperStreamToken(
    val serverName: String,
    val audio: String,  // "sub" or "dub"
    val token: String,  // full embed/player URL (mapper links are used as-is)
    val label: String,  // display audio label: H-SUB / A-DUB (mapper servers are hardsub per reference)
)

/**
 * Parse the mapper API response into a list of streaming [MapperStreamToken]s.
 *
 * ★ session 63 (v16.16):
 * - Accepts server keys with OR without a trailing dash ("Kiwi", "Kiwi-", "Kiwi-Stream").
 * - Skips non-server metadata keys ("status", "error", "message") case-insensitively.
 * - Normalizes server display names per the yuzono/anikototheme reference:
 *   gogoanime→Vidstream, anivibe→Vibe-Stream, animepahe/kiwi*→Kiwi-Stream.
 * - Only entries with a STREAMING url are emitted (download-only dicts produce nothing).
 */
fun parseMapperResponse(obj: JsonObject): List<MapperStreamToken> {
    val result = mutableListOf<MapperStreamToken>()
    for ((key, value) in obj) {
        val normalizedKey = key.trim().removeSuffix("-")
        if (normalizedKey.isEmpty() ||
            normalizedKey.equals("status", true) ||
            normalizedKey.equals("error", true) ||
            normalizedKey.equals("message", true)
        ) continue
        val serverObj = try {
            value.jsonObject
        } catch (e: Exception) {
            continue
        }
        val serverName = mapperServerDisplayName(normalizedKey)
        for (audio in listOf("sub", "dub")) {
            val url = serverObj[audio]?.let { extractUrl(it) }
            if (!url.isNullOrBlank() && url.startsWith("http")) {
                val label = if (audio == "sub") "H-SUB" else "A-DUB"
                result.add(MapperStreamToken(serverName, audio, url, label))
            }
        }
    }
    return result
}

/** ★ session 63: mirror the yuzono reference's mapMapperServerName(). */
private fun mapperServerDisplayName(key: String): String = when {
    key.equals("gogoanime", true) -> "Vidstream"
    key.equals("anivibe", true) -> "Vibe-Stream"
    key.equals("animepahe", true) -> "Kiwi-Stream"
    key.startsWith("kiwi", true) -> "Kiwi-Stream"
    else -> key.replaceFirstChar { it.uppercase() }
}

private fun extractUrl(el: JsonElement): String? {
    return try {
        el.jsonObject["url"]?.jsonPrimitive?.content
    } catch (e: Exception) {
        null
    }
}
