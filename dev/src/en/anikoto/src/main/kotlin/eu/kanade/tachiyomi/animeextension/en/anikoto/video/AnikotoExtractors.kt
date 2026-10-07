package eu.kanade.tachiyomi.animeextension.en.anikoto.video

import eu.kanade.tachiyomi.animeextension.en.anikoto.AnikotoLog
import eu.kanade.tachiyomi.animeextension.en.anikoto.VidTubeSourcesResponse
import eu.kanade.tachiyomi.animeextension.en.anikoto.VidTubeTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.regex.Pattern

/**
 * Video stream extractors for Anikoto.
 * Per WORKSPACE/WORKFLOW/04_VIDEO_EXTRACTION_PLAYBACK/ANIKOTO/extraction-flows.md.
 *
 * Three flows:
 * - [resolveVidTube] (Flow A): VidPlay-1, HD-1, Vidstream-2, VidCloud-1
 *   iframe → data-id → getSources/getSourcesNew → master m3u8 → variants → segments
 *   ★ session 27: unified to getSources (works on all 3 hosts with the type param).
 *   ★ session 52: megaplay.buzz re-encrypted getSources ("enc" AES blob, sources.file
 *   gone). Now: try getSourcesNew (plaintext again on ALL hosts) first, then getSources
 *   with AES-256-CBC decryption of the "enc" blob via [MegaPlayDecrypt]. See
 *   MEMORY/sites/getsources-migration-and-id-analysis.md §3.
 *   ★ session 27: per-stream Referer stored in AudioStream for the proxy to use.
 *   ★ session 54 (v16.11): megaplay now serves the master m3u8 from ROTATING CDN hosts
 *   (fetch.nexabloom.top, xdw5v.qeltrix.top, megap.shiora.site/.top, ...). The DEFAULT
 *   CDN's master m3u8 rejects non-browser TLS (openresty 403 to OkHttp — Chrome passes;
 *   variants + subtitles on the same host DO work with a megaplay Referer). The site's
 *   own player picks a CDN via the `s=` query param, which lib/newclient.min.js appends
 *   to every getSources call (HD-1's iframe URL carries ?s=tcdn → megap.shiora.*, which
 *   is OkHttp-friendly). We mirror that: try each `s` candidate × [getSourcesNew,
 *   getSources] and VERIFY the master m3u8 is actually fetchable before accepting —
 *   self-correcting against future CDN rotation. WebView (Chrome TLS) is the last-resort
 *   master fetcher.
 * - [resolveKiwi] (Flow B): Kiwi-Stream
 *   iframe URL#<base64-fragment> → decode → direct m3u8 → variants → segments
 *   ★ session 63: mewcdn HOST_MAP support (player page can remap the m3u8 host).
 * - [resolveDirectM3u8] (Flow C): mapper servers that hand out plain master-m3u8 URLs
 *   (no player page, no data-id) — master + variants parsed directly (yuzono parity).
 *   ★ session 63: DECRYPTED megaplay m3u8 URLs get the site player's HMAC CDN token
 *   appended ([addMegaPlayToken] via [parseSourcesBody], enc-shape only).
 */
