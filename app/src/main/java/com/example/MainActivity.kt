package com.example

import android.Manifest
import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.LiveTv
import androidx.compose.material.icons.outlined.QueueMusic
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.media.PlaybackController
import com.example.ui.components.AppLockScreen
import com.example.ui.components.UniversalMediaDock
import com.example.ui.screens.DownloadsScreen
import com.example.ui.screens.ExploreScreen
import com.example.ui.screens.PlaylistsScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.StreamHubScreen
import com.example.ui.theme.OmniStreamTheme
import com.example.viewmodel.AppDestination
import com.example.viewmodel.OmniStreamViewModel

class MainActivity : FragmentActivity() {

    private val viewModel: OmniStreamViewModel by viewModels()
    private var isInPipModeState by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            val isUnlocked by viewModel.isAppUnlocked.collectAsStateWithLifecycle()

            OmniStreamTheme(themeMode = settings.themeMode) {
                RequestNotificationPermissionOnce()

                val requireLock = settings.appLockEnabled && settings.hasPinConfigured && !isUnlocked
                if (requireLock) {
                    AppLockScreen(
                        biometricEnabled = settings.biometricUnlockEnabled,
                        onVerifyPin = { pin -> viewModel.verifyPin(pin) },
                        onUnlocked = { viewModel.unlockApp() }
                    )
                } else {
                    OmniStreamRootApp(
                        viewModel = viewModel,
                        isInPipMode = isInPipModeState,
                        onEnterPip = { enterStudioPipMode() }
                    )
                }
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val settings = viewModel.settings.value
        val playback = PlaybackController.state.value
        if (settings.backgroundPlayEnabled && playback.isPlaying && !playback.audioOnlyMode) {
            enterStudioPipMode()
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPipModeState = isInPictureInPictureMode
    }

    private fun enterStudioPipMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
        ) {
            try {
                val params = PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(16, 9))
                    .build()
                enterPictureInPictureMode(params)
            } catch (_: Exception) {
            }
        }
    }
}

@Composable
private fun RequestNotificationPermissionOnce() {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = {}
    )
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}

