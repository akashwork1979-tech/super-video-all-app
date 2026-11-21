package com.example.core

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.ui.theme.AppThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.MessageDigest
import java.security.SecureRandom

private val Context.omniDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "omnistream_pro_prefs"
)

data class AppSettings(
    val themeMode: AppThemeMode = AppThemeMode.AMOLED,
    val adBlockerEnabled: Boolean = true,
    val backgroundPlayEnabled: Boolean = true,
    val strictAudioFocusLock: Boolean = true,
    val dataSaverEnabled: Boolean = false,
    val desktopUserAgentDefault: Boolean = false,
    val gesturesEnabled: Boolean = true,
    val appLockEnabled: Boolean = false,
    val biometricUnlockEnabled: Boolean = true,
    val hasPinConfigured: Boolean = false,
    val defaultPlaybackSpeed: Float = 1.0f,
    val incognitoPlatforms: Set<String> = emptySet(),
    val desktopUaPlatforms: Set<String> = emptySet()
)

class AppPreferences(private val context: Context) {

    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val AD_BLOCKER = booleanPreferencesKey("ad_blocker_enabled")
        val BACKGROUND_PLAY = booleanPreferencesKey("background_play_enabled")
        val STRICT_AUDIO_LOCK = booleanPreferencesKey("strict_audio_focus_lock")
        val DATA_SAVER = booleanPreferencesKey("data_saver_enabled")
        val DESKTOP_UA_DEFAULT = booleanPreferencesKey("desktop_ua_default")
        val GESTURES_ENABLED = booleanPreferencesKey("gestures_enabled")
        val APP_LOCK_ENABLED = booleanPreferencesKey("app_lock_enabled")
        val BIOMETRIC_ENABLED = booleanPreferencesKey("biometric_unlock_enabled")
        val PIN_HASH = stringPreferencesKey("app_lock_pin_hash")
        val PIN_SALT = stringPreferencesKey("app_lock_pin_salt")
        val PLAYBACK_SPEED = floatPreferencesKey("default_playback_speed")
        val INCOGNITO_PLATFORMS = stringSetPreferencesKey("incognito_platforms")
        val DESKTOP_UA_PLATFORMS = stringSetPreferencesKey("desktop_ua_platforms")
    }

    val settingsFlow: Flow<AppSettings> = context.omniDataStore.data.map { prefs ->
        AppSettings(
            themeMode = AppThemeMode.fromKey(prefs[Keys.THEME_MODE]),
            adBlockerEnabled = prefs[Keys.AD_BLOCKER] ?: true,
            backgroundPlayEnabled = prefs[Keys.BACKGROUND_PLAY] ?: true,
            strictAudioFocusLock = prefs[Keys.STRICT_AUDIO_LOCK] ?: true,
            dataSaverEnabled = prefs[Keys.DATA_SAVER] ?: false,
            desktopUserAgentDefault = prefs[Keys.DESKTOP_UA_DEFAULT] ?: false,
            gesturesEnabled = prefs[Keys.GESTURES_ENABLED] ?: true,
            appLockEnabled = prefs[Keys.APP_LOCK_ENABLED] ?: false,
            biometricUnlockEnabled = prefs[Keys.BIOMETRIC_ENABLED] ?: true,
            hasPinConfigured = !prefs[Keys.PIN_HASH].isNullOrBlank(),
            defaultPlaybackSpeed = prefs[Keys.PLAYBACK_SPEED] ?: 1.0f,
            incognitoPlatforms = prefs[Keys.INCOGNITO_PLATFORMS] ?: emptySet(),
            desktopUaPlatforms = prefs[Keys.DESKTOP_UA_PLATFORMS] ?: emptySet()
        )
    }

    suspend fun setThemeMode(mode: AppThemeMode) {
        context.omniDataStore.edit { it[Keys.THEME_MODE] = mode.key }
    }

    suspend fun setAdBlockerEnabled(enabled: Boolean) {
        context.omniDataStore.edit { it[Keys.AD_BLOCKER] = enabled }
    }

    suspend fun setBackgroundPlayEnabled(enabled: Boolean) {
        context.omniDataStore.edit { it[Keys.BACKGROUND_PLAY] = enabled }
    }

    suspend fun setStrictAudioFocusLock(enabled: Boolean) {
        context.omniDataStore.edit { it[Keys.STRICT_AUDIO_LOCK] = enabled }
    }

    suspend fun setDataSaverEnabled(enabled: Boolean) {
        context.omniDataStore.edit { it[Keys.DATA_SAVER] = enabled }
    }

    suspend fun setGesturesEnabled(enabled: Boolean) {
        context.omniDataStore.edit { it[Keys.GESTURES_ENABLED] = enabled }
    }

    suspend fun setAppLockEnabled(enabled: Boolean) {
        context.omniDataStore.edit { it[Keys.APP_LOCK_ENABLED] = enabled }
    }

    suspend fun setBiometricUnlockEnabled(enabled: Boolean) {
        context.omniDataStore.edit { it[Keys.BIOMETRIC_ENABLED] = enabled }
    }

    suspend fun setDefaultPlaybackSpeed(speed: Float) {
        context.omniDataStore.edit { it[Keys.PLAYBACK_SPEED] = speed.coerceIn(0.25f, 3.0f) }
    }

    suspend fun toggleIncognitoForPlatform(platformId: String) {
        context.omniDataStore.edit { prefs ->
            val current = prefs[Keys.INCOGNITO_PLATFORMS]?.toMutableSet() ?: mutableSetOf()
            if (current.contains(platformId)) current.remove(platformId) else current.add(platformId)
            prefs[Keys.INCOGNITO_PLATFORMS] = current
        }
    }

    suspend fun toggleDesktopUaForPlatform(platformId: String) {
        context.omniDataStore.edit { prefs ->
            val current = prefs[Keys.DESKTOP_UA_PLATFORMS]?.toMutableSet() ?: mutableSetOf()
            if (current.contains(platformId)) current.remove(platformId) else current.add(platformId)
            prefs[Keys.DESKTOP_UA_PLATFORMS] = current
        }
    }

    suspend fun configurePin(pin: String) {
        val saltBytes = ByteArray(16)
        SecureRandom().nextBytes(saltBytes)
        val saltHex = saltBytes.joinToString("") { "%02x".format(it) }
        val hash = sha256("$saltHex:$pin")
        context.omniDataStore.edit { prefs ->
            prefs[Keys.PIN_SALT] = saltHex
            prefs[Keys.PIN_HASH] = hash
            prefs[Keys.APP_LOCK_ENABLED] = true
        }
    }

    suspend fun verifyPin(pin: String): Boolean {
        val prefs = context.omniDataStore.data.first()
        val salt = prefs[Keys.PIN_SALT] ?: return false
        val expectedHash = prefs[Keys.PIN_HASH] ?: return false
        val actualHash = sha256("$salt:$pin")
        return constantTimeEquals(actualHash, expectedHash)
    }

    suspend fun applyRestoredSettings(settings: AppSettings) {
        context.omniDataStore.edit { prefs ->
            prefs[Keys.THEME_MODE] = settings.themeMode.key
            prefs[Keys.AD_BLOCKER] = settings.adBlockerEnabled
            prefs[Keys.BACKGROUND_PLAY] = settings.backgroundPlayEnabled
            prefs[Keys.STRICT_AUDIO_LOCK] = settings.strictAudioFocusLock
            prefs[Keys.DATA_SAVER] = settings.dataSaverEnabled
            prefs[Keys.GESTURES_ENABLED] = settings.gesturesEnabled
            prefs[Keys.PLAYBACK_SPEED] = settings.defaultPlaybackSpeed.coerceIn(0.25f, 3.0f)
            prefs[Keys.INCOGNITO_PLATFORMS] = settings.incognitoPlatforms
            prefs[Keys.DESKTOP_UA_PLATFORMS] = settings.desktopUaPlatforms
        }
    }

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) {
            diff = diff or (a[i].code xor b[i].code)
        }
        return diff == 0
    }
}
