package com.example.vinehelper

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import java.io.Serializable

data class VineItem(
    val id: String,
    val title: String,
    val imgUrl: String,
    val href: String,
    val asin: String,
    val recId: String,
    val queue: String,
    val timestampAdded: Long = System.currentTimeMillis(),
    val isHighlighted: Boolean = false,
    val lastSeenTimestamp: Long = System.currentTimeMillis()
) : Serializable

fun getQueueDisplayName(queue: String): String {
    return when (queue) {
        "potluck" -> "RFY"
        "last_chance" -> "AFA"
        "encore" -> "AI"
        else -> queue
    }
}
const val MIN_GLOBAL_REQUEST_GAP_MS = 3_000L

internal const val SCRAPER_IMAGE_EXTRACTION_JS = """
    var image = tile.querySelector('img');
    var imageCandidates = [];
    var addImageCandidate = function(value) {
        if (!value) return;
        String(value).split(',').forEach(function(part) {
            var candidate = part.trim().split(/\s+/)[0];
            if (candidate.indexOf('//') === 0) candidate = 'https:' + candidate;
            if (/^https:\/\//i.test(candidate) &&
                candidate.indexOf('transparent-pixel') === -1 &&
                candidate.indexOf('data:image') !== 0) {
                imageCandidates.push(candidate);
            }
        });
    };
    addImageCandidate(tile.getAttribute('data-img-url'));
    if (image) {
        [
            image.currentSrc,
            image.getAttribute('src'),
            image.getAttribute('data-src'),
            image.getAttribute('data-lazy-src'),
            image.getAttribute('data-a-hires'),
            image.getAttribute('data-old-hires'),
            image.getAttribute('srcset'),
            image.getAttribute('data-srcset')
        ].forEach(addImageCandidate);
        var dynamicImages = image.getAttribute('data-a-dynamic-image');
        if (dynamicImages) {
            try {
                Object.keys(JSON.parse(dynamicImages)).forEach(addImageCandidate);
            } catch (ignored) {}
        }
        var backgroundImage = window.getComputedStyle(image).backgroundImage || '';
        var backgroundMatch = backgroundImage.match(/url\(["']?(https:[^"')]+)["']?\)/i);
        if (backgroundMatch) addImageCandidate(backgroundMatch[1]);
    }
    var source = tile.querySelector('picture source');
    if (source) {
        addImageCandidate(source.getAttribute('srcset'));
        addImageCandidate(source.getAttribute('data-srcset'));
    }
    var imgUrl = imageCandidates.length ? imageCandidates[0] : '';
"""

fun minimumRefreshSeconds(queue: String): Int = if (queue == "potluck") 5 else 3

fun clampRefreshSeconds(queue: String, requestedSeconds: Int): Int =
    requestedSeconds.coerceIn(minimumRefreshSeconds(queue), 60)

