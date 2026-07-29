package com.example.vinehelper.ui.main

import android.content.Context
import android.webkit.WebView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.vinehelper.MainViewModel
import com.example.vinehelper.VineItem
import com.example.vinehelper.getQueueDisplayName
import com.example.vinehelper.minimumRefreshSeconds
import com.example.vinehelper.ScraperSettings
import com.example.vinehelper.theme.*
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    visibleWebView: WebView?,
    onSeeDetailsClick: (String, String, String) -> Unit,
    onPickCustomSound: (String) -> Unit,
    onLoginClick: () -> Unit,
    onCloseApp: () -> Unit,
    onTestNotification: () -> Unit,
    onManualRefresh: () -> Unit,
    onCategoryClick: (String) -> Unit,
    activeTabState: MutableState<Int>,
    webViewsInitializedFlow: kotlinx.coroutines.flow.StateFlow<Boolean>,
    initializationProgressFlow: kotlinx.coroutines.flow.StateFlow<Float>,
    modifier: Modifier = Modifier
) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val statuses by viewModel.scraperStatuses.collectAsStateWithLifecycle()
    val isLoggedIn by viewModel.isLoggedIn.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()

    var activeTab by activeTabState
    val context = LocalContext.current


    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = VineSurface,
                tonalElevation = 8.dp
            ) {
                NavigationBarItem(
                    selected = activeTab == 0,
                    onClick = { activeTab = 0 },
                    icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Feed") },
                    label = { Text("Feed") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = VineOrange,
                        selectedTextColor = VineOrange,
                        unselectedIconColor = VineTextSecondary,
                        unselectedTextColor = VineTextSecondary,
                        indicatorColor = VineCard
                    )
                )
                NavigationBarItem(
                    selected = activeTab == 1,
                    onClick = { activeTab = 1 },
                    icon = { Icon(Icons.Default.Search, contentDescription = "Browser") },
                    label = { Text("Browser") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = VineOrange,
                        selectedTextColor = VineOrange,
                        unselectedIconColor = VineTextSecondary,
                        unselectedTextColor = VineTextSecondary,
                        indicatorColor = VineCard
                    )
                )
                NavigationBarItem(
                    selected = activeTab == 2,
                    onClick = { activeTab = 2 },
                    icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                    label = { Text("Settings") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = VineOrange,
                        selectedTextColor = VineOrange,
                        unselectedIconColor = VineTextSecondary,
                        unselectedTextColor = VineTextSecondary,
                        indicatorColor = VineCard
                    )
                )
            }
        },
        containerColor = VineDarkBg
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (activeTab == 0) {
                FeedScreen(
                    items = items,
                    statuses = statuses,
                    settings = settings,
                    isLoggedIn = isLoggedIn,
                    isRefreshing = isRefreshing,
                    onRefresh = onManualRefresh,
                    notificationEvents = viewModel.notificationEvents,
                    onAmazonClick = { item ->
                        val intent = android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://www.amazon.com/dp/${item.asin}")
                        )
                        context.startActivity(intent)
                    },
                    onSeeDetailsClick = onSeeDetailsClick,
                    onDismissItem = { viewModel.dismissItem(it) },
                    onClearAll = { viewModel.clearAllItems() },
                    onLoginClick = onLoginClick,
                    onCategoryClick = onCategoryClick,
                    onKeepAwakeToggled = { viewModel.updateKeepAwakeWhileCharging(it) }
                )
            }

            // Keep the WebView object alive, but attach its native view only on the Browser tab.
            // Hidden AndroidViews can otherwise cover Compose with a black surface and steal taps.
            if (activeTab == 1) {
                BrowserScreen(visibleWebView, viewModel)
            }

            // Settings are built only when opened to keep startup light.
            if (activeTab == 2) {
                SettingsScreen(
                    settings = settings,
                    statuses = statuses,
                    onRefreshRateChange = { queue, seconds -> viewModel.updateRefreshRate(queue, seconds) },
                    onTimeoutChange = { viewModel.updateTimeoutMinutes(it) },
                    onSoundToggled = { viewModel.updateSoundEnabled(it) },
                    onSoundSelected = { queue, sound -> viewModel.updateQueueSound(queue, sound) },
                    onQueueSoundToggled = { queue, enabled -> viewModel.updateQueueSoundEnabled(queue, enabled) },
                    onPickCustomSound = onPickCustomSound,
                    onLoginClick = onLoginClick,
                    onHighlightKeywordsChange = { viewModel.updateHighlightKeywords(it) },
                    onBlocklistKeywordsChange = { viewModel.updateBlocklistKeywords(it) },
                    onAutoClaimToggled = { viewModel.updateAutoClaimEnabled(it) },
                    onPlaySound = { viewModel.testPlaySound(it) },
                    onCloseApp = onCloseApp,
                    onTestNotification = onTestNotification,
                    onMockModeToggled = { viewModel.updateMockModeEnabled(it) },
                    mockModeAvailable = viewModel.isMockModeAvailable
                )
            }
            if (activeTab != 2) {
                IconButton(
                    onClick = onCloseApp,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .size(44.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(VineCard)
                        .border(1.dp, VineOrange, RoundedCornerShape(8.dp))
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close App",
                        tint = VineTextPrimary,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }
            // Splash screen overlay removed for instant startup
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(
    items: List<VineItem>,
    statuses: Map<String, String>,
    settings: ScraperSettings,
    isLoggedIn: Boolean,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    notificationEvents: kotlinx.coroutines.flow.SharedFlow<VineItem>,
    onAmazonClick: (VineItem) -> Unit,
    onSeeDetailsClick: (String, String, String) -> Unit,
    onDismissItem: (VineItem) -> Unit,
    onClearAll: () -> Unit,
    onLoginClick: () -> Unit,
    onCategoryClick: (String) -> Unit,
    onKeepAwakeToggled: (Boolean) -> Unit
) {
    var tick by remember { mutableStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        while (true) {
            val now = System.currentTimeMillis()
            kotlinx.coroutines.delay(60_000L - (now % 60_000L))
            tick = System.currentTimeMillis()
        }
    }

    val filteredItems = items

    val listState = rememberLazyListState()
    var previousTopItemByAsin by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(items) {
        val currentTopAsin = items.firstOrNull()?.asin
        if (currentTopAsin != null && previousTopItemByAsin != null && currentTopAsin != previousTopItemByAsin) {
            listState.animateScrollToItem(0)
        }
        previousTopItemByAsin = currentTopAsin
    }

    LaunchedEffect(notificationEvents) {
        notificationEvents.collect { item ->
            tick = System.currentTimeMillis()
            if (items.isNotEmpty()) {
                listState.animateScrollToItem(0)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Feed Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = 56.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Vine Helper Feed",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = VineTextPrimary
                )
                Text(
                    text = "${items.size} active items | Expiry: ${settings.timeoutMinutes}m",
                    fontSize = 13.sp,
                    color = VineTextSecondary
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Keep Awake Toggle
                val keepAwake = settings.keepAwakeWhileCharging
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (keepAwake) VineOrange.copy(alpha = 0.15f) else Color.Transparent)
                        .border(BorderStroke(1.dp, if (keepAwake) VineOrange else Color.Transparent), RoundedCornerShape(8.dp))
                        .clickable { onKeepAwakeToggled(!keepAwake) }
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Power,
                        contentDescription = "Keep Awake",
                        tint = if (keepAwake) VineOrange else VineTextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Keep Awake",
                        color = if (keepAwake) VineOrange else VineTextSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                if (items.isNotEmpty()) {
                    Button(
                        onClick = onClearAll,
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.2f)),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Clear All",
                            tint = Color.Red,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Clear All", color = Color.Red, fontSize = 12.sp)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Scraping Status Indicators / Category Filters
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(VineSurface, RoundedCornerShape(12.dp))
                .border(BorderStroke(1.dp, BorderColor), RoundedCornerShape(12.dp))
                .padding(8.dp),
            horizontalArrangement = Arrangement.SpaceAround
        ) {
            val queues = listOf("potluck", "last_chance", "encore")
            queues.forEach { queue ->
                key(queue) {
                    val label = getQueueDisplayName(queue).uppercase()
                    val status = statuses[queue] ?: "Stopped"
                    val isRefreshing = status.contains("Refreshing") || status.contains("Scraping")

                    var visualStatus by remember { mutableStateOf(status) }
                    var visualRefreshing by remember { mutableStateOf(false) }

                    LaunchedEffect(status) {
                        if (isRefreshing) {
                            visualStatus = status
                            visualRefreshing = true
                        } else {
                            // Hold the refreshing status visually for 1.5 seconds
                            kotlinx.coroutines.delay(1500)
                            visualStatus = status
                            visualRefreshing = false
                        }
                    }

                    val statusColor by animateColorAsState(
                        targetValue = if (visualRefreshing) Color.Red else VineAccent,
                        animationSpec = tween(
                            durationMillis = if (visualRefreshing) 150 else 500,
                            easing = LinearOutSlowInEasing
                        ),
                        label = "statusColor_$queue"
                    )

                    val isActive = visualStatus.contains("Idle") || visualStatus.contains("Scraping") || visualStatus.contains("First") || visualStatus.contains("Ready") || visualStatus.contains("Refreshing")

                    val infiniteTransition = rememberInfiniteTransition(label = "pulse_$queue")
                    val scale by if (isActive) {
                        infiniteTransition.animateFloat(
                            initialValue = 1f,
                            targetValue = 2f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(1200, easing = LinearEasing),
                                repeatMode = RepeatMode.Restart
                            ),
                            label = "scale_$queue"
                        )
                    } else {
                        remember { mutableStateOf(1f) }
                    }
                    val alpha by if (isActive) {
                        infiniteTransition.animateFloat(
                            initialValue = 0.8f,
                            targetValue = 0f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(1200, easing = LinearEasing),
                                repeatMode = RepeatMode.Restart
                            ),
                            label = "alpha_$queue"
                        )
                    } else {
                        remember { mutableStateOf(0f) }
                    }

                    val isSelected = false
                    val bgColor = Color.Transparent

                    Column(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(bgColor)
                            .clickable {
                                onCategoryClick(queue)
                            }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.size(16.dp)
                            ) {
                                if (isActive) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .scale(scale)
                                            .background(statusColor.copy(alpha = alpha), CircleShape)
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .background(statusColor, CircleShape)
                                )
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = label,
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                                color = statusColor,
                                maxLines = 1
                            )
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        val cleanStatus = when {
                            visualStatus.startsWith("First scrape completed") -> "Ready"
                            visualStatus.contains("Waiting next refresh") -> "Idle"
                            else -> visualStatus
                        }
                        Text(
                            text = cleanStatus,
                            fontSize = 8.sp,
                            color = VineTextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        AnimatedVisibility(visible = !isLoggedIn) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(containerColor = Color.Red.copy(alpha = 0.2f)),
                border = BorderStroke(1.dp, Color.Red.copy(alpha = 0.5f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Warning",
                            tint = Color.Red,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Session Expired",
                                color = VineTextPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Scraper paused. Log in to resume.",
                                color = VineTextSecondary,
                                fontSize = 12.sp
                            )
                        }
                    }
                    Button(
                        onClick = onLoginClick,
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Text("Log In", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Feed Items List
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier.weight(1f)
        ) {
            if (filteredItems.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (!isLoggedIn) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = null,
                                tint = VineTextSecondary.copy(alpha = 0.3f),
                                modifier = Modifier.size(64.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Log In Required",
                                color = VineTextPrimary,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Log in on the Browser tab to begin monitoring.",
                                color = VineTextSecondary.copy(alpha = 0.7f),
                                fontSize = 13.sp
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(
                                onClick = onLoginClick,
                                colors = ButtonDefaults.buttonColors(containerColor = VineOrange),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(Icons.Default.Lock, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Log In to Amazon", color = Color.Black, fontWeight = FontWeight.Bold)
                            }
                        } else {
                            CircularProgressIndicator(
                                color = VineOrange,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "Active & Monitoring",
                                color = VineTextPrimary,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Waiting for new items to appear on Amazon Vine...",
                                color = VineTextSecondary.copy(alpha = 0.7f),
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(filteredItems, key = { it.id }) { item ->
                        val dismissState = rememberSwipeToDismissBoxState()
                        LaunchedEffect(dismissState.currentValue) {
                            if (dismissState.currentValue == SwipeToDismissBoxValue.StartToEnd ||
                                dismissState.currentValue == SwipeToDismissBoxValue.EndToStart
                            ) {
                                onDismissItem(item)
                            }
                        }
                        SwipeToDismissBox(
                            state = dismissState,
                            backgroundContent = {
                                val color = when (dismissState.dismissDirection) {
                                    SwipeToDismissBoxValue.StartToEnd -> Color.Red.copy(alpha = 0.8f)
                                    SwipeToDismissBoxValue.EndToStart -> Color.Red.copy(alpha = 0.8f)
                                    else -> Color.Transparent
                                }
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(color)
                                        .padding(horizontal = 20.dp),
                                    contentAlignment = if (dismissState.dismissDirection == SwipeToDismissBoxValue.StartToEnd) Alignment.CenterStart else Alignment.CenterEnd
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Dismiss",
                                        tint = Color.White
                                    )
                                }
                            },
                            content = {
                                FeedItemCard(
                                    item = item,
                                    tick = tick,
                                    onAmazonClick = { onAmazonClick(item) },
                                    onSeeDetailsClick = { onSeeDetailsClick(item.queue, item.asin, item.recId) },
                                    onDismiss = { onDismissItem(item) }
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedItemCard(
    item: VineItem,
    tick: Long,
    onAmazonClick: () -> Unit,
    onSeeDetailsClick: () -> Unit,
    onDismiss: () -> Unit
) {
    var isPressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (isPressed) 0.98f else 1f)

    val borderStroke = if (item.isHighlighted) {
        BorderStroke(2.dp, Color(0xFFD4AF37)) // Gold glowing border for highlights
    } else {
        BorderStroke(1.dp, BorderColor)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            .clickable {
                android.util.Log.d("VineHelperDebug", "FeedItemCard Card Clicked: title = ${item.title}, asin = ${item.asin}")
                onSeeDetailsClick()
            },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = VineCard),
        border = borderStroke
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            // Dismiss Button (Upper Right)
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(24.dp)
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Dismiss",
                    tint = VineTextSecondary,
                    modifier = Modifier.size(16.dp)
                )
            }

            // Card Content
            Row(
                modifier = Modifier.padding(16.dp)
            ) {
                // Left Column (Image + ETV underneath)
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.width(80.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White.copy(alpha = 0.05f)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (item.imgUrl.isBlank()) {
                            Icon(
                                imageVector = Icons.Default.ImageNotSupported,
                                contentDescription = "Product image unavailable",
                                tint = VineTextSecondary,
                                modifier = Modifier.size(32.dp)
                            )
                        } else {
                            val unavailablePainter = rememberVectorPainter(Icons.Default.ImageNotSupported)
                            AsyncImage(
                                model = ImageRequest.Builder(LocalContext.current)
                                    .data(item.imgUrl)
                                    .crossfade(true)
                                    .build(),
                                contentDescription = "Product image",
                                contentScale = ContentScale.Crop,
                                placeholder = unavailablePainter,
                                error = unavailablePainter,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(16.dp))

                // Text Details
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    // Queue Badge & Time
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val badgeGradient = when (item.queue) {
                            "potluck" -> Brush.horizontalGradient(listOf(Color(0xFF00C6FF), Color(0xFF0072FF)))
                            "last_chance" -> Brush.horizontalGradient(listOf(Color(0xFFFF416C), Color(0xFFFF4B2B)))
                            else -> Brush.horizontalGradient(listOf(Color(0xFF9C27B0), Color(0xFFE91E63)))
                        }

                        Box(
                            modifier = Modifier
                                .background(badgeGradient, RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = getQueueDisplayName(item.queue).uppercase(),
                                color = Color.White,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        val timeText = remember(item.timestampAdded, tick) {
                            val diffSec = (tick - item.timestampAdded) / 1000L
                            when {
                                diffSec < 60 -> "Just now"
                                diffSec < 3600 -> "${diffSec / 60}m ago"
                                else -> "${diffSec / 3600}h ago"
                            }
                        }

                        Text(
                            text = timeText,
                            fontSize = 11.sp,
                            color = VineTextSecondary
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // Title
                    Text(
                        text = item.title,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = VineTextPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Links / Buttons
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        // View on Amazon
                        OutlinedButton(
                            onClick = onAmazonClick,
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, BorderColor),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = VineAccentBlue),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(12.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Amazon Link", fontSize = 11.sp)
                        }

                        // See Details
                        Button(
                            onClick = {
                                android.util.Log.d("VineHelperDebug", "FeedItemCard Button Clicked: title = ${item.title}, asin = ${item.asin}")
                                onSeeDetailsClick()
                            },
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = VineOrange),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            modifier = Modifier
                                .height(32.dp)
                                .weight(1f)
                        ) {
                            Text("See Details", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun BrowserScreen(webView: WebView?, viewModel: MainViewModel) {
    val isBrowserLoading by viewModel.isBrowserLoading.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize()) {
        // Web Page Title Bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(VineSurface)
                .padding(vertical = 4.dp, horizontal = 16.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.align(Alignment.CenterStart)
            ) {
                Icon(Icons.Default.Search, contentDescription = null, tint = VineOrange, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Amazon Vine Portal",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = VineTextPrimary
                )
            }
            IconButton(
                onClick = { webView?.reload() },
                enabled = webView != null,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 56.dp)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = if (webView != null) VineOrange else VineTextSecondary)
            }
        }

        if (webView == null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = VineOrange)
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(VineSurface)
            ) {
                AndroidView(
                    factory = { context ->
                        androidx.swiperefreshlayout.widget.SwipeRefreshLayout(context).apply {
                            layoutParams = android.view.ViewGroup.LayoutParams(
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            setBackgroundColor(android.graphics.Color.parseColor("#141923"))
                            (webView.parent as? android.view.ViewGroup)?.removeView(webView)
                            webView.layoutParams = android.view.ViewGroup.LayoutParams(
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            addView(webView)

                            setOnRefreshListener {
                                webView.reload()
                            }

                            setColorSchemeColors(android.graphics.Color.parseColor("#FF9900"))
                            setProgressBackgroundColorSchemeColor(android.graphics.Color.parseColor("#1F2635"))
                        }
                    },
                    update = { swipeRefresh ->
                        swipeRefresh.isRefreshing = false
                        webView.visibility = android.view.View.VISIBLE
                        webView.onResume()
                        webView.resumeTimers()
                        webView.requestLayout()
                        webView.postInvalidateOnAnimation()
                    },
                    modifier = Modifier.fillMaxSize()
                )

                if (isBrowserLoading) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.TopCenter),
                        color = VineOrange
                    )
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(
    settings: ScraperSettings,
    statuses: Map<String, String>,
    onRefreshRateChange: (String, Int) -> Unit,
    onTimeoutChange: (Int) -> Unit,
    onSoundToggled: (Boolean) -> Unit,
    onSoundSelected: (String, String) -> Unit,
    onQueueSoundToggled: (String, Boolean) -> Unit,
    onPickCustomSound: (String) -> Unit,
    onLoginClick: () -> Unit,
    onHighlightKeywordsChange: (String) -> Unit,
    onBlocklistKeywordsChange: (String) -> Unit,
    onAutoClaimToggled: (Boolean) -> Unit,
    onPlaySound: (String) -> Unit,
    onCloseApp: () -> Unit,
    onTestNotification: () -> Unit,
    onMockModeToggled: (Boolean) -> Unit,
    mockModeAvailable: Boolean
) {
    var showKeywordExplorer by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "App Settings",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = VineTextPrimary
                )
                Text(
                    text = "Configure monitor intervals, feed retention, and alerts",
                    fontSize = 13.sp,
                    color = VineTextSecondary
                )
            }
            IconButton(
                onClick = onCloseApp,
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(VineCard)
                    .border(1.dp, VineOrange, RoundedCornerShape(8.dp))
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close App",
                    tint = VineTextPrimary,
                    modifier = Modifier.size(26.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = onLoginClick,
            colors = ButtonDefaults.buttonColors(containerColor = VineOrange),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Lock, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Log In to Amazon", color = Color.Black, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = onCloseApp,
            colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.2f)),
            border = BorderStroke(1.dp, Color.Red),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = null, tint = Color.Red, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Close App", color = Color.Red, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Developer Mock Mode Setting
        Text(
            text = "Developer Options",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = VineOrange
        )
        Spacer(modifier = Modifier.height(8.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = VineCard),
            border = BorderStroke(1.dp, BorderColor)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Mock Mode (Offline Test)", color = VineTextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        Text(
                            if (mockModeAvailable) {
                                "Enable local mock Amazon store to test all scraping, alerts, and navigation features offline."
                            } else {
                                "Disabled in release builds so live Amazon data is always used."
                            },
                            color = VineTextSecondary,
                            fontSize = 12.sp
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Switch(
                        checked = settings.mockModeEnabled,
                        onCheckedChange = onMockModeToggled,
                        enabled = mockModeAvailable,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = VineOrange,
                            checkedTrackColor = VineOrange.copy(alpha = 0.4f),
                            uncheckedThumbColor = VineTextSecondary,
                            uncheckedTrackColor = BorderColor
                        )
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Monitor Status card
        Text(
            text = "Monitor Status",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = VineOrange
        )
        Spacer(modifier = Modifier.height(8.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = VineCard),
            border = BorderStroke(1.dp, BorderColor)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val queues = listOf("potluck", "last_chance", "encore")
                queues.forEach { queue ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = getQueueDisplayName(queue).uppercase(),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = VineTextPrimary
                        )
                        Text(
                            text = statuses[queue] ?: "Stopped",
                            fontSize = 12.sp,
                            color = VineTextSecondary
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Per-queue refresh rate settings with non-bypassable account-safety floors.
        Text(
            text = "Amazon Refresh Rates",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = VineOrange
        )
        Spacer(modifier = Modifier.height(8.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF3A2B12)),
            border = BorderStroke(1.dp, VineOrange)
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = VineOrange,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = "Amazon may throttle or restrict your account when requests are sent too frequently. RFY cannot be set below 5 seconds. AFA and AI cannot be set below 3 seconds. Vine Helper also keeps at least 3 seconds between all Amazon requests. These safety limits cannot be disabled.",
                    color = VineTextPrimary,
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
            }
        }
        Spacer(modifier = Modifier.height(12.dp))

        val refreshControls = listOf(
            Triple("potluck", "Recommended for You (RFY)", settings.refreshRateRfySeconds),
            Triple("last_chance", "Available for All (AFA)", settings.refreshRateAfaSeconds),
            Triple("encore", "Additional Items (AI)", settings.refreshRateAiSeconds)
        )
        refreshControls.forEach { (queue, label, seconds) ->
            val minimumSeconds = minimumRefreshSeconds(queue)
            Text(
                text = "$label: $seconds seconds",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = VineTextPrimary
            )
            Slider(
                value = seconds.toFloat(),
                onValueChange = { onRefreshRateChange(queue, it.toInt()) },
                valueRange = minimumSeconds.toFloat()..60f,
                steps = 60 - minimumSeconds - 1,
                colors = SliderDefaults.colors(
                    thumbColor = VineOrange,
                    activeTrackColor = VineOrange,
                    inactiveTrackColor = BorderColor
                )
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Expiry Timeout Setting
        Text(
            text = "Item Retention: ${settings.timeoutMinutes} minutes",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = VineOrange
        )
        Spacer(modifier = Modifier.height(4.dp))
        Slider(
            value = settings.timeoutMinutes.toFloat(),
            onValueChange = { onTimeoutChange(it.toInt()) },
            valueRange = 1f..60f,
            steps = 58,
            colors = SliderDefaults.colors(
                thumbColor = VineOrange,
                activeTrackColor = VineOrange,
                inactiveTrackColor = BorderColor
            )
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Keywords Filter Settings
        Text(
            text = "Keyword Filters",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = VineOrange
        )
        Spacer(modifier = Modifier.height(8.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = VineCard),
            border = BorderStroke(1.dp, BorderColor)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = settings.highlightKeywords,
                    onValueChange = onHighlightKeywordsChange,
                    label = { Text("Highlight Keywords (comma-separated)") },
                    placeholder = { Text("e.g. SSD, Charger, Mouse") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = VineTextPrimary,
                        unfocusedTextColor = VineTextPrimary,
                        focusedBorderColor = VineOrange,
                        unfocusedBorderColor = BorderColor,
                        focusedLabelColor = VineOrange,
                        unfocusedLabelColor = VineTextSecondary
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Button(
                    onClick = { showKeywordExplorer = true },
                    colors = ButtonDefaults.buttonColors(containerColor = VineOrange),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Search, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Manage Research Keywords", color = Color.Black, fontWeight = FontWeight.Bold)
                }

                OutlinedTextField(
                    value = settings.blocklistKeywords,
                    onValueChange = onBlocklistKeywordsChange,
                    label = { Text("Blocklist Keywords (comma-separated)") },
                    placeholder = { Text("e.g. iPhone Case, Screen Protector") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = VineTextPrimary,
                        unfocusedTextColor = VineTextPrimary,
                        focusedBorderColor = VineOrange,
                        unfocusedBorderColor = BorderColor,
                        focusedLabelColor = VineOrange,
                        unfocusedLabelColor = VineTextSecondary
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Auto-Claim Settings
        Text(
            text = "Auto-Claim Assistant",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = VineOrange
        )
        Spacer(modifier = Modifier.height(8.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = VineCard),
            border = BorderStroke(1.dp, BorderColor)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Auto-Click Request", color = VineTextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        Text("Automatically request and confirm item inside the Amazon Vine details modal overlay.", color = VineTextSecondary, fontSize = 12.sp)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Switch(
                        checked = settings.autoClaimEnabled,
                        onCheckedChange = onAutoClaimToggled,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = VineOrange,
                            checkedTrackColor = VineOrange.copy(alpha = 0.4f),
                            uncheckedThumbColor = VineTextSecondary,
                            uncheckedTrackColor = BorderColor
                        )
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Alerts Header
        Text(
            text = "Alerts",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = VineOrange
        )
        Spacer(modifier = Modifier.height(8.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = VineCard),
            border = BorderStroke(1.dp, BorderColor)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // Sound Enabled Switch
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Audio Alert", color = VineTextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        Text("Play sound on new items", color = VineTextSecondary, fontSize = 12.sp)
                    }
                    Switch(
                        checked = settings.soundEnabled,
                        onCheckedChange = onSoundToggled,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = VineOrange,
                            checkedTrackColor = VineOrange.copy(alpha = 0.4f),
                            uncheckedThumbColor = VineTextSecondary,
                            uncheckedTrackColor = BorderColor
                        )
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = onTestNotification,
                    colors = ButtonDefaults.buttonColors(containerColor = VineOrange),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.Notifications,
                        contentDescription = null,
                        tint = Color.Black,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Send Test Notification", color = Color.Black, fontWeight = FontWeight.Bold)
                }

                if (settings.soundEnabled) {
                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider(color = BorderColor)
                    Spacer(modifier = Modifier.height(8.dp))

                    val queues = listOf("potluck", "last_chance", "encore", "highlight")
                    queues.forEach { queue ->
                        var showSoundDropdown by remember { mutableStateOf(false) }

                        val isQueueEnabled = when (queue) {
                            "potluck" -> settings.soundPotluckEnabled
                            "last_chance" -> settings.soundLastChanceEnabled
                            "encore" -> settings.soundEncoreEnabled
                            "highlight" -> settings.soundHighlightEnabled
                            else -> true
                        }

                        val label = when (queue) {
                            "potluck" -> "RFY"
                            "last_chance" -> "AFA"
                            "encore" -> "AI"
                            "highlight" -> "Highlight"
                            else -> queue
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Switch(
                                    checked = isQueueEnabled,
                                    onCheckedChange = { onQueueSoundToggled(queue, it) },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = VineOrange,
                                        checkedTrackColor = VineOrange.copy(alpha = 0.4f),
                                        uncheckedThumbColor = VineTextSecondary,
                                        uncheckedTrackColor = BorderColor
                                    ),
                                    modifier = Modifier.scale(0.8f)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(label, color = VineTextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            }

                            val currentSound = when(queue) {
                                "potluck" -> settings.soundPotluck
                                "last_chance" -> settings.soundLastChance
                                "encore" -> settings.soundEncore
                                "highlight" -> settings.soundHighlight
                                else -> "chime"
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box {
                                    val soundDisplay = when {
                                        currentSound == "chime" -> "Chime"
                                        currentSound == "pop" -> "Pop"
                                        currentSound == "bell" -> "Bell"
                                        currentSound == "ding" -> "Ding"
                                        currentSound == "success" -> "Success"
                                        currentSound == "alarm" -> "Alarm"
                                        currentSound == "bubble" -> "Bubble"
                                        currentSound == "laser" -> "Laser"
                                        currentSound == "horn" -> "Horn"
                                        currentSound == "crystal" -> "Crystal"
                                        currentSound == "digital" -> "Digital"
                                        currentSound == "sweep" -> "Sweep"
                                        currentSound == "click" -> "Click"
                                        currentSound == "ping" -> "Ping"
                                        currentSound == "magic" -> "Magic"
                                        currentSound == "guitar" -> "Guitar"
                                        currentSound == "piano" -> "Piano"
                                        currentSound == "violin" -> "Violin"
                                        currentSound == "alien" -> "Alien"
                                        currentSound == "cyber" -> "Cyber"
                                        currentSound.startsWith("content://") -> "Custom Sound"
                                        else -> currentSound.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
                                    }

                                    Button(
                                        onClick = { showSoundDropdown = true },
                                        enabled = isQueueEnabled,
                                        colors = ButtonDefaults.buttonColors(containerColor = VineSurface),
                                        shape = RoundedCornerShape(8.dp),
                                        border = BorderStroke(1.dp, BorderColor),
                                        modifier = Modifier.height(36.dp),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                    ) {
                                        Text(soundDisplay, color = if (isQueueEnabled) VineTextPrimary else VineTextSecondary, fontSize = 12.sp)
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = if (isQueueEnabled) VineOrange else VineTextSecondary, modifier = Modifier.size(16.dp))
                                    }

                                    DropdownMenu(
                                        expanded = showSoundDropdown,
                                        onDismissRequest = { showSoundDropdown = false },
                                        modifier = Modifier.background(VineSurface)
                                    ) {
                                        val soundOptions = listOf(
                                            "chime" to "Chime", "pop" to "Pop", "bell" to "Bell", "ding" to "Ding",
                                            "success" to "Success", "alarm" to "Alarm", "bubble" to "Bubble", "laser" to "Laser",
                                            "horn" to "Horn", "crystal" to "Crystal", "digital" to "Digital", "sweep" to "Sweep",
                                            "click" to "Click", "ping" to "Ping", "magic" to "Magic", "guitar" to "Guitar",
                                            "piano" to "Piano", "violin" to "Violin", "alien" to "Alien", "cyber" to "Cyber"
                                        )
                                        soundOptions.forEach { (key, label) ->
                                            DropdownMenuItem(
                                                text = { Text(label, color = VineTextPrimary) },
                                                onClick = {
                                                    onSoundSelected(queue, key)
                                                    showSoundDropdown = false
                                                }
                                            )
                                        }
                                        DropdownMenuItem(
                                            text = { Text("Custom Sound...", color = VineOrange) },
                                            onClick = {
                                                onPickCustomSound(queue)
                                                showSoundDropdown = false
                                            }
                                        )
                                    }
                                }

                                IconButton(
                                    onClick = { onPlaySound(currentSound) },
                                    enabled = isQueueEnabled,
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = "Test Sound",
                                        tint = if (isQueueEnabled) VineOrange else VineTextSecondary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showKeywordExplorer) {
        KeywordExplorerDialog(
            currentKeywords = settings.highlightKeywords,
            onKeywordsChanged = onHighlightKeywordsChange,
            onDismiss = { showKeywordExplorer = false }
        )
    }
}

@Composable
fun KeywordExplorerDialog(
    currentKeywords: String,
    onKeywordsChanged: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val categoriesMap = remember { parseKeywordResearchList(context) }
    val activeSet = remember(currentKeywords) {
        currentKeywords.split(",")
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()
    }
    var searchQuery by remember { mutableStateOf("") }
    val expandedCategories = remember { mutableStateMapOf<String, Boolean>() }

    fun toggleKeyword(keyword: String) {
        val list = currentKeywords.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
        val match = list.find { it.equals(keyword, ignoreCase = true) }
        if (match != null) {
            list.remove(match)
        } else {
            list.add(keyword)
        }
        onKeywordsChanged(list.joinToString(", "))
    }

    fun toggleCategory(categoryKeywords: List<String>) {
        val list = currentKeywords.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
        val categoryKeywordsLower = categoryKeywords.map { it.lowercase() }.toSet()
        val activeCategoryKeywords = list.filter { categoryKeywordsLower.contains(it.lowercase()) }

        if (activeCategoryKeywords.size == categoryKeywords.size) {
            val updated = list.filter { !categoryKeywordsLower.contains(it.lowercase()) }
            onKeywordsChanged(updated.joinToString(", "))
        } else {
            val baseList = list.filter { !categoryKeywordsLower.contains(it.lowercase()) }.toMutableList()
            baseList.addAll(categoryKeywords)
            onKeywordsChanged(baseList.joinToString(", "))
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Keyword Research Explorer",
                color = VineTextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxHeight(0.8f).fillMaxWidth()) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text("Search default list...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = VineTextSecondary) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = VineTextPrimary,
                        unfocusedTextColor = VineTextPrimary,
                        focusedBorderColor = VineOrange,
                        unfocusedBorderColor = BorderColor,
                        focusedLabelColor = VineOrange,
                        unfocusedLabelColor = VineTextSecondary
                    )
                )
                Spacer(modifier = Modifier.height(12.dp))
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    categoriesMap.forEach { (category, keywords) ->
                        val filteredKeywords = keywords.filter {
                            it.contains(searchQuery, ignoreCase = true)
                        }
                        if (filteredKeywords.isNotEmpty()) {
                            val isExpanded = if (searchQuery.isNotEmpty()) true else (expandedCategories[category] ?: false)
                            item {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    val categoryKeywordsLower = filteredKeywords.map { it.lowercase() }.toSet()
                                    val activeInCat = activeSet.filter { categoryKeywordsLower.contains(it) }
                                    val allSelected = activeInCat.size == filteredKeywords.size && filteredKeywords.isNotEmpty()

                                    Checkbox(
                                        checked = allSelected,
                                        onCheckedChange = { toggleCategory(filteredKeywords) },
                                        colors = CheckboxDefaults.colors(
                                            checkedColor = VineOrange,
                                            uncheckedColor = BorderColor,
                                            checkmarkColor = Color.Black
                                        ),
                                        modifier = Modifier.scale(0.85f)
                                    )

                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .weight(1f)
                                            .clickable { expandedCategories[category] = !isExpanded }
                                            .padding(vertical = 4.dp)
                                    ) {
                                        Icon(
                                            imageVector = getCategoryIcon(category),
                                            contentDescription = null,
                                            tint = VineOrange,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = category,
                                            fontWeight = FontWeight.Bold,
                                            color = VineOrange,
                                            fontSize = 15.sp,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Icon(
                                            imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                            contentDescription = if (isExpanded) "Collapse" else "Expand",
                                            tint = VineTextSecondary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                                if (isExpanded) {
                                    HorizontalDivider(color = BorderColor.copy(alpha = 0.5f))
                                }
                            }
                            if (isExpanded) {
                                items(filteredKeywords) { keyword ->
                                    val isChecked = activeSet.contains(keyword.lowercase())
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { toggleKeyword(keyword) }
                                            .padding(vertical = 4.dp)
                                    ) {
                                        Checkbox(
                                            checked = isChecked,
                                            onCheckedChange = { toggleKeyword(keyword) },
                                            colors = CheckboxDefaults.colors(
                                                checkedColor = VineOrange,
                                                uncheckedColor = BorderColor,
                                                checkmarkColor = Color.Black
                                            )
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = keyword,
                                            color = VineTextPrimary,
                                            fontSize = 14.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done", color = VineOrange, fontWeight = FontWeight.Bold)
            }
        },
        containerColor = VineCard
    )
}

fun parseKeywordResearchList(context: Context): Map<String, List<String>> {
    val result = mutableMapOf<String, List<String>>()
    try {
        val inputStream = context.assets.open("amazon_review_keyword_research_list.txt")
        val reader = inputStream.bufferedReader()
        var currentCategory = ""
        val currentKeywords = mutableListOf<String>()

        reader.forEachLine { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@forEachLine
            if (trimmed.startsWith("===")) return@forEachLine
            if (trimmed.startsWith("Amazon Review") || trimmed.startsWith("Cleanly Formatted") || trimmed.startsWith("Important note:") || trimmed.startsWith("Use these keywords")) {
                return@forEachLine
            }

            // Check if category header (e.g., "1. Tech and Gaming Accessories")
            val isHeader = trimmed.firstOrNull()?.isDigit() == true && trimmed.contains(".")
            if (isHeader) {
                if (currentCategory.isNotEmpty() && currentKeywords.isNotEmpty()) {
                    result[currentCategory] = currentKeywords.toList()
                    currentKeywords.clear()
                }
                currentCategory = trimmed.substring(trimmed.indexOf(".") + 1).trim()
            } else {
                if (currentCategory.isNotEmpty()) {
                    currentKeywords.add(trimmed)
                }
            }
        }
        if (currentCategory.isNotEmpty() && currentKeywords.isNotEmpty()) {
            result[currentCategory] = currentKeywords.toList()
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return result
}

@Composable
fun SplashScreen(progress: Float) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(VineDarkBg, VineSurface)
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(32.dp)
        ) {
            // Icon / Logo
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(VineOrange.copy(alpha = 0.2f), Color.Transparent)
                        )
                    )
                    .border(
                        BorderStroke(2.dp, Brush.sweepGradient(listOf(VineOrange, VineOrangeLight, VineOrange))),
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.ShoppingCart,
                    contentDescription = "Logo",
                    tint = VineOrange,
                    modifier = Modifier.size(48.dp)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // App Name
            Text(
                text = "VINE HELPER",
                color = VineTextPrimary,
                fontSize = 28.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 4.sp
            )

            Text(
                text = "Smart Assistant for Amazon Vine",
                color = VineTextSecondary,
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 4.dp)
            )

            Spacer(modifier = Modifier.height(64.dp))

            // Progress details
            val statusText = when {
                progress < 0.35f -> "Initializing background monitors..."
                progress < 0.70f -> "Configuring scraper engines..."
                progress < 1.0f -> "Establishing secure connection..."
                else -> "Ready!"
            }

            Text(
                text = statusText,
                color = VineTextSecondary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Progress Bar
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = VineOrange,
                trackColor = BorderColor
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Progress Percent
            Text(
                text = "${(progress * 100).toInt()}%",
                color = VineOrange,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

fun getCategoryIcon(categoryName: String): androidx.compose.ui.graphics.vector.ImageVector {
    val name = categoryName.lowercase()
    return when {
        name.contains("tech") || name.contains("gaming accessories") -> Icons.Default.Computer
        name.contains("smart home") -> Icons.Default.Home
        name.contains("kitchen") -> Icons.Default.Restaurant
        name.contains("organization") -> Icons.Default.Layers
        name.contains("cleaning") || name.contains("household") -> Icons.Default.CleaningServices
        name.contains("tools") || name.contains("diy") -> Icons.Default.Build
        name.contains("car") -> Icons.Default.DirectionsCar
        name.contains("school") || name.contains("office") -> Icons.Default.Edit
        name.contains("beauty") || name.contains("personal care") -> Icons.Default.Face
        name.contains("fitness") || name.contains("outdoor") -> Icons.Default.FitnessCenter
        name.contains("pet") -> Icons.Default.Pets
        name.contains("computer parts") -> Icons.Default.DeveloperBoard
        name.contains("prebuilt pcs") || name.contains("manufacturers") -> Icons.Default.SettingsSuggest
        name.contains("computer gaming") -> Icons.Default.Gamepad
        name.contains("bike") || name.contains("scooter") || name.contains("vehicle") -> Icons.Default.ElectricBike
        name.contains("robotic") || name.contains("appliance") -> Icons.Default.SmartToy
        name.contains("tax") || name.contains("$0") -> Icons.Default.AttachMoney
        name.contains("formula") -> Icons.Default.Functions
        name.contains("best categories") -> Icons.Default.Star
        name.contains("angle") || name.contains("idea") -> Icons.Default.Lightbulb
        else -> Icons.Default.Folder
    }
}
