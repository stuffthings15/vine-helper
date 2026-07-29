package com.example.vinehelper

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ScraperSettings(
    val refreshRateRfySeconds: Int = 5,
    val refreshRateAfaSeconds: Int = 3,
    val refreshRateAiSeconds: Int = 3,
    val timeoutMinutes: Int = 10,
    val soundEnabled: Boolean = true,
    val soundPotluck: String = "chime",
    val soundLastChance: String = "pop",
    val soundEncore: String = "bell",
    val soundHighlight: String = "success",
    val soundPotluckEnabled: Boolean = true,
    val soundLastChanceEnabled: Boolean = true,
    val soundEncoreEnabled: Boolean = true,
    val soundHighlightEnabled: Boolean = true,
    val highlightKeywords: String = "",
    val blocklistKeywords: String = "",
    val autoClaimEnabled: Boolean = false,
    val keepAwakeWhileCharging: Boolean = false,
    val mockModeEnabled: Boolean = false
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val sharedPrefs: SharedPreferences =
        application.getSharedPreferences("VineHelperPrefs", Context.MODE_PRIVATE)
    val isMockModeAvailable: Boolean =
        application.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    private val dbHelper = VineDbHelper(application)

    private val _items = MutableStateFlow<List<VineItem>>(emptyList())
    val items: StateFlow<List<VineItem>> = _items.asStateFlow()

    private val _scraperStatuses = MutableStateFlow<Map<String, String>>(
        mapOf("potluck" to "Stopped", "last_chance" to "Stopped", "encore" to "Stopped")
    )
    val scraperStatuses: StateFlow<Map<String, String>> = _scraperStatuses.asStateFlow()

    private val _settings = MutableStateFlow(ScraperSettings())
    val settings: StateFlow<ScraperSettings> = _settings.asStateFlow()

    private val _isLoggedIn = MutableStateFlow(false)
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

    fun updateLoginState(loggedIn: Boolean) {
        _isLoggedIn.value = loggedIn
    }

    private val _isBrowserLoading = MutableStateFlow(false)
    val isBrowserLoading: StateFlow<Boolean> = _isBrowserLoading.asStateFlow()

    fun setBrowserLoading(loading: Boolean) {
        _isBrowserLoading.value = loading
    }

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    fun triggerManualRefresh(scraper: VineScraper) {
        viewModelScope.launch {
            _isRefreshing.value = true
            scraper.triggerManualRefresh {
                _isRefreshing.value = false
            }
        }
    }

    private val _soundEvents = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val soundEvents: SharedFlow<String> = _soundEvents.asSharedFlow()

    private val _notificationEvents = MutableSharedFlow<VineItem>(extraBufferCapacity = 64)
    val notificationEvents: SharedFlow<VineItem> = _notificationEvents.asSharedFlow()

    private val seenAsins = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val dismissedAsins = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val persistenceMutex = Mutex()

    private val firstScrapeCompleted = java.util.concurrent.ConcurrentHashMap<String, Boolean>().apply {
        put("potluck", false)
        put("last_chance", false)
        put("encore", false)
    }

    init {
        loadPersistedStateAsync()
        startPruningJob()
        startDatabasePruningJob()
    }

    private fun loadPersistedStateAsync() {
        viewModelScope.launch(Dispatchers.IO) {
            loadSettings()
            loadPersistedData()
        }
    }

    private fun loadSettings() {
        val legacyRefresh = sharedPrefs.getInt("refresh_rate", 3)
        val refreshRfy = clampRefreshSeconds(
            "potluck",
            sharedPrefs.getInt("refresh_rate_rfy", legacyRefresh.coerceAtLeast(5))
        )
        val refreshAfa = clampRefreshSeconds(
            "last_chance",
            sharedPrefs.getInt("refresh_rate_afa", legacyRefresh)
        )
        val refreshAi = clampRefreshSeconds(
            "encore",
            sharedPrefs.getInt("refresh_rate_ai", legacyRefresh)
        )
        val timeout = sharedPrefs.getInt("timeout_minutes", 10)
        val sound = sharedPrefs.getBoolean("sound_enabled", true)
        val soundPotluck = sharedPrefs.getString("sound_potluck", "chime") ?: "chime"
        val soundLastChance = sharedPrefs.getString("sound_last_chance", "pop") ?: "pop"
        val soundEncore = sharedPrefs.getString("sound_encore", "bell") ?: "bell"
        val soundHighlight = sharedPrefs.getString("sound_highlight", "success") ?: "success"
        val soundPotluckEnabled = sharedPrefs.getBoolean("sound_potluck_enabled", true)
        val soundLastChanceEnabled = sharedPrefs.getBoolean("sound_last_chance_enabled", true)
        val soundEncoreEnabled = sharedPrefs.getBoolean("sound_encore_enabled", true)
        val soundHighlightEnabled = sharedPrefs.getBoolean("sound_highlight_enabled", true)
        val highlights = sharedPrefs.getString("highlight_keywords", "") ?: ""
        val blocklist = sharedPrefs.getString("blocklist_keywords", "") ?: ""
        val autoClaim = sharedPrefs.getBoolean("auto_claim_enabled", false)
        val keepAwake = sharedPrefs.getBoolean("keep_awake_while_charging", false)
        val mockMode = if (isMockModeAvailable) {
            sharedPrefs.getBoolean("mock_mode_enabled", false)
        } else {
            sharedPrefs.edit().remove("mock_mode_enabled").apply()
            false
        }

        _settings.value = ScraperSettings(
            refreshRateRfySeconds = refreshRfy,
            refreshRateAfaSeconds = refreshAfa,
            refreshRateAiSeconds = refreshAi,
            timeoutMinutes = timeout,
            soundEnabled = sound,
            soundPotluck = soundPotluck,
            soundLastChance = soundLastChance,
            soundEncore = soundEncore,
            soundHighlight = soundHighlight,
            soundPotluckEnabled = soundPotluckEnabled,
            soundLastChanceEnabled = soundLastChanceEnabled,
            soundEncoreEnabled = soundEncoreEnabled,
            soundHighlightEnabled = soundHighlightEnabled,
            highlightKeywords = highlights,
            blocklistKeywords = blocklist,
            autoClaimEnabled = autoClaim,
            keepAwakeWhileCharging = keepAwake,
            mockModeEnabled = mockMode
        )
    }

    private fun loadPersistedData() {
        val jsonStr = sharedPrefs.getString("saved_items", null)
        val list = mutableListOf<VineItem>()
        if (jsonStr != null) {
            try {
                val array = org.json.JSONArray(jsonStr)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val tsAdded = obj.optLong("timestampAdded", System.currentTimeMillis())
                    list.add(
                        VineItem(
                            id = obj.optString("id", ""),
                            title = obj.optString("title", ""),
                            imgUrl = obj.optString("imgUrl", ""),
                            href = obj.optString("href", ""),
                            asin = obj.optString("asin", ""),
                            recId = obj.optString("recId", ""),
                            queue = obj.optString("queue", ""),
                            timestampAdded = tsAdded,
                            isHighlighted = obj.optBoolean("isHighlighted", false),
                            lastSeenTimestamp = obj.optLong("lastSeenTimestamp", tsAdded)
                        )
                    )
                }
                _items.value = list
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        
        val set = sharedPrefs.getStringSet("seen_asins", null)
        if (set != null) {
            seenAsins.addAll(set)
        }
    }

    private fun savePersistedData() {
        viewModelScope.launch(Dispatchers.IO) {
            persistenceMutex.withLock {
                val itemsSnapshot = _items.value
                val seenSnapshot = HashSet(seenAsins)
                val array = org.json.JSONArray()
                itemsSnapshot.forEach { item ->
                    val obj = org.json.JSONObject().apply {
                        put("id", item.id)
                        put("title", item.title)
                        put("imgUrl", item.imgUrl)
                        put("href", item.href)
                        put("asin", item.asin)
                        put("recId", item.recId)
                        put("queue", item.queue)
                        put("timestampAdded", item.timestampAdded)
                        put("isHighlighted", item.isHighlighted)
                        put("lastSeenTimestamp", item.lastSeenTimestamp)
                    }
                    array.put(obj)
                }
                sharedPrefs.edit()
                    .putString("saved_items", array.toString())
                    .putStringSet("seen_asins", seenSnapshot)
                    .apply()
            }
        }
    }

    fun updateRefreshRate(queue: String, seconds: Int) {
        val clamped = clampRefreshSeconds(queue, seconds)
        when (queue) {
            "potluck" -> {
                _settings.update { it.copy(refreshRateRfySeconds = clamped) }
                sharedPrefs.edit().putInt("refresh_rate_rfy", clamped).apply()
            }
            "last_chance" -> {
                _settings.update { it.copy(refreshRateAfaSeconds = clamped) }
                sharedPrefs.edit().putInt("refresh_rate_afa", clamped).apply()
            }
            "encore" -> {
                _settings.update { it.copy(refreshRateAiSeconds = clamped) }
                sharedPrefs.edit().putInt("refresh_rate_ai", clamped).apply()
            }
        }
    }

    fun updateTimeoutMinutes(minutes: Int) {
        _settings.update { it.copy(timeoutMinutes = minutes) }
        sharedPrefs.edit().putInt("timeout_minutes", minutes).apply()
    }

    fun updateSoundEnabled(enabled: Boolean) {
        _settings.update { it.copy(soundEnabled = enabled) }
        sharedPrefs.edit().putBoolean("sound_enabled", enabled).apply()
    }

    fun updateHighlightKeywords(keywords: String) {
        _settings.update { it.copy(highlightKeywords = keywords) }
        sharedPrefs.edit().putString("highlight_keywords", keywords).apply()
    }

    fun updateBlocklistKeywords(keywords: String) {
        _settings.update { it.copy(blocklistKeywords = keywords) }
        sharedPrefs.edit().putString("blocklist_keywords", keywords).apply()
    }

    fun updateAutoClaimEnabled(enabled: Boolean) {
        _settings.update { it.copy(autoClaimEnabled = enabled) }
        sharedPrefs.edit().putBoolean("auto_claim_enabled", enabled).apply()
    }

    fun updateKeepAwakeWhileCharging(enabled: Boolean) {
        _settings.update { it.copy(keepAwakeWhileCharging = enabled) }
        sharedPrefs.edit().putBoolean("keep_awake_while_charging", enabled).apply()
    }

    fun updateMockModeEnabled(enabled: Boolean) {
        val allowedEnabled = enabled && isMockModeAvailable
        _settings.update { it.copy(mockModeEnabled = allowedEnabled) }
        sharedPrefs.edit().putBoolean("mock_mode_enabled", allowedEnabled).apply()
        if (allowedEnabled) {
            updateLoginState(true)
        }
    }

    fun updateQueueSound(queue: String, soundName: String) {
        when (queue) {
            "potluck" -> {
                _settings.update { it.copy(soundPotluck = soundName) }
                sharedPrefs.edit().putString("sound_potluck", soundName).apply()
            }
            "last_chance" -> {
                _settings.update { it.copy(soundLastChance = soundName) }
                sharedPrefs.edit().putString("sound_last_chance", soundName).apply()
            }
            "encore" -> {
                _settings.update { it.copy(soundEncore = soundName) }
                sharedPrefs.edit().putString("sound_encore", soundName).apply()
            }
            "highlight" -> {
                _settings.update { it.copy(soundHighlight = soundName) }
                sharedPrefs.edit().putString("sound_highlight", soundName).apply()
            }
        }
    }

    fun updateQueueSoundEnabled(queue: String, enabled: Boolean) {
        when (queue) {
            "potluck" -> {
                _settings.update { it.copy(soundPotluckEnabled = enabled) }
                sharedPrefs.edit().putBoolean("sound_potluck_enabled", enabled).apply()
            }
            "last_chance" -> {
                _settings.update { it.copy(soundLastChanceEnabled = enabled) }
                sharedPrefs.edit().putBoolean("sound_last_chance_enabled", enabled).apply()
            }
            "encore" -> {
                _settings.update { it.copy(soundEncoreEnabled = enabled) }
                sharedPrefs.edit().putBoolean("sound_encore_enabled", enabled).apply()
            }
            "highlight" -> {
                _settings.update { it.copy(soundHighlightEnabled = enabled) }
                sharedPrefs.edit().putBoolean("sound_highlight_enabled", enabled).apply()
            }
        }
    }

    fun testPlaySound(soundName: String) {
        _soundEvents.tryEmit(soundName)
    }

    fun testSendNotification() {
        android.util.Log.d("VineHelperDebug", "testSendNotification() called")
        _notificationEvents.tryEmit(
            VineItem(
                id = "test_asin",
                title = "Test Notification: Vine Helper is working correctly!",
                imgUrl = "",
                href = "",
                asin = "TESTASIN123",
                recId = "test_rec",
                queue = "potluck"
            )
        )
        if (_settings.value.soundEnabled) {
            _soundEvents.tryEmit(_settings.value.soundHighlight)
        }
    }

    fun handleScrapedItems(queue: String, scrapedItems: List<VineItem>) {
        val currentSeen = firstScrapeCompleted[queue] ?: false
        
        if (!currentSeen) {
            val now = System.currentTimeMillis()
            val highlightTerms = _settings.value.highlightKeywords.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
            
            scrapedItems.forEach { item ->
                seenAsins.add(item.asin)
                if (!dbHelper.isSeen(item.asin)) {
                    dbHelper.insertOrUpdateItem(item.asin, item.title, queue, now, notified = true, recId = item.recId)
                } else {
                    dbHelper.updateItemMetadata(item.asin, item.title, queue, item.recId)
                }
            }
            firstScrapeCompleted[queue] = true
            
            // Populate first scrape items into feed so they are immediately visible (without notification alerts)
            val limitMs = _settings.value.timeoutMinutes * 60 * 1000L
            _items.update { currentList ->
                val mapped = scrapedItems.mapNotNull { item ->
                    val existing = currentList.find { it.asin == item.asin }
                    val originalTs = existing?.timestampAdded ?: if (dbHelper.isSeen(item.asin)) {
                        dbHelper.getItemTimestamp(item.asin)
                    } else {
                        now
                    }
                    if (now - originalTs >= limitMs) {
                        return@mapNotNull null
                    }
                    val isHighlighted = highlightTerms.any { item.title.lowercase().contains(it) }
                    item.copy(
                        timestampAdded = originalTs,
                        lastSeenTimestamp = now,
                        isHighlighted = isHighlighted
                    )
                }
                val combined = mapped + currentList
                val unique = combined.distinctBy { it.asin }
                unique.sortedByDescending { it.timestampAdded }
            }
            savePersistedData()
            _scraperStatuses.update { it + (queue to "First scrape completed (${scrapedItems.size} items cached)") }
            return
        }

        val timestamp = System.currentTimeMillis()
        val newlyDiscoveredItems = mutableListOf<VineItem>()
        val silentItemsToAdd = mutableListOf<VineItem>()
        val itemsToUpdateInFeed = mutableSetOf<String>()

        val blockTerms = _settings.value.blocklistKeywords.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        val highlightTerms = _settings.value.highlightKeywords.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }

        scrapedItems.forEach { item ->
            val itemAlreadySeen = dbHelper.isSeen(item.asin)
            if (itemAlreadySeen) {
                dbHelper.updateItemMetadata(item.asin, item.title, queue, item.recId)
            }
            val titleLower = item.title.lowercase()
            val isBlocked = blockTerms.any { titleLower.contains(it) }
            if (isBlocked) {
                return@forEach
            }

            if (dismissedAsins.contains(item.asin)) {
                return@forEach
            }

            val isHighlighted = highlightTerms.any { titleLower.contains(it) }
            val inFeed = _items.value.any { it.asin == item.asin }

            if (!inFeed) {
                val alreadyNotified = itemAlreadySeen
                if (!alreadyNotified) {
                    dbHelper.insertOrUpdateItem(item.asin, item.title, queue, timestamp, notified = false, recId = item.recId)
                    seenAsins.add(item.asin)
                    val newItem = item.copy(
                        timestampAdded = timestamp,
                        lastSeenTimestamp = timestamp,
                        isHighlighted = isHighlighted
                    )
                    newlyDiscoveredItems.add(newItem)
                } else {
                    val originalTs = dbHelper.getItemTimestamp(item.asin)
                    val limitMs = _settings.value.timeoutMinutes * 60 * 1000L
                    if (timestamp - originalTs < limitMs) {
                        val silentItem = item.copy(
                            timestampAdded = originalTs,
                            lastSeenTimestamp = timestamp,
                            isHighlighted = isHighlighted
                        )
                        silentItemsToAdd.add(silentItem)
                    }
                }
            } else {
                itemsToUpdateInFeed.add(item.asin)
            }
        }

        val scrapedAsins = scrapedItems.map { it.asin }.toSet()
        val scrapedItemsByAsin = scrapedItems.associateBy { it.asin }

        // Apply feed state updates and prune sold-out/expired items
        val limitMs = _settings.value.timeoutMinutes * 60 * 1000L
        _items.update { currentList ->
            val filteredList = currentList.filter { item ->
                (item.queue != queue || scrapedAsins.contains(item.asin)) && (timestamp - item.timestampAdded < limitMs)
            }
            
            val updatedList = filteredList.map { existingItem ->
                val latestItem = scrapedItemsByAsin[existingItem.asin]
                if (itemsToUpdateInFeed.contains(existingItem.asin) && latestItem != null) {
                    val refreshedTitle = latestItem.title.ifBlank { existingItem.title }
                    existingItem.copy(
                        id = latestItem.id.ifBlank { existingItem.id },
                        title = refreshedTitle,
                        imgUrl = latestItem.imgUrl.ifBlank { existingItem.imgUrl },
                        href = latestItem.href.ifBlank { existingItem.href },
                        recId = latestItem.recId.ifBlank { existingItem.recId },
                        queue = latestItem.queue.ifBlank { existingItem.queue },
                        lastSeenTimestamp = timestamp,
                        isHighlighted = highlightTerms.any { refreshedTitle.lowercase().contains(it) }
                    )
                } else {
                    existingItem
                }
            }
            val combined = newlyDiscoveredItems + silentItemsToAdd + updatedList
            val unique = combined.distinctBy { it.asin }
            unique.sortedByDescending { it.timestampAdded }
        }
        savePersistedData()

        // Trigger notifications and sounds ONLY for newly discovered items
        if (newlyDiscoveredItems.isNotEmpty()) {
            var playPrioritySound = false
            newlyDiscoveredItems.forEach { item ->
                if (item.isHighlighted) {
                    playPrioritySound = true
                }
                
                if (queue == "potluck" || queue == "last_chance") {
                    if (!dbHelper.isNotified(item.asin)) {
                        _notificationEvents.tryEmit(item)
                        dbHelper.markAsNotified(item.asin)
                    }
                } else {
                    _notificationEvents.tryEmit(item)
                }
            }

            if (_settings.value.soundEnabled) {
                val playSound = if (playPrioritySound) {
                    _settings.value.soundHighlightEnabled
                } else {
                    when (queue) {
                        "potluck" -> _settings.value.soundPotluckEnabled
                        "last_chance" -> _settings.value.soundLastChanceEnabled
                        "encore" -> _settings.value.soundEncoreEnabled
                        else -> false
                    }
                }

                if (playSound) {
                    val soundToPlay = if (playPrioritySound) {
                        _settings.value.soundHighlight
                    } else {
                        when (queue) {
                            "potluck" -> _settings.value.soundPotluck
                            "last_chance" -> _settings.value.soundLastChance
                            "encore" -> _settings.value.soundEncore
                            else -> "chime"
                        }
                    }
                    _soundEvents.tryEmit(soundToPlay)
                }
            }
        }
    }

    fun updateScraperStatus(queue: String, status: String) {
        _scraperStatuses.update { it + (queue to status) }
    }

    fun getRecIdFromDb(asin: String): String {
        return dbHelper.getRecId(asin)
    }

    fun dismissItem(item: VineItem) {
        dismissedAsins.add(item.asin)
        _items.update { currentList ->
            currentList.filter { it.id != item.id }
        }
        savePersistedData()
    }

    fun clearAllItems() {
        _items.value.forEach { dismissedAsins.add(it.asin) }
        _items.value = emptyList()
        savePersistedData()
    }

    private fun startPruningJob() {
        viewModelScope.launch {
            while (isActive) {
                delay(1000)
                pruneExpiredItems()
            }
        }
    }

    private fun startDatabasePruningJob() {
        viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                dbHelper.pruneOldItems()
                delay(6 * 60 * 60 * 1000L)
            }
        }
    }

    private fun pruneExpiredItems() {
        val now = System.currentTimeMillis()
        val limitMs = _settings.value.timeoutMinutes * 60 * 1000L
        var changed = false
        _items.update { currentList ->
            val pruned = currentList.filter { item ->
                (now - item.timestampAdded) < limitMs
            }
            if (pruned.size != currentList.size) {
                changed = true
            }
            pruned
        }
        if (changed) {
            savePersistedData()
        }
    }
}
