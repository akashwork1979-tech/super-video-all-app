package com.example.ui.components

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.BrightnessHigh
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Edge-mounted gesture overlay for Player Gesture Controls:
 * - Left vertical edge drag: adjust screen brightness smoothly
 * - Right vertical edge drag: adjust STREAM_MUSIC volume smoothly
 * - Double-tap left/right edge: seek -10s / +10s
 *
 * Uses thin edge strips (36.dp) so center scrolling & tapping inside the WebView remain completely unobstructed.
 */
@Composable
fun PlayerGestureEdgeOverlay(
    enabled: Boolean,
    onSeekDelta: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    if (!enabled) return

    val context = LocalContext.current
    val activity = context as? Activity
    val audioManager = remember {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    var hudText by remember { mutableStateOf<String?>(null) }
    var hudMode by remember { mutableStateOf("") } // BRIGHTNESS, VOLUME, SEEK_BACK, SEEK_FWD
    var hudTrigger by remember { mutableIntStateOf(0) }

    var currentBrightness by remember {
        mutableFloatStateOf(
            activity?.window?.attributes?.screenBrightness?.takeIf { it >= 0f } ?: 0.55f
        )
    }

    LaunchedEffect(hudTrigger) {
        if (hudTrigger > 0) {
            delay(1100L)
            hudText = null
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        // Left edge: Brightness drag + Double-tap -10s
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .width(34.dp)
                .fillMaxHeight(0.7f)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = {
                            onSeekDelta(-10)
                            hudMode = "SEEK_BACK"
                            hudText = "-10s"
                            hudTrigger++
                        }
                    )
                }
                .pointerInput(Unit) {
                    detectVerticalDragGestures { _, dragAmount ->
                        val delta = -dragAmount / 450f
                        currentBrightness = (currentBrightness + delta).coerceIn(0.05f, 1.0f)
                        activity?.window?.let { win ->
                            val lp = win.attributes
                            lp.screenBrightness = currentBrightness
                            win.attributes = lp
                        }
                        hudMode = "BRIGHTNESS"
                        hudText = "${(currentBrightness * 100).toInt()}%"
                        hudTrigger++
                    }
                }
        )

        // Right edge: Volume drag + Double-tap +10s
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(34.dp)
                .fillMaxHeight(0.7f)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = {
                            onSeekDelta(10)
                            hudMode = "SEEK_FWD"
                            hudText = "+10s"
                            hudTrigger++
                        }
                    )
                }
                .pointerInput(Unit) {
                    var accumulated = 0f
                    detectVerticalDragGestures { _, dragAmount ->
                        accumulated += dragAmount
                        if (kotlin.math.abs(accumulated) > 28f) {
                            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                            val curVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                            val step = if (accumulated < 0f) 1 else -1
                            val nextVol = (curVol + step).coerceIn(0, maxVol)
                            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, nextVol, 0)
                            accumulated = 0f
                            hudMode = "VOLUME"
                            hudText = "${(nextVol * 100) / maxVol}%"
                            hudTrigger++
                        }
                    }
                }
        )

        // Centered Animated HUD Pill
        AnimatedVisibility(
            visible = hudText != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = Color.Black.copy(alpha = 0.78f),
                tonalElevation = 8.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val icon = when (hudMode) {
                        "BRIGHTNESS" -> Icons.Default.BrightnessHigh
                        "VOLUME" -> Icons.AutoMirrored.Filled.VolumeUp
                        "SEEK_BACK" -> Icons.Default.Replay10
                        else -> Icons.Default.Forward10
                    }
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(26.dp)
                    )
                    Column {
                        Text(
                            text = hudText.orEmpty(),
                            style = MaterialTheme.typography.titleLarge,
                            color = Color.White
                        )
                    }
                }
            }
        }
    }
}