@Composable
private fun OmniStreamRootApp(
    viewModel: OmniStreamViewModel,
    isInPipMode: Boolean,
    onEnterPip: () -> Unit
) {
    val selectedTab by viewModel.selectedTab.collectAsStateWithLifecycle()
    val hubState by viewModel.hubState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val selectedCategoryId by viewModel.selectedCategoryId.collectAsStateWithLifecycle()
    val feedItems by viewModel.activeFeedItems.collectAsStateWithLifecycle()
    val isRefreshingFeed by viewModel.isRefreshingFeed.collectAsStateWithLifecycle()
    val feedStatus by viewModel.feedStatusMessage.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val allPlaylistItems by viewModel.allPlaylistItems.collectAsStateWithLifecycle()
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.snackbarMessages.collect { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    // Back press on secondary tabs returns smoothly to Stream Hub
    BackHandler(enabled = selectedTab != AppDestination.HUB) {
        viewModel.selectTab(AppDestination.HUB)
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useNavigationRail = maxWidth >= 600.dp && !isInPipMode

        Scaffold(
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                if (!isInPipMode) {
                    Column {
                        UniversalMediaDock(
                            playbackState = playbackState,
                            onTogglePlayPause = {
                                if (settings.backgroundPlayEnabled && !playbackState.isBackgroundServiceActive) {
                                    viewModel.startBackgroundServiceNow()
                                }
                                PlaybackController.togglePlayPause()
                            },
                            onNext = { PlaybackController.playNext() },
                            onPrevious = { PlaybackController.playPrevious() },
                            onSeekDelta = { delta -> PlaybackController.seekBy(delta) },
                            onSpeedSelected = { speed -> viewModel.setPlaybackSpeed(speed) },
                            onToggleAudioOnly = { viewModel.toggleAudioOnly() },
                            onCycleRepeat = { PlaybackController.cycleRepeatMode() },
                            onSleepTimerSelected = { opt -> viewModel.setSleepTimer(opt) },
                            onEnterPip = onEnterPip,
                            onSelectQueueIndex = { idx ->
                                viewModel.playPlaylist(playbackState.queue, idx)
                            }
                        )

                        if (!useNavigationRail) {
                            NavigationBar(
                                containerColor = MaterialTheme.colorScheme.surface,
                                windowInsets = WindowInsets.navigationBars
                            ) {
                                AppDestination.entries.forEach { dest ->
                                    val selected = selectedTab == dest
                                    NavigationBarItem(
                                        selected = selected,
                                        onClick = { viewModel.selectTab(dest) },
                                        icon = {
                                            Icon(
                                                imageVector = destinationIcon(dest, selected),
                                                contentDescription = dest.label
                                            )
                                        },
                                        label = { Text(dest.label, style = MaterialTheme.typography.labelSmall) },
                                        modifier = Modifier.testTag("nav_${dest.route}")
                                    )
                                }
                            }
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .padding(innerPadding)
            ) {
                if (useNavigationRail) {
                    NavigationRail(
                        containerColor = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxHeight()
                    ) {
                        AppDestination.entries.forEach { dest ->
                            val selected = selectedTab == dest
                            NavigationRailItem(
                                selected = selected,
                                onClick = { viewModel.selectTab(dest) },
                                icon = {
                                    Icon(
                                        imageVector = destinationIcon(dest, selected),
                                        contentDescription = dest.label
                                    )
                                },
                                label = { Text(dest.label, style = MaterialTheme.typography.labelSmall) },
                                modifier = Modifier.testTag("rail_${dest.route}")
                            )
                        }
                    }
                }

                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    // Keep StreamHubScreen mounted in the composition tree when switching tabs
                    // so embedded WebView video/audio streams continue uninterrupted!
                    StreamHubScreen(
                        hubState = hubState,
                        settings = settings,
                        playbackState = playbackState,
                        playlists = playlists,
                        isInPipMode = isInPipMode,
                        reloadTrigger = viewModel.webReloadTrigger,
                        onSelectPlatform = viewModel::selectPlatform,
                        onUrlChanged = viewModel::onWebUrlChanged,
                        onTitleChanged = viewModel::onWebTitleChanged,
                        onLoadingProgress = viewModel::onWebLoadingProgress,
                        onAdsBlockedUpdated = viewModel::onAdsBlockedUpdated,
                        onMediaStateUpdate = viewModel::onWebMediaStateUpdate,
                        onMediaEnded = viewModel::onWebTrackEnded,
                        onStreamDetected = viewModel::onStreamDetected,
                        onDismissDetectedStream = viewModel::dismissLatestDetectedStream,
                        onDownloadStream = viewModel::startDownload,
                        onToggleIncognito = viewModel::toggleIncognitoForPlatform,
                        onToggleDesktopUa = viewModel::toggleDesktopUaForPlatform,
                        onSaveToPlaylist = viewModel::saveCurrentPageToPlaylist,
                        onToggleAudioOnly = viewModel::toggleAudioOnly,
                        modifier = Modifier.fillMaxSize()
                    )

                    // Overlay secondary tabs cleanly without detaching the active WebView stream
                    if (!isInPipMode && selectedTab != AppDestination.HUB) {
                        androidx.compose.material3.Surface(
                            color = MaterialTheme.colorScheme.background,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            when (selectedTab) {
                                AppDestination.EXPLORE -> ExploreScreen(
                                    categories = categories,
                                    selectedCategoryId = selectedCategoryId,
                                    feedItems = feedItems,
                                    playlists = playlists,
                                    isRefreshing = isRefreshingFeed,
                                    statusMessage = feedStatus,
                                    onSelectCategory = viewModel::selectCategory,
                                    onRefresh = { viewModel.refreshSelectedCategory() },
                                    onAddCategory = viewModel::addCategory,
                                    onUpdateCategory = viewModel::updateCategory,
                                    onDeleteCategory = viewModel::deleteCategory,
                                    onOpenArticleInHub = { url -> viewModel.openUrlInHub(url) },
                                    onSaveFeedItemToPlaylist = { playlistId, item ->
                                        viewModel.addLinkToPlaylist(
                                            playlistId = playlistId,
                                            title = item.title,
                                            url = item.link,
                                            thumbnailUrl = item.imageUrl
                                        )
                                    }
                                )

                                AppDestination.PLAYLISTS -> PlaylistsScreen(
                                    playlists = playlists,
                                    allItems = allPlaylistItems,
                                    onCreatePlaylist = viewModel::createPlaylist,
                                    onDeletePlaylist = viewModel::deletePlaylist,
                                    onAddLinkToPlaylist = { playlistId, title, url ->
                                        viewModel.addLinkToPlaylist(playlistId, title, url)
                                    },
                                    onRemoveItem = viewModel::removePlaylistItem,
                                    onPlayPlaylist = viewModel::playPlaylist
                                )

                                AppDestination.DOWNLOADS -> DownloadsScreen(
                                    downloads = downloads,
                                    detectedStreams = hubState.detectedStreams,
                                    onStartDownload = viewModel::startDownload,
                                    onPauseDownload = viewModel::pauseDownload,
                                    onResumeDownload = viewModel::resumeDownload,
                                    onDeleteDownload = viewModel::deleteDownload,
                                    onOpenDownload = viewModel::openDownload
                                )

                                AppDestination.SETTINGS -> SettingsScreen(
                                    settings = settings,
                                    onSelectTheme = viewModel::setThemeMode,
                                    onToggleAdBlock = viewModel::setAdBlockerEnabled,
                                    onToggleBackgroundPlay = viewModel::setBackgroundPlayEnabled,
                                    onToggleStrictAudioLock = viewModel::setStrictAudioFocusLock,
                                    onToggleDataSaver = viewModel::setDataSaverEnabled,
                                    onToggleGestures = viewModel::setGesturesEnabled,
                                    onToggleAppLock = viewModel::setAppLockEnabled,
                                    onToggleBiometric = viewModel::setBiometricUnlockEnabled,
                                    onConfigurePin = viewModel::configurePin,
                                    onClearWebSessions = viewModel::clearAllWebSessions,
                                    onExportBackup = viewModel::exportBackup,
                                    onImportBackup = viewModel::importBackup
                                )

                                AppDestination.HUB -> Unit
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun destinationIcon(destination: AppDestination, selected: Boolean) = when (destination) {
    AppDestination.HUB -> if (selected) Icons.Filled.LiveTv else Icons.Outlined.LiveTv
    AppDestination.EXPLORE -> if (selected) Icons.Filled.Explore else Icons.Outlined.Explore
    AppDestination.PLAYLISTS -> if (selected) Icons.AutoMirrored.Filled.QueueMusic else Icons.Outlined.QueueMusic
    AppDestination.DOWNLOADS -> if (selected) Icons.Filled.Download else Icons.Outlined.Download
    AppDestination.SETTINGS -> if (selected) Icons.Filled.Settings else Icons.Outlined.Settings
}
