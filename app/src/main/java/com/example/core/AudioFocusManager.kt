package com.example.core

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build

/**
 * Strict Audio Focus Lock layer.
 * Holds media playback focus and deliberately ignores transient ducking
 * (AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) from incoming messaging/SMS notifications
 * so playback volume remains 100% stable and uninterrupted until the user pauses.
 */
class StrictAudioFocusManager(
    context: Context,
    private val onPermanentLossRequested: () -> Unit = {}
) {
    private val audioManager =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    @Volatile
    var strictLockEnabled: Boolean = true

    private var focusRequest: AudioFocusRequest? = null
    private var hasFocus: Boolean = false

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // Intentionally ignore ducking when Strict Audio Focus Lock is active
                // so notification chimes never lower volume or stutter audio.
                if (strictLockEnabled) {
                    requestFocus()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                // Keep playing uninterrupted if strict lock is active; re-assert focus
                if (strictLockEnabled) {
                    requestFocus()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                if (!strictLockEnabled) {
                    hasFocus = false
                    onPermanentLossRequested()
                } else {
                    // Maintain active session unless explicitly paused by user
                    requestFocus()
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasFocus = true
            }
        }
    }

    fun requestFocus(): Boolean {
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs)
                .setAcceptsDelayedFocusGain(true)
                .setWillPauseWhenDucked(false)
                .setOnAudioFocusChangeListener(focusChangeListener)
                .build()
            focusRequest = req
            audioManager.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                focusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
        }
        hasFocus = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        return hasFocus
    }

    fun abandonFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(focusChangeListener)
        }
        hasFocus = false
    }
}
