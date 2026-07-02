package eu.kanade.tachiyomi.animeextension.en.anikoto

import android.app.Application
import android.content.SharedPreferences
import android.widget.Toast
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.en.anikoto.video.AnikotoExtractors
import eu.kanade.tachiyomi.animeextension.en.anikoto.video.AudioStream
import eu.kanade.tachiyomi.animeextension.en.anikoto.video.HosterTask
import eu.kanade.tachiyomi.animeextension.en.anikoto.video.LocalProxyServer
import eu.kanade.tachiyomi.animeextension.en.anikoto.video.Playlist
import eu.kanade.tachiyomi.animeextension.en.anikoto.video.WebViewFetcher
import eu.kanade.tachiyomi.animeextension.en.anikoto.metadata.EpisodeMetadataFetcher
import eu.kanade.tachiyomi.animeextension.en.anikoto.smartsearch.SmartSearch
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.AnimeUpdateStrategy
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class Anikoto : AnimeHttpSource(), ConfigurableAnimeSource {

    override val name = "AniKoto 180"
    override val baseUrl = "https://anikototv.to"
    override val lang = "en"
    override val supportsLatest = true
    override val versionId = 11

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    // ── Preferences (delegated to AnikotoSettings) ────────────────────
    // All preference keys, defaults, typed getters, and the settings UI are
    // in AnikotoSettings.kt — modify that file to change settings.
    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0)
    }

    private val settings: AnikotoSettings by lazy {
        AnikotoSettings(preferences)
    }

    private val preferredQuality: String get() = settings.preferredQuality
    private val preferredAudio: String get() = settings.preferredAudio
    private val prefetchBuffer: String get() = settings.prefetchBuffer
    private val preferredServer: String get() = settings.preferredServer
    private val enableKiwi: Boolean get() = settings.enableKiwi
    private val loadThumbnails: Boolean get() = settings.loadThumbnails
    private val loadTitles: Boolean get() = settings.loadTitles
    private val loadDescriptions: Boolean get() = settings.loadDescriptions
    private val smartSearchEnabled: Boolean get() = settings.smartSearchEnabled
    private val smartSearchPhrase: String get() = settings.smartSearchPhrase

    // ── Clients ──────────────────────────────────────────────────────────
    // ★ session 28: use the inherited `client` (app's network.client) for extractors + proxy.
    // The inherited client has the CloudflareInterceptor + cookieJar, which is REQUIRED for
    // cdn.mewstream.buzz (Vidstream-2/HD-1). That CDN returns HTTP 403 to OkHttp due to
    // Cloudflare WAF bot detection (likely TLS fingerprint). The CloudflareInterceptor opens
    // a WebView (Chrome engine, real TLS fingerprint) to obtain a cf_clearance cookie, then
    // retries. Without this, Vidstream-2 fails with "HTTP 403" at the master m3u8 fetch.
    // The interceptor only triggers on 403/503 from Server: cloudflare — VidCloud-1 (200) is
    // unaffected. See EXTENSIONS/anikoto/MEMORY/sites/getsources-migration-and-id-analysis.md §3.
    @Suppress("unused") // kept for reference; no longer used after session 28
    private val noCloudflareClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    // ★ Dedicated proxy fetch client with LONGER timeouts for segment downloads.
    // Derived from the inherited `client` (preserves CloudflareInterceptor + cookieJar).
    // The cf_clearance cookie (obtained during getHosterList) is reused for segment fetches.
    private val proxyFetchClient: OkHttpClient by lazy {
        client.newBuilder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS) // no call timeout — let segments download fully
            .retryOnConnectionFailure(true)
            .build()
    }

    // ★ session 30: WebView-based fetcher for WAF-blocked CDNs (cdn.mewstream.buzz).
    // Uses Chrome's TLS via WebView to bypass the WAF that blocks OkHttp's Conscrypt TLS.
    // Only triggered as a fallback when OkHttp returns 403.
    private val webViewFetcher: WebViewFetcher by lazy {
        WebViewFetcher(Injekt.get<Application>(), "https://megaplay.buzz/")
    }

    private val extractors: AnikotoExtractors by lazy {
        AnikotoExtractors(client, json, webViewFetcher)
    }

    // ★ session 35-36: Episode metadata fetcher — uses inherited `client` (CloudflareInterceptor).
    // AniList + Anikage.cc are both behind Cloudflare and block OkHttp's Conscrypt TLS (same WAF
    // as cdn.mewstream.buzz). The inherited client has the CloudflareInterceptor which handles 403.
    // Also passes the WebViewFetcher for fallback on WAF-blocked hosts.
    private val metadataFetcher: EpisodeMetadataFetcher by lazy {
        EpisodeMetadataFetcher(client, json, webViewFetcher)
    }

    // ★ session 51: Smart Search module — AI-powered search via Google AI Search.
    // Self-contained in smartsearch/ package. To remove: delete the package, remove this
    // field, remove getSearchAnime() override, remove smart search settings from AnikotoSettings.
    private val smartSearch: SmartSearch by lazy {
        SmartSearch(webViewFetcher)
    }

    // ── Headers ──────────────────────────────────────────────────────────
    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("User-Agent", "Mozilla/5.0")
        .set("Referer", "$baseUrl/")
        .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .set("Accept-Language", "en-US,en;q=0.9")

    private fun xhrHeaders(referer: String = "$baseUrl/"): Headers = headersBuilder()
        .set("Referer", referer)
        .set("X-Requested-With", "XMLHttpRequest")
        .build()

    // ── Popular ──────────────────────────────────────────────────────────
    override fun popularAnimeRequest(page: Int): Request =
        GET("$baseUrl/most-viewed?page=$page", headers)

    override fun popularAnimeParse(response: Response): AnimesPage = parseFilterResults(response)

    // ── Latest ───────────────────────────────────────────────────────────
    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/latest-updated?page=$page", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = parseFilterResults(response)

    // ── Search ───────────────────────────────────────────────────────────
    // Always use the paginated /filter endpoint (40 items/page, proper next-page links).
    // The /ajax/anime/search autosuggest endpoint returns only ~5 results and is now
    // returning HTTP 500 on the live site (verified 2026-06-26). The /filter endpoint
    // accepts an optional `keyword` param that works for both empty and non-empty queries,
    // AND respects all filters below — so search + filters work together. Verified live.
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request =
        GET("$baseUrl/filter?keyword=${URLEncoder.encode(query, "UTF-8")}&${AnikotoFilters.buildQuery(filters)}&page=$page", headers)

    override fun searchAnimeParse(response: Response): AnimesPage = parseFilterResults(response)

    // ── Smart Search (session 51) ───────────────────────────────────────
    // ★ session 51: AI-powered search. Logic is in the smartsearch/ package.
    // This section just wires it into the search flow and handles toast notifications.

    /** ★ session 51: Show a toast notification to the user (on main thread). */
    private suspend fun showToast(message: String) {
        try {
            val app = Injekt.get<Application>()
            withContext(Dispatchers.Main) {
                Toast.makeText(app, message, Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            AnikotoLog.e("SmartSearch: failed to show toast", e)
        }
    }

    /**
     * ★ session 51: Override getSearchAnime to intercept smart search queries.
     *
     * If smart search is triggered (toggle ON + phrase matches):
     * 1. Strip the phrase
     * 2. Check cache (for pagination — page 2+ reuses)
     * 3. Resolve via AI → ONE anime title
     * 4. Search anikototv.to for that title
     * 5. If 0 results on page 1, retry with first 3 significant words
     *
     * If anything fails, shows a toast and falls back to normal search.
     */
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        if (!smartSearch.shouldTrigger(query, smartSearchEnabled, smartSearchPhrase)) {
            return super.getSearchAnime(page, query, filters)
        }

        AnikotoLog.i("SmartSearch: triggered (page=$page, query=\"$query\")")
        val strippedQuery = smartSearch.stripPhrase(query, smartSearchPhrase)

        if (strippedQuery.isBlank()) {
            AnikotoLog.w("SmartSearch: empty query after phrase strip, falling back to normal search")
            return super.getSearchAnime(page, query, filters)
        }

        // Check cache (for pagination)
        val cachedTitle = smartSearch.getCachedTitle(strippedQuery, page)
        val title = if (cachedTitle != null) {
            cachedTitle
        } else {
            val resolved = smartSearch.resolve(strippedQuery)
            if (resolved == null) {
                AnikotoLog.w("SmartSearch: AI resolution failed, falling back to normal search")
                smartSearch.cacheTitle(strippedQuery, strippedQuery)
                showToast("AI search was unable to initiate and fell back to normal search")
                return super.getSearchAnime(page, query, filters)
            }
            smartSearch.cacheTitle(strippedQuery, resolved)
            resolved
        }

        AnikotoLog.i("SmartSearch: searching AniKoto for \"$title\" (page $page)")
        val request = searchAnimeRequest(page, title, filters)
        val response = client.newCall(request).awaitSuccess()
        var results = searchAnimeParse(response)

        // Fallback: if 0 results on page 1, try first 3 significant words
        if (results.animes.isEmpty() && page == 1 && title != strippedQuery) {
            val shortTitle = title.split(Regex("\\s+"))
                .filter { it.length > 2 }
                .take(3)
                .joinToString(" ")
            if (shortTitle.isNotEmpty() && shortTitle != title) {
                AnikotoLog.i("SmartSearch: 0 results for full title, trying short: \"$shortTitle\"")
                val fallbackRequest = searchAnimeRequest(page, shortTitle, filters)
                val fallbackResponse = client.newCall(fallbackRequest).awaitSuccess()
                results = searchAnimeParse(fallbackResponse)
                if (results.animes.isNotEmpty()) {
                    AnikotoLog.i("SmartSearch: short title found ${results.animes.size} results")
                } else {
                    AnikotoLog.w("SmartSearch: short title also returned 0 results")
                }
            }
        }

        AnikotoLog.i("SmartSearch: returning ${results.animes.size} results (hasNext=${results.hasNextPage})")
        return results
    }

    // ── Shared filter-result parser ──────────────────────────────────────
    private fun parseFilterResults(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val items = doc.select("div#list-items > div.item").map { el ->
            parseSearchItem(el)
        }
        val hasNext = doc.select("a.page-link[rel=next]").isNotEmpty()
        return AnimesPage(items, hasNext)
    }

    /**
     * Parse a search/listing item. Accepts either:
     * - The container element (div.item) — for Popular/Latest pages (img is in a sibling div)
     * - The link element (a.item / a.name.d-title) — for Search pages (img is inside)
     * Finds the link and img from whatever context is given.
     */
    private fun parseSearchItem(el: Element): SAnime = SAnime.create().apply {
        // Find the title link (may be el itself, or a descendant)
        val link = if (el.tagName() == "a" && el.hasClass("name")) el
            else el.selectFirst("a.name.d-title") ?: el.selectFirst("a[href*=/watch/]") ?: el
        val href = link.attr("href")
        url = if (href.startsWith("http")) href.substringAfter(baseUrl) else href
        // Normalize to just the slug: /watch/<slug>/ep-1 → <slug>
        url = url.removePrefix("/watch/").substringBefore("/ep-")
        title = link.selectFirst(".name")?.text()?.trim()
            ?: link.text()?.trim() ?: "Unknown"
        // Find img: check el itself, then descendants, then parent (for sibling img)
        thumbnail_url = el.selectFirst("img")?.attr("abs:src")
            ?: link.selectFirst("img")?.attr("abs:src")
            ?: el.parent()?.selectFirst("img")?.attr("abs:src")
    }

    // ── Anime Details ────────────────────────────────────────────────────
    override fun animeDetailsRequest(anime: SAnime): Request =
        GET("$baseUrl/watch/${anime.url}/ep-1", headers)

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.asJsoup()
        return SAnime.create().apply {
            val binfo = doc.selectFirst("#w-info .binfo") ?: doc.selectFirst("div.binfo") ?: return@apply
            url = response.request.url.toString()
                .substringAfter("/watch/").substringBefore("/ep-")
            title = binfo.selectFirst("h1.title")?.text()?.trim() ?: ""
            thumbnail_url = binfo.selectFirst("div.poster img")?.attr("abs:src")
            val altNames = binfo.selectFirst("div.names")?.text()
            val synopsis = binfo.selectFirst("div.synopsis div.content")?.text()?.trim()
            val bmeta = doc.selectFirst("div.bmeta")
            val metaRows = bmeta?.select("div.meta > div")?.associate {
                val label = it.ownText().removeSuffix(":").trim()
                val value = it.select("span").text().trim()
                label to value
            } ?: emptyMap()
            val type = metaRows["Type"] ?: ""
            val premiered = metaRows["Premiered"] ?: ""
            val aired = metaRows["Aired"] ?: ""
            val statusText = metaRows["Status"] ?: ""
            val genres = bmeta?.select("div:contains(Genres) span a")?.eachText()?.joinToString(", ") ?: ""
            val malScore = metaRows["MAL"] ?: ""
            val duration = metaRows["Duration"] ?: ""
            val studios = bmeta?.select("div:contains(Studios) span a")?.eachText()?.joinToString(", ") ?: ""
            val rating = binfo.selectFirst("i.rating")?.text() ?: ""
            description = buildString {
                synopsis?.let { append(it) }
                if (malScore.isNotBlank()) append("\n\nMAL Score: $malScore")
                if (type.isNotBlank()) append("\nType: $type")
                if (premiered.isNotBlank()) append("\nPremiered: $premiered")
                if (aired.isNotBlank()) append("\nAired: $aired")
                if (duration.isNotBlank()) append("\nDuration: $duration")
                if (studios.isNotBlank()) append("\nStudio: $studios")
                if (rating.isNotBlank()) append("\nRating: $rating")
                altNames?.takeIf { it.isNotBlank() }?.let { append("\n\nAlt titles: $it") }
                // ★ session 50: promotional credit line at the bottom of the description.
                // Empty line + new line with the promo.
                append("\n\nThank the Confused_creature_180")
            }
            genre = genres
            status = when {
                statusText.contains("Currently Airing", true) -> SAnime.ONGOING
                statusText.contains("Finished Airing", true) -> SAnime.COMPLETED
                else -> SAnime.UNKNOWN
            }
            update_strategy = if (status == SAnime.COMPLETED) {
                AnimeUpdateStrategy.ONLY_FETCH_ONCE
            } else {
                AnimeUpdateStrategy.ALWAYS_UPDATE
            }
            initialized = true
        }
    }

    // ── Episode List (with RC4 vrf + EpisodeMeta encoding) ───────────────
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val slug = anime.url
        AnikotoLog.i("getEpisodeList: START slug=$slug")

        // ★ session 51: Pre-warm WebViewFetcher in background.
        // The user typically browses episodes for a few seconds before clicking play.
        // By warming the WebView now, the 2–30s cold start is hidden from click-to-play.
        // Non-blocking: returns immediately, init happens on a background thread.
        // If the user never plays a video, this is harmless (WebView just stays idle).
        webViewFetcher.warmUp()

        val detailResponse = client.newCall(animeDetailsRequest(anime)).awaitSuccess()
        val detailDoc = detailResponse.asJsoup()
        val animeId = detailDoc.selectFirst("#watch-main")?.attr("data-id")
        if (animeId == null) {
            AnikotoLog.e("getEpisodeList: could not find animeId (#watch-main data-id) on detail page")
            throw Exception("Could not find animeId on detail page")
        }
        AnikotoLog.i("getEpisodeList: animeId=$animeId")

        // RC4 vrf (server doesn't validate but we implement for safety)
        val vrf = URLEncoder.encode(AnikotoRC4.encodeVrf(animeId), "UTF-8")
        AnikotoLog.d("getEpisodeList: vrf=$vrf")
        val epResponse = client.newCall(
            GET("$baseUrl/ajax/episode/list/$animeId?vrf=$vrf&style=default", xhrHeaders("$baseUrl/watch/$slug/ep-1"))
        ).awaitSuccess()
        val episodes = episodeListParse(epResponse, slug)
        AnikotoLog.i("getEpisodeList: SUCCESS — ${episodes.size} episodes")

        // ★ session 35: enrich episodes with metadata (thumbnails + descriptions + titles).
        // Always runs (the Aniyomi app has its own per-anime display toggles).
        // Completely isolated — if this fails, episodes are returned as-is.
        enrichEpisodesWithMetadata(episodes, detailDoc)

        return episodes
    }

    /**
     * ★ session 35-39: Enrich episodes with metadata from multiple sources.
     * - Thumbnails: Anikage → AniList → Kitsu → banner → anime cover
     * - Titles: Jikan → Anikage → Kitsu
     * - Descriptions: Anikage → Kitsu
     *
     * ★ session 39: respects 3 user toggles (loadThumbnails, loadTitles, loadDescriptions).
     * If ALL are OFF, the fetcher is skipped entirely (no API calls — zero latency).
     * Non-breaking: wrapped in try-catch, never throws.
     */
    private suspend fun enrichEpisodesWithMetadata(episodes: List<SEpisode>, detailDoc: org.jsoup.nodes.Document) {
        if (episodes.isEmpty()) return

        // ★ session 39: skip entirely if all toggles are OFF
        if (!loadThumbnails && !loadTitles && !loadDescriptions) {
            AnikotoLog.d("enrichEpisodesWithMetadata: skipped (all toggles OFF)")
            return
        }

        try {
            // Extract the anime-level MAL ID from the first episode's EpisodeMeta
            val firstMeta = EpisodeMeta.decode(episodes.first().url)
            val malId = firstMeta?.malId?.takeIf { it.isNotBlank() }
            if (malId == null) {
                AnikotoLog.d("enrichEpisodesWithMetadata: no MAL ID available, skipping")
                return
            }

            // Get the anime's cover image from the detail page (fallback thumbnail)
            val animeCoverUrl = detailDoc.selectFirst("div.poster img")?.attr("abs:src")
            AnikotoLog.d("enrichEpisodesWithMetadata: malId=$malId, cover=${animeCoverUrl?.take(50)}, thumbs=$loadThumbnails, titles=$loadTitles, descs=$loadDescriptions")

            // Fetch from all sources (cached, never throws)
            val metadata = metadataFetcher.fetch(malId, animeCoverUrl)
            if (metadata.isEmpty()) {
                AnikotoLog.d("enrichEpisodesWithMetadata: no metadata for malId=$malId")
                return
            }

            // Enrich each episode — only set fields the user has enabled
            var enrichedCount = 0
            for (ep in episodes) {
                val meta = EpisodeMeta.decode(ep.url) ?: continue
                val epNum = meta.epNum.toIntOrNull() ?: continue
                val epMeta = metadata[epNum] ?: continue

                // Thumbnail (only if toggle is ON)
                if (loadThumbnails) {
                    ep.preview_url = epMeta.thumbnailUrl
                }

                // Description (only if toggle is ON; leave null if no data — no placeholder)
                if (loadDescriptions) {
                    ep.summary = epMeta.description
                }

                // Title (only if toggle is ON)
                if (loadTitles) {
                    val sourceTitle = epMeta.title
                    if (!sourceTitle.isNullOrBlank()) {
                        ep.name = "EP $epNum - $sourceTitle"
                    }
                }

                enrichedCount++
            }
            AnikotoLog.i("enrichEpisodesWithMetadata: enriched $enrichedCount/${episodes.size} episodes")
        } catch (e: Exception) {
            AnikotoLog.w("enrichEpisodesWithMetadata: failed — ${e.message}. Episodes will load without enrichment.")
        }
    }

    private fun episodeListParse(response: Response, slug: String): List<SEpisode> {
        val data = json.decodeFromString<EpisodeListResponse>(response.body.string())
        val doc = Jsoup.parse(data.result)
        val episodes = doc.select("a[data-ids]").map { el ->
            val epNum = el.attr("data-num")
            val malId = el.attr("data-mal")
            val timestamp = el.attr("data-timestamp")
            val dataIds = el.attr("data-ids")
            val hasSub = el.attr("data-sub") == "1"
            val hasDub = el.attr("data-dub") == "1"
            val epTitle = el.selectFirst("span.d-title")?.text()?.takeIf { it.isNotBlank() }
                ?: el.parent()?.parent()?.attr("title")?.takeIf { it.isNotBlank() }
                ?: "Episode $epNum"
            val meta = EpisodeMeta(slug, epNum, malId, timestamp, dataIds, hasSub, hasDub, epTitle)
            SEpisode.create().apply {
                url = meta.encode()
                episode_number = epNum.toFloatOrNull() ?: 0f
                name = epTitle
                // Scanlator shows sub/dub availability in the episode list (rule §8).
                // Match the reference format: "Sub", "Dub", "Sub / Dub", or "Raw".
                scanlator = when {
                    hasSub && hasDub -> "Sub / Dub"
                    hasSub -> "Sub"
                    hasDub -> "Dub"
                    else -> "Raw"
                }
                date_upload = (timestamp.toLongOrNull() ?: 0L) * 1000
            }
        }
        return episodes.reversed()
    }

    // ── ★ Video pipeline (Stage 4) ───────────────────────────────────────
    // Per WORKSPACE/WORKFLOW/04_VIDEO_EXTRACTION_PLAYBACK/ANIKOTO/extraction-flows.md

    private var activeProxyServer: LocalProxyServer? = null

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        AnikotoLog.i("========== getHosterList START ==========")
        AnikotoLog.i("episode.url = ${episode.url}")
        AnikotoLog.i("episode.name = ${episode.name}")
        val meta = EpisodeMeta.decode(episode.url)
        if (meta == null) {
            AnikotoLog.e("getHosterList: EpisodeMeta.decode FAILED — episode.url is not a valid encoded meta")
            return emptyList()
        }
        AnikotoLog.i("getHosterList: EpisodeMeta parsed OK: slug=${meta.slug} num=${meta.epNum} mal=${meta.malId} ts=${meta.timestamp} hasSub=${meta.hasSub} hasDub=${meta.hasDub}")
        AnikotoLog.d("getHosterList: dataIds token (first 60) = ${AnikotoLog.trunc(meta.dataIds, 60)}")

        // ── Discovery: PATH A (primary server list) + PATH B (mapper) in parallel ──
        // ★ session 51: PATH A and PATH B now run concurrently via coroutineScope.
        // Previously these were sequential — the mapper API call (200-500ms) was blocked
        // behind the primary server list call. Now they overlap, saving ~200-500ms.
        val tasks = mutableListOf<HosterTask>()
        coroutineScope {
            // ── PATH A: primary server list (/ajax/server/list) ──────────
            val pathA = async {
                val primaryTasks = mutableListOf<HosterTask>()
                try {
                    AnikotoLog.d("PATH A: GET $baseUrl/ajax/server/list?servers=${AnikotoLog.trunc(meta.dataIds, 50)}")
                    val primaryResp = client.newCall(
                        GET("$baseUrl/ajax/server/list?servers=${meta.dataIds}", xhrHeaders("$baseUrl/watch/${meta.slug}/ep-1"))
                    ).awaitSuccess()
                    val pJson = json.decodeFromString<ServerListResponse>(primaryResp.body.string())
                    AnikotoLog.d("PATH A: parsed status=${pJson.status}, result HTML length = ${pJson.result.length}")
                    if (pJson.status == 200 && pJson.result.isNotEmpty()) {
                        val pDoc = Jsoup.parse(pJson.result)
                        for (typeDiv in pDoc.select("div.servers > div.type")) {
                            val audioType = typeDiv.attr("data-type") // sub|hsub|dub
                            val audioLabel = when (audioType) {
                                "sub" -> "SUB"
                                "hsub" -> "HSUB"
                                "dub" -> "DUB"
                                else -> audioType.uppercase()
                            }
                            for (li in typeDiv.select("li[data-link-id]")) {
                                val serverName = li.text().trim()
                                val linkId = li.attr("data-link-id")
                                if (linkId.isNotEmpty()) {
                                    AnikotoLog.d("PATH A: found [$audioType] $serverName token=${AnikotoLog.trunc(linkId, 40)}")
                                    primaryTasks.add(HosterTask("$audioLabel - $serverName", linkId, audioType, "primary"))
                                }
                            }
                        }
                    }
                    AnikotoLog.i("PATH A: found ${primaryTasks.size} primary servers")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AnikotoLog.e("PATH A: FAILED — continuing to mapper", e)
                }
                primaryTasks
            }

            // ── PATH B: mapper API (Kiwi-Stream) ─────────────────────────
            // ★ session 45 (v16.29): gated by the enableKiwi toggle (default ON).
            // When OFF, the mapper API is not called at all — no Kiwi-Stream servers.
            val pathB = async {
                val mapperTasks = mutableListOf<HosterTask>()
                if (!enableKiwi) {
                    AnikotoLog.i("PATH B: skipped (Kiwi-Stream disabled in settings)")
                } else if (meta.malId.isNotEmpty() && meta.epNum.isNotEmpty() && meta.timestamp.isNotEmpty()) {
                    AnikotoLog.d("PATH B: fetching mapper for mal=${meta.malId} ep=${meta.epNum} ts=${meta.timestamp}")
                    try {
                        val mapperUrl = "https://mapper.nekostream.site/api/mal/${meta.malId}/${meta.epNum}/${meta.timestamp}"
                        AnikotoLog.d("PATH B: GET $mapperUrl")
                        val mapperResp = client.newCall(
                            GET(mapperUrl, xhrHeaders("$baseUrl/watch/${meta.slug}/ep-1"))
                        ).awaitSuccess()
                        val mapperBody = mapperResp.body.string()
                        AnikotoLog.d("PATH B: response (first 300 chars) = ${AnikotoLog.trunc(mapperBody, 300)}")
                        val mapperJson = json.parseToJsonElement(mapperBody) as? kotlinx.serialization.json.JsonObject
                        if (mapperJson != null) {
                            val tokens = parseMapperResponse(mapperJson)
                            AnikotoLog.i("PATH B: parsed ${tokens.size} mapper tokens")
                            if (tokens.isEmpty()) {
                                // ★ session 40: log when Kiwi-Stream only has download (no streaming)
                                val hasKiwiDownload = mapperJson.keys.any { it == "Kiwi-Stream" }
                                if (hasKiwiDownload) {
                                    AnikotoLog.i("PATH B: Kiwi-Stream has download links but no streaming URL — streaming not available for this episode")
                                } else {
                                    AnikotoLog.i("PATH B: no Kiwi-Stream entries found in mapper response")
                                }
                            }
                            for (token in tokens) {
                                AnikotoLog.d("PATH B: found [${token.audio}] ${token.serverName}- token=${AnikotoLog.trunc(token.token, 40)}")
                                if (token.serverName != "Kiwi-Stream") continue
                                val label = if (token.audio == "sub") "H-SUB" else "A-DUB"
                                mapperTasks.add(HosterTask("$label - ${token.serverName}", token.token, token.audio, "mapper"))
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        AnikotoLog.e("PATH B: mapper FAILED — continuing with primary tasks", e)
                    }
                } else {
                    AnikotoLog.w("PATH B: skipped (missing malId/epNum/timestamp in EpisodeMeta)")
                }
                mapperTasks
            }

            // Merge results from both paths
            tasks.addAll(pathA.await())
            tasks.addAll(pathB.await())
        }

        AnikotoLog.i("getHosterList: total servers found = ${tasks.size}")
        if (tasks.isEmpty()) {
            AnikotoLog.e("getHosterList: no tasks to resolve — returning empty")
            return emptyList()
        }

        // ── Parallel resolution ───────────────────────────────────────────
        AnikotoLog.i("getHosterList: resolving ${tasks.size} servers in parallel...")
        for (task in tasks) {
            AnikotoLog.d("  server: ${task.label} [${task.audioType}] source=${task.source} token=${AnikotoLog.trunc(task.token, 40)}")
        }
        val resolvedStreams = coroutineScope {
            tasks.map { task ->
                async { resolveStreamForTask(task, meta.slug) }
            }.awaitAll()
        }.filterNotNull()

        AnikotoLog.i("getHosterList: resolved ${resolvedStreams.size}/${tasks.size} streams")
        if (resolvedStreams.isEmpty()) {
            AnikotoLog.e("getHosterList: all streams failed — returning empty")
            return emptyList()
        }
        for (stream in resolvedStreams) {
            AnikotoLog.i("  resolved: ${stream.hosterName} [${stream.audioLabel}] — ${stream.variants.size} variants, ${stream.subtitles.size} subs")
        }

        // ── Proxy setup ───────────────────────────────────────────────────
        // ★ session 29: desktop Chrome UA + no Accept-Language (matches reference project).
        // This is the fallback when a stream has no per-stream referer (headersForStream falls back).
        val segHeaders = Headers.Builder()
            .set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .set("Referer", "https://vidtube.site/")
            .set("Accept", "*/*")
            .build()
        val server = LocalProxyServer(proxyFetchClient, segHeaders, webViewFetcher)
        server.setPlaylist(Playlist(resolvedStreams))
        server.prefetchCount = prefetchBuffer.toIntOrNull()?.coerceIn(10, 100) ?: 10
        val proxyBaseUrl = server.start()
        AnikotoLog.i("getHosterList: proxy started at $proxyBaseUrl (prefetch=${server.prefetchCount}%)")

        // Stop old proxy if any
        activeProxyServer?.stop()
        activeProxyServer = server

        // ── Build Video objects, grouped by server ────────────────────────
        // ★ Return MULTIPLE Hosters — one per server name. Each Hoster becomes a
        // collapsible section in the player UI (e.g., "VidPlay-1", "HD-1", etc.).
        // This is better than the v16.4 reference (which puts everything under "default")
        // because it gives clear server categories for all our 4+ servers.
        AnikotoLog.i("getHosterList: building Video objects (grouped by server)...")
        // ★ initialized=false: forces the app to call resolveVideo() on each switch.
        // This makes mpv re-initialize the stream → re-reads PAT/PMT → auto-selects
        // the first audio track. Without this (initialized=true), mpv hot-swaps and
        // retains the old audio PID → different PIDs in new quality → no audio.
        // Verified against the v16.4 reference APK (uses initialized=false, no mpvArgs).
        val allVideos = mutableMapOf<String, MutableList<Video>>()
        for ((streamIndex, stream) in resolvedStreams.withIndex()) {
            val subtitleTracks = server.getSubtitleTracks(streamIndex)
            for (variant in stream.variants) {
                val videoUrl = "$proxyBaseUrl/variant/$streamIndex/${variant.quality}.m3u8"
                val title = "${stream.audioLabel} - ${variant.quality}"
                val resolution = variant.resolution.takeIf { it > 0 }
                AnikotoLog.d("  Video: ${stream.hosterName} - $title -> $videoUrl")
                val video = Video(
                    videoUrl, title, resolution, null, null, false,
                    subtitleTracks, emptyList(), emptyList(),
                    emptyList(), // ★ no mpvArgs — reference doesn't use them
                    emptyList(), emptyList(), "",
                    false, // ★ initialized=false — forces resolveVideo on each switch
                )
                allVideos.getOrPut(stream.hosterName) { mutableListOf() }.add(video)
            }
        }

        // Sort videos within each server group + mark preferred on the best one
        val hosters = mutableListOf<Hoster>()
        var globalPreferredSet = false
        for ((serverName, videos) in allVideos) {
            val sorted = sortVideosInternal(videos, markPreferred = !globalPreferredSet)
            if (!globalPreferredSet && sorted.isNotEmpty()) {
                globalPreferredSet = true
            }
            AnikotoLog.i("  Hoster: $serverName — ${sorted.size} videos, preferred=${sorted.firstOrNull()?.preferred}")
            hosters.add(Hoster(hosterName = serverName, videoList = sorted))
        }

        // Sort hosters: preferred server first, then others
        val prefServer = preferredServer
        val sortedHosters = if (prefServer != "auto") {
            hosters.sortedWith(
                compareByDescending<Hoster> { it.hosterName.contains(prefServer, true) }
            )
        } else {
            hosters
        }

        AnikotoLog.i("getHosterList: ${sortedHosters.size} hosters, ${sortedHosters.sumOf { it.videoList?.size ?: 0 }} total videos")
        AnikotoLog.i("========== getHosterList END ==========")
        return sortedHosters
    }

    private suspend fun resolveStreamForTask(task: HosterTask, slug: String): AudioStream? {
        AnikotoLog.i("resolveStreamForTask: START ${task.label} [${task.audioType}]")
        return try {
            // Step 1: resolve the server link → iframe URL
            val encToken = URLEncoder.encode(task.token, "UTF-8")
            AnikotoLog.d("resolveStreamForTask: GET $baseUrl/ajax/server?get=${AnikotoLog.trunc(encToken, 50)}")
            val resolveResp = client.newCall(
                GET("$baseUrl/ajax/server?get=$encToken", xhrHeaders("$baseUrl/watch/$slug/ep-1"))
            ).awaitSuccess()
            val resolveJson = json.decodeFromString<ServerResponse>(resolveResp.body.string())
            if (resolveJson.status != 200) {
                AnikotoLog.e("resolveStreamForTask: ${task.label} — resolve status=${resolveJson.status}")
                return null
            }
            val iframeUrl = resolveJson.result?.url?.takeIf { it.isNotEmpty() }
            if (iframeUrl == null) {
                AnikotoLog.e("resolveStreamForTask: ${task.label} — no iframe URL in response")
                return null
            }
            AnikotoLog.d("resolveStreamForTask: ${task.label} -> iframe=${AnikotoLog.trunc(iframeUrl, 80)}")

            // Step 2: dispatch by player host
            // ★ vidwish.live (VidCloud-1) now routes to resolveVidTube too (session 26).
            //   The site migrated from getSourcesNew → getSources; resolveVidTube selects the
            //   endpoint by host. vidwish.live's data-id is audio-specific, so getSources?id=X
            //   returns the correct audio. See EXTENSIONS/anikoto/MEMORY/sites/getsources-migration-and-id-analysis.md §1.
            val host = iframeUrl.substringAfter("://").substringBefore("/")
            val hosterName = task.label.substringAfter(" - ")
            val result = when {
                host.contains("vidtube.site") || host.contains("megaplay.buzz") || host.contains("vidwish.live") -> {
                    AnikotoLog.d("resolveStreamForTask: ${task.label} -> Flow A (VidTube), host=$host")
                    extractors.resolveVidTube(iframeUrl, task.audioType, hosterName)
                }
                host.contains("mewcdn.online") -> {
                    AnikotoLog.d("resolveStreamForTask: ${task.label} -> Flow B (Kiwi), host=$host")
                    extractors.resolveKiwi(iframeUrl, task.audioType, hosterName)
                }
                else -> {
                    AnikotoLog.w("resolveStreamForTask: ${task.label} — UNKNOWN host=$host, skipping")
                    null
                }
            }
            if (result == null) {
                AnikotoLog.e("resolveStreamForTask: ${task.label} — FAILED (returned null)")
            }
            result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AnikotoLog.e("resolveStreamForTask: ${task.label} — CRASHED", e)
            null
        }
    }

    override suspend fun resolveVideo(video: Video): Video? {
        AnikotoLog.i("resolveVideo: CALLED for ${video.videoTitle} (url=${AnikotoLog.trunc(video.videoUrl, 60)})")
        AnikotoLog.i("resolveVideo: subtitleTracks=${video.subtitleTracks.size}, initialized=${video.initialized}")
        // Cancel any in-flight prefetch — the player is switching to a new video.
        activeProxyServer?.onQualitySwitch()
        // Return the video unchanged. The app re-opens the videoUrl → mpv re-initializes
        // the stream → reads PAT/PMT → auto-selects the first audio track → audio works.
        // This matches the v16.4 reference's resolveVideo (returns video unchanged).
        return video
    }

    // ── Hoster/Video parse stubs (not used — we override the suspend get* methods) ──
    override fun hosterListParse(response: Response): List<Hoster> = emptyList()
    override fun videoListParse(response: Response, hoster: Hoster): List<Video> = emptyList()
    override fun videoListParse(response: Response): List<Video> = emptyList()
    override fun episodeListParse(response: Response): List<SEpisode> = emptyList()
    override fun seasonListParse(response: Response): List<SAnime> =
        throw UnsupportedOperationException("Anikoto does not use seasons")

    /**
     * ★ session 43 (v16.27): LEGACY getVideoList(episode) — required for fork compatibility.
     *
     * Forks that haven't adopted the ext-lib-16 hoster pipeline call this method instead of
     * [getHosterList]. The default base-class implementation does `GET(baseUrl + episode.url)`,
     * which fails with a DNS error if `episode.url` contains encoded metadata rather than a
     * valid URL path (see `EXTENSIONS/anikoto/MEMORY/issues-resolutions/04-episode-url-dns-error-in-forks.md`).
     *
     * This override delegates to [getHosterList] (which already handles everything — decoding
     * EpisodeMeta, fetching servers, resolving streams, starting the local proxy) and flattens
     * the resulting hoster list into a single flat video list (the legacy format).
     *
     * Never throws — returns an empty list on any failure so the user sees "no videos" rather
     * than a crash.
     */
    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        return try {
            val hosters = getHosterList(episode)
            if (hosters.isEmpty()) return emptyList()
            // Flatten all hosters' video lists into a single list (legacy flat format)
            hosters.flatMap { it.videoList ?: emptyList() }
        } catch (e: Exception) {
            AnikotoLog.e("getVideoList(episode) [legacy]: failed — ${e.message}", e)
            emptyList()
        }
    }

    /**
     * ★ session 43 (v16.27): Override getEpisodeUrl — used by "Open in WebView" + deep links.
     *
     * The default returns `episode.url` as-is, which is a relative path (or, before v16.27,
     * encoded metadata — not a URL at all). We construct the full URL from the path component
     * of the encoded [EpisodeMeta] so the WebView loads the correct watch page.
     */
    override fun getEpisodeUrl(episode: SEpisode): String {
        val path = EpisodeMeta.extractUrlPath(episode.url)
        return "$baseUrl$path"
    }

    // ── Sort ─────────────────────────────────────────────────────────────
    // Sort priority: preferred audio → preferred quality (desc) → resolution (desc).
    // @param markPreferred if true, mark the first video as preferred=true
    private fun sortVideosInternal(videos: List<Video>, markPreferred: Boolean = true): List<Video> {
        val prefAudioLabel = when (preferredAudio) {
            "A-DUB" -> "DUB"
            "H-SUB" -> "HSUB"
            else -> "SUB"
        }
        val prefQuality = preferredQuality
        val sorted = videos.sortedWith(
            compareByDescending<Video> { it.videoTitle.contains(prefAudioLabel, true) }
                .thenByDescending { it.videoTitle.contains(prefQuality, true) }
                .thenByDescending { it.resolution ?: 0 }
        )
        if (!markPreferred) return sorted
        // Rebuild the first Video with preferred=true (avoid copy() which uses the bitmask ctor).
        return sorted.mapIndexed { i, v ->
            if (i == 0) {
                Video(
                    v.videoUrl, v.videoTitle, v.resolution, v.bitrate, v.headers,
                    true, // preferred
                    v.subtitleTracks, v.audioTracks, v.timestamps,
                    v.mpvArgs, v.ffmpegStreamArgs, v.ffmpegVideoArgs,
                    v.internalData, v.initialized,
                )
            } else v
        }
    }

    // ── Filters ──────────────────────────────────────────────────────────
    override fun getFilterList(): AnimeFilterList {
        // ★ session 51: Pre-warm the Google WebView when the search page opens.
        if (smartSearchEnabled) {
            smartSearch.warmUp()
        }
        return AnikotoFilters.get()
    }

    // ── Preferences UI (delegated to AnikotoSettings) ──────────────────
    // All preference UI setup is in AnikotoSettings.kt.
    // To add/modify settings, edit AnikotoSettings.setupPreferenceScreen().
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        settings.setupPreferenceScreen(screen)
    }

    companion object {
        // Preference keys and defaults have been moved to AnikotoSettings.kt.
        // Access them through `settings.*` (e.g., settings.preferredQuality).
    }
}
