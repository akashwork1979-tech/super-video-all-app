package com.example.viewmodel

import android.app.Application
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebStorage
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.OmniStreamApp
import com.example.core.AdBlocker
import com.example.core.AppSettings
import com.example.data.CategoryEntity
import com.example.data.DownloadRecordEntity
import com.example.data.FeedItemCacheEntity
import com.example.data.PlaylistEntity
import com.example.data.PlaylistItemEntity
import com.example.download.DetectedStream
import com.example.download.DownloadHelper
import com.example.media.MediaControlCommand
import com.example.media.MediaPlaybackService
import com.example.media.PlaybackController
import com.example.media.PlaybackEngine
import com.example.media.PlaybackState
import com.example.media.SleepTimerOption
import com.example.ui.theme.AppThemeMode
import com.example.web.StreamPlatform
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AppDestination(val route: String, val label: String) {
    HUB("stream_hub", "Hub"),
    EXPLORE("explore", "Explore"),
    PLAYLISTS("playlists", "Playlists"),
    DOWNLOADS("downloads", "Downloads"),
    SETTINGS("settings", "Settings")
}

data class HubUiState(
    val activePlatform: StreamPlatform = StreamPlatform.YOUTUBE,
    val currentUrlByPlatform: Map<StreamPlatform, String> = StreamPlatform.entries.associateWith { it.homeUrl },
    val currentTitleByPlatform: Map<StreamPlatform, String> = StreamPlatform.entries.associateWith { it.label },
    val loadingProgress: Int = 100,
    val adsBlockedCount: Int = 0,
    val detectedStreams: List<DetectedStream> = emptyList(),
    val latestDetectedStream: DetectedStream? = null
)

class OmniStreamViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as OmniStreamApp
    private val repository = app.repository
    private val preferences = app.preferences
    private val backupRestore = app.backupRestoreManager

    private val _selectedTab = MutableStateFlow(AppDestination.HUB)
    val selectedTab: StateFlow<AppDestination> = _selectedTab.asStateFlow()

    private val _hubState = MutableStateFlow(HubUiState())
    val hubState: StateFlow<HubUiState> = _hubState.asStateFlow()

    private val _selectedCategoryId = MutableStateFlow<Long?>(null)
    val selectedCategoryId: StateFlow<Long?> = _selectedCategoryId.asStateFlow()

    private val _isRefreshingFeed = MutableStateFlow(false)
    val isRefreshingFeed: StateFlow<Boolean> = _isRefreshingFeed.asStateFlow()

    private val _feedStatusMessage = MutableStateFlow<String?>(null)
    val feedStatusMessage: StateFlow<String?> = _feedStatusMessage.asStateFlow()

    private val _isAppUnlocked = MutableStateFlow(false)
    val isAppUnlocked: StateFlow<Boolean> = _isAppUnlocked.asStateFlow()

    private val _snackbarMessages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val snackbarMessages: SharedFlow<String> = _snackbarMessages.asSharedFlow()

    private val _webReloadTrigger = MutableSharedFlow<Pair<StreamPlatform, String>>(extraBufferCapacity = 16)
    val webReloadTrigger: SharedFlow<Pair<StreamPlatform, String>> = _webReloadTrigger.asSharedFlow()

    val playbackState: StateFlow<PlaybackState> = PlaybackController.state

    val settings: StateFlow<AppSettings> = preferences.settingsFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = AppSettings()
    )

    val categories: StateFlow<List<CategoryEntity>> = repository.categories.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    val activeFeedItems: StateFlow<List<FeedItemCacheEntity>> = _selectedCategoryId
        .flatMapLatest { id ->
            if (id == null) flowOf(emptyList()) else repository.getCachedFeedForCategory(id)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val playlists: StateFlow<List<PlaylistEntity>> = repository.playlists.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val allPlaylistItems: StateFlow<List<PlaylistItemEntity>> = repository.allPlaylistItems.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val downloads: StateFlow<List<DownloadRecordEntity>> = repository.downloads.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    init {
        viewModelScope.launch {
            repository.ensureSeeded()
        }
        viewModelScope.launch {
            categories.collect { list ->
                if (_selectedCategoryId.value == null && list.isNotEmpty()) {
                    selectCategory(list.first())
                }
            }
        }
        viewModelScope.launch {
            PlaybackController.commands.collect { command ->
                if (command is MediaControlCommand.LoadQueueItem && !command.item.isDirectStream) {
                    val platform = StreamPlatform.fromId(command.item.platformId)
                    openUrlInHub(command.item.url, platform, switchTab = false)
                }
            }
        }
    }

    fun selectTab(destination: AppDestination) {
        _selectedTab.value = destination
    }

    fun selectPlatform(platform: StreamPlatform) {
        _hubState.update { it.copy(activePlatform = platform) }
    }

    fun openUrlInHub(url: String, preferredPlatform: StreamPlatform? = null, switchTab: Boolean = true) {
        val targetPlatform = preferredPlatform ?: StreamPlatform.detectFromUrl(url)
        _hubState.update { state ->
            state.copy(
                activePlatform = targetPlatform,
                currentUrlByPlatform = state.currentUrlByPlatform + (targetPlatform to url)
            )
        }
        _webReloadTrigger.tryEmit(targetPlatform to url)
        if (switchTab) {
            _selectedTab.value = AppDestination.HUB
        }
        if (settings.value.backgroundPlayEnabled) {
            MediaPlaybackService.startForegroundSession(app, settings.value.strictAudioFocusLock)
        }
    }

    fun onWebUrlChanged(platform: StreamPlatform, url: String) {
        _hubState.update { state ->
            state.copy(
                currentUrlByPlatform = state.currentUrlByPlatform + (platform to url)
            )
        }
    }

    fun onWebTitleChanged(platform: StreamPlatform, title: String) {
        _hubState.update { state ->
            state.copy(
                currentTitleByPlatform = state.currentTitleByPlatform + (platform to title)
            )
        }
    }

    fun onWebLoadingProgress(progress: Int) {
        _hubState.update { it.copy(loadingProgress = progress) }
    }

    fun onAdsBlockedUpdated(count: Int) {
        _hubState.update { it.copy(adsBlockedCount = count) }
    }

    fun onWebMediaStateUpdate(platform: StreamPlatform, isPlaying: Boolean, title: String, url: String) {
        PlaybackController.updateNowPlaying(
            title = title,
            subtitle = "${platform.label} • ${if (isPlaying) "Streaming" else "Paused"}",
            url = url,
            platformId = platform.id,
            isPlaying = isPlaying,
            engine = PlaybackEngine.WEBVIEW
        )
        if (isPlaying && settings.value.backgroundPlayEnabled) {
            MediaPlaybackService.startForegroundSession(app, settings.value.strictAudioFocusLock)
        }
    }

    fun onWebTrackEnded() {
        PlaybackController.onCurrentTrackEnded()
    }

    fun onStreamDetected(stream: DetectedStream) {
        _hubState.update { state ->
            val updated = (listOf(stream) + state.detectedStreams.filterNot { it.url == stream.url })
                .take(25)
            state.copy(
                detectedStreams = updated,
                latestDetectedStream = stream
            )
        }
    }

    fun dismissLatestDetectedStream() {
        _hubState.update { it.copy(latestDetectedStream = null) }
    }

    // --- Categories & RSS ---

    fun selectCategory(category: CategoryEntity) {
        _selectedCategoryId.value = category.id
        refreshSelectedCategory(category)
    }

    fun refreshSelectedCategory(categoryOverride: CategoryEntity? = null) {
        val target = categoryOverride
            ?: categories.value.firstOrNull { it.id == _selectedCategoryId.value }
            ?: return
        viewModelScope.launch {
            _isRefreshingFeed.value = true
            _feedStatusMessage.value = null
            val result = repository.refreshFeed(target)
            _isRefreshingFeed.value = false
            result.onFailure { err ->
                _feedStatusMessage.value = "Offline cache shown (${err.message ?: "Network unavailable"})"
            }
        }
    }

    fun addCategory(titleHindi: String, titleEnglish: String, rssUrl: String, accentHex: Long) {
        viewModelScope.launch {
            if (titleHindi.isBlank() && titleEnglish.isBlank()) {
                emitMessage("Please enter a category name")
                return@launch
            }
            val newId = repository.addCategory(
                titleHindi = titleHindi.ifBlank { titleEnglish },
                titleEnglish = titleEnglish.ifBlank { titleHindi },
                rssUrl = rssUrl,
                accentHex = accentHex
            )
            emitMessage("Category added")
            categories.value.firstOrNull { it.id == newId }?.let { selectCategory(it) }
        }
    }

    fun updateCategory(category: CategoryEntity) {
        viewModelScope.launch {
            repository.updateCategory(category)
            emitMessage("Category updated")
        }
    }

    fun deleteCategory(category: CategoryEntity) {
        viewModelScope.launch {
            repository.deleteCategory(category)
            if (_selectedCategoryId.value == category.id) {
                _selectedCategoryId.value = categories.value.firstOrNull { it.id != category.id }?.id
            }
            emitMessage("Deleted ${category.titleEnglish}")
        }
    }

    // --- Cross-Platform Playlists ---

    fun createPlaylist(name: String, description: String) {
        viewModelScope.launch {
            if (name.isBlank()) {
                emitMessage("Playlist name cannot be empty")
                return@launch
            }
            repository.createPlaylist(name, description)
            emitMessage("Created playlist \"$name\"")
        }
    }

    fun deletePlaylist(playlist: PlaylistEntity) {
        viewModelScope.launch {
            repository.deletePlaylist(playlist)
            emitMessage("Deleted playlist \"${playlist.name}\"")
        }
    }

    fun saveCurrentPageToPlaylist(playlistId: Long) {
        val active = _hubState.value.activePlatform
        val url = _hubState.value.currentUrlByPlatform[active] ?: active.homeUrl
        val title = _hubState.value.currentTitleByPlatform[active] ?: active.label
        addLinkToPlaylist(playlistId, title, url, active.id)
    }

    fun addLinkToPlaylist(
        playlistId: Long,
        title: String,
        url: String,
        platformId: String? = null,
        thumbnailUrl: String = ""
    ) {
        viewModelScope.launch {
            val detectedPlatform = platformId ?: StreamPlatform.detectFromUrl(url).id
            val isDirect = DownloadHelper.detectMediaType(url) != null
            repository.addItemToPlaylist(
                playlistId = playlistId,
                title = title,
                url = url,
                platformId = detectedPlatform,
                thumbnailUrl = thumbnailUrl,
                isDirectStream = isDirect
            )
            emitMessage("Saved to playlist")
        }
    }

    fun removePlaylistItem(itemId: Long) {
        viewModelScope.launch {
            repository.removePlaylistItem(itemId)
        }
    }

    fun playPlaylist(items: List<PlaylistItemEntity>, startIndex: Int = 0) {
        if (items.isEmpty()) {
            emitMessage("Playlist is empty")
            return
        }
        val first = items[startIndex.coerceIn(0, items.lastIndex)]
        PlaybackController.setQueueAndPlay(items, startIndex)
        if (first.isDirectStream) {
            MediaPlaybackService.playDirectMedia(
                context = app,
                url = first.url,
                title = first.title,
                strictAudioLock = settings.value.strictAudioFocusLock
            )
        } else {
            val platform = StreamPlatform.fromId(first.platformId)
            openUrlInHub(first.url, platform, switchTab = true)
        }
    }

    // --- Downloads ---

    fun startDownload(url: String, title: String, audioOnly: Boolean) {
        viewModelScope.launch {
            val result = DownloadHelper.enqueueDownload(app, url, title, audioOnly)
            result.onSuccess {
                emitMessage("Downloading to /Downloads/OmniStream/")
                dismissLatestDetectedStream()
            }.onFailure { err ->
                emitMessage("Download error: ${err.message ?: "Invalid URL"}")
            }
        }
    }

    fun pauseDownload(record: DownloadRecordEntity) {
        viewModelScope.launch {
            DownloadHelper.cancelOrPauseDownload(app, record)
            emitMessage("Download paused")
        }
    }

    fun resumeDownload(record: DownloadRecordEntity) {
        viewModelScope.launch {
            DownloadHelper.retryOrResumeDownload(app, record)
                .onSuccess { emitMessage("Resuming download") }
                .onFailure { emitMessage("Could not resume download") }
        }
    }

    fun deleteDownload(record: DownloadRecordEntity) {
        viewModelScope.launch {
            DownloadHelper.cancelOrPauseDownload(app, record)
            repository.deleteDownloadRecord(record)
        }
    }

    fun openDownload(record: DownloadRecordEntity) {
        if (!DownloadHelper.openDownloadedFile(app, record)) {
            emitMessage("File not available yet or no external viewer installed")
        }
    }

    // --- Playback Controls ---

    fun setPlaybackSpeed(speed: Float) {
        PlaybackController.setPlaybackSpeed(speed)
        viewModelScope.launch { preferences.setDefaultPlaybackSpeed(speed) }
    }

    fun toggleAudioOnly() {
        PlaybackController.setAudioOnlyMode(!playbackState.value.audioOnlyMode)
    }

    fun setSleepTimer(option: SleepTimerOption) {
        PlaybackController.setSleepTimer(option)
        emitMessage(
            if (option == SleepTimerOption.OFF) "Sleep timer turned off"
            else "Sleep timer set: ${option.label}"
        )
    }

    fun startBackgroundServiceNow() {
        MediaPlaybackService.startForegroundSession(app, settings.value.strictAudioFocusLock)
    }

    // --- Preferences & Privacy ---

    fun setThemeMode(mode: AppThemeMode) {
        viewModelScope.launch { preferences.setThemeMode(mode) }
    }

    fun setAdBlockerEnabled(enabled: Boolean) {
        viewModelScope.launch { preferences.setAdBlockerEnabled(enabled) }
    }

    fun setBackgroundPlayEnabled(enabled: Boolean) {
        viewModelScope.launch { preferences.setBackgroundPlayEnabled(enabled) }
    }

    fun setStrictAudioFocusLock(enabled: Boolean) {
        viewModelScope.launch { preferences.setStrictAudioFocusLock(enabled) }
    }

    fun setDataSaverEnabled(enabled: Boolean) {
        viewModelScope.launch { preferences.setDataSaverEnabled(enabled) }
    }

    fun setGesturesEnabled(enabled: Boolean) {
        viewModelScope.launch { preferences.setGesturesEnabled(enabled) }
    }

    fun toggleIncognitoForPlatform(platform: StreamPlatform) {
        viewModelScope.launch {
            preferences.toggleIncognitoForPlatform(platform.id)
        }
    }

    fun toggleDesktopUaForPlatform(platform: StreamPlatform) {
        viewModelScope.launch {
            preferences.toggleDesktopUaForPlatform(platform.id)
        }
    }

    fun clearAllWebSessions() {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
        AdBlocker.resetSessionCount()
        _hubState.update { it.copy(adsBlockedCount = 0) }
        emitMessage("Cleared all cookies & web storage")
    }

    // --- App Lock ---

    fun unlockApp() {
        _isAppUnlocked.value = true
    }

    fun configurePin(pin: String) {
        viewModelScope.launch {
            preferences.configurePin(pin)
            _isAppUnlocked.value = true
            emitMessage("App Lock PIN configured")
        }
    }

    fun setAppLockEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferences.setAppLockEnabled(enabled)
            if (!enabled) _isAppUnlocked.value = true
        }
    }

    fun setBiometricUnlockEnabled(enabled: Boolean) {
        viewModelScope.launch { preferences.setBiometricUnlockEnabled(enabled) }
    }

    suspend fun verifyPin(pin: String): Boolean = preferences.verifyPin(pin)

    // --- Backup & Restore ---

    fun exportBackup(uri: Uri) {
        viewModelScope.launch {
            backupRestore.exportToUri(uri)
                .onSuccess { emitMessage(it) }
                .onFailure { emitMessage("Export failed: ${it.message}") }
        }
    }

    fun importBackup(uri: Uri) {
        viewModelScope.launch {
            backupRestore.importFromUri(uri)
                .onSuccess { emitMessage(it) }
                .onFailure { emitMessage("Restore failed: ${it.message}") }
        }
    }

    private fun emitMessage(message: String) {
        _snackbarMessages.tryEmit(message)
    }
}
