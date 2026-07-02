package eu.kanade.tachiyomi.animeextension.en.anikoto.video

import eu.kanade.tachiyomi.animeextension.en.anikoto.AnikotoLog
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * A local HTTP proxy server that serves rewritten m3u8 playlists + PNG-stripped segments.
 *
 * Per MEMORY/research/apk-reference/02-video-pipeline-and-proxy.md §5 + ADR 03 point 7.
 *
 * Design:
 * - Raw `java.net.ServerSocket` on 127.0.0.1:0 (OS-assigned port).
 * - Index-based URL scheme using STREAM INDEX (not audioType) to avoid collisions:
 *   `/variant/{streamIndex}/{quality}.m3u8` → build-from-scratch media playlist
 *   `/seg/{streamIndex}/{quality}/{index}` → fetch upstream, strip PNG, serve
 *   `/sub/{streamIndex}/{subIndex}` → subtitle passthrough (with proper headers)
 * - LRU segment cache (200 entries) + prefetch (configurable %, generation-cancellable).
 * - Idle auto-stop after 10 minutes.
 */
class LocalProxyServer(
    private val fetchClient: OkHttpClient,
    private val segmentHeaders: Headers,
    private val webViewFetcher: WebViewFetcher? = null,
) {
    companion object {
        private const val IDLE_TIMEOUT_MS = 600_000L
        private const val MAX_CACHE_ENTRIES = 200 // ★ increased from 50 → 200 (enough for full episode)
        private const val SOCKET_TIMEOUT_MS = 120_000
        private const val PREFETCH_CAP = 5
        // ★ session 29: DESKTOP Chrome UA (not mobile). cdn.mewstream.buzz WAF rejects mobile UAs.
        // Matches the reference Next.js project's UA exactly (proven working).
        private const val BROWSER_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }

    private var serverSocket: ServerSocket? = null
    private var playlist: Playlist? = null
    var prefetchCount: Int = 10 // percentage of total segments to prefetch ahead
    private var baseUrl: String = ""

    private val running = AtomicBoolean(false)
    private val lastActivity = AtomicLong(System.currentTimeMillis())
    private val prefetchGeneration = AtomicLong(0)

    // LRU segment cache: key = "{streamIndex}/{quality}/{index}"
    private val segmentCache = ConcurrentHashMap<String, ByteArray>()
    private val cacheOrder = Collections.synchronizedList(mutableListOf<String>())
    private val fetching = ConcurrentHashMap<String, Boolean>()

    private val executor = Executors.newCachedThreadPool { r ->
        Thread(r, "AnikotoProxy-Worker").apply { isDaemon = true }
    }
    private var acceptThread: Thread? = null
    private var idleThread: Thread? = null

    /** Set the in-memory playlist before calling [start]. */
    fun setPlaylist(p: Playlist) {
        this.playlist = p
    }

    /** Start the server. Returns the base URL (e.g. "http://127.0.0.1:12345"). */
    fun start(): String {
        if (running.get()) return baseUrl
        val sock = ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
        serverSocket = sock
        baseUrl = "http://127.0.0.1:${sock.localPort}"
        running.set(true)
        lastActivity.set(System.currentTimeMillis())

        acceptThread = Thread({ acceptLoop() }, "AnikotoProxy-Accept").apply { isDaemon = true; start() }
        idleThread = Thread({ idleMonitor() }, "AnikotoProxy-Idle").apply { isDaemon = true; start() }
        AnikotoLog.i("Proxy started at $baseUrl (prefetch=$prefetchCount%, cache=$MAX_CACHE_ENTRIES)")
        return baseUrl
    }

    /** Stop the server and release all resources. */
    fun stop() {
        if (!running.getAndSet(false)) return
        try { serverSocket?.close() } catch (_: Exception) {}
        acceptThread?.interrupt()
        executor.shutdownNow()
        segmentCache.clear()
        cacheOrder.clear()
        fetching.clear()
        prefetchGeneration.incrementAndGet()
        AnikotoLog.i("Proxy stopped")
    }

    /** Cancel in-flight prefetches (call on quality switch). */
    fun onQualitySwitch() {
        prefetchGeneration.incrementAndGet()
    }

    /** Get the proxied subtitle track URLs for a given stream index. */
    fun getSubtitleTracks(streamIndex: Int): List<eu.kanade.tachiyomi.animesource.model.Track> {
        val p = playlist ?: return emptyList()
        if (streamIndex < 0 || streamIndex >= p.streams.size) return emptyList()
        val stream = p.streams[streamIndex]
        return stream.subtitles.mapIndexed { i, sub ->
            // ★ Track(url, lang) — the second param is the DISPLAY LABEL (e.g., "English"),
            // NOT the ISO code. The reference v16.4 uses the label, not ISO code.
            eu.kanade.tachiyomi.animesource.model.Track(
                "$baseUrl/sub/$streamIndex/$i",
                sub.label,
            )
        }
    }

    private fun acceptLoop() {
        val sock = serverSocket ?: return
        while (running.get()) {
            try {
                val client = sock.accept()
                client.soTimeout = SOCKET_TIMEOUT_MS
                executor.execute { handleClient(client) }
            } catch (e: Exception) {
                if (running.get()) AnikotoLog.e("accept error", e)
            }
        }
    }

    private fun idleMonitor() {
        while (running.get()) {
            try {
                Thread.sleep(5000)
                if (System.currentTimeMillis() - lastActivity.get() > IDLE_TIMEOUT_MS) {
                    AnikotoLog.i("Idle timeout — stopping")
                    stop()
                    break
                }
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            lastActivity.set(System.currentTimeMillis())
            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            // Read request line
            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 3 || parts[0] != "GET") {
                sendError(output, 405, "Method Not Allowed")
                return
            }
            val path = parts[1]
            // Drain headers
            while (true) {
                val headerLine = readLine(input) ?: break
                if (headerLine.isEmpty()) break
            }
            routeRequest(path, output)
        } catch (e: Exception) {
            AnikotoLog.e("handleClient error", e)
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun routeRequest(path: String, output: OutputStream) {
        try {
            val clean = path.trim('/').removeSuffix(".m3u8")
            val segments = clean.split("/")
            when {
                segments.size == 3 && segments[0] == "variant" -> {
                    serveVariantPlaylist(segments[1].toInt(), segments[2], output)
                }
                segments.size == 4 && segments[0] == "seg" -> {
                    serveSegment(segments[1].toInt(), segments[2], segments[3].toInt(), output)
                }
                segments.size == 3 && segments[0] == "sub" -> {
                    serveSubtitle(segments[1].toInt(), segments[2].toInt(), output)
                }
                else -> sendError(output, 404, "Not Found")
            }
        } catch (e: java.net.SocketException) {
            AnikotoLog.d("route: connection closed by player for $path (normal)")
        } catch (e: Exception) {
            AnikotoLog.e("route error for $path", e)
            try { sendError(output, 500, "Internal Server Error") } catch (_: Exception) {}
        }
    }

    // ── Variant playlist: build-from-scratch from in-memory Playlist ──────────
    private fun serveVariantPlaylist(streamIndex: Int, quality: String, output: OutputStream) {
        val p = playlist ?: return sendError(output, 503, "No playlist")
        if (streamIndex < 0 || streamIndex >= p.streams.size) return sendError(output, 404, "Bad stream index")
        val stream = p.streams[streamIndex]
        val variant = stream.variants.find { it.quality == quality }
            ?: return sendError(output, 404, "No variant for quality=$quality")

        val maxDuration = variant.segments.maxOfOrNull { it.duration }?.toInt()?.plus(1) ?: 10
        val totalDuration = variant.segments.sumOf { it.duration }
        val sb = StringBuilder()
        sb.append("#EXTM3U\n")
        sb.append("#EXT-X-VERSION:3\n")
        sb.append("#EXT-X-PLAYLIST-TYPE:VOD\n")
        sb.append("#EXT-X-TARGETDURATION:$maxDuration\n")
        sb.append("#EXT-X-MEDIA-SEQUENCE:0\n")
        for ((i, seg) in variant.segments.withIndex()) {
            sb.append("#EXTINF:${seg.duration},\n")
            sb.append("$baseUrl/seg/$streamIndex/$quality/$i\n")
        }
        sb.append("#EXT-X-ENDLIST\n")
        AnikotoLog.d("Proxy: serving variant playlist $streamIndex/$quality — ${variant.segments.size} segments, total=${totalDuration.toInt()}s")
        sendText(output, "application/vnd.apple.mpegurl", sb.toString().toByteArray())
    }

    // ── Per-stream headers ─────────────────────────────────────────────────────
    // ★ session 27: each stream carries its own Referer (derived from the iframe host).
    // ★ session 29: desktop Chrome UA + no Accept-Language (minimal headers, matches
    //   reference project). Extra headers can trigger WAF rules on cdn.mewstream.buzz.
    // Falls back to the constructor segmentHeaders if the stream has no referer set.
    private fun headersForStream(streamIndex: Int): Headers {
        val p = playlist ?: return segmentHeaders
        if (streamIndex < 0 || streamIndex >= p.streams.size) return segmentHeaders
        val referer = p.streams[streamIndex].referer
        if (referer.isBlank()) return segmentHeaders
        return Headers.Builder()
            .set("User-Agent", BROWSER_UA)
            .set("Referer", referer)
            .set("Accept", "*/*")
            .build()
    }

    // ── Segment: fetch upstream, strip PNG, cache, serve ───────────────────────
    private fun serveSegment(streamIndex: Int, quality: String, index: Int, output: OutputStream) {
        lastActivity.set(System.currentTimeMillis())
        val p = playlist ?: return sendError(output, 503, "No playlist")
        if (streamIndex < 0 || streamIndex >= p.streams.size) return sendError(output, 404, "Bad stream index")
        val stream = p.streams[streamIndex]
        val variant = stream.variants.find { it.quality == quality }
            ?: return sendError(output, 404, "No variant")
        if (index < 0 || index >= variant.segments.size) return sendError(output, 404, "Bad index")

        // ★ per-stream headers (correct Referer for this stream's CDN)
        val fetchHeaders = headersForStream(streamIndex)

        val cacheKey = "$streamIndex/$quality/$index"
        var data = segmentCache[cacheKey]

        if (data == null) {
            // Wait for in-flight fetch if present
            if (fetching[cacheKey] == true) {
                val deadline = System.currentTimeMillis() + 15000
                while (data == null && System.currentTimeMillis() < deadline && fetching[cacheKey] == true) {
                    Thread.sleep(50)
                    data = segmentCache[cacheKey]
                }
            }
            if (data == null) {
                fetching[cacheKey] = true
                try {
                    val segUrl = variant.segments[index].url
                    data = fetchSegment(segUrl, fetchHeaders)
                    data = stripPngHeader(data)
                    cacheSegment(cacheKey, data)
                    AnikotoLog.i("STRIPPED: $cacheKey size=${data.size}")
                } catch (e: Exception) {
                    AnikotoLog.e("fetch seg failed: $cacheKey", e)
                    return sendError(output, 502, "Upstream fetch failed")
                } finally {
                    fetching.remove(cacheKey)
                }
            }
        }

        sendBytes(output, "video/MP2T", data)

        // Trigger prefetch (non-blocking) — pass per-stream headers
        triggerPrefetch(stream, variant, streamIndex, quality, index, fetchHeaders)
    }

    // ── Subtitle: passthrough with proper headers ──────────────────────────────
    private fun serveSubtitle(streamIndex: Int, subIndex: Int, output: OutputStream) {
        val p = playlist ?: return sendError(output, 503, "No playlist")
        if (streamIndex < 0 || streamIndex >= p.streams.size) return sendError(output, 404, "Bad stream index")
        val stream = p.streams[streamIndex]
        if (subIndex < 0 || subIndex >= stream.subtitles.size) return sendError(output, 404, "Bad sub index")
        try {
            val subUrl = stream.subtitles[subIndex].url
            // ★ per-stream Referer for subtitle fetch (same CDN as segments)
            val fetchHeaders = headersForStream(streamIndex)
            AnikotoLog.d("Proxy: serving subtitle $streamIndex/$subIndex from ${AnikotoLog.trunc(subUrl, 60)}")
            val req = Request.Builder().url(subUrl).headers(fetchHeaders).build()
            fetchClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return sendError(output, resp.code, "Upstream ${resp.code}")
                val body = resp.body?.bytes() ?: return sendError(output, 502, "Empty body")
                sendText(output, "text/vtt", body)
            }
        } catch (e: Exception) {
            AnikotoLog.e("sub fetch failed", e)
            sendError(output, 502, "Sub fetch failed")
        }
    }

    // ── Fetch + cache helpers ──────────────────────────────────────────────────
    // ★ session 30-31: falls back to WebViewFetcher (Chrome TLS) on ANY error for WAF-blocked CDNs.
    // session 31: broadened from 403-only to ANY exception — fixes UnknownHostException for
    // segment CDN hosts (g5vh.voltara.click, f4qh.zaptrix.buzz) that OkHttp's DNS can't resolve.
    private fun fetchSegment(url: String, headers: Headers, retry: Boolean = true): ByteArray {
        // ★ session 31: for known WAF-blocked CDN hosts, skip OkHttp entirely and go straight
        // to WebView (saves the 1-2s wasted on the OkHttp 403/DNS-failure attempt).
        val isWafHost = isWafBlockedHost(url)
        if (isWafHost && webViewFetcher != null) {
            try {
                return webViewFetcher.fetchBytes(url)
            } catch (e: Exception) {
                AnikotoLog.e("fetchSegment: WebView fetch failed for ${AnikotoLog.trunc(url, 60)}", e)
                throw e
            }
        }

        var lastError: Exception? = null
        val maxAttempts = if (retry) 2 else 1
        for (attempt in 0 until maxAttempts) {
            try {
                val req = Request.Builder().url(url).headers(headers).build()
                fetchClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw RuntimeException("Upstream ${resp.code}")
                    return resp.body?.bytes() ?: throw RuntimeException("Empty body")
                }
            } catch (e: Exception) {
                lastError = e
                // ★ session 31: fall back to WebView on ANY error if the host is WAF-blocked
                // (covers 403, UnknownHostException, SocketTimeoutException, etc.)
                if (isWafHost && webViewFetcher != null) {
                    AnikotoLog.i("fetchSegment: OkHttp failed (${e.message?.take(50)}), falling back to WebView for ${AnikotoLog.trunc(url, 60)}")
                    try {
                        return webViewFetcher.fetchBytes(url)
                    } catch (wve: Exception) {
                        AnikotoLog.e("fetchSegment: WebView fallback also failed", wve)
                        throw wve
                    }
                }
                if (retry && attempt == 0 && !e.message.isNullOrEmpty() &&
                    (e.message!!.contains("Unable to resolve host") || e.message!!.contains("Failed to connect"))) {
                    throw e
                }
                if (attempt < maxAttempts - 1) Thread.sleep(500)
            }
        }
        throw lastError ?: RuntimeException("fetch failed")
    }

    /** ★ session 31: WAF-blocked CDN hosts that require WebView (Chrome TLS) for segment fetches. */
    private fun isWafBlockedHost(url: String): Boolean {
        return url.contains("mewstream.buzz") ||
            url.contains("voltara.click") ||
            url.contains("zaptrix.buzz")
    }

    private fun cacheSegment(key: String, data: ByteArray) {
        while (segmentCache.size >= MAX_CACHE_ENTRIES) {
            synchronized(cacheOrder) {
                if (cacheOrder.isNotEmpty()) {
                    val evict = cacheOrder.removeAt(0)
                    segmentCache.remove(evict)
                }
            }
        }
        segmentCache[key] = data
        synchronized(cacheOrder) {
            cacheOrder.remove(key)
            cacheOrder.add(key)
        }
    }

    private fun triggerPrefetch(
        stream: AudioStream,
        variant: VariantData,
        streamIndex: Int,
        quality: String,
        currentIndex: Int,
        fetchHeaders: Headers,
    ) {
        val total = variant.segments.size
        val end = minOf(currentIndex + maxOf(prefetchCount * total / 100, 1), total - 1)
        val gen = prefetchGeneration.get()
        var enqueued = 0
        for (i in (currentIndex + 1)..end) {
            if (enqueued >= PREFETCH_CAP) break
            val key = "$streamIndex/$quality/$i"
            if (segmentCache.containsKey(key) || fetching[key] == true) continue
            enqueued++
            executor.execute {
                if (prefetchGeneration.get() != gen) return@execute
                if (segmentCache.containsKey(key)) return@execute
                fetching[key] = true
                try {
                    val data = fetchSegment(variant.segments[i].url, fetchHeaders, retry = false)
                    val stripped = stripPngHeader(data)
                    cacheSegment(key, stripped)
                } catch (e: Exception) {
                    AnikotoLog.w("prefetch failed: $key — ${e.message}")
                } finally {
                    fetching.remove(key)
                }
            }
        }
    }

    // ── PNG header stripping ───────────────────────────────────────────────────
    private fun stripPngHeader(data: ByteArray): ByteArray {
        if (data.size < 8) return data
        if (!(data[0] == 0x89.toByte() && data[1] == 'P'.code.toByte() &&
                data[2] == 'N'.code.toByte() && data[3] == 'G'.code.toByte())) {
            return data
        }
        var cut = -1
        for (i in 0 until data.size - 4) {
            if (data[i] == 'I'.code.toByte() && data[i + 1] == 'E'.code.toByte() &&
                data[i + 2] == 'N'.code.toByte() && data[i + 3] == 'D'.code.toByte()) {
                cut = i + 8
                break
            }
        }
        if (cut < 0 || cut >= data.size) return data
        val scanLimit = minOf(data.size - 188, 400)
        for (i in cut until scanLimit) {
            if (data[i] == 0x47.toByte() && data[i + 188] == 0x47.toByte()) {
                return data.copyOfRange(i, data.size)
            }
        }
        return data.copyOfRange(cut, data.size)
    }

    // ── HTTP response helpers ──────────────────────────────────────────────────
    private fun readLine(input: java.io.InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
        }
        return sb.toString()
    }

    private fun sendText(output: OutputStream, contentType: String, body: ByteArray) {
        val header = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: $contentType\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Connection: close\r\n" +
            "Cache-Control: no-cache\r\n" +
            "Access-Control-Allow-Origin: *\r\n\r\n"
        output.write(header.toByteArray())
        output.write(body)
        output.flush()
    }

    private fun sendBytes(output: OutputStream, contentType: String, body: ByteArray) {
        sendText(output, contentType, body)
    }

    private fun sendError(output: OutputStream, code: Int, message: String) {
        val body = "$code: $message\n".toByteArray()
        val header = "HTTP/1.1 $code $message\r\n" +
            "Content-Type: text/plain\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Connection: close\r\n\r\n"
        output.write(header.toByteArray())
        output.write(body)
        output.flush()
    }
}
