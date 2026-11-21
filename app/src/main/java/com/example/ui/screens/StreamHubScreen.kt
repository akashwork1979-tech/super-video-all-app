package com.example.ui.screens

import android.view.ViewGroup
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.R
import com.example.core.AppSettings
import com.example.data.PlaylistEntity
import com.example.download.DetectedStream
import com.example.media.MediaControlCommand
import com.example.media.PlaybackController
import com.example.media.PlaybackState
import com.example.media.RepeatMode
import com.example.ui.components.PlayerGestureEdgeOverlay
import com.example.viewmodel.HubUiState
import com.example.web.StreamPlatform
import com.example.web.SuperWebView
import com.example.web.SuperWebViewListener
import kotlinx.coroutines.flow.SharedFlow

@Composable
fun StreamHubScreen(
    hubState: HubUiState,
    settings: AppSettings,
    playbackState: PlaybackState,
    playlists: List<PlaylistEntity>,
    isInPipMode: Boolean,
    reloadTrigger: SharedFlow<Pair<StreamPlatform, String>>,
    onSelectPlatform: (StreamPlatform) -> Unit,
    onUrlChanged: (StreamPlatform, String) -> Unit,
    onTitleChanged: (StreamPlatform, String) -> Unit,
    onLoadingProgress: (Int) -> Unit,
    onAdsBlockedUpdated: (Int) -> Unit,
    onMediaStateUpdate: (StreamPlatform, Boolean, String, String) -> Unit,
    onMediaEnded: () -> Unit,
    onStreamDetected: (DetectedStream) -> Unit,
    onDismissDetectedStream: () -> Unit,
    onDownloadStream: (url: String, title: String, audioOnly: Boolean) -> Unit,
    onToggleIncognito: (StreamPlatform) -> Unit,
    onToggleDesktopUa: (StreamPlatform) -> Unit,
    onSaveToPlaylist: (Long) -> Unit,
    onToggleAudioOnly: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val activePlatform = hubState.activePlatform

    // Isolated pool of SuperWebViews keyed by platform so background playback survives tab switches
    val webViewPool = remember { mutableStateMapOf<StreamPlatform, SuperWebView>() }

    val isGuest = settings.incognitoPlatforms.contains(activePlatform.id)
    val isDesktopUa = settings.desktopUaPlatforms.contains(activePlatform.id) ||
        (activePlatform.defaultDesktopUa && !settings.desktopUaPlatforms.contains("mobile_${activePlatform.id}"))

    var addressText by remember(activePlatform, hubState.currentUrlByPlatform[activePlatform]) {
        mutableStateOf(hubState.currentUrlByPlatform[activePlatform] ?: activePlatform.homeUrl)
    }
    var showSavePlaylistDialog by remember { mutableStateOf(false) }
    var showDownloadChoiceDialog by remember { mutableStateOf<DetectedStream?>(null) }

    fun getOrCreateWebView(platform: StreamPlatform): SuperWebView {
        return webViewPool.getOrPut(platform) {
            val platformGuest = settings.incognitoPlatforms.contains(platform.id)
            val platformDesktop = settings.desktopUaPlatforms.contains(platform.id) ||
                platform.defaultDesktopUa
            SuperWebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                configureStudioDefaults(
                    desktopUa = platformDesktop,
                    guestMode = platformGuest,
                    adBlock = settings.adBlockerEnabled,
                    bgPlay = settings.backgroundPlayEnabled,
                    dataSaver = settings.dataSaverEnabled,
                    audioOnly = playbackState.audioOnlyMode,
                    speed = playbackState.playbackSpeed
                )
                listener = object : SuperWebViewListener {
                    override fun onPageTitleChanged(title: String) {
                        onTitleChanged(platform, title)
                    }

                    override fun onUrlChanged(url: String) {
                        onUrlChanged(platform, url)
                    }

                    override fun onLoadingProgress(progress: Int) {
                        if (hubState.activePlatform == platform) {
                            onLoadingProgress(progress)
                        }
                    }

                    override fun onMediaStateUpdate(isPlaying: Boolean, title: String, url: String) {
                        onMediaStateUpdate(platform, isPlaying, title, url)
                    }

                    override fun onMediaTrackEnded(url: String) {
                        onMediaEnded()
                    }

                    override fun onPlayableStreamDetected(stream: DetectedStream) {
                        onStreamDetected(stream)
                    }

                    override fun onAdsBlockedCountUpdated(count: Int) {
                        onAdsBlockedUpdated(count)
                    }
                }
                loadSafeUrl(hubState.currentUrlByPlatform[platform] ?: platform.homeUrl)
            }
        }
    }

    // Sync runtime setting toggles across active WebViews
    LaunchedEffect(
        settings.adBlockerEnabled,
        settings.backgroundPlayEnabled,
        settings.dataSaverEnabled,
        settings.incognitoPlatforms,
        settings.desktopUaPlatforms
    ) {
        webViewPool.forEach { (platform, wv) ->
            wv.adBlockerEnabled = settings.adBlockerEnabled
            wv.backgroundPlayEnabled = settings.backgroundPlayEnabled
            wv.applyDataSaver(settings.dataSaverEnabled)
            val guest = settings.incognitoPlatforms.contains(platform.id)
            if (wv.isGuestMode != guest) {
                wv.applyCookiePolicy(guest)
            }
            val desktop = settings.desktopUaPlatforms.contains(platform.id) || platform.defaultDesktopUa
            if (wv.isDesktopUa != desktop) {
                wv.applyUserAgent(desktop)
                wv.reload()
            }
        }
    }

    // Listen for explicit URL loads (e.g. from Explore or Playlists)
    LaunchedEffect(Unit) {
        reloadTrigger.collect { (platform, url) ->
            val wv = getOrCreateWebView(platform)
            wv.loadSafeUrl(url)
        }
    }

    // Listen to universal playback commands and inject into active WebView
    LaunchedEffect(Unit) {
        PlaybackController.commands.collect { command ->
            val activeWv = webViewPool[hubState.activePlatform] ?: return@collect
            when (command) {
                is MediaControlCommand.Play -> activeWv.setHtml5Playing(true)
                is MediaControlCommand.Pause -> activeWv.setHtml5Playing(false)
                is MediaControlCommand.SeekDelta -> activeWv.seekHtml5BySeconds(command.deltaSeconds)
                is MediaControlCommand.SetSpeed -> activeWv.setHtml5PlaybackSpeed(command.speed)
                is MediaControlCommand.SetAudioOnly -> activeWv.setHtml5AudioOnly(command.enabled)
                is MediaControlCommand.SetRepeatMode -> {
                    activeWv.setHtml5LoopSingle(command.mode == RepeatMode.REPEAT_ONE)
                }
                is MediaControlCommand.StopAndDismiss -> activeWv.setHtml5Playing(false)
                else -> Unit
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            // Keep WebView instances clean if screen is completely torn down
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        if (!isInPipMode) {
            // Platform Selector Strip
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
            ) {
                items(StreamPlatform.entries, key = { it.id }) { platform ->
                    val selected = platform == activePlatform
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = if (selected) {
                            platform.accentColor.copy(alpha = 0.18f)
                        } else {
                            MaterialTheme.colorScheme.surfaceContainer
                        },
                        border = BorderStroke(
                            width = if (selected) 1.5.dp else 1.dp,
                            color = if (selected) platform.accentColor else MaterialTheme.colorScheme.outlineVariant
                        ),
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { onSelectPlatform(platform) }
                            .testTag("platform_tab_${platform.id}")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(platform.accentColor)
                            )
                            Text(
                                text = platform.shortLabel,
                                style = MaterialTheme.typography.labelLarge,
                                color = if (selected) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                    }
                }
            }

            // Studio Address Bar & Quick Toggles
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        IconButton(
                            onClick = {
                                webViewPool[activePlatform]?.let { wv ->
                                    if (wv.canGoBack()) wv.goBack()
                                }
                            },
                            modifier = Modifier
                                .size(40.dp)
                                .testTag("web_back_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier
                                .weight(1f)
                                .height(40.dp)
                        ) {
                            Box(
                                contentAlignment = Alignment.CenterStart,
                                modifier = Modifier.padding(horizontal = 12.dp)
                            ) {
                                BasicTextField(
                                    value = addressText,
                                    onValueChange = { addressText = it },
                                    singleLine = true,
                                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                                        color = MaterialTheme.colorScheme.onSurface
                                    ),
                                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                                    keyboardActions = KeyboardActions(
                                        onGo = {
                                            focusManager.clearFocus()
                                            getOrCreateWebView(activePlatform).loadSafeUrl(addressText)
                                        }
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("hub_url_input")
                                )
                            }
                        }

                        IconButton(
                            onClick = { webViewPool[activePlatform]?.reload() },
                            modifier = Modifier
                                .size(40.dp)
                                .testTag("web_reload_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Reload",
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        IconButton(
                            onClick = { showSavePlaylistDialog = true },
                            modifier = Modifier
                                .size(40.dp)
                                .testTag("hub_save_playlist_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.PlaylistAdd,
                                contentDescription = stringResource(R.string.hub_save_to_playlist),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // Secondary Micro-Controls: Guest/Logged-In, Desktop/Mobile UA, AdBlock Shield
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        MicroStatusPill(
                            label = if (isGuest) stringResource(R.string.hub_guest_mode) else stringResource(R.string.hub_signed_in_mode),
                            icon = if (isGuest) Icons.Default.VisibilityOff else Icons.Default.Person,
                            active = isGuest,
                            activeColor = MaterialTheme.colorScheme.tertiary,
                            onClick = { onToggleIncognito(activePlatform) },
                            tag = "toggle_guest_mode"
                        )

                        MicroStatusPill(
                            label = if (isDesktopUa) stringResource(R.string.hub_desktop_ua) else stringResource(R.string.hub_mobile_ua),
                            icon = if (isDesktopUa) Icons.Default.Computer else Icons.Default.Smartphone,
                            active = isDesktopUa,
                            activeColor = MaterialTheme.colorScheme.primary,
                            onClick = { onToggleDesktopUa(activePlatform) },
                            tag = "toggle_user_agent"
                        )

                        MicroStatusPill(
                            label = "${hubState.adsBlockedCount} ${stringResource(R.string.hub_ads_blocked)}",
                            icon = Icons.Default.Shield,
                            active = settings.adBlockerEnabled,
                            activeColor = Color(0xFF10B981),
                            onClick = {},
                            tag = "ads_blocked_badge"
                        )
                    }
                }
            }

            if (hubState.loadingProgress in 1..99) {
                LinearProgressIndicator(
                    progress = { hubState.loadingProgress / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.5.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = Color.Transparent
                )
            }
        }

        // WebView Viewport + Audio-Only Overlay + Edge Gestures + Detected Download Pill
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color.Black)
        ) {
            androidx.compose.runtime.key(activePlatform) {
                val targetView = getOrCreateWebView(activePlatform)
                AndroidView(
                    factory = {
                        (targetView.parent as? ViewGroup)?.removeView(targetView)
                        targetView
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Audio-Only Ambient Visual Shield (saves battery/GPU rendering while audio keeps playing)
            androidx.compose.animation.AnimatedVisibility(
                visible = playbackState.audioOnlyMode && !isInPipMode,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.fillMaxSize()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.img_audio_visualizer),
                        contentDescription = stringResource(R.string.hub_audio_only_active),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        Color.Black.copy(alpha = 0.55f),
                                        Color.Black.copy(alpha = 0.85f)
                                    )
                                )
                            )
                    )
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(64.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Headphones,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.hub_audio_only_active),
                            style = MaterialTheme.typography.headlineSmall,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.hub_audio_only_subtitle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.78f)
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                        FilledTonalButton(onClick = onToggleAudioOnly) {
                            Text("Restore Video View")
                        }
                    }
                }
            }

            // Edge Gesture Overlay (Brightness / Volume / Double-Tap Seek)
            if (!isInPipMode) {
                PlayerGestureEdgeOverlay(
                    enabled = settings.gesturesEnabled,
                    onSeekDelta = { delta -> PlaybackController.seekBy(delta) }
                )
            }

            // Floating Detected Playable Stream Download Pill
            androidx.compose.animation.AnimatedVisibility(
                visible = hubState.latestDetectedStream != null && !isInPipMode,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(14.dp)
            ) {
                val detected = hubState.latestDetectedStream
                if (detected != null) {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.secondary,
                        tonalElevation = 8.dp,
                        modifier = Modifier.testTag("floating_download_pill")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.clickable {
                                    showDownloadChoiceDialog = detected
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Download,
                                    contentDescription = stringResource(R.string.hub_detected_stream),
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = "Download ${detected.mediaType}",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = Color.White
                                    )
                                    Text(
                                        text = detected.title,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.White.copy(alpha = 0.85f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.width(140.dp)
                                    )
                                }
                            }
                            IconButton(
                                onClick = onDismissDetectedStream,
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Dismiss detected stream",
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showSavePlaylistDialog) {
        AlertDialog(
            onDismissRequest = { showSavePlaylistDialog = false },
            title = { Text("Save to Cross-Platform Playlist") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (playlists.isEmpty()) {
                        Text(
                            "Create a playlist in the Playlists tab first.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    } else {
                        playlists.forEach { playlist ->
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable {
                                        onSaveToPlaylist(playlist.id)
                                        showSavePlaylistDialog = false
                                    }
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(playlist.name, style = MaterialTheme.typography.titleSmall)
                                    if (playlist.description.isNotBlank()) {
                                        Text(
                                            playlist.description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSavePlaylistDialog = false }) {
                    Text("Close")
                }
            }
        )
    }

    showDownloadChoiceDialog?.let { stream ->
        AlertDialog(
            onDismissRequest = { showDownloadChoiceDialog = null },
            title = { Text("Download Detected Stream") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stream.title,
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = "Destination: /Downloads/OmniStream/",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = stream.url,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDownloadStream(stream.url, stream.title, false)
                        showDownloadChoiceDialog = null
                    }
                ) {
                    Text("Download Video")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = {
                        onDownloadStream(stream.url, stream.title, true)
                        showDownloadChoiceDialog = null
                    }
                ) {
                    Text("Audio Only")
                }
            }
        )
    }
}

@Composable
private fun MicroStatusPill(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    active: Boolean,
    activeColor: Color,
    onClick: () -> Unit,
    tag: String
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (active) activeColor.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(
            width = 1.dp,
            color = if (active) activeColor.copy(alpha = 0.55f) else MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .testTag(tag)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (active) activeColor else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
