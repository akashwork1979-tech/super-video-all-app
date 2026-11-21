package com.example.media

import com.example.data.PlaylistItemEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class RepeatMode(val label: String) {
    OFF("Off"),
    REPEAT_ONE("Repeat One"),
    REPEAT_ALL("Auto-Play All")
}

enum class SleepTimerOption(val label: String, val minutes: Int?) {
    OFF("Off", null),
    MIN_15("15 min", 15),
    MIN_30("30 min", 30),
    MIN_45("45 min", 45),
    MIN_60("60 min", 60),
    END_OF_TRACK("End of track", -1)
}

enum class PlaybackEngine {
    WEBVIEW,
    EXOPLAYER
}

data class PlaybackState(
    val title: String = "OmniStream Ready",
    val subtitle: String = "Select a platform or playlist to start streaming",
    val activeUrl: String = "",
    val activePlatformId: String = "youtube",
    val isPlaying: Boolean = false,
    val isBackgroundServiceActive: Boolean = false,
    val engine: PlaybackEngine = PlaybackEngine.WEBVIEW,
    val playbackSpeed: Float = 1.0f,
    val audioOnlyMode: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.REPEAT_ALL,
    val sleepTimerOption: SleepTimerOption = SleepTimerOption.OFF,
    val sleepTimerRemainingSeconds: Int = 0,
    val queue: List<PlaylistItemEntity> = emptyList(),
    val currentQueueIndex: Int = -1
)

sealed interface MediaControlCommand {
    data object Play : MediaControlCommand
    data object Pause : MediaControlCommand
    data object TogglePlayPause : MediaControlCommand
    data object Next : MediaControlCommand
    data object Previous : MediaControlCommand
    data class SeekDelta(val deltaSeconds: Int) : MediaControlCommand
    data class LoadQueueItem(val item: PlaylistItemEntity, val index: Int) : MediaControlCommand
    data class SetSpeed(val speed: Float) : MediaControlCommand
    data class SetAudioOnly(val enabled: Boolean) : MediaControlCommand
    data class SetRepeatMode(val mode: RepeatMode) : MediaControlCommand
    data object StopAndDismiss : MediaControlCommand
}

/**
 * Central singleton media playback coordinator bridging:
 * - SuperWebView HTML5 media events
 * - Media3 ExoPlayer native direct-stream playback
 * - MediaPlaybackService foreground notification controls
 * - Cross-platform playlist Queue Engine & Sleep Timer
 */
