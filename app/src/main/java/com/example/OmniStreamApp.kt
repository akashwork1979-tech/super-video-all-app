package com.example

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.example.core.AppPreferences
import com.example.core.BackupRestoreManager
import com.example.data.OmniStreamRepository
import com.example.data.PlaylistDatabase

class OmniStreamApp : Application() {

    lateinit var database: PlaylistDatabase
        private set

    lateinit var repository: OmniStreamRepository
        private set

    lateinit var preferences: AppPreferences
        private set

    lateinit var backupRestoreManager: BackupRestoreManager
        private set

    override fun onCreate() {
        super.onCreate()
        database = PlaylistDatabase.getInstance(this)
        repository = OmniStreamRepository(database)
        preferences = AppPreferences(this)
        backupRestoreManager = BackupRestoreManager(this, repository, preferences)
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            val mediaChannel = NotificationChannel(
                MEDIA_CHANNEL_ID,
                "OmniStream Background Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Persistent media playback controls for audio and video streams"
                setShowBadge(false)
            }
            nm.createNotificationChannel(mediaChannel)
        }
    }

    companion object {
        const val MEDIA_CHANNEL_ID = "omnistream_media_playback_channel"
    }
}
