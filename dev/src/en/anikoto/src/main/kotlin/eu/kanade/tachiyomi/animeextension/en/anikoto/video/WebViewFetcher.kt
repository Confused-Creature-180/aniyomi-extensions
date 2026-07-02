package eu.kanade.tachiyomi.animeextension.en.anikoto.video

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import eu.kanade.tachiyomi.animeextension.en.anikoto.AnikotoLog
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * ★ session 30-31: WebView-based HTTP fetcher that uses Chrome's TLS stack.
 *
 * The cdn.mewstream.buzz WAF blocks OkHttp's TLS fingerprint (Conscrypt/JA3) with HTTP 403.
 * Segment CDN hosts (g5vh.voltara.click, f4qh.zaptrix.buzz) may also be unresolvable by
 * OkHttp's DNS. Chrome (BoringSSL) is allowed. This class uses Android's WebView (Chrome's
 * network stack) to execute `fetch()` calls, bypassing both the WAF TLS block and DNS issues.
 *
 * ★ session 31 optimizations:
 * - Uses FileReader.readAsDataURL (native) instead of String.fromCharCode+btoa (JS, very slow)
 * - Larger chunks (700KB → ~933KB base64, under 1MB IPC limit)
 * - Serialized fetches (no concurrent evaluateJavascript — prevents "connection abort")
 * - Timing logs for diagnosis
 * - 60s timeout for bytes (segments can be 2MB+)
 */
