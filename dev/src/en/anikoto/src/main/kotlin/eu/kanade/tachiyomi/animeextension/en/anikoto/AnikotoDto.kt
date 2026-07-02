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

// ── VidTube sources: /stream/getSourcesNew?id=<data-id>&type=<audio> ─────────
@Serializable
data class VidTubeSourcesResponse(
    val sources: VidTubeSources? = null,
    val tracks: List<VidTubeTrack> = emptyList(),
)

@Serializable
data class VidTubeSources(
    val file: String = "",
)

@Serializable
data class VidTubeTrack(
    val file: String = "",
    val label: String = "",
    val kind: String = "",
)

// ── Mapper API: mapper.nekostream.site/api/mal/<mal>/<ep>/<ts> ───────────────
// Response shape: {"Kiwi-Stream-": {"sub": {"url": "..."}, "dub": {"url": "..."}}, "status": {...}}
// Keys ending with "-" are server entries.

data class MapperStreamToken(
    val serverName: String,
    val audio: String, // "sub" or "dub"
    val token: String, // actually a URL (base64 token to pass to /ajax/server?get=)
)

/**
 * Parse the mapper API response into a list of [MapperStreamToken]s.
 * Only processes keys ending with "-" (server entries), looking for "sub" and "dub" children.
 */
fun parseMapperResponse(obj: JsonObject): List<MapperStreamToken> {
    val result = mutableListOf<MapperStreamToken>()
    for ((key, value) in obj) {
        if (!key.endsWith("-")) continue // skip "status" etc.
        val serverName = key.removeSuffix("-")
        val serverObj = try {
            value.jsonObject
        } catch (e: Exception) {
            continue
        }
        for (audio in listOf("sub", "dub")) {
            val url = serverObj[audio]?.let { extractUrl(it) }
            if (url != null) {
                result.add(MapperStreamToken(serverName, audio, url))
            }
        }
    }
    return result
}

private fun extractUrl(el: JsonElement): String? {
    return try {
        el.jsonObject["url"]?.jsonPrimitive?.content
    } catch (e: Exception) {
        null
    }
}