class VineScraper(
    private val context: Context,
    private val onItemsScraped: (queue: String, items: List<VineItem>) -> Unit,
    private val onStatusUpdate: (queue: String, status: String) -> Unit,
    private val onLoginStatusUpdate: (Boolean) -> Unit,
    private val isMockModeProvider: () -> Boolean = { false }
) {
    private val handler = Handler(Looper.getMainLooper())
    private val webViews = mutableMapOf<String, WebView>()
    private var refreshRunnable: Runnable? = null
    private var currentQueueIndex = 0
    
    private val _webViewsInitialized = MutableStateFlow(false)
    val webViewsInitialized: StateFlow<Boolean> = _webViewsInitialized.asStateFlow()

    private val _initializationProgress = MutableStateFlow(0f)
    val initializationProgress: StateFlow<Float> = _initializationProgress.asStateFlow()
    
    val scraperWebViews: Map<String, WebView> get() = webViews
    
    private val refreshIntervalsMs = mutableMapOf(
        "potluck" to 5_000L,
        "last_chance" to 3_000L,
        "encore" to 3_000L
    )
    private val activeIntervalsMs = refreshIntervalsMs.toMutableMap()
    private var lastServerRequestAt = 0L

    fun setRefreshIntervals(rfySeconds: Int, afaSeconds: Int, aiSeconds: Int) {
        val updated = mapOf(
            "potluck" to clampRefreshSeconds("potluck", rfySeconds) * 1_000L,
            "last_chance" to clampRefreshSeconds("last_chance", afaSeconds) * 1_000L,
            "encore" to clampRefreshSeconds("encore", aiSeconds) * 1_000L
        )
        if (updated == refreshIntervalsMs) return
        refreshIntervalsMs.putAll(updated)
        activeIntervalsMs.putAll(updated)
        if (refreshRunnable != null) restartScheduler()
    }

    private fun activeIntervalFor(queue: String): Long =
        activeIntervalsMs[queue] ?: refreshIntervalsMs.getValue(queue)

    private fun delayUntilQueueAllowed(queue: String, now: Long): Long {
        val queueDelay = ((lastRefreshTimes[queue] ?: 0L) + activeIntervalFor(queue) - now)
            .coerceAtLeast(0L)
        val globalDelay = (lastServerRequestAt + MIN_GLOBAL_REQUEST_GAP_MS - now)
            .coerceAtLeast(0L)
        return maxOf(queueDelay, globalDelay)
    }

    private val queues = listOf("potluck", "last_chance", "encore")
    private val lastRefreshTimes = mutableMapOf<String, Long>()
    private val desktopUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    fun start() {
        handler.post {
            _initializationProgress.value = 0f
            _webViewsInitialized.value = false
            initializeQueuesStaggered(0)
        }
    }

    private fun initializeQueuesStaggered(index: Int) {
        if (index >= queues.size) {
            _initializationProgress.value = 1f
            _webViewsInitialized.value = true
            startScheduler()
            return
        }
        val queue = queues[index]
        if (!webViews.containsKey(queue)) {
            setupWebViewForQueue(queue)
        }
        _initializationProgress.value = (index + 1).toFloat() / queues.size.toFloat()
        
        handler.postDelayed({
            initializeQueuesStaggered(index + 1)
        }, 300) // Staggered allocation
    }

    fun stop() {
        handler.post {
            stopScheduler()
            webViews.forEach { (_, webView) ->
                webView.destroy()
            }
            webViews.clear()
        }
    }

    private fun setupWebViewForQueue(queue: String) {
        val webView = WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = desktopUserAgent
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            settings.loadsImagesAutomatically = false // Disable image downloads for background scraper
            
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean {
                    val url = request?.url?.toString() ?: return false
                    return !isTrustedAmazonUrl(url)
                }

                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: android.webkit.WebResourceRequest?
                ): android.webkit.WebResourceResponse? {
                    val url = request?.url?.toString() ?: return null
                    val isMock = isMockModeProvider()
                    val isMain = request.isForMainFrame
                    android.util.Log.d("VineHelperDebug", "Scraper shouldInterceptRequest: url = $url, isMock = $isMock, isMain = $isMain")
                    if (isMock && (isMain || url.contains("vine-items"))) {
                        android.util.Log.d("VineHelperDebug", "Scraper INTERCEPTING mock request for url = $url")
                        val mockHtml = MockStoreProvider.getMockHtml()
                        val inputStream = java.io.ByteArrayInputStream(mockHtml.toByteArray(Charsets.UTF_8))
                        return android.webkit.WebResourceResponse("text/html", "UTF-8", inputStream)
                    }
                    if (url.endsWith(".css") || url.contains(".css?") ||
                        url.endsWith(".png") || url.endsWith(".jpg") || url.endsWith(".jpeg") || url.endsWith(".gif") || url.endsWith(".webp") ||
                        url.contains("/images/") || url.contains("media") ||
                        url.contains("analytics") || url.contains("telemetry") || url.contains("doubleclick") ||
                        url.contains("fonts.googleapis.com") || url.contains("fonts.gstatic.com") ||
                        url.contains("amazon-adsystem.com")
                    ) {
                        return android.webkit.WebResourceResponse(
                            "text/plain",
                            "UTF-8",
                            java.io.ByteArrayInputStream(ByteArray(0))
                        )
                    }
                    return null
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (url != null && isTrustedAmazonUrl(url)) {
                        onStatusUpdate(queue, "Scraping...")
                        runScraperScript(queue)
                    } else {
                        onStatusUpdate(queue, "Blocked: Untrusted Page")
                    }
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: android.webkit.WebResourceRequest?,
                    error: android.webkit.WebResourceError?
                ) {
                    super.onReceivedError(view, request, error)
                    if (request?.isForMainFrame == true) {
                        onStatusUpdate(queue, "Error: ${error?.description ?: "Unknown error"}")
                        triggerThrottling(queue)
                    }
                }
            }
            
            addJavascriptInterface(
                AndroidBridgeInterface(
                    onPost = { json -> parseAndDeliverItems(queue, json) }
                ),
                "AndroidBridge"
            )
        }
        
        webViews[queue] = webView
        onStatusUpdate(queue, "Ready")
    }

    private fun loadQueuePage(queue: String) {
        val url = "https://www.amazon.com/vine/vine-items?queue=$queue"
        webViews[queue]?.loadUrl(url)
    }

    private fun runScraperScript(queue: String) {
        val script = """
            (function() {
                var loggedIn = true;
                if (document.title.indexOf("Sign-In") !== -1 || 
                    document.title.indexOf("Sign In") !== -1 || 
                    document.querySelector("#ap_signin_form") !== null || 
                    document.title.indexOf("Looking for Something") !== -1) {
                    loggedIn = false;
                }
                
                var isDogPage = document.title.indexOf("Looking for Something") !== -1 || 
                                document.title.indexOf("Looking for something") !== -1 ||
                                document.title.indexOf("Looking for Something...") !== -1;
                
                var isValidPage = document.querySelector('#vvp-items-grid') !== null || 
                                  document.querySelector('.vvp-no-items-feedback') !== null;
                
                var tiles = document.querySelectorAll('.vvp-item-tile');
                var items = [];
                
                for (var i = 0; i < tiles.length; i++) {
                    var tile = tiles[i];
                    var id = tile.getAttribute('data-recommendation-id') || '';
                    var titleEl = tile.querySelector('.vvp-item-product-title-container a');
                    var title = titleEl ? titleEl.textContent.trim() : '';
                    var href = titleEl ? titleEl.getAttribute('href') : '';
                    var input = tile.querySelector('.vvp-details-btn input');
                    var buttonAsin = input ? (input.getAttribute('data-asin') || '').toUpperCase() : '';
                    var hrefMatch = href ? href.match(/\/dp\/([A-Z0-9]{10})(?:[/?]|$)/i) : null;
                    var linkAsin = hrefMatch ? hrefMatch[1].toUpperCase() : '';
                    var asin = linkAsin || buttonAsin;
                    var recId = input ? input.getAttribute('data-recommendation-id') : '';
                    $SCRAPER_IMAGE_EXTRACTION_JS
                    
                    if (asin && (!linkAsin || !buttonAsin || linkAsin === buttonAsin)) {
                        items.push({
                            id: id || recId || asin,
                            title: title,
                            imgUrl: imgUrl,
                            href: href,
                            asin: asin,
                            recId: recId || ''
                        });
                    }
                }
                
                var result = {
                    loggedIn: loggedIn,
                    isDogPage: isDogPage,
                    isValidPage: isValidPage,
                    items: items
                };
                if (window.AndroidBridge) {
                    window.AndroidBridge.postItems(JSON.stringify(result));
                }
            })();
        """.trimIndent()
        
        webViews[queue]?.evaluateJavascript(script, null)
    }

    private fun parseAndDeliverItems(queue: String, json: String) {
        try {
            val list = mutableListOf<VineItem>()
            val wrapper = org.json.JSONObject(json)
            val loggedIn = wrapper.optBoolean("loggedIn", true)
            
            onLoginStatusUpdate(loggedIn)
            
            if (!loggedIn) {
                onStatusUpdate(queue, "Logged Out")
                return
            }

            val isDogPage = wrapper.optBoolean("isDogPage", false)
            val isValidPage = wrapper.optBoolean("isValidPage", false)

            if (isDogPage || !isValidPage) {
                triggerThrottling(queue)
                return
            }

            // Success! Reset any throttling for this queue
            resetThrottling(queue)

            val array = wrapper.getJSONArray("items")
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val asin = obj.optString("asin", "")
                if (asin.isEmpty()) continue
                
                list.add(
                    VineItem(
                        id = obj.optString("id", asin),
                        title = obj.optString("title", ""),
                        imgUrl = obj.optString("imgUrl", ""),
                        href = obj.optString("href", ""),
                        asin = asin,
                        recId = obj.optString("recId", ""),
                        queue = queue
                    )
                )
            }
            onItemsScraped(queue, list)
            onStatusUpdate(queue, "Idle (Waiting next refresh)")
        } catch (e: Exception) {
            e.printStackTrace()
            onStatusUpdate(queue, "Parse Error")
        }
    }

    private fun triggerThrottling(queue: String) {
        val currentInterval = activeIntervalFor(queue)
        val newInterval = (currentInterval * 2).coerceAtMost(120_000L)
        activeIntervalsMs[queue] = newInterval
        android.util.Log.d("VineHelperDebug", "Throttling $queue: rate slowed to ${newInterval}ms")
        onStatusUpdate(queue, "Throttled (${newInterval / 1000}s)")
    }

    private fun resetThrottling(queue: String) {
        val baseInterval = refreshIntervalsMs.getValue(queue)
        if (activeIntervalFor(queue) != baseInterval) {
            activeIntervalsMs[queue] = baseInterval
            android.util.Log.d("VineHelperDebug", "Throttling reset for $queue: ${baseInterval}ms")
        }
    }

    private fun startScheduler() {
        stopScheduler()
        currentQueueIndex = 0

        val runnable = object : Runnable {
            override fun run() {
                val now = System.currentTimeMillis()
                val dueIndex = queues.indices
                    .map { offset -> (currentQueueIndex + offset) % queues.size }
                    .firstOrNull { index -> delayUntilQueueAllowed(queues[index], now) == 0L }

                if (dueIndex == null) {
                    val nextDelay = queues.minOf { queue -> delayUntilQueueAllowed(queue, now) }
                        .coerceIn(100L, MIN_GLOBAL_REQUEST_GAP_MS)
                    handler.postDelayed(this, nextDelay)
                    return
                }

                val queue = queues[dueIndex]
                onStatusUpdate(queue, "Refreshing...")
                lastRefreshTimes[queue] = now
                lastServerRequestAt = now
                loadQueuePage(queue)

                currentQueueIndex = (dueIndex + 1) % queues.size
                handler.postDelayed(this, MIN_GLOBAL_REQUEST_GAP_MS)
            }
        }
        refreshRunnable = runnable
        handler.post(runnable)
    }

    private fun stopScheduler() {
        refreshRunnable?.let { handler.removeCallbacks(it) }
        refreshRunnable = null
        queues.forEach { queue ->
            onStatusUpdate(queue, "Stopped")
        }
    }

    private fun restartScheduler() {
        stopScheduler()
        startScheduler()
    }

    fun triggerManualRefresh(onComplete: () -> Unit) {
        handler.post {
            stopScheduler()

            fun refreshNext(index: Int) {
                if (index >= queues.size) {
                    startScheduler()
                    onComplete()
                    return
                }

                val queue = queues[index]
                val delay = delayUntilQueueAllowed(queue, System.currentTimeMillis())
                handler.postDelayed({
                    val requestTime = System.currentTimeMillis()
                    onStatusUpdate(queue, "Refreshing...")
                    lastRefreshTimes[queue] = requestTime
                    lastServerRequestAt = requestTime
                    loadQueuePage(queue)
                    refreshNext(index + 1)
                }, delay)
            }

            refreshNext(0)
        }
    }

    private class AndroidBridgeInterface(
        private val onPost: (String) -> Unit
    ) {
        @JavascriptInterface
        fun postItems(json: String) {
            onPost(json)
        }
    }
}