class WebViewFetcher(
    private val context: Context,
    private val originUrl: String = "https://megaplay.buzz/",
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var webView: WebView? = null
    @Volatile private var webViewReady = false
    private val atomicId = AtomicLong(0)
    private val pendingRequests = ConcurrentHashMap<String, RequestState>()
    private val fetchLock = Any() // ★ serialize all fetches (prevents concurrent WebView issues)

    private open inner class RequestState {
        val latch = CountDownLatch(1)
        var error: String? = null
    }

    private inner class TextRequestState : RequestState() {
        var textResult: String? = null
    }

    private inner class ByteRequestState : RequestState() {
        val chunks = mutableListOf<ByteArray>()
    }

    inner class JSInterface {
        @JavascriptInterface
        fun onResult(id: String, text: String) {
            pendingRequests[id]?.let { state ->
                (state as? TextRequestState)?.let {
                    it.textResult = text
                    it.latch.countDown()
                }
            }
        }

        @JavascriptInterface
        fun onChunk(id: String, index: Int, total: Int, base64data: String) {
            pendingRequests[id]?.let { state ->
                (state as? ByteRequestState)?.let {
                    synchronized(it.chunks) {
                        it.chunks.add(Base64.decode(base64data, Base64.DEFAULT))
                    }
                }
            }
        }

        @JavascriptInterface
        fun onBytesComplete(id: String, totalSize: Int) {
            pendingRequests[id]?.let { state ->
                (state as? ByteRequestState)?.latch?.countDown()
            }
        }

        @JavascriptInterface
        fun onError(id: String, error: String) {
            AnikotoLog.e("WebViewFetcher: JS error for request $id: $error")
            pendingRequests[id]?.let { state ->
                state.error = error
                state.latch.countDown()
            }
        }
    }

    /**
     * ★ session 51: Pre-warm the WebView on a background thread.
     * Call this early (e.g., during episode list fetch) so the WebView is ready
     * by the time the user clicks play. Non-blocking: starts init and returns immediately.
     * If already warmed, returns instantly.
     * ★ session 51 fix: Uses a 10-second timeout (shorter than ensureWebView's 30s).
     * If warmUp times out, ensureWebView will be called again with the full 30s when
     * the video is actually played — no data is lost, just a slower first play.
     */
    fun warmUp() {
        if (webView != null && webViewReady) return
        Thread {
            try {
                // Start WebView init on main thread
                mainHandler.post {
                    try {
                        if (webView == null) {
                            webView = WebView(context).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.blockNetworkImage = true
                                webViewClient = object : WebViewClient() {
                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        AnikotoLog.i("WebViewFetcher: warmUp page loaded: $url")
                                        webViewReady = true
                                    }
                                }
                                addJavascriptInterface(JSInterface(), "Android")
                            }
                            AnikotoLog.i("WebViewFetcher: warmUp loading origin: $originUrl")
                            webView?.loadUrl(originUrl)
                        }
                    } catch (e: Exception) {
                        AnikotoLog.e("WebViewFetcher: warmUp WebView creation failed", e)
                        webViewReady = true // Prevent spin-wait from hanging
                    }
                }
                // Wait with shorter timeout than ensureWebView (10s vs 30s)
                val deadline = System.currentTimeMillis() + 10_000
                while (!webViewReady && System.currentTimeMillis() < deadline) {
                    Thread.sleep(200)
                }
                AnikotoLog.i("WebViewFetcher: warmUp ${if (webViewReady) "complete" else "timed out (will retry on first use)"}")
            } catch (e: Exception) {
                AnikotoLog.e("WebViewFetcher: warmUp failed (will retry on first use)", e)
            }
        }.start()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureWebView() {
        if (webView != null && webViewReady) return
        synchronized(fetchLock) {
            if (webView != null && webViewReady) return
            mainHandler.post {
                try {
                    webView = WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.blockNetworkImage = true
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                AnikotoLog.i("WebViewFetcher: origin page loaded: $url")
                                webViewReady = true
                            }
                        }
                        addJavascriptInterface(JSInterface(), "Android")
                    }
                    AnikotoLog.i("WebViewFetcher: loading origin: $originUrl")
                    webView?.loadUrl(originUrl)
                } catch (e: Exception) {
                    AnikotoLog.e("WebViewFetcher: failed to create WebView", e)
                    webViewReady = true
                }
            }
            val deadline = System.currentTimeMillis() + 30_000
            while (!webViewReady && System.currentTimeMillis() < deadline) {
                Thread.sleep(200)
            }
            if (!webViewReady) {
                AnikotoLog.e("WebViewFetcher: timeout waiting for origin page load")
            }
        }
    }

    fun fetchText(url: String, timeoutMs: Long = 30_000): String {
        ensureWebView()
        val id = atomicId.incrementAndGet().toString()
        val state = TextRequestState()
        pendingRequests[id] = state
        val startTime = System.currentTimeMillis()
        AnikotoLog.d("WebViewFetcher: fetchText id=$id url=${AnikotoLog.trunc(url, 80)}")

        synchronized(fetchLock) {
            mainHandler.post {
                webView?.evaluateJavascript(buildFetchTextJs(id, url), null)
            }
            if (!state.latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                pendingRequests.remove(id)
                throw RuntimeException("WebViewFetcher: text fetch timeout for ${AnikotoLog.trunc(url, 60)}")
            }
        }
        pendingRequests.remove(id)
        state.error?.let { throw RuntimeException("WebViewFetcher: $it") }
        val elapsed = System.currentTimeMillis() - startTime
        AnikotoLog.i("WebViewFetcher: fetchText id=$id DONE in ${elapsed}ms")
        return state.textResult ?: throw RuntimeException("WebViewFetcher: no text result")
    }

    /** ★ session 36: POST JSON to a URL via WebView (for AniList GraphQL). Uses Chrome's TLS. */
    fun postJson(url: String, jsonBody: String, timeoutMs: Long = 30_000): String {
        ensureWebView()
        val id = atomicId.incrementAndGet().toString()
        val state = TextRequestState()
        pendingRequests[id] = state
        val startTime = System.currentTimeMillis()
        AnikotoLog.d("WebViewFetcher: postJson id=$id url=${AnikotoLog.trunc(url, 60)}")

        synchronized(fetchLock) {
            mainHandler.post {
                webView?.evaluateJavascript(buildPostJsonJs(id, url, jsonBody), null)
            }
            if (!state.latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                pendingRequests.remove(id)
                throw RuntimeException("WebViewFetcher: postJson timeout for ${AnikotoLog.trunc(url, 60)}")
            }
        }
        pendingRequests.remove(id)
        state.error?.let { throw RuntimeException("WebViewFetcher: $it") }
        val elapsed = System.currentTimeMillis() - startTime
        AnikotoLog.i("WebViewFetcher: postJson id=$id DONE in ${elapsed}ms")
        return state.textResult ?: throw RuntimeException("WebViewFetcher: no postJson result")
    }

    fun fetchBytes(url: String, timeoutMs: Long = 60_000): ByteArray {
        ensureWebView()
        val id = atomicId.incrementAndGet().toString()
        val state = ByteRequestState()
        pendingRequests[id] = state
        val startTime = System.currentTimeMillis()
        AnikotoLog.d("WebViewFetcher: fetchBytes id=$id url=${AnikotoLog.trunc(url, 80)}")

        synchronized(fetchLock) {
            mainHandler.post {
                webView?.evaluateJavascript(buildFetchBytesJs(id, url), null)
            }
            if (!state.latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                pendingRequests.remove(id)
                throw RuntimeException("WebViewFetcher: bytes fetch timeout for ${AnikotoLog.trunc(url, 60)}")
            }
        }
        pendingRequests.remove(id)
        state.error?.let { throw RuntimeException("WebViewFetcher: $it") }
        synchronized(state.chunks) {
            if (state.chunks.isEmpty()) throw RuntimeException("WebViewFetcher: no bytes received")
            val result = if (state.chunks.size == 1) state.chunks[0] else state.chunks.reduce { acc, chunk -> acc + chunk }
            val elapsed = System.currentTimeMillis() - startTime
            AnikotoLog.i("WebViewFetcher: fetchBytes id=$id DONE in ${elapsed}ms size=${result.size}")
            return result
        }
    }

    fun destroy() {
        webViewReady = false
        mainHandler.post {
            try { webView?.destroy() } catch (_: Exception) {}
            webView = null
        }
        pendingRequests.clear()
        destroyGoogleWebView()
    }

    // ── ★ session 51: Google AI Search WebView (separate from video pipeline) ──
    // This WebView is used for Smart Search: it loads Google AI search URLs directly
    // and extracts the rendered page text. It's separate from the video WebView because:
    // 1. The video WebView stays on megaplay.buzz (for WAF bypass) — loading Google would break it
    // 2. Google AI pages are JS-rendered — we need to wait for the AI overview to appear
    // 3. Uses a "generation token" stabilization approach (Google fires multiple onPageFinished)

    @Volatile private var googleWebView: WebView? = null
    @Volatile private var googleWebViewReady = false
    private val googleLock = Any()
    private val googleGenCounter = AtomicInteger(0)

    /**
     * ★ session 51: Pre-warm the Google WebView on a background thread.
     * Call this when the search page opens (getFilterList) so the WebView is ready
     * by the time the user submits a smart search. Non-blocking.
     */
    fun warmUpGoogleWebView() {
        if (googleWebView != null && googleWebViewReady) return
        AnikotoLog.i("SmartSearch: warmUpGoogleWebView — pre-creating Google WebView")
        Thread {
            try {
                ensureGoogleWebView()
            } catch (e: Exception) {
                AnikotoLog.e("SmartSearch: warmUpGoogleWebView failed (will retry on first use)", e)
            }
        }.start()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureGoogleWebView() {
        if (googleWebView != null && googleWebViewReady) return
        synchronized(googleLock) {
            if (googleWebView != null && googleWebViewReady) return
            AnikotoLog.d("SmartSearch: creating Google WebView")
            mainHandler.post {
                try {
                    googleWebView = WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.blockNetworkImage = true
                        settings.userAgentString = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                    }
                    googleWebViewReady = true
                    AnikotoLog.i("SmartSearch: Google WebView created and ready")
                } catch (e: Exception) {
                    AnikotoLog.e("SmartSearch: failed to create Google WebView", e)
                    googleWebViewReady = true // Prevent hang
                }
            }
            // Wait for creation (should be fast — no URL load)
            val deadline = System.currentTimeMillis() + 5_000
            while (!googleWebViewReady && System.currentTimeMillis() < deadline) {
                Thread.sleep(100)
            }
        }
    }

    /**
     * ★ session 51: Fetch the rendered text content of a URL via Google WebView.
     * Used for scraping Google AI Search results (JS-rendered pages).
     *
     * Uses a "generation token" stabilization approach:
     * - Google fires multiple onPageFinished callbacks (redirects, consent, async JS)
     * - Each callback increments the generation counter
     * - Only the LAST callback's extraction timer fires (1.5s delay)
     * - Stale timers detect their generation changed and abort
     * - If first extraction returns <200 chars, retries once after 2s
     *
     * @param url The URL to load and scrape
     * @param timeoutMs Overall timeout (default 20s)
     * @return The rendered page text, or empty string on failure
     */
    fun fetchRenderedText(url: String, timeoutMs: Long = 20_000): String {
        ensureGoogleWebView()
        if (googleWebView == null) {
            AnikotoLog.e("SmartSearch: fetchRenderedText — Google WebView not available")
            return ""
        }

        val startTime = System.currentTimeMillis()
        AnikotoLog.i("SmartSearch: scraping ${AnikotoLog.trunc(url, 100)}")

        val latch = CountDownLatch(1)
        val resultHolder = arrayOfNulls<String>(1) // [0] = extracted text
        val retryUsed = java.util.concurrent.atomic.AtomicBoolean(false)

        synchronized(googleLock) {
            mainHandler.post {
                try {
                    // Set up WebViewClient with generation-token stabilization
                    googleWebView?.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                            AnikotoLog.d("SmartSearch: onPageFinished: ${AnikotoLog.trunc(loadedUrl ?: "?", 80)}")
                            val myGen = googleGenCounter.incrementAndGet()

                            // Schedule extraction 1.5s from now
                            mainHandler.postDelayed({
                                if (googleGenCounter.get() != myGen) {
                                    AnikotoLog.d("SmartSearch: extraction gen $myGen stale (current=${googleGenCounter.get()}), skipping")
                                    return@postDelayed
                                }
                                doExtract(view, myGen)
                            }, 1500)
                        }

                        private fun doExtract(view: WebView?, myGen: Int) {
                            if (googleGenCounter.get() != myGen) return
                            view?.evaluateJavascript("(function(){ return document.body.innerText; })()") { result ->
                                if (googleGenCounter.get() != myGen) return@evaluateJavascript

                                val text = parseJsStringResult(result)
                                AnikotoLog.d("SmartSearch: extracted ${text.length} chars (first 200: ${AnikotoLog.trunc(text, 200)})")

                                if (text.length < 200 && !retryUsed.get()) {
                                    // Content too short — likely consent/redirect page. Retry after 2s.
                                    AnikotoLog.d("SmartSearch: content short (${text.length} < 200), retrying in 2s")
                                    retryUsed.set(true)
                                    mainHandler.postDelayed({
                                        if (googleGenCounter.get() != myGen) return@postDelayed
                                        view?.evaluateJavascript("(function(){ return document.body.innerText; })()") { result2 ->
                                            if (googleGenCounter.get() != myGen) return@evaluateJavascript
                                            val text2 = parseJsStringResult(result2)
                                            AnikotoLog.d("SmartSearch: retry extracted ${text2.length} chars")
                                            resultHolder[0] = text2
                                            latch.countDown()
                                        }
                                    }, 2000)
                                } else {
                                    resultHolder[0] = text
                                    latch.countDown()
                                }
                            }
                        }
                    }

                    // Load the URL
                    googleGenCounter.set(0)
                    googleWebView?.loadUrl(url)
                } catch (e: Exception) {
                    AnikotoLog.e("SmartSearch: failed to load URL", e)
                    latch.countDown()
                }
            }

            // Wait for extraction or timeout
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                AnikotoLog.e("SmartSearch: scrape timeout after ${timeoutMs}ms")
                return ""
            }
        }

        val elapsed = System.currentTimeMillis() - startTime
        val text = resultHolder[0] ?: ""
        AnikotoLog.i("SmartSearch: scrape DONE in ${elapsed}ms, ${text.length} chars")
        return text
    }

    /** Destroy the Google WebView to free memory. */
    fun destroyGoogleWebView() {
        googleWebViewReady = false
        mainHandler.post {
            try { googleWebView?.destroy() } catch (_: Exception) {}
            googleWebView = null
        }
        AnikotoLog.d("SmartSearch: Google WebView destroyed")
    }

    /**
     * Parse the result of evaluateJavascript("(function(){ return document.body.innerText; })()").
     * The result is a JSON-encoded string (with quotes and escaped chars) or "null".
     */
    private fun parseJsStringResult(result: String?): String {
        if (result.isNullOrBlank() || result == "null") return ""
        // evaluateJavascript returns the value as a JSON string literal
        // e.g., "\"Hello\nWorld\"" → Hello\nWorld
        return try {
            if (result.startsWith("\"") && result.endsWith("\"")) {
                // Remove surrounding quotes and unescape
                val inner = result.substring(1, result.length - 1)
                inner
                    .replace("\\n", "\n")
                    .replace("\\t", "\t")
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\")
                    .replace("\\'", "'")
            } else {
                result
            }
        } catch (e: Exception) {
            result
        }
    }

    // ── JavaScript builders ──────────────────────────────────────────────────

    private fun buildPostJsonJs(id: String, url: String, jsonBody: String): String {
        val escapedUrl = escapeJsString(url)
        val escapedBody = escapeJsString(jsonBody)
        return """
            (async function() {
                try {
                    const response = await fetch('$escapedUrl', {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json', 'Accept': 'application/json' },
                        body: '$escapedBody'
                    });
                    if (!response.ok) { Android.onError('$id', 'HTTP ' + response.status); return; }
                    const text = await response.text();
                    Android.onResult('$id', text);
                } catch(e) { Android.onError('$id', e.message); }
            })();
        """.trimIndent()
    }

    private fun buildFetchTextJs(id: String, url: String): String {
        val escapedUrl = escapeJsString(url)
        return """
            (async function() {
                try {
                    const response = await fetch('$escapedUrl');
                    if (!response.ok) { Android.onError('$id', 'HTTP ' + response.status); return; }
                    const text = await response.text();
                    Android.onResult('$id', text);
                } catch(e) { Android.onError('$id', e.message); }
            })();
        """.trimIndent()
    }

    private fun buildFetchBytesJs(id: String, url: String): String {
        val escapedUrl = escapeJsString(url)
        // ★ session 31: use FileReader.readAsDataURL (native, fast) instead of
        // String.fromCharCode+btoa (JS, extremely slow for 1MB+).
        // Chunk size: 700KB binary → ~933KB base64 (under 1MB IPC limit).
        return """
            (async function() {
                try {
                    const response = await fetch('$escapedUrl');
                    if (!response.ok) { Android.onError('$id', 'HTTP ' + response.status); return; }
                    const buf = await response.arrayBuffer();
                    const bytes = new Uint8Array(buf);
                    var chunkSize = 700000;
                    var numChunks = Math.ceil(bytes.length / chunkSize);
                    for (var i = 0; i < numChunks; i++) {
                        var start = i * chunkSize;
                        var end = Math.min(start + chunkSize, bytes.length);
                        var chunk = bytes.subarray(start, end);
                        var base64 = await new Promise(function(resolve) {
                            var reader = new FileReader();
                            reader.onload = function() { resolve(reader.result.split(',')[1]); };
                            reader.readAsDataURL(new Blob([chunk]));
                        });
                        Android.onChunk('$id', i, numChunks, base64);
                    }
                    Android.onBytesComplete('$id', bytes.length);
                } catch(e) { Android.onError('$id', e.message); }
            })();
        """.trimIndent()
    }

    private fun escapeJsString(s: String): String {
        return s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "\\r")
    }
}