object PlaybackController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val _commands = MutableSharedFlow<MediaControlCommand>(extraBufferCapacity = 32)
    val commands: SharedFlow<MediaControlCommand> = _commands.asSharedFlow()

    private var sleepTimerJob: Job? = null

    fun updateNowPlaying(
        title: String,
        subtitle: String,
        url: String,
        platformId: String,
        isPlaying: Boolean,
        engine: PlaybackEngine = _state.value.engine
    ) {
        _state.update {
            it.copy(
                title = title.ifBlank { it.title },
                subtitle = subtitle.ifBlank { it.subtitle },
                activeUrl = url.ifBlank { it.activeUrl },
                activePlatformId = platformId,
                isPlaying = isPlaying,
                engine = engine
            )
        }
    }

    fun setServiceActive(active: Boolean) {
        _state.update { it.copy(isBackgroundServiceActive = active) }
    }

    fun setPlaying(playing: Boolean) {
        _state.update { it.copy(isPlaying = playing) }
        _commands.tryEmit(if (playing) MediaControlCommand.Play else MediaControlCommand.Pause)
    }

    fun togglePlayPause() {
        val nextState = !_state.value.isPlaying
        _state.update { it.copy(isPlaying = nextState) }
        _commands.tryEmit(if (nextState) MediaControlCommand.Play else MediaControlCommand.Pause)
    }

    fun seekBy(deltaSeconds: Int) {
        _commands.tryEmit(MediaControlCommand.SeekDelta(deltaSeconds))
    }

    fun setPlaybackSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.25f, 3.0f)
        _state.update { it.copy(playbackSpeed = clamped) }
        _commands.tryEmit(MediaControlCommand.SetSpeed(clamped))
    }

    fun setAudioOnlyMode(enabled: Boolean) {
        _state.update { it.copy(audioOnlyMode = enabled) }
        _commands.tryEmit(MediaControlCommand.SetAudioOnly(enabled))
    }

    fun cycleRepeatMode() {
        val next = when (_state.value.repeatMode) {
            RepeatMode.OFF -> RepeatMode.REPEAT_ONE
            RepeatMode.REPEAT_ONE -> RepeatMode.REPEAT_ALL
            RepeatMode.REPEAT_ALL -> RepeatMode.OFF
        }
        _state.update { it.copy(repeatMode = next) }
        _commands.tryEmit(MediaControlCommand.SetRepeatMode(next))
    }

    fun setQueueAndPlay(items: List<PlaylistItemEntity>, startIndex: Int = 0) {
        if (items.isEmpty()) return
        val safeIdx = startIndex.coerceIn(0, items.lastIndex)
        val item = items[safeIdx]
        val engine = if (item.isDirectStream) PlaybackEngine.EXOPLAYER else PlaybackEngine.WEBVIEW
        _state.update {
            it.copy(
                queue = items,
                currentQueueIndex = safeIdx,
                title = item.title,
                subtitle = "Queue ${safeIdx + 1}/${items.size} • ${item.platformId.uppercase()}",
                activeUrl = item.url,
                activePlatformId = item.platformId,
                isPlaying = true,
                engine = engine
            )
        }
        _commands.tryEmit(MediaControlCommand.LoadQueueItem(item, safeIdx))
    }

    fun playNext(): Boolean {
        val current = _state.value
        if (current.queue.isEmpty()) {
            _commands.tryEmit(MediaControlCommand.Next)
            return false
        }
        val nextIndex = current.currentQueueIndex + 1
        val targetIndex = when {
            nextIndex < current.queue.size -> nextIndex
            current.repeatMode == RepeatMode.REPEAT_ALL -> 0
            else -> return false
        }
        val item = current.queue[targetIndex]
        val engine = if (item.isDirectStream) PlaybackEngine.EXOPLAYER else PlaybackEngine.WEBVIEW
        _state.update {
            it.copy(
                currentQueueIndex = targetIndex,
                title = item.title,
                subtitle = "Queue ${targetIndex + 1}/${current.queue.size} • ${item.platformId.uppercase()}",
                activeUrl = item.url,
                activePlatformId = item.platformId,
                isPlaying = true,
                engine = engine
            )
        }
        _commands.tryEmit(MediaControlCommand.LoadQueueItem(item, targetIndex))
        return true
    }

    fun playPrevious(): Boolean {
        val current = _state.value
        if (current.queue.isEmpty()) {
            _commands.tryEmit(MediaControlCommand.Previous)
            return false
        }
        val prevIndex = (current.currentQueueIndex - 1).coerceAtLeast(0)
        val item = current.queue[prevIndex]
        val engine = if (item.isDirectStream) PlaybackEngine.EXOPLAYER else PlaybackEngine.WEBVIEW
        _state.update {
            it.copy(
                currentQueueIndex = prevIndex,
                title = item.title,
                subtitle = "Queue ${prevIndex + 1}/${current.queue.size} • ${item.platformId.uppercase()}",
                activeUrl = item.url,
                activePlatformId = item.platformId,
                isPlaying = true,
                engine = engine
            )
        }
        _commands.tryEmit(MediaControlCommand.LoadQueueItem(item, prevIndex))
        return true
    }

    fun onCurrentTrackEnded() {
        val current = _state.value
        if (current.sleepTimerOption == SleepTimerOption.END_OF_TRACK) {
            cancelSleepTimer()
            setPlaying(false)
            return
        }
        when (current.repeatMode) {
            RepeatMode.REPEAT_ONE -> {
                if (current.queue.isNotEmpty() && current.currentQueueIndex in current.queue.indices) {
                    val item = current.queue[current.currentQueueIndex]
                    _commands.tryEmit(MediaControlCommand.LoadQueueItem(item, current.currentQueueIndex))
                } else {
                    _commands.tryEmit(MediaControlCommand.SeekDelta(-99999))
                    _commands.tryEmit(MediaControlCommand.Play)
                }
            }
            RepeatMode.REPEAT_ALL -> {
                playNext()
            }
            RepeatMode.OFF -> {
                if (current.queue.isNotEmpty() && current.currentQueueIndex + 1 < current.queue.size) {
                    playNext()
                } else {
                    setPlaying(false)
                }
            }
        }
    }

    fun setSleepTimer(option: SleepTimerOption) {
        sleepTimerJob?.cancel()
        if (option == SleepTimerOption.OFF) {
            _state.update {
                it.copy(
                    sleepTimerOption = SleepTimerOption.OFF,
                    sleepTimerRemainingSeconds = 0
                )
            }
            return
        }
        if (option == SleepTimerOption.END_OF_TRACK) {
            _state.update {
                it.copy(
                    sleepTimerOption = SleepTimerOption.END_OF_TRACK,
                    sleepTimerRemainingSeconds = -1
                )
            }
            return
        }

        val totalSeconds = (option.minutes ?: 0) * 60
        _state.update {
            it.copy(
                sleepTimerOption = option,
                sleepTimerRemainingSeconds = totalSeconds
            )
        }

        sleepTimerJob = scope.launch {
            var remaining = totalSeconds
            while (remaining > 0) {
                delay(1000L)
                remaining -= 1
                _state.update { it.copy(sleepTimerRemainingSeconds = remaining) }
            }
            setPlaying(false)
            _state.update {
                it.copy(
                    sleepTimerOption = SleepTimerOption.OFF,
                    sleepTimerRemainingSeconds = 0
                )
            }
        }
    }

    fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        _state.update {
            it.copy(
                sleepTimerOption = SleepTimerOption.OFF,
                sleepTimerRemainingSeconds = 0
            )
        }
    }

    fun stopAll() {
        cancelSleepTimer()
        _state.update { it.copy(isPlaying = false, isBackgroundServiceActive = false) }
        _commands.tryEmit(MediaControlCommand.StopAndDismiss)
    }

    val speedOptions: List<Float> = listOf(0.25f, 0.5f, 1.0f, 1.25f, 1.5f, 2.0f, 3.0f)
}
