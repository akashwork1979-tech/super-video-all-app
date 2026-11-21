package com.example.media

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.example.MainActivity
import com.example.OmniStreamApp
import com.example.R
import com.example.core.StrictAudioFocusManager
import com.example.data.RssFeedRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Persistent Android Foreground MediaSessionService backed by Android Media3 (ExoPlayer)
 * and StrictAudioFocusManager.
 *
 * Keeps both direct Media3 streams (.mp3/.mp4/.m3u8) AND WebView background streams alive
 * with lock-screen & notification shade controls (Play, Pause, Next, Previous, Seek, Stop).
 */
class MediaPlaybackService : MediaSessionService() {

    private var exoPlayer: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private var audioFocusManager: StrictAudioFocusManager? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()

        audioFocusManager = StrictAudioFocusManager(
            context = this,
            onPermanentLossRequested = {
                PlaybackController.setPlaying(false)
            }
        )

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        // handleAudioFocus = false so ExoPlayer delegates audio focus strictly
        // to StrictAudioFocusManager (preventing notification ducking/pauses).
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, false)
            .setHandleAudioBecomingNoisy(false)
            .build()

        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    PlaybackController.onCurrentTrackEnded()
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (PlaybackController.state.value.engine == PlaybackEngine.EXOPLAYER) {
                    val current = PlaybackController.state.value
                    PlaybackController.updateNowPlaying(
                        title = current.title,
                        subtitle = current.subtitle,
                        url = current.activeUrl,
                        platformId = current.activePlatformId,
                        isPlaying = isPlaying,
                        engine = PlaybackEngine.EXOPLAYER
                    )
                    updateForegroundNotification()
                }
            }
        })

        exoPlayer = player
        mediaSession = MediaSession.Builder(this, player).build()

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OmniStreamPro::MediaWakeLock")
            .apply { setReferenceCounted(false) }

        observePlaybackState()
        observeCommands()
    }

    private fun observePlaybackState() {
        serviceScope.launch {
            PlaybackController.state.collect { state ->
                if (state.isBackgroundServiceActive) {
                    updateForegroundNotification()
                }
                if (state.isPlaying) {
                    audioFocusManager?.requestFocus()
                    acquireWakeLockSafely()
                } else {
                    releaseWakeLockSafely()
                }
            }
        }
    }

    private fun observeCommands() {
        serviceScope.launch {
            PlaybackController.commands.collect { cmd ->
                val player = exoPlayer ?: return@collect
                val engine = PlaybackController.state.value.engine
                when (cmd) {
                    is MediaControlCommand.Play -> {
                        audioFocusManager?.requestFocus()
                        if (engine == PlaybackEngine.EXOPLAYER) player.play()
                        updateForegroundNotification()
                    }
                    is MediaControlCommand.Pause -> {
                        if (engine == PlaybackEngine.EXOPLAYER) player.pause()
                        updateForegroundNotification()
                    }
                    is MediaControlCommand.TogglePlayPause -> {
                        if (engine == PlaybackEngine.EXOPLAYER) {
                            if (player.isPlaying) player.pause() else player.play()
                        }
                        updateForegroundNotification()
                    }
                    is MediaControlCommand.SeekDelta -> {
                        if (engine == PlaybackEngine.EXOPLAYER) {
                            val nextPos = (player.currentPosition + cmd.deltaSeconds * 1000L)
                                .coerceAtLeast(0L)
                            player.seekTo(nextPos)
                        }
                    }
                    is MediaControlCommand.SetSpeed -> {
                        player.playbackParameters = PlaybackParameters(cmd.speed)
                    }
                    is MediaControlCommand.SetRepeatMode -> {
                        player.repeatMode = when (cmd.mode) {
                            RepeatMode.OFF -> Player.REPEAT_MODE_OFF
                            RepeatMode.REPEAT_ONE -> Player.REPEAT_MODE_ONE
                            RepeatMode.REPEAT_ALL -> Player.REPEAT_MODE_ALL
                        }
                    }
                    is MediaControlCommand.LoadQueueItem -> {
                        if (cmd.item.isDirectStream && RssFeedRepository.isSafeHttpUrl(cmd.item.url)) {
                            val mediaItem = MediaItem.Builder()
                                .setUri(cmd.item.url)
                                .setMediaMetadata(
                                    MediaMetadata.Builder()
                                        .setTitle(cmd.item.title)
                                        .setArtist("OmniStream Pro")
                                        .build()
                                )
                                .build()
                            player.setMediaItem(mediaItem)
                            player.prepare()
                            player.playbackParameters = PlaybackParameters(
                                PlaybackController.state.value.playbackSpeed
                            )
                            audioFocusManager?.requestFocus()
                            player.play()
                        } else {
                            if (player.isPlaying) player.pause()
                        }
                        updateForegroundNotification()
                    }
                    is MediaControlCommand.StopAndDismiss -> {
                        player.stop()
                        audioFocusManager?.abandonFocus()
                        releaseWakeLockSafely()
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                    else -> Unit
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val strictLock = intent?.getBooleanExtra(EXTRA_STRICT_AUDIO_LOCK, true) ?: true
        audioFocusManager?.strictLockEnabled = strictLock

        when (intent?.action) {
            ACTION_START_FOREGROUND -> {
                PlaybackController.setServiceActive(true)
                startAsMediaForeground()
            }
            ACTION_PLAY_PAUSE -> {
                PlaybackController.togglePlayPause()
                updateForegroundNotification()
            }
            ACTION_NEXT -> {
                PlaybackController.playNext()
                updateForegroundNotification()
            }
            ACTION_PREV -> {
                PlaybackController.playPrevious()
                updateForegroundNotification()
            }
            ACTION_SEEK_FORWARD -> {
                PlaybackController.seekBy(10)
            }
            ACTION_STOP -> {
                PlaybackController.stopAll()
            }
            ACTION_PLAY_DIRECT_STREAM -> {
                val url = intent.getStringExtra(EXTRA_STREAM_URL).orEmpty()
                val title = intent.getStringExtra(EXTRA_STREAM_TITLE).orEmpty()
                if (RssFeedRepository.isSafeHttpUrl(url)) {
                    PlaybackController.setServiceActive(true)
                    startAsMediaForeground()
                    exoPlayer?.let { player ->
                        val item = MediaItem.Builder()
                            .setUri(url)
                            .setMediaMetadata(
                                MediaMetadata.Builder()
                                    .setTitle(title.ifBlank { "OmniStream Audio" })
                                    .build()
                            )
                            .build()
                        player.setMediaItem(item)
                        player.prepare()
                        player.playbackParameters = PlaybackParameters(
                            PlaybackController.state.value.playbackSpeed
                        )
                        audioFocusManager?.requestFocus()
                        player.play()
                    }
                    PlaybackController.updateNowPlaying(
                        title = title.ifBlank { "Direct Stream" },
                        subtitle = "Media3 ExoPlayer • Strict Audio Focus",
                        url = url,
                        platformId = "custom",
                        isPlaying = true,
                        engine = PlaybackEngine.EXOPLAYER
                    )
                    updateForegroundNotification()
                }
            }
        }
        return START_STICKY
    }

    private fun startAsMediaForeground() {
        val notification = buildPlaybackNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateForegroundNotification() {
        if (!PlaybackController.state.value.isBackgroundServiceActive) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.notify(NOTIFICATION_ID, buildPlaybackNotification())
    }

    private fun buildPlaybackNotification(): Notification {
        val state = PlaybackController.state.value
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            100,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val prevIntent = serviceActionIntent(ACTION_PREV, 101)
        val playPauseIntent = serviceActionIntent(ACTION_PLAY_PAUSE, 102)
        val nextIntent = serviceActionIntent(ACTION_NEXT, 103)
        val seekIntent = serviceActionIntent(ACTION_SEEK_FORWARD, 104)
        val stopIntent = serviceActionIntent(ACTION_STOP, 105)

        val playPauseIcon = if (state.isPlaying) {
            android.R.drawable.ic_media_pause
        } else {
            android.R.drawable.ic_media_play
        }
        val playPauseTitle = if (state.isPlaying) "Pause" else "Play"

        val speedTag = "${state.playbackSpeed}x"
        val modeTag = if (state.audioOnlyMode) "Audio-Only" else state.activePlatformId.uppercase()

        return NotificationCompat.Builder(this, OmniStreamApp.MEDIA_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(state.title)
            .setContentText("$modeTag • $speedTag • ${state.subtitle}")
            .setContentIntent(contentPendingIntent)
            .setOngoing(state.isPlaying)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(android.R.drawable.ic_media_previous, "Previous", prevIntent)
            .addAction(playPauseIcon, playPauseTitle, playPauseIntent)
            .addAction(android.R.drawable.ic_media_next, "Next", nextIntent)
            .addAction(android.R.drawable.ic_media_ff, "+10s", seekIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
            .build()
    }

    private fun serviceActionIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, MediaPlaybackService::class.java).apply {
            this.action = action
        }
        return PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    private fun acquireWakeLockSafely() {
        try {
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(4 * 60 * 60 * 1000L) // max 4 hours continuous
            }
        } catch (_: Exception) {
        }
    }

    private fun releaseWakeLockSafely() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        PlaybackController.setServiceActive(false)
        releaseWakeLockSafely()
        audioFocusManager?.abandonFocus()
        mediaSession?.release()
        mediaSession = null
        exoPlayer?.release()
        exoPlayer = null
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 7001
        const val ACTION_START_FOREGROUND = "com.example.action.START_FOREGROUND"
        const val ACTION_PLAY_PAUSE = "com.example.action.PLAY_PAUSE"
        const val ACTION_NEXT = "com.example.action.NEXT"
        const val ACTION_PREV = "com.example.action.PREV"
        const val ACTION_SEEK_FORWARD = "com.example.action.SEEK_FORWARD"
        const val ACTION_STOP = "com.example.action.STOP"
        const val ACTION_PLAY_DIRECT_STREAM = "com.example.action.PLAY_DIRECT_STREAM"
        const val EXTRA_STREAM_URL = "extra_stream_url"
        const val EXTRA_STREAM_TITLE = "extra_stream_title"
        const val EXTRA_STRICT_AUDIO_LOCK = "extra_strict_audio_lock"

        fun startForegroundSession(context: Context, strictAudioLock: Boolean) {
            val intent = Intent(context, MediaPlaybackService::class.java).apply {
                action = ACTION_START_FOREGROUND
                putExtra(EXTRA_STRICT_AUDIO_LOCK, strictAudioLock)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun playDirectMedia(
            context: Context,
            url: String,
            title: String,
            strictAudioLock: Boolean
        ) {
            val intent = Intent(context, MediaPlaybackService::class.java).apply {
                action = ACTION_PLAY_DIRECT_STREAM
                putExtra(EXTRA_STREAM_URL, url)
                putExtra(EXTRA_STREAM_TITLE, title)
                putExtra(EXTRA_STRICT_AUDIO_LOCK, strictAudioLock)
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
