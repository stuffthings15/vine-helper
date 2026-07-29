package com.example.vinehelper

import android.Manifest
import androidx.compose.foundation.layout.Box

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import android.view.WindowManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.lifecycleScope
import com.example.vinehelper.theme.VineHelperTheme
import com.example.vinehelper.ui.main.MainScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private lateinit var vineScraper: VineScraper
    private val visibleWebViewState = mutableStateOf<WebView?>(null)

    private var pendingAsinClick: String? = null
    private var pendingRecIdClick: String? = null
    private var pendingQueueClick: String? = null
    private var pendingImageUrl: String = ""
    private val detailsClickHandler = Handler(Looper.getMainLooper())
    private var browserLoadGeneration = 0L
    private var detailsRetryRunnable: Runnable? = null
    private var detailsRequestGeneration = 0L
    private var detailsClickAttempts = 0
    private var detailsModalCheckAttempts = 0
    private var detailsModalReclickAttempted = false
    private var detailsPageReloadAttempted = false
    private var pendingDetailsTargetUrl: String? = null
    private var pendingDetailsSearchUrl: String? = null
    private var pendingCategoryUrl: String? = null
    private var pendingSoundQueuePicker: String? = null
    private val activeTabState = mutableStateOf(0)
    private var autoClaimEnabled: Boolean = false
    private var monitoringServiceStarted = false
    private var webViewInitializationScheduled = false
    private var webViewInitializationStarted = false
    private val requestNotificationPermissionRunnable = Runnable { requestNotificationPermission() }
    private val startWebViewInitializationRunnable = Runnable { startWebViewInitialization() }

    private var isPluggedIn = false

    private val powerConnectionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_POWER_CONNECTED -> {
                    isPluggedIn = true
                    updateKeepAwakeFlag()
                }
                Intent.ACTION_POWER_DISCONNECTED -> {
                    isPluggedIn = false
                    updateKeepAwakeFlag()
                }
                Intent.ACTION_BATTERY_CHANGED -> {
                    val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
                    isPluggedIn = plugged == BatteryManager.BATTERY_PLUGGED_AC ||
                                  plugged == BatteryManager.BATTERY_PLUGGED_USB ||
                                  plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS
                    updateKeepAwakeFlag()
                }
            }
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        // Permission result handled automatically by system notification service
    }

    private val pickAudioLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            try {
                // Grant persistent read access to the audio URI
                contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
            pendingSoundQueuePicker?.let { queue ->
                viewModel.updateQueueSound(queue, it.toString())
                pendingSoundQueuePicker = null
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        createNotificationChannel()

        // Instantiate scraper immediately (lightweight, no WebViews allocated yet)
        vineScraper = VineScraper(
            context = this,
            onItemsScraped = { queue, items -> viewModel.handleScrapedItems(queue, items) },
            onStatusUpdate = { queue, status -> viewModel.updateScraperStatus(queue, status) },
            onLoginStatusUpdate = { loggedIn -> viewModel.updateLoginState(loggedIn) },
            isMockModeProvider = { viewModel.settings.value.mockModeEnabled }
        )

        // Render Layout immediately for instant startup
        setContent {
            VineHelperTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val webView = visibleWebViewState.value
                    Box(modifier = Modifier.fillMaxSize()) {
                        MainScreen(
                            viewModel = viewModel,
                            visibleWebView = webView,
                            onSeeDetailsClick = { queue, asin, recId ->
                                triggerSeeDetails(queue, asin, recId)
                            },
                            onPickCustomSound = { queue ->
                                pendingSoundQueuePicker = queue
                                pickAudioLauncher.launch("audio/*")
                            },
                            onLoginClick = {
                                activeTabState.value = 1
                                webView?.loadUrl("https://www.amazon.com/gp/sign-in.html")
                            },
                            onCloseApp = {
                                stopService(Intent(this@MainActivity, VineScraperService::class.java))
                                finishAndRemoveTask()
                            },
                            onTestNotification = {
                                viewModel.testSendNotification()
                            },
                            onManualRefresh = { viewModel.triggerManualRefresh(vineScraper) },
                            onCategoryClick = { queue -> openCategory(queue) },
                            activeTabState = activeTabState,
                            webViewsInitializedFlow = vineScraper.webViewsInitialized,
                            initializationProgressFlow = vineScraper.initializationProgress
                        )
                        // Scraper WebViews stay detached. Even a 1.dp attached WebView owns a native
                        // surface and can cover the whole Compose window on Samsung devices.
                    }
                }
            }
        }

        // Register power receiver for "Keep Awake"
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
        }
        val batteryStatus = registerReceiver(powerConnectionReceiver, filter)
        val plugged = batteryStatus?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
        isPluggedIn = plugged == BatteryManager.BATTERY_PLUGGED_AC ||
                      plugged == BatteryManager.BATTERY_PLUGGED_USB ||
                      plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS

        // Handle System Back Navigation
        val onBackPressedCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val webView = visibleWebViewState.value
                if (activeTabState.value == 1) {
                    abandonPendingDetailsRequest()
                }
                if (activeTabState.value == 1 && webView != null && webView.canGoBack()) {
                    webView.goBack()
                } else if (activeTabState.value != 0) {
                    activeTabState.value = 0
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        }
        onBackPressedDispatcher.addCallback(this, onBackPressedCallback)

        // Handle Intent from Notification (if any)
        handleIntent(intent)

        // Asynchronously initialize WebViews staggered to allow instantaneous startup
        initializeWebViewsAsync()
    }

    private fun initializeWebViewsAsync() {
        if (webViewInitializationScheduled || webViewInitializationStarted) return
        webViewInitializationScheduled = true

        val decorView = window.decorView
        var firstDrawHandled = false
        val drawListener = object : android.view.ViewTreeObserver.OnDrawListener {
            override fun onDraw() {
                if (firstDrawHandled) return
                firstDrawHandled = true
                decorView.post {
                    if (decorView.viewTreeObserver.isAlive) {
                        decorView.viewTreeObserver.removeOnDrawListener(this)
                    }
                    if (isFinishing || isDestroyed) return@post
                    decorView.postDelayed(requestNotificationPermissionRunnable, 500L)
                    decorView.postDelayed(startWebViewInitializationRunnable, 1_500L)
                }
            }
        }
        decorView.viewTreeObserver.addOnDrawListener(drawListener)
    }

    private fun startWebViewInitialization() {
        if (webViewInitializationStarted || isFinishing || isDestroyed) return
        webViewInitializationStarted = true
        lifecycleScope.launch {
            // 1. Initialize Visible WebView for manual claiming
            val webView = WebView(this@MainActivity).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                settings.cacheMode = WebSettings.LOAD_DEFAULT
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                settings.setSupportZoom(true)
                settings.layoutAlgorithm = WebSettings.LayoutAlgorithm.TEXT_AUTOSIZING

                // Enable Force Dark / Algorithmic Darkening to prevent white flashes when loading web content
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    settings.isAlgorithmicDarkeningAllowed = true
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    @Suppress("DEPRECATION")
                    settings.forceDark = WebSettings.FORCE_DARK_ON
                }

                // Let WebView choose its own layer. A forced hardware layer can deadlock the render surface.
                // The loading timeout below also prevents a permanent mask if a page never completes.
                setBackgroundColor(android.graphics.Color.parseColor("#141923")) // matches VineSurface dark color

                // Enable Autofill Integration for Password Managers (Bitwarden/1Password/Google)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_YES
                }

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
                        val isMock = viewModel.settings.value.mockModeEnabled
                        val isMain = request.isForMainFrame
                        android.util.Log.d("VineHelperDebug", "Visible WebView shouldInterceptRequest: url = $url, isMock = $isMock, isMain = $isMain")
                        if (isMock && (isMain || url.contains("vine-items"))) {
                            android.util.Log.d("VineHelperDebug", "Visible WebView INTERCEPTING mock request for url = $url")
                            val mockHtml = MockStoreProvider.getMockHtml()
                            val inputStream = java.io.ByteArrayInputStream(mockHtml.toByteArray(Charsets.UTF_8))
                            return android.webkit.WebResourceResponse("text/html", "UTF-8", inputStream)
                        }
                        if (isMock && (url.contains("placehold.co") || url.endsWith(".png") || url.endsWith(".jpg") || url.endsWith(".jpeg") || url.endsWith(".gif") || url.endsWith(".webp") || url.endsWith(".ico") || url.endsWith(".css") || url.contains(".css?"))) {
                            android.util.Log.d("VineHelperDebug", "Visible WebView INTERCEPTING asset request for url = $url")
                            return android.webkit.WebResourceResponse(
                                "text/plain",
                                "UTF-8",
                                java.io.ByteArrayInputStream(ByteArray(0))
                            )
                        }
                        return null
                    }

                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        viewModel.setBrowserLoading(true)
                        browserLoadGeneration += 1L
                        val loadGeneration = browserLoadGeneration
                        detailsClickHandler.postDelayed({
                            if (loadGeneration == browserLoadGeneration) {
                                android.util.Log.w(
                                    "VineHelperDebug",
                                    "Browser loading mask timed out; revealing the WebView"
                                )
                                viewModel.setBrowserLoading(false)
                            }
                        }, 15_000L)
                    }

                    override fun onPageCommitVisible(view: WebView?, url: String?) {
                        super.onPageCommitVisible(view, url)
                        val currentView = view ?: return
                        viewModel.setBrowserLoading(false)
                        injectCustomMobileStyles(currentView)
                        // Do not click the details button here. Amazon paints the grid before
                        // its modal event handlers are attached, so an early click can report
                        // success yet open nothing. onPageFinished starts the exact lookup.
                    }

                    override fun onReceivedError(view: WebView?, request: android.webkit.WebResourceRequest?, error: android.webkit.WebResourceError?) {
                        super.onReceivedError(view, request, error)
                        browserLoadGeneration += 1L
                        viewModel.setBrowserLoading(false)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        browserLoadGeneration += 1L
                        viewModel.setBrowserLoading(false)
                        android.util.Log.d("VineHelperDebug", "Visible WebView onPageFinished: url = $url, pendingAsinClick = $pendingAsinClick, pendingRecIdClick = $pendingRecIdClick")

                        // Inject mobile viewport and custom styles
                        injectCustomMobileStyles(this@apply)

                        url?.let { currentUrl ->
                            if (
                                pendingCategoryUrl != null &&
                                currentUrl.contains("/vine/vine-items")
                            ) {
                                pendingCategoryUrl = null
                            }
                            if (currentUrl.contains("/vine/vine-items")) {
                                val targetAsin = pendingAsinClick
                                val targetQueue = pendingQueueClick
                                if (
                                    targetAsin != null &&
                                    targetQueue != null &&
                                    isMatchingQueueUrl(currentUrl, targetQueue)
                                ) {
                                    android.util.Log.d(
                                        "VineHelperDebug",
                                        "Visible WebView starting real details-button lookup for ASIN = $targetAsin, recId = $pendingRecIdClick"
                                    )
                                    beginPendingDetailsClick(this@apply)
                                } else if (targetAsin != null) {
                                    android.util.Log.d(
                                        "VineHelperDebug",
                                        "Ignoring stale onPageFinished while waiting for $pendingDetailsTargetUrl; finished URL = $currentUrl"
                                    )
                                }
                            }
                        }

                        url?.let { currentUrl ->
                            val cookieManager = android.webkit.CookieManager.getInstance()
                            val cookies = cookieManager.getCookie("https://www.amazon.com") ?: ""
                            val hasSession = cookies.contains("session-id")

                            if (currentUrl.contains("vine-items")) {
                                if (hasSession) {
                                    val wasLoggedIn = viewModel.isLoggedIn.value
                                    viewModel.updateLoginState(true)
                                    cookieManager.flush()
                                    if (!wasLoggedIn) {
                                        // Only start the scraper if they weren't logged in before!
                                        startScraperMonitoring()
                                    }
                                }
                            } else if (!currentUrl.contains("/ap/signin") && !currentUrl.contains("/ap/mfa")) {
                                if (hasSession) {
                                    val parsedUri = android.net.Uri.parse(currentUrl)
                                    val isHomePage = parsedUri.host == "www.amazon.com" &&
                                                     (parsedUri.path == null || parsedUri.path == "/" || parsedUri.path == "")

                                    if (isHomePage || currentUrl.contains("ref=nav_youraccount_btn") || currentUrl.contains("yourstore") || currentUrl.contains("gateway")) {
                                        cookieManager.flush()
                                        view?.loadUrl(pendingDetailsTargetUrl ?: "https://www.amazon.com/vine/vine-items")

                                        val wasLoggedIn = viewModel.isLoggedIn.value
                                        viewModel.updateLoginState(true)
                                        if (!wasLoggedIn) {
                                            startScraperMonitoring()
                                        }
                                    }
                                }
                            }
                        }
                        val autoClaimScript = """
                            (function() {
                                if (window.autoClaimInterval) {
                                    clearInterval(window.autoClaimInterval);
                                }
                                window.autoClaimInterval = setInterval(function() {
                                    if (!window.autoClaimEnabled) return;

                                    var reqButton = document.querySelector('#vvp-join-btn input');
                                    if (reqButton && !reqButton.disabled) {
                                        reqButton.click();
                                    }

                                    var submitButton = document.querySelector('#vvp-shipping-address-modal--submit-btn input');
                                    if (submitButton && !submitButton.disabled) {
                                        submitButton.click();
                                    }
                                }, 500);
                            })();
                        """.trimIndent()
                        evaluateJavascript(autoClaimScript, null)
                        updateAutoClaimStateInWebView()
                    }
                }
                // Load Vine items page directly. If logged out, it loads the 'dogs' page initially, but center 'Log In' button takes them to sign-in.
                val initialQueue = pendingQueueClick
                val initialUrl = pendingDetailsTargetUrl ?: pendingCategoryUrl ?: if (initialQueue != null) {
                    "https://www.amazon.com/vine/vine-items?queue=$initialQueue"
                } else {
                    "https://www.amazon.com/vine/vine-items"
                }
                loadUrl(initialUrl)
            }

            visibleWebViewState.value = webView

            // 2. Start Background Scraper (only if user is already logged in)
            val cookieManager = android.webkit.CookieManager.getInstance()
            val cookies = cookieManager.getCookie("https://www.amazon.com") ?: ""
            if (cookies.contains("session-id")) {
                viewModel.updateLoginState(true)
                startScraperMonitoring()
            }

            // 3. Collect Settings & Events from ViewModel
            var lastMockMode = false
            lifecycleScope.launch {
                viewModel.settings.collect { settings ->
                    vineScraper.setRefreshIntervals(
                        rfySeconds = settings.refreshRateRfySeconds,
                        afaSeconds = settings.refreshRateAfaSeconds,
                        aiSeconds = settings.refreshRateAiSeconds
                    )
                    autoClaimEnabled = settings.autoClaimEnabled
                    updateAutoClaimStateInWebView()
                    updateKeepAwakeFlag()

                    if (settings.mockModeEnabled && !lastMockMode) {
                        startScraperMonitoring()
                    }
                    lastMockMode = settings.mockModeEnabled
                }
            }

            lifecycleScope.launch {
                viewModel.soundEvents.collect { soundType ->
                    playSound(soundType)
                }
            }

            lifecycleScope.launch {
                viewModel.notificationEvents.collect { item ->
                    android.util.Log.d("VineHelperDebug", "MainActivity collected notification event: ${item.asin}")
                    showNotification(item)
                }
            }
        }
    }

    private fun startScraperMonitoring() {
        vineScraper.start()
        if (monitoringServiceStarted) return

        val serviceIntent = Intent(this, VineScraperService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        monitoringServiceStarted = true
    }
    private fun requestNotificationPermission() {
        if (isFinishing || isDestroyed) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Vine Helper Alerts"
            val descriptionText = "Alerts when new items are found on Amazon Vine"
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel("VINE_HELPER_ALERTS", name, importance).apply {
                description = descriptionText
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun showNotification(item: VineItem) {
        android.util.Log.d("VineHelperDebug", "showNotification called for ${item.asin}, title: ${item.title}")
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Prune old notifications to avoid hitting the Android 50 notification limit per package
        try {
            val activeNotifs = notificationManager.activeNotifications
                .filter { it.id != 9999 } // Keep the scraper service notification
                .sortedBy { it.postTime }
            if (activeNotifs.size >= 10) {
                val toCancelCount = activeNotifs.size - 9
                for (i in 0 until toCancelCount) {
                    notificationManager.cancel(activeNotifs[i].tag, activeNotifs[i].id)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("EXTRA_ASIN", item.asin)
            putExtra("EXTRA_QUEUE", item.queue)
            putExtra("EXTRA_REC_ID", item.recId)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            item.asin.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = "New Item in ${getQueueDisplayName(item.queue).uppercase()}"

        val builder = NotificationCompat.Builder(this, "VINE_HELPER_ALERTS")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(item.title)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        with(NotificationManagerCompat.from(this)) {
            val hasPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
            if (hasPermission) {
                notify(item.asin.hashCode(), builder.build())
            }
        }
    }

    private fun createMediaPlayer(context: Context, resId: Int, audioAttributes: android.media.AudioAttributes): MediaPlayer? {
        return try {
            MediaPlayer.create(context, resId, audioAttributes, 0) ?: MediaPlayer.create(context, resId)
        } catch (e: Exception) {
            MediaPlayer.create(context, resId)
        }
    }

    private fun playSound(soundType: String) {
        try {
            val audioAttributes = android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()

            val mediaPlayer = when (soundType) {
                "chime" -> createMediaPlayer(this, R.raw.chime, audioAttributes)
                "pop" -> createMediaPlayer(this, R.raw.pop, audioAttributes)
                "bell" -> createMediaPlayer(this, R.raw.bell, audioAttributes)
                "ding" -> createMediaPlayer(this, R.raw.ding, audioAttributes)
                "success" -> createMediaPlayer(this, R.raw.success, audioAttributes)
                "alarm" -> createMediaPlayer(this, R.raw.alarm, audioAttributes)
                "bubble" -> createMediaPlayer(this, R.raw.bubble, audioAttributes)
                "laser" -> createMediaPlayer(this, R.raw.laser, audioAttributes)
                "horn" -> createMediaPlayer(this, R.raw.horn, audioAttributes)
                "crystal" -> createMediaPlayer(this, R.raw.crystal, audioAttributes)
                "digital" -> createMediaPlayer(this, R.raw.digital, audioAttributes)
                "sweep" -> createMediaPlayer(this, R.raw.sweep, audioAttributes)
                "click" -> createMediaPlayer(this, R.raw.click, audioAttributes)
                "ping" -> createMediaPlayer(this, R.raw.ping, audioAttributes)
                "magic" -> createMediaPlayer(this, R.raw.magic, audioAttributes)
                "guitar" -> createMediaPlayer(this, R.raw.guitar, audioAttributes)
                "piano" -> createMediaPlayer(this, R.raw.piano, audioAttributes)
                "violin" -> createMediaPlayer(this, R.raw.violin, audioAttributes)
                "alien" -> createMediaPlayer(this, R.raw.alien, audioAttributes)
                "cyber" -> createMediaPlayer(this, R.raw.cyber, audioAttributes)
                else -> {
                    try {
                        MediaPlayer().apply {
                            setAudioAttributes(audioAttributes)
                            setDataSource(this@MainActivity, Uri.parse(soundType))
                            prepare()
                        }
                    } catch (e: Exception) {
                        // Fallback to chime if custom Uri fails
                        createMediaPlayer(this, R.raw.chime, audioAttributes)
                    }
                }
            }
            mediaPlayer?.setOnCompletionListener { it.release() }
            mediaPlayer?.start()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun updateKeepAwakeFlag() {
        val keepAwakeSetting = viewModel.settings.value.keepAwakeWhileCharging
        if (keepAwakeSetting && isPluggedIn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun openCategory(queue: String) {
        val categoryUrl = buildQueueUrl(queue)
        pendingCategoryUrl = categoryUrl
        activeTabState.value = 1
        viewModel.setBrowserLoading(true)
        visibleWebViewState.value?.loadUrl(categoryUrl)
    }

    private fun triggerSeeDetails(queue: String, asin: String) {
        val item = viewModel.items.value.find { it.asin == asin }
        val recId = if (item != null) item.recId else viewModel.getRecIdFromDb(asin)
        triggerSeeDetails(queue, asin, recId)
    }

    private fun triggerSeeDetails(queue: String, asin: String, recId: String) {
        android.util.Log.d("VineHelperDebug", "triggerSeeDetails called: queue = $queue, asin = $asin, recId = $recId")
        val pendingItem = viewModel.items.value.firstOrNull { it.asin == asin }
        activeTabState.value = 1

        cancelDetailsClickRetry()
        detailsRequestGeneration += 1L
        detailsClickAttempts = 0
        detailsModalCheckAttempts = 0
        detailsModalReclickAttempted = false
        detailsPageReloadAttempted = false
        pendingAsinClick = asin
        pendingRecIdClick = recId
        pendingQueueClick = queue
        pendingImageUrl = pendingItem?.imgUrl.orEmpty()
        // Amazon's Vine search box does not reliably accept ASINs. Loading a
        // ?search=<asin> URL can produce "No results found" even while the tile
        // is still requestable. Load the live queue and select its exact real
        // button by recommendation ID/ASIN instead.
        pendingDetailsTargetUrl = buildQueueUrl(queue)
        pendingDetailsSearchUrl = pendingItem?.title
            ?.takeIf { it.isNotBlank() }
            ?.let { buildQueueTitleSearchUrl(queue, it) }

        val webView = visibleWebViewState.value
        if (webView == null) {
            android.util.Log.d(
                "VineHelperDebug",
                "triggerSeeDetails: visible WebView is not ready; queued search for ASIN = $asin"
            )
            return
        }

        val targetUrl = requireNotNull(pendingDetailsTargetUrl)
        val currentUrl = webView.url.orEmpty()
        if (isMatchingQueueUrl(currentUrl, queue)) {
            android.util.Log.d("VineHelperDebug", "triggerSeeDetails: using already-loaded queue")
            viewModel.setBrowserLoading(false)
            beginPendingDetailsClick(webView)
        } else {
            android.util.Log.d("VineHelperDebug", "triggerSeeDetails: loading queue: $targetUrl")
            viewModel.setBrowserLoading(true)
            webView.loadUrl(targetUrl)
        }
    }

    private fun beginPendingDetailsClick(webView: WebView) {
        if (pendingAsinClick == null) return
        cancelDetailsClickRetry()
        detailsClickAttempts = 0
        attemptPendingDetailsClick(webView, detailsRequestGeneration)
    }

    private fun attemptPendingDetailsClick(webView: WebView, requestGeneration: Long) {
        if (requestGeneration != detailsRequestGeneration) return
        val asin = pendingAsinClick ?: return
        val recId = pendingRecIdClick.orEmpty()

        webView.evaluateJavascript(buildDetailsClickScript(asin, recId, pendingImageUrl)) { rawResult ->
            if (requestGeneration != detailsRequestGeneration) return@evaluateJavascript

            if (didClickExistingDetailsButton(rawResult)) {
                android.util.Log.d(
                    "VineHelperDebug",
                    "Details click started using Amazon's real button for ASIN = $asin"
                )
                detailsModalCheckAttempts = 0
                verifyPendingDetailsModal(webView, requestGeneration)
                return@evaluateJavascript
            }

            // A live Vine grid binds its tile buttons quickly. Long polling here made
            // stale feed entries feel frozen. Fall back to a title search after 1.2s.
            if (detailsClickAttempts < 12) {
                detailsClickAttempts += 1
                val retry = Runnable {
                    detailsRetryRunnable = null
                    attemptPendingDetailsClick(webView, requestGeneration)
                }
                detailsRetryRunnable = retry
                detailsClickHandler.postDelayed(retry, 100L)
                return@evaluateJavascript
            }

            val fallbackUrl = pendingDetailsSearchUrl
            if (!detailsPageReloadAttempted && fallbackUrl != null) {
                detailsPageReloadAttempted = true
                detailsClickAttempts = 0
                cancelDetailsClickRetry()
                pendingDetailsTargetUrl = fallbackUrl
                android.util.Log.w(
                    "VineHelperDebug",
                    "Real details button was not on the current grid for ASIN = $asin; searching by title"
                )
                viewModel.setBrowserLoading(true)
                webView.loadUrl(fallbackUrl)
                return@evaluateJavascript
            }

            android.util.Log.w(
                "VineHelperDebug",
                "Amazon did not render a real details button for ASIN = $asin after title search"
            )
            clearPendingDetailsRequest(requestGeneration)
            Toast.makeText(
                this,
                "Amazon did not load this item's details. Refresh the feed and try again.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun verifyPendingDetailsModal(webView: WebView, requestGeneration: Long) {
        if (requestGeneration != detailsRequestGeneration) return
        val asin = pendingAsinClick ?: return

        webView.evaluateJavascript(buildDetailsModalReadyScript()) { rawResult ->
            if (requestGeneration != detailsRequestGeneration) return@evaluateJavascript
            if (isDetailsModalReady(rawResult)) {
                android.util.Log.d(
                    "VineHelperDebug",
                    "Amazon details modal confirmed for ASIN = $asin"
                )
                viewModel.setBrowserLoading(false)
                clearPendingDetailsRequest(requestGeneration)
                return@evaluateJavascript
            }

            if (detailsModalCheckAttempts < 30) {
                detailsModalCheckAttempts += 1
                val retry = Runnable {
                    detailsRetryRunnable = null
                    verifyPendingDetailsModal(webView, requestGeneration)
                }
                detailsRetryRunnable = retry
                detailsClickHandler.postDelayed(retry, 100L)
                return@evaluateJavascript
            }

            if (!detailsModalReclickAttempted) {
                detailsModalReclickAttempted = true
                detailsModalCheckAttempts = 0
                detailsClickAttempts = 0
                android.util.Log.w(
                    "VineHelperDebug",
                    "Details modal did not open for ASIN = $asin; closing stale modal and retrying once"
                )
                attemptPendingDetailsClick(webView, requestGeneration)
                return@evaluateJavascript
            }

            val fallbackUrl = pendingDetailsSearchUrl
            if (!detailsPageReloadAttempted && fallbackUrl != null) {
                detailsPageReloadAttempted = true
                detailsModalCheckAttempts = 0
                detailsClickAttempts = 0
                pendingDetailsTargetUrl = fallbackUrl
                viewModel.setBrowserLoading(true)
                webView.loadUrl(fallbackUrl)
                return@evaluateJavascript
            }

            android.util.Log.w(
                "VineHelperDebug",
                "Amazon button click did not open a usable details modal for ASIN = $asin"
            )
            clearPendingDetailsRequest(requestGeneration)
            Toast.makeText(
                this,
                "Amazon did not open this item's details. Refresh the feed and try again.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun cancelDetailsClickRetry() {
        detailsRetryRunnable?.let { detailsClickHandler.removeCallbacks(it) }
        detailsRetryRunnable = null
    }

    private fun abandonPendingDetailsRequest() {
        detailsRequestGeneration += 1L
        cancelDetailsClickRetry()
        pendingAsinClick = null
        pendingRecIdClick = null
        pendingQueueClick = null
        pendingImageUrl = ""
        pendingDetailsTargetUrl = null
        pendingDetailsSearchUrl = null
        detailsClickAttempts = 0
        detailsModalCheckAttempts = 0
        detailsModalReclickAttempted = false
        detailsPageReloadAttempted = false
    }
    private fun clearPendingDetailsRequest(requestGeneration: Long) {
        if (requestGeneration != detailsRequestGeneration) return
        cancelDetailsClickRetry()
        pendingAsinClick = null
        pendingRecIdClick = null
        pendingQueueClick = null
        pendingImageUrl = ""
        pendingDetailsTargetUrl = null
        pendingDetailsSearchUrl = null
        detailsClickAttempts = 0
        detailsModalCheckAttempts = 0
        detailsModalReclickAttempted = false
        detailsPageReloadAttempted = false
    }

    private fun updateAutoClaimStateInWebView() {
        val script = "window.autoClaimEnabled = $autoClaimEnabled;"
        visibleWebViewState.value?.evaluateJavascript(script, null)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent?.let {
            val asin = it.getStringExtra("EXTRA_ASIN")
            val queue = it.getStringExtra("EXTRA_QUEUE")
            val recId = it.getStringExtra("EXTRA_REC_ID") ?: ""
            android.util.Log.d("VineHelperDebug", "handleIntent: ASIN = $asin, Queue = $queue, recId = $recId")
            if (!asin.isNullOrEmpty() && !queue.isNullOrEmpty()) {
                it.removeExtra("EXTRA_ASIN")
                it.removeExtra("EXTRA_QUEUE")
                it.removeExtra("EXTRA_REC_ID")
                if (recId.isNotEmpty()) {
                    triggerSeeDetails(queue, asin, recId)
                } else {
                    triggerSeeDetails(queue, asin)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        clearItemNotifications()
    }

    private fun clearItemNotifications() {
        try {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val activeNotifs = notificationManager.activeNotifications
            activeNotifs.forEach { notif ->
                if (notif.id != 9999) {
                    notificationManager.cancel(notif.tag, notif.id)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun injectCustomMobileStyles(webView: WebView) {
        val css = """
            #a-page, .a-container {
                width: 100% !important;
                max-width: 100% !important;
                padding: 8px !important;
                margin: 0 !important;
            }
            #nav-footer, #vvp-logo, #vvp-header-links, #vvp-learning-resources-container, .nav-sprite-v1 {
                display: none !important;
            }
            #nav-logobar {
                display: none !important;
            }
            #vvp-items-button-container {
                padding: 8px 4px !important;
                margin: 0 0 12px 0 !important;
                display: flex !important;
                gap: 8px !important;
                justify-content: space-around !important;
                float: none !important;
            }
            #vvp-items-button-container .a-button {
                margin: 0 !important;
                flex: 1 !important;
                text-align: center !important;
            }
            #vvp-items-button-container .a-button-text {
                padding: 8px 12px !important;
                font-size: 13px !important;
            }
            .vvp-container-search {
                display: flex !important;
                width: 100% !important;
                box-sizing: border-box !important;
                padding: 8px !important;
                margin: 8px 0 !important;
                float: none !important;
                justify-content: space-between !important;
                align-items: center !important;
            }
            .vvp-container-search .a-search {
                flex: 1 !important;
                margin: 0 12px 0 0 !important;
            }
            .vvp-container-search .a-search input {
                width: 100% !important;
                height: 38px !important;
                box-sizing: border-box !important;
                border-radius: 4px !important;
                font-size: 13px !important;
            }
            #vvp-search-button {
                height: 38px !important;
                display: inline-flex !important;
                align-items: center !important;
                margin: 0 !important;
            }
            #vvp-search-button .a-button-text {
                padding: 0 16px !important;
                line-height: 38px !important;
                font-size: 13px !important;
            }
            #vvp-items-grid {
                display: flex !important;
                flex-wrap: wrap !important;
                justify-content: center !important;
                gap: 12px !important;
                width: 100% !important;
                max-width: 100% !important;
                padding: 0 !important;
                margin: 0 !important;
            }
            #vvp-items-grid::after, #vvp-items-grid::before {
                display: none !important;
            }
            .vvp-item-tile {
                float: none !important;
                width: 23% !important;
                min-width: 220px !important;
                margin: 0 !important;
                padding: 12px !important;
                box-sizing: border-box !important;
                border-radius: 8px !important;
                display: inline-flex !important;
                flex-direction: column !important;
                justify-content: space-between !important;
                align-items: center !important;
            }
            .vvp-item-tile-content {
                display: flex !important;
                flex-direction: column !important;
                align-items: center !important;
                width: 100% !important;
                height: 100% !important;
            }
            .vvp-item-tile-content img {
                width: auto !important;
                max-width: 160px !important;
                height: 160px !important;
                object-fit: contain !important;
                margin: 4px 0 !important;
            }
            .vvp-item-product-title-container {
                width: 100% !important;
                margin: 6px 0 !important;
                text-align: left !important;
            }
            .vvp-item-product-title-container a {
                font-size: 13px !important;
                line-height: 1.3em !important;
                height: 2.6em !important;
                overflow: hidden !important;
                display: -webkit-box !important;
                -webkit-line-clamp: 2 !important;
                -webkit-box-orient: vertical !important;
                text-overflow: ellipsis !important;
                word-break: break-word !important;
            }
            .a-truncate-full {
                display: inline !important;
            }
            .a-truncate-cut {
                display: none !important;
            }
            .vvp-details-btn {
                width: 100% !important;
                margin-top: auto !important;
                box-shadow: none !important;
                border-radius: 4px !important;
            }
            .vvp-details-btn .a-button-inner {
                border-radius: 4px !important;
                box-shadow: none !important;
            }
            .vvp-details-btn input {
                height: 36px !important;
                line-height: 36px !important;
                font-size: 13px !important;
                padding: 0 !important;
            }
            .a-pagination {
                display: flex !important;
                justify-content: center !important;
                flex-wrap: wrap !important;
                gap: 6px !important;
                padding: 0 !important;
                margin: 24px 0 !important;
            }
            .a-pagination li {
                margin: 0 !important;
            }
            .a-pagination li a, .a-pagination li.a-disabled, .a-pagination li.a-selected {
                padding: 10px 16px !important;
                font-size: 13px !important;
                border-radius: 4px !important;
            }
            .a-popover-modal, .a-popover[role="dialog"] {
                position: fixed !important;
                top: 50% !important;
                left: 50% !important;
                transform: translate(-50%, -50%) !important;
                width: 95% !important;
                max-width: 550px !important;
                height: 55% !important;
                max-height: 55% !important;
                z-index: 100000000 !important;
                background-color: #1f2635 !important;
                color: #ffffff !important;
                border: 1px solid #FF9900 !important;
                border-radius: 12px !important;
                box-shadow: 0 10px 30px rgba(0,0,0,0.8) !important;
                flex-direction: column !important;
            }
            .a-popover-scrim {
                position: fixed !important;
                top: 0 !important;
                left: 0 !important;
                width: 100% !important;
                height: 100% !important;
                background-color: rgba(0,0,0,0.45) !important;
                z-index: 99999999 !important;
            }
            .a-popover-wrapper {
                background-color: #1f2635 !important;
                border-radius: 12px !important;
                height: 100% !important;
                display: flex !important;
                flex-direction: column !important;
            }
            .a-popover-inner {
                background-color: #1f2635 !important;
                color: #ffffff !important;
                flex: 1 !important;
                overflow-y: auto !important;
                padding: 12px !important;
            }
            .a-popover-header {
                background-color: #2a3245 !important;
                border-bottom: 1px solid #363f54 !important;
                color: #ffffff !important;
                padding: 8px 12px !important;
            }
            .a-popover-header-content {
                color: #ffffff !important;
                font-size: 15px !important;
            }
            .a-button-close {
                filter: invert(1) !important;
                opacity: 0.8 !important;
            }
            .a-button-close:hover {
                opacity: 1 !important;
            }
            /* Styling details content to be compact so ETV is always visible */
            #vvp-product-details-modal, .vvp-details-modal {
                display: flex !important;
                flex-direction: column !important;
                gap: 4px !important;
            }
            #vvp-product-details-modal--main {
                display: flex !important;
                flex-direction: row !important;
                gap: 8px !important;
                align-items: center !important;
            }
            #vvp-product-details-img-container {
                width: 90px !important;
                min-width: 90px !important;
                margin: 0 !important;
            }
            #vvp-product-details-img-container img, #vvp-product-details-modal--hero-image {
                max-height: 80px !important;
                max-width: 80px !important;
                object-fit: contain !important;
            }
            #vvp-product-details-info-container {
                flex: 1 !important;
                margin: 0 !important;
                padding: 0 !important;
            }
            #vvp-product-description-expander {
                display: none !important; /* Hide long list details to fit ETV */
            }
            #vvp-product-details-modal--product-title, .vvp-details-title {
                font-size: 12px !important;
                line-height: 1.2em !important;
                max-height: 2.4em !important;
                overflow: hidden !important;
                display: -webkit-box !important;
                -webkit-line-clamp: 2 !important;
                -webkit-box-orient: vertical !important;
                font-weight: bold !important;
                margin: 0 0 4px 0 !important;
            }
            #vvp-product-details-modal--by-line {
                display: none !important; /* Hide manufacturer line */
            }
            /* Variation dropdowns and size items */
            #vvp-product-details-modal--variations-container, .vvp-details-variations {
                margin: 4px 0 !important;
            }
            #vvp-product-details-modal--variations-container select {
                padding: 4px !important;
                font-size: 11px !important;
                height: 28px !important;
            }
            /* Estimated Tax Value text styling */
            #vvp-product-details-modal--tax-value, .vvp-details-tax-value {
                font-size: 12px !important;
                margin: 6px 0 !important;
                padding: 4px 8px !important;
                background-color: #2a3245 !important;
                border-radius: 4px !important;
                border-left: 3px solid #FF9900 !important;
                font-weight: bold !important;
            }
            .vvp-estimated-tax-label {
                font-size: 12px !important;
                color: #ffffff !important;
            }
            #vvp-product-details-modal--tax-value-string {
                color: #FF9900 !important;
                font-size: 12px !important;
                margin-left: 4px !important;
            }
            /* Modal actions and footer */
            .vvp-modal-footer, .modal-buttons {
                display: flex !important;
                justify-content: flex-end !important;
                gap: 8px !important;
                margin-top: 10px !important;
                border-top: 1px solid #363f54 !important;
                padding-top: 8px !important;
            }
            .vvp-modal-footer .a-button, .modal-buttons .a-button {
                height: 32px !important;
                line-height: 32px !important;
                font-size: 12px !important;
            }
            .vvp-modal-footer .a-button-input, .modal-buttons input {
                height: 32px !important;
                line-height: 32px !important;
                font-size: 12px !important;
                padding: 0 12px !important;
            }
        """.trimIndent().replace("\n", " ")

        val js = """
            (function() {
                var style = document.getElementById('vine-helper-custom-css');
                if (!style) {
                    style = document.createElement('style');
                    style.id = 'vine-helper-custom-css';
                    var head = document.getElementsByTagName('head')[0];
                    if (head) head.appendChild(style);
                }
                style.textContent = '$css';

                var existing = document.querySelectorAll('.a-popover, .a-popover-scrim');
                existing.forEach(function(node) {
                    if (node.parentNode !== document.body) {
                        document.body.appendChild(node);
                    }
                });

                if (window.vineHelperObserver) {
                    window.vineHelperObserver.disconnect();
                }
                window.vineHelperObserver = new MutationObserver(function(mutations) {
                    mutations.forEach(function(mutation) {
                        mutation.addedNodes.forEach(function(node) {
                            if (node.nodeType === 1) {
                                if (node.classList && (node.classList.contains('a-popover') || node.classList.contains('a-popover-scrim'))) {
                                    if (node.parentNode !== document.body) {
                                        document.body.appendChild(node);
                                    }
                                }
                                var popovers = node.querySelectorAll('.a-popover, .a-popover-scrim');
                                popovers.forEach(function(popover) {
                                    if (popover.parentNode !== document.body) {
                                        document.body.appendChild(popover);
                                    }
                                });
                            }
                        });
                    });
                });
                window.vineHelperObserver.observe(document.documentElement, { childList: true, subtree: true });
            })();
        """.trimIndent()

        webView.evaluateJavascript(js, null)
    }

    override fun onDestroy() {
        super.onDestroy()
        abandonPendingDetailsRequest()
        window.decorView.removeCallbacks(requestNotificationPermissionRunnable)
        window.decorView.removeCallbacks(startWebViewInitializationRunnable)
        try {
            unregisterReceiver(powerConnectionReceiver)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        if (::vineScraper.isInitialized) {
            vineScraper.stop()
        }
        visibleWebViewState.value?.destroy()
        if (isFinishing && monitoringServiceStarted) {
            stopService(Intent(this, VineScraperService::class.java))
            monitoringServiceStarted = false
        }
    }
}