class AnikotoExtractors(
    private val client: OkHttpClient,
    private val json: Json,
    private val webViewFetcher: WebViewFetcher? = null,
) {
    // ── Flow A: VidTube (VidPlay-1, HD-1, Vidstream-2) ──────────────────────

    /** ★ session 52: parsed getSources/getSourcesNew result — master m3u8 + subtitle tracks.
     *  ★ session 54/60: [masterText] carries the ALREADY-FETCHED master playlist text
     *  (verified fetchable inside [fetchAndVerifySources]) so the winner candidate never
     *  re-fetches it. */
    private data class SourcesData(val masterM3u8: String, val tracks: List<VidTubeTrack>, val masterText: String? = null)

    /**
     * ★ session 60: CDN-candidate loop moved INTO [resolveVidTube] — a candidate must now
     * verify at BOTH stages (master fetchable AND ≥1 variant playlist loaded) before it is
     * accepted; otherwise the loop falls through to the next `s` candidate.
     *
     * WHY (live-verified 2026-09-17, Exiled Heavy Knight ep-12 — the reported
     * "no resolved video streams" bug): the decrypted master for the default/tcdn CDN lives
     * on fetch.nexabloom.top, whose MASTER 403s non-browser TLS (variants on the same host
     * are fine). The old flow accepted a candidate as soon as its master verified — possibly
     * via the WebView fallback (a single shared WebView behind a serialized lock with 30s
     * timeouts, fragile under the 3 parallel server tasks). If the variant stage then failed,
     * resolveVidTube returned null WITHOUT ever trying the OkHttp-friendly bcdn candidate
     * (ncdn.imgnex.top — verified live: master+variants+segments all 200 to plain OkHttp).
     * Fresh episodes resolved 0 streams. The old per-candidate helper fetchSourcesData
     * was removed; [fetchAndVerifySources] (master-stage verify) is unchanged.
     */

    /**
     * ★ session 54: fetch one getSources/getSourcesNew variant, parse it (plaintext or
     * enc blob), then verify the master m3u8 is fetchable. Returns the [SourcesData]
     * with the pre-fetched master text, or null (caller tries the next candidate).
     */
    private suspend fun fetchAndVerifySources(
        host: String,
        endpoint: String,
        dataId: String,
        audioType: String,
        sSuffix: String,
    ): SourcesData? {
        try {
            val url = "https://$host/stream/$endpoint?id=$dataId&type=$audioType$sSuffix"
            AnikotoLog.d("resolveVidTube: [2/5] GET $endpoint$sSuffix: ${AnikotoLog.trunc(url, 90)}")
            val body = fetchString(url, vidtubeApiHeaders(host))
            val parsed = parseSourcesBody(body) ?: run {
                AnikotoLog.w("resolveVidTube: $endpoint response had no usable file (host=$host$sSuffix)")
                return null
            }
            // ★ session 54: verify the master m3u8 is fetchable — the default CDN's master
            // 403s to non-browser TLS. If it fails, fall through to the next s candidate.
            val masterText = try {
                fetchString(parsed.masterM3u8, segHeaders(host))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AnikotoLog.w("resolveVidTube: master m3u8 NOT fetchable via OkHttp ($endpoint$sSuffix) — ${e.message?.take(60)}")
                null
            }
            if (masterText != null && masterText.startsWith("#EXTM3U")) {
                return parsed.copy(masterText = masterText)
            }
            // Last resort: WebView (Chrome TLS) can fetch masters that block OkHttp's TLS.
            if (masterText == null && webViewFetcher != null) {
                try {
                    val webText = webViewFetcher.fetchText(parsed.masterM3u8)
                    if (webText.startsWith("#EXTM3U")) {
                        AnikotoLog.i("resolveVidTube: master fetched via WebView fallback ($endpoint$sSuffix)")
                        return parsed.copy(masterText = webText)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AnikotoLog.w("resolveVidTube: WebView master fetch failed — ${e.message?.take(60)}")
                }
            }
            return null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AnikotoLog.w("resolveVidTube: $endpoint FAILED (host=$host$sSuffix) — ${e.message?.take(80)}")
            return null
        }
    }

    /**
     * Parse a getSources/getSourcesNew JSON body → [SourcesData], handling BOTH shapes
     * (verified live session 52):
     * - plaintext: `{"sources":{"file":"https://...m3u8"},"tracks":[...]}`
     * - encrypted: `{"tracks":[...],"enc":"<base64url AES blob>"}` → decrypt → `{"file":"..."}`
     *
     * ★ session 63 (v16.16): MegaPlay CDN TOKEN — yuzono/anikototheme parity.
     * The site's own player signs every DECRYPTED m3u8 URL with an HMAC-SHA256 token
     * (secret extracted from megaplay's client bundle by the yuzono maintainers):
     *   payload = "<epoch+90s>|<pathKey>"   pathKey = the two 32-hex path segments, lowercased
     *   token   = base64url(payload) + "." + base64url(HMAC-SHA256(secret, payload))
     * Live test 2026-10-07 (Sakamoto ep-4): the CDN currently serves the SAME master with
     * and without the token — but yuzono ships it, it is what the site player does, it is
     * free when unused, and it future-proofs against the CDN starting to REQUIRE it (a
     * likely cause of future "single-variant / 403 master" regressions).
     */
    private fun parseSourcesBody(body: String): SourcesData? {
        val sources = json.decodeFromString(VidTubeSourcesResponse.serializer(), body)
        // Shape 1: plaintext sources.file
        sources.sources?.file?.takeIf { it.startsWith("http") }?.let { file ->
            return SourcesData(file, sources.tracks)
        }
        // Shape 2: encrypted enc blob
        val enc = sources.enc?.takeIf { it.isNotBlank() } ?: return null
        AnikotoLog.i("resolveVidTube: response is ENCRYPTED (enc blob, ${enc.length} chars) — decrypting")
        val decrypted = MegaPlayDecrypt.decrypt(enc) ?: return null
        AnikotoLog.d("resolveVidTube: decrypted enc → ${AnikotoLog.trunc(decrypted, 80)}")
        val file = try {
            (json.parseToJsonElement(decrypted) as? JsonObject)?.get("file")?.jsonPrimitive?.content
        } catch (e: Exception) {
            AnikotoLog.e("resolveVidTube: decrypted enc JSON parse FAILED — ${e.message?.take(80)}")
            null
        }
        return file?.takeIf { it.startsWith("http") }?.let { SourcesData(addMegaPlayToken(it), sources.tracks) }
    }

    /**
     * ★ session 63: append the MegaPlay HMAC token to a decrypted m3u8 URL.
     * Mirrors yuzono's processMegaPlaySource: only for token-less URLs whose path carries
     * the two 32-hex segments (the megaplay CDN signature). Any failure returns the URL
     * unchanged — extraction then behaves exactly as before this feature existed.
     */
    private fun addMegaPlayToken(m3u8: String): String {
        if (m3u8.contains("token=")) return m3u8
        val matcher = PATH_KEY_REGEX.matcher(m3u8)
        if (!matcher.find()) return m3u8
        return try {
            val pathKey = "${matcher.group(1).lowercase()}/${matcher.group(2).lowercase()}"
            val expiry = System.currentTimeMillis() / 1000 + 90
            val payload = "$expiry|$pathKey"
            val mac = javax.crypto.Mac.getInstance("HmacSHA256")
            mac.init(javax.crypto.spec.SecretKeySpec(MEGAPLAY_TOKEN_SECRET.toByteArray(Charsets.UTF_8), "HmacSHA256"))
            val sig = android.util.Base64.encodeToString(
                mac.doFinal(payload.toByteArray(Charsets.UTF_8)),
                android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP,
            ).trimEnd('=')
            val payloadB64 = android.util.Base64.encodeToString(
                payload.toByteArray(Charsets.UTF_8),
                android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP,
            ).trimEnd('=')
            val sep = if (m3u8.contains('?')) "&" else "?"
            val signed = "$m3u8${sep}token=$payloadB64.$sig"
            AnikotoLog.d("resolveVidTube: MegaPlay CDN token appended (${signed.length - m3u8.length} chars)")
            signed
        } catch (e: Exception) {
            AnikotoLog.w("resolveVidTube: MegaPlay token generation failed — ${e.message?.take(60)}")
            m3u8
        }
    }

    suspend fun resolveVidTube(iframeUrl: String, audioType: String, hosterName: String): AudioStream? {
        val host = extractHost(iframeUrl) ?: run {
            AnikotoLog.e("resolveVidTube: could not extract host from $iframeUrl")
            return null
        }
        AnikotoLog.i("resolveVidTube: START hoster=$hosterName audio=$audioType host=$host")
        try {
            // Step 1: GET iframe page → extract data-id
            AnikotoLog.d("resolveVidTube: [1/5] GET iframe page: ${AnikotoLog.trunc(iframeUrl, 80)}")
            val pageHtml = fetchString(iframeUrl, vidtubePageHeaders(host))
            // ★ session 56: pageHtml is reused below for CDN-selector discovery — keep it in scope.
            val dataId = DATA_ID_REGEX.matcher(pageHtml).takeIf { it.find() }?.group(1)
            if (dataId == null) {
                AnikotoLog.e("resolveVidTube: no data-id found in iframe HTML (len=${pageHtml.length})")
                return null
            }
            AnikotoLog.i("resolveVidTube: data-id=$dataId")

            // Step 2: Fetch sources m3u8 + tracks.
            // ★ session 52: megaplay.buzz (HD-1, Vidstream-2) ENCRYPTS its getSources response
            // ("enc" AES-256-CBC blob — decrypted by MegaPlayDecrypt).
            // ★ session 54: the DECRYPTED master m3u8 lives on ROTATING CDN hosts whose
            // DEFAULT member 403s non-browser TLS at the master (openresty; Chrome passes).
            // The site's player appends the page's `s=` CDN-selector to every getSources call
            // (newclient.min.js).
            // ★ session 60: the candidate loop below accepts a candidate only when the master
            // AND ≥1 variant playlist both verify — otherwise it falls through to the next
            // candidate. See the class kdoc for the full ep-12 rationale.
            val sParam = iframeUrl.substringAfter('?', "")
                .substringBefore('#')
                .split('&')
                .firstOrNull { it.startsWith("s=") }
                ?.substringAfter('=')
                ?.takeIf { it.isNotBlank() }
            // ★ session 56/60: discover ALL valid CDN selectors instead of hardcoding one.
            // ★ session 60 ORDER now mirrors measured fetchability: (1) the iframe's own s=
            // (the site player's choice), (2) "bcdn" — the OkHttp-friendly CDN (verified live
            // 2026-09-17: master+variants+segments all 200 to plain OkHttp), (3) selectors
            // named in the iframe page's bypass-check ("X"!==s pattern — self-updating),
            // (4) s= links found in the page, (5) "tcdn" + the default CDN (their MASTER 403s
            // non-browser TLS — reachable only via the WebView fallback, so they go LAST).
            // Old order tried tcdn first for no-s iframes (Vidstream-2/1beta), forcing the
            // fragile WebView detour on every fresh episode.
            val sCandidates = buildList {
                if (!sParam.isNullOrBlank()) add(sParam)
                add("bcdn") // session 56: the OkHttp-friendly CDN (ncdn.imgnex.top) — try first
                for (m in Regex("\"([a-z0-9_]{2,12})\"!==s").findAll(pageHtml)) add(m.groupValues[1])
                for (m in Regex("[?&]s=([a-z0-9_]{2,12})").findAll(pageHtml)) add(m.groupValues[1])
                add("tcdn") // session 54 CDN — master 403s non-browser TLS since ~2026-09-13
                add("")     // default CDN — works in browsers; kept for future-proofing
            }.distinct().take(6)

            // ★ session 60: a candidate wins only when master AND ≥1 variant verify; otherwise
            // we FALL THROUGH to the next candidate instead of aborting (see class kdoc).
            // ★ session 61: partial-load robustness — a candidate that loads only SOME of the
            // master's listed variants (transient CDN/network failure) is no longer accepted
            // immediately; it is remembered as best-partial and the remaining candidates are
            // tried. This prevents a transient 720p/360p playlist failure from permanently
            // narrowing the quality list to 1080p-only ("only one resolution" symptom) when
            // the episode actually has more qualities. Fast paths are unchanged: a candidate
            // that loads ALL its listed variants is still accepted on the spot, so fully
            // healthy episodes AND single-variant masters (1 == 1) resolve with zero extra
            // requests. Only genuine partial failures pay for extra candidates.
            // ★ session 62: THIN-LADDER RICHNESS SCAN — live-verified 2026-10-07 (Sakamoto
            // Days ep-4, user-reported "only two resolutions"): the site now serves SOME
            // shows a single-variant (1080p-only) master on EVERY s-candidate (all 6
            // candidates × both endpoints probed — identical one-line ladders), while other
            // shows list 1080/720/360. Since a LATER CDN candidate can carry a deeper
            // ladder than the first one, a full win with a THIN ladder (≤2 variants) no
            // longer ends the scan: the remaining candidates are still probed and the
            // RICHEST full result wins (ties keep the first — the site player's own CDN
            // choice). Healthy ladders (>2 variants) still break immediately — zero extra
            // requests for fully-working episodes. A seenMasters set skips candidates that
            // resolve to an ALREADY-VERIFIED master URL (different s= values frequently
            // share one master URL), so the extra cost is ~1 getSources call per
            // remaining candidate.
            var chosenSources: SourcesData? = null
            var chosenVariants: List<VariantData> = emptyList()
            var winnerLabel = ""
            var partialSources: SourcesData? = null
            var partialVariants: List<VariantData> = emptyList()
            var partialLabel = ""
            var fullVariantCount = 0
            val seenMasters = mutableSetOf<String>()
            for (s in sCandidates) {
                val sSuffix = if (s.isBlank()) "" else "&s=" + URLEncoder.encode(s, "UTF-8")
                val candidate = fetchAndVerifySources(host, "getSourcesNew", dataId, audioType, sSuffix)
                    ?: fetchAndVerifySources(host, "getSources", dataId, audioType, sSuffix)
                    ?: continue
                // Defensive: the master text was already fetched + verified inside
                // fetchAndVerifySources (that's how the working CDN was chosen).
                val masterText = candidate.masterText
                    ?: fetchString(candidate.masterM3u8, segHeaders(host))
                if (!masterText.startsWith("#EXTM3U")) {
                    AnikotoLog.w("resolveVidTube: candidate s=$s master is not m3u8 (starts with ${masterText.take(30)}) — trying next candidate")
                    continue
                }
                val variantInfos = parseMasterPlaylist(masterText, candidate.masterM3u8)
                if (variantInfos.isEmpty()) {
                    AnikotoLog.w("resolveVidTube: candidate s=$s master has no variants — trying next candidate")
                    continue
                }
                AnikotoLog.i("resolveVidTube: candidate s=$s → ${variantInfos.size} variants: ${variantInfos.joinToString { "${it.quality}(${it.bandwidth})" }}")
                // ★ session 62: skip candidates whose master URL we already verified this run —
                // their ladder is identical by definition, only the getSources call is wasted.
                // ★ session 63 review m3: key on the BASE master URL (token stripped) — the
                // session-63 CDN token embeds a 90s expiry, so the SAME master tokened in
                // different seconds would otherwise defeat the dedup.
                val dedupKey = candidate.masterM3u8.substringBefore("token=").trimEnd('?', '&')
                if (dedupKey in seenMasters) {
                    AnikotoLog.d("resolveVidTube: candidate s=$s resolves to an already-verified master — skipping")
                    continue
                }
                seenMasters += dedupKey
                AnikotoLog.d("resolveVidTube: [4/5] fetching ${variantInfos.size} variant playlists in parallel (NO ad filter)")
                val variants = loadVariantPlaylists(variantInfos, host)
                val label = if (s.isBlank()) "default-CDN" else "s=$s"
                if (variants.isEmpty()) {
                    AnikotoLog.w("resolveVidTube: candidate s=$s verified but 0 variants loaded — trying next candidate")
                    continue
                }
                if (variants.size >= variantInfos.size) {
                    // All listed variants loaded — full result. Keep the FIRST full win
                    // unless a later candidate offers a STRICTLY RICHER ladder.
                    if (chosenSources == null || variantInfos.size > fullVariantCount) {
                        chosenSources = candidate
                        chosenVariants = variants
                        winnerLabel = label
                        fullVariantCount = variantInfos.size
                    }
                    if (variantInfos.size > THIN_LADDER_MAX) {
                        // Healthy multi-variant ladder — fast path, stop scanning.
                        break
                    }
                    // Thin ladder (1–2 variants) — keep scanning for a richer CDN candidate.
                    AnikotoLog.i(
                        "resolveVidTube: candidate s=$s is a full win but THIN (${variantInfos.size} variant(s)) — " +
                            "scanning remaining candidates for a richer ladder",
                    )
                    continue
                }
                AnikotoLog.w(
                    "resolveVidTube: candidate s=$s loaded only ${variants.size}/${variantInfos.size} variants " +
                        "(transient CDN failure?) — trying next candidate, keeping this as fallback",
                )
                if (variants.size > partialVariants.size) {
                    partialSources = candidate
                    partialVariants = variants
                    partialLabel = label
                }
            }
            if (chosenSources == null && partialSources != null) {
                // No candidate loaded its full variant list — use the fullest partial result.
                AnikotoLog.w(
                    "resolveVidTube: no fully-loading candidate — using best partial " +
                        "($partialLabel, ${partialVariants.size} variants)",
                )
                chosenSources = partialSources
                chosenVariants = partialVariants
                winnerLabel = partialLabel
            }
            val sourcesData = chosenSources ?: run {
                AnikotoLog.e("resolveVidTube: no candidate produced a verifiable stream (host=$host, sCandidates=$sCandidates)")
                return null
            }
            val variantDataList = chosenVariants
            val masterM3u8 = sourcesData.masterM3u8
            AnikotoLog.i("resolveVidTube: m3u8=${AnikotoLog.trunc(masterM3u8, 80)} (winner=$winnerLabel)")
            // ★ session 61: make the "only one quality" case self-explanatory in logs.
            // Live-verified 2026-10-07: some shows/episodes ship a single-variant HLS master
            // (beyblade-x-aj6fn = 1080p-only, sakamoto-days = 1080p-only on every episode,
            // every mirror, server entry, s-candidate and endpoint — the raw master text
            // contains exactly one #EXT-X-STREAM-INF plus an I-FRAME trick-play track), and
            // the site's own player shows the same single quality. It is a source limitation,
            // NOT an extraction bug. The settings screen carries a user-facing note about it.
            if (variantDataList.size == 1) {
                AnikotoLog.i(
                    "resolveVidTube: master has a SINGLE variant (${variantDataList[0].quality}) — " +
                        "the site itself only serves this quality for this episode (not an extraction failure)",
                )
            }
            AnikotoLog.i("resolveVidTube: subs=${sourcesData.tracks.size} track(s)")

            // Step 5: build subtitles
            val subtitles = sourcesData.tracks.mapNotNull { track ->
                if (track.file.startsWith("http") && track.label.isNotEmpty()) {
                    SubtitleData(track.file, track.label, inferLang(track.label))
                } else null
            }

            val audioLabel = when (audioType) {
                "sub" -> "SUB"
                "hsub" -> "HSUB"
                "dub" -> "DUB"
                else -> audioType.uppercase()
            }
            // ★ per-stream Referer: the proxy must send the iframe host as Referer when
            // fetching segments/subtitles. VidCloud-1 segments (on cloudvideo.lat) 403
            // with vidtube.site Referer; they need vidwish.live. Same pattern for other hosts.
            val streamReferer = "https://$host/"
            AnikotoLog.i("resolveVidTube: SUCCESS hoster=$hosterName audio=$audioLabel variants=${variantDataList.size} subs=${subtitles.size} referer=$streamReferer")
            return AudioStream(audioType, audioLabel, hosterName, variantDataList, subtitles, streamReferer)
        } catch (e: CancellationException) {
            throw e // ★ session 64 review: never swallow cancellation (matches loadVariantPlaylists invariant)
        } catch (e: Exception) {
            AnikotoLog.e("resolveVidTube: FAILED hoster=$hosterName audio=$audioType", e)
            return null
        }
    }

    /**
     * ★ session 60: fetch + parse every variant playlist, with a per-variant WebView
     * (Chrome TLS) fallback when OkHttp fails. CDN rotation can spread the master-only-403
     * pattern to variant playlists at any time — previously that silently emptied the
     * variant list and killed the whole server. Semaphore keeps concurrency at 2 (session 51).
     */
    private suspend fun loadVariantPlaylists(variantInfos: List<VariantInfo>, host: String): List<VariantData> =
        coroutineScope {
            variantInfos.map { v ->
                async(Dispatchers.IO) {
                    variantSemaphore.withPermit {
                        try {
                            val varText = try {
                                fetchString(v.url, segHeaders(host))
                            } catch (e: CancellationException) {
                                throw e // ★ MUST re-throw — never swallow CancellationException
                            } catch (e: Exception) {
                                if (webViewFetcher == null) throw e
                                AnikotoLog.w("resolveVidTube: variant ${v.quality} OkHttp fetch failed (${e.message?.take(50)}) — WebView fallback")
                                webViewFetcher.fetchText(v.url)
                            }
                            val segs = parseVariantSegments(varText, v.url, filterAds = false)
                            AnikotoLog.d("resolveVidTube:   variant ${v.quality}: ${segs.size} segments (all kept, no filter)")
                            if (segs.isNotEmpty()) VariantData(v.quality, v.bandwidth, v.resolution, segs) else null
                        } catch (e: CancellationException) {
                            throw e // ★ MUST re-throw — never swallow CancellationException
                        } catch (e: Exception) {
                            AnikotoLog.e("resolveVidTube:   variant ${v.quality} fetch FAILED: ${e.message}")
                            null
                        }
                    }
                }
            }.awaitAll().filterNotNull()
        }

    // ── Flow C: direct m3u8 (mapper servers that hand out plain HLS URLs) ─────

    /**
     * ★ session 63 (v16.16, review fix m2): yuzono/anikototheme parity — the mapper can
     * return entries whose URL IS the master m3u8 (no player page, no data-id). The old
     * dispatch sent those into resolveVidTube, which found no data-id and silently
     * dropped the server. Parse the master + variants directly instead.
     */
    suspend fun resolveDirectM3u8(m3u8Url: String, audioType: String, hosterName: String, referer: String): AudioStream? {
        AnikotoLog.i("resolveDirectM3u8: START hoster=$hosterName audio=$audioType url=${AnikotoLog.trunc(m3u8Url, 80)}")
        return try {
            val headers = Headers.Builder()
                .set("User-Agent", BROWSER_UA)
                .set("Referer", referer)
                .set("Accept", "*/*")
                .build()
            val masterText = fetchString(m3u8Url, headers)
            if (!masterText.startsWith("#EXTM3U")) {
                AnikotoLog.e("resolveDirectM3u8: not an m3u8 (starts with ${masterText.take(40)})")
                return null
            }
            val variantInfos = parseMasterPlaylist(masterText, m3u8Url)
            if (variantInfos.isEmpty()) {
                AnikotoLog.e("resolveDirectM3u8: no variants in master m3u8")
                return null
            }
            AnikotoLog.i("resolveDirectM3u8: ${variantInfos.size} variants: ${variantInfos.joinToString { it.quality }}")
            val variantDataList = coroutineScope {
                variantInfos.map { v ->
                    async(Dispatchers.IO) {
                        variantSemaphore.withPermit {
                            try {
                                val varText = fetchString(v.url, headers)
                                val segs = parseVariantSegments(varText, v.url, filterAds = false)
                                if (segs.isNotEmpty()) VariantData(v.quality, v.bandwidth, v.resolution, segs) else null
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                AnikotoLog.e("resolveDirectM3u8:   variant ${v.quality} fetch FAILED: ${e.message}")
                                null
                            }
                        }
                    }
                }.awaitAll().filterNotNull()
            }
            if (variantDataList.isEmpty()) {
                AnikotoLog.e("resolveDirectM3u8: no variants could be loaded")
                return null
            }
            val audioLabel = if (audioType == "sub") "H-SUB" else "A-DUB"
            AnikotoLog.i("resolveDirectM3u8: SUCCESS hoster=$hosterName audio=$audioLabel variants=${variantDataList.size} referer=$referer")
            AudioStream(audioType, audioLabel, hosterName, variantDataList, emptyList(), referer)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AnikotoLog.e("resolveDirectM3u8: FAILED hoster=$hosterName audio=$audioType", e)
            return null
        }
    }

    // ── Flow B: Kiwi-Stream (base64 fragment → direct m3u8) ──────────────────

    suspend fun resolveKiwi(iframeUrl: String, audioType: String, hosterName: String): AudioStream? {
        AnikotoLog.i("resolveKiwi: START hoster=$hosterName audio=$audioType")
        try {
            // Step 1: decode base64 fragment → direct m3u8 URL
            val fragment = iframeUrl.substringAfter("#")
            if (fragment.isBlank()) {
                AnikotoLog.e("resolveKiwi: no #fragment in iframe URL")
                return null
            }
            val masterM3u8 = try {
                android.util.Base64.decode(fragment, android.util.Base64.DEFAULT)
                    .toString(Charsets.ISO_8859_1)
                    .trim() // ★ session 63 review n3: tolerate whitespace-padded fragments
            } catch (e: Exception) {
                AnikotoLog.e("resolveKiwi: base64 decode failed", e)
                return null
            }
            if (!masterM3u8.startsWith("http")) {
                AnikotoLog.e("resolveKiwi: decoded fragment is not a URL: ${masterM3u8.take(60)}")
                return null
            }
            AnikotoLog.i("resolveKiwi: decoded m3u8=${AnikotoLog.trunc(masterM3u8, 80)}")

            // ★ session 63 (v16.16): HOST_MAP — yuzono/anikototheme parity.
            // The mewcdn player page can carry `var HOST_MAP = {"origin":"proxy", ...}` —
            // the player rewrites the decoded m3u8's host through it before fetching.
            // Without this, a mapped CDN host would be fetched directly and fail (or the
            // correct proxy host would never be used). Defensive: any failure keeps the
            // original URL and the previous referer behavior.
            var effectiveM3u8 = masterM3u8
            var playerReferer = "https://vibeplayer.site/"
            try {
                val playerHost = extractHost(iframeUrl) ?: "mewcdn.online"
                val pageHtml = fetchString(
                    iframeUrl,
                    Headers.Builder()
                        .set("User-Agent", BROWSER_UA)
                        .set("Referer", "https://$playerHost/")
                        .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                        .build(),
                )
                val hostMap = parseHostMap(pageHtml)
                if (hostMap.isNotEmpty()) {
                    for ((origin, proxy) in hostMap) {
                        if (effectiveM3u8.contains(origin)) {
                            effectiveM3u8 = effectiveM3u8.replace(origin, proxy)
                            AnikotoLog.i("resolveKiwi: HOST_MAP applied $origin → $proxy")
                            break
                        }
                    }
                }
                playerReferer = "https://$playerHost/"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AnikotoLog.w("resolveKiwi: player page/HOST_MAP fetch failed (${e.message?.take(60)}) — using decoded URL directly")
            }

            // Step 2: parse master m3u8 (Referer: the player host — where the m3u8 lives).
            AnikotoLog.d("resolveKiwi: [2/4] fetching master m3u8")
            val masterText = fetchString(effectiveM3u8, kiwiHeaders(playerReferer))
            if (!masterText.startsWith("#EXTM3U")) {
                AnikotoLog.e("resolveKiwi: master is not m3u8 (starts with ${masterText.take(40)})")
                return null
            }
            val variantInfos = parseMasterPlaylist(masterText, effectiveM3u8)
            if (variantInfos.isEmpty()) {
                AnikotoLog.e("resolveKiwi: no variants in master m3u8")
                return null
            }
            AnikotoLog.i("resolveKiwi: ${variantInfos.size} variants: ${variantInfos.joinToString { it.quality }}")

            // Step 3: for each variant, fetch + parse segments (★ NO ad filtering for Kiwi)
            // ★ session 51: parallelized — all variants fetched concurrently.
            AnikotoLog.d("resolveKiwi: [3/4] fetching ${variantInfos.size} variant playlists in parallel (NO ad filter)")
            val variantDataList = coroutineScope {
                variantInfos.map { v ->
                    async(Dispatchers.IO) {
                        variantSemaphore.withPermit {
                            try {
                                val varText = fetchString(v.url, kiwiHeaders(playerReferer))
                                val segs = parseVariantSegments(varText, v.url, filterAds = false)
                                AnikotoLog.d("resolveKiwi:   variant ${v.quality}: ${segs.size} segments (no filter)")
                                if (segs.isNotEmpty()) VariantData(v.quality, v.bandwidth, v.resolution, segs) else null
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                AnikotoLog.e("resolveKiwi:   variant ${v.quality} fetch FAILED: ${e.message}")
                                null
                            }
                        }
                    }
                }.awaitAll().filterNotNull()
            }
            if (variantDataList.isEmpty()) {
                AnikotoLog.e("resolveKiwi: no variants could be loaded")
                return null
            }

            // Kiwi labels: mapper "sub" = H-SUB, "dub" = A-DUB
            val audioLabel = if (audioType == "sub") "H-SUB" else "A-DUB"
            // ★ per-stream Referer for Kiwi: the player host (where the m3u8 lives).
            // The proxy uses this for segment fetches. Matches the kiwiHeaders(playerReferer)
            // used for the master AND every variant playlist fetch above (session 63 review
            // fix M1 — previously the variant fetch used the default vibeplayer referer,
            // giving three different referers in one flow, exactly the mismatch that
            // HOST_MAP-mapped hosts would reject).
            val streamReferer = playerReferer
            AnikotoLog.i("resolveKiwi: SUCCESS hoster=$hosterName audio=$audioLabel variants=${variantDataList.size} referer=$streamReferer")
            return AudioStream(audioType, audioLabel, hosterName, variantDataList, emptyList(), streamReferer)
        } catch (e: CancellationException) {
            throw e // ★ session 64 review: never swallow cancellation
        } catch (e: Exception) {
            AnikotoLog.e("resolveKiwi: FAILED hoster=$hosterName audio=$audioType", e)
            return null
        }
    }

    // ── HLS parsing helpers ──────────────────────────────────────────────────

    data class VariantInfo(
        val url: String,
        val bandwidth: Int,
        val quality: String,
        val resolution: Int,
    )

    private fun parseMasterPlaylist(text: String, masterUrl: String): List<VariantInfo> {
        val base = masterUrl.substringBeforeLast("/") + "/"
        val variants = mutableListOf<VariantInfo>()
        val lines = text.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.startsWith("#EXT-X-STREAM-INF:")) {
                val nextLine = lines.getOrNull(i + 1)?.trim() ?: ""
                if (nextLine.isNotEmpty() && !nextLine.startsWith("#")) {
                    val bandwidth = Regex("BANDWIDTH=(\\d+)").find(line)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    val resStr = Regex("RESOLUTION=(\\d+)x(\\d+)").find(line)?.groupValues?.get(2) ?: ""
                    val name = Regex("""NAME="([^"]+)"""").find(line)?.groupValues?.get(1)
                    val resolution = resStr.toIntOrNull() ?: 0
                    // ★ session 56: RESOLUTION is the ground truth — megaplay's playlist ships
                    // mislabeled NAMEs (verified live: RESOLUTION=640x360 NAME="480p"), and the
                    // site's own player displays the RESOLUTION height (hls.js level height),
                    // which is why the app showed 480p while the site showed 360p.
                    // Prefer RESOLUTION; fall back to NAME only when RESOLUTION is missing.
                    val quality = when {
                        resolution > 0 -> "${resolution}p"
                        !name.isNullOrBlank() && name != "Unknown" -> name
                        else -> "auto"
                    }
                    val url = if (nextLine.startsWith("http")) nextLine else base + nextLine
                    variants.add(VariantInfo(url, bandwidth, quality, resolution))
                    i += 2
                } else { i++ }
            } else { i++ }
        }
        return variants
    }

    /**
     * Parse a media playlist into [SegmentInfo]s.
     * @param filterAds if true, keep only segments on nekostream.site (real CDN).
     *                  If false, keep all segments (Kiwi — all on ad CDN).
     */
    private fun parseVariantSegments(text: String, variantUrl: String, filterAds: Boolean): List<SegmentInfo> {
        val base = variantUrl.substringBeforeLast("/") + "/"
        val segments = mutableListOf<SegmentInfo>()
        val lines = text.lines()
        var i = 0
        while (i < lines.size) {
            if (lines[i].startsWith("#EXTINF:")) {
                val duration = lines[i].substringAfter("#EXTINF:").substringBefore(",").toDoubleOrNull() ?: 0.0
                val nextLine = lines.getOrNull(i + 1)?.trim() ?: ""
                if (nextLine.isNotEmpty() && !nextLine.startsWith("#")) {
                    val url = if (nextLine.startsWith("http")) nextLine else base + nextLine
                    if (!filterAds || isRealSegment(url)) {
                        segments.add(SegmentInfo(url, duration))
                    }
                    i += 2
                } else { i++ }
            } else { i++ }
        }
        return segments
    }

    /** Keep nekostream.site (real CDN), drop ipstatp.com/ibyteimg.com (ad CDN). */
    private fun isRealSegment(url: String): Boolean = url.contains("nekostream.site")

    // ── HTTP + headers ───────────────────────────────────────────────────────

    /**
     * ★ session 30-31: Fetch a URL as a string, with WebView fallback for WAF-blocked CDNs.
     *
     * OkHttp's TLS fingerprint (Conscrypt/JA3) is blocked by cdn.mewstream.buzz's WAF.
     * For known WAF-blocked hosts, skip OkHttp and go straight to WebViewFetcher (Chrome's TLS).
     * For other hosts, try OkHttp first — VidCloud-1/VidPlay-1/Kiwi never trigger the fallback.
     */
    private suspend fun fetchString(url: String, headers: Headers): String = withContext(Dispatchers.IO) {
        // ★ session 31: for WAF-blocked CDN hosts, go straight to WebView (skip OkHttp 403 wait)
        if (isWafBlockedHost(url) && webViewFetcher != null) {
            return@withContext webViewFetcher.fetchText(url)
        }
        val req = Request.Builder().url(url).headers(headers).build()
        try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
                resp.body?.string() ?: throw RuntimeException("Empty body")
            }
        } catch (e: CancellationException) {
            throw e // ★ session 64 review: cancellation must not divert into a WebView fallback
        } catch (e: Exception) {
            // Fallback to WebView for WAF hosts on any error
            if (isWafBlockedHost(url) && webViewFetcher != null) {
                AnikotoLog.i("fetchString: OkHttp failed (${e.message?.take(50)}), falling back to WebView for ${AnikotoLog.trunc(url, 60)}")
                webViewFetcher.fetchText(url)
            } else {
                throw e
            }
        }
    }

    /** ★ session 31: WAF-blocked CDN hosts that require WebView (Chrome TLS). */
    private fun isWafBlockedHost(url: String): Boolean {
        return url.contains("mewstream.buzz") ||
            url.contains("voltara.click") ||
            url.contains("zaptrix.buzz")
    }

    private fun extractHost(url: String): String? {
        return try {
            val m = HOST_REGEX.matcher(url)
            if (m.find()) m.group(1) else null
        } catch (e: Exception) { null }
    }

    private fun vidtubePageHeaders(host: String) = Headers.Builder()
        .set("User-Agent", BROWSER_UA)
        .set("Referer", "https://$host/")
        .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .build()

    private fun vidtubeApiHeaders(host: String) = Headers.Builder()
        .set("User-Agent", BROWSER_UA)
        .set("Referer", "https://$host/")
        .set("X-Requested-With", "XMLHttpRequest")
        .set("Accept", "*/*")
        .build()

    private fun segHeaders(host: String) = Headers.Builder()
        .set("User-Agent", BROWSER_UA)
        .set("Referer", "https://$host/")
        .set("Accept", "*/*")
        .build()

    private fun kiwiHeaders(referer: String = "https://vibeplayer.site/") = Headers.Builder()
        .set("User-Agent", BROWSER_UA)
        .set("Referer", referer)
        .set("Origin", referer.trimEnd('/'))
        .set("Accept", "*/*")
        .build()

    /**
     * ★ session 63: parse `var HOST_MAP = {"origin":"proxy", ...}` from a mewcdn player
     * page (yuzono parity). Returns an empty map when the page carries no HOST_MAP.
     */
    private fun parseHostMap(html: String): Map<String, String> {
        return try {
            val mapMatch = HOST_MAP_REGEX.find(html) ?: return emptyMap()
            HOST_ENTRY_REGEX.findAll(mapMatch.groupValues[1])
                .associate { it.groupValues[1] to it.groupValues[2] }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun inferLang(label: String): String = when {
        label.contains("English", true) -> "eng"
        label.contains("Spanish", true) -> "spa"
        label.contains("French", true) -> "fra"
        label.contains("German", true) -> "deu"
        label.contains("Portuguese", true) -> "por"
        label.contains("Japanese", true) -> "jpn"
        else -> "und"
    }

    companion object {
        // ★ session 62: full wins with ≤ this many variants keep scanning the remaining
        // s-candidates for a richer ladder (see resolveVidTube). Ladders above this break
        // immediately — healthy episodes pay zero extra requests.
        private const val THIN_LADDER_MAX = 2

        // ★ session 51: limit concurrent variant playlist fetches to 2.
        // With N server tasks × M variants, parallel fetching could create 40+ concurrent
        // requests, overwhelming the CDN. The semaphore limits each server's variant
        // fetches to 2 at a time — enough for parallelism but safe for CDNs.
        private val variantSemaphore = Semaphore(2)

        // ★ session 29: DESKTOP Chrome UA (not mobile). The cdn.mewstream.buzz WAF rejects
        // mobile UAs — verified via the reference Next.js project which uses this exact UA
        // and successfully fetches from cdn.mewstream.buzz. Mobile Chrome UA gets 403.
        // Also: no Accept-Language header (the reference project sends minimal headers;
        // extra headers can trigger WAF rules).
        private const val BROWSER_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        private val DATA_ID_REGEX: Pattern = Pattern.compile("""data-id="(\d+)"""")
        private val HOST_REGEX: Pattern = Pattern.compile("https?://([^/]+)")

        // ★ session 63: MegaPlay CDN token (yuzono parity) — secret + path signature.
        private const val MEGAPLAY_TOKEN_SECRET = "MpCdnT0k3n!9f2K#xQ7vL5mR8wN1pY4s"
        private val PATH_KEY_REGEX: Pattern = Pattern.compile("/([a-f0-9]{32})/([a-f0-9]{32})/", Pattern.CASE_INSENSITIVE)

        // ★ session 63: mewcdn player HOST_MAP patterns (yuzono parity).
        private val HOST_MAP_REGEX = Regex("""var HOST_MAP\s*=\s*\{([^}]+)\}""")
        private val HOST_ENTRY_REGEX = Regex("""'([^']+)'\s*:\s*'([^']+)'""")
    }
}
