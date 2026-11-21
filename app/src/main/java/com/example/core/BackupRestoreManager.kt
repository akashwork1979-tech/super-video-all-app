package com.example.core

import android.content.Context
import android.net.Uri
import com.example.data.CategoryEntity
import com.example.data.OmniStreamRepository
import com.example.data.PlaylistEntity
import com.example.data.PlaylistItemEntity
import com.example.ui.theme.AppThemeMode
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

@JsonClass(generateAdapter = true)
data class BackupCategoryDto(
    val titleHindi: String,
    val titleEnglish: String,
    val rssUrl: String,
    val accentHex: Long,
    val sortOrder: Int
)

@JsonClass(generateAdapter = true)
data class BackupPlaylistItemDto(
    val title: String,
    val url: String,
    val platformId: String,
    val thumbnailUrl: String = "",
    val isDirectStream: Boolean = false
)

@JsonClass(generateAdapter = true)
data class BackupPlaylistDto(
    val name: String,
    val description: String = "",
    val items: List<BackupPlaylistItemDto> = emptyList()
)

@JsonClass(generateAdapter = true)
data class BackupSettingsDto(
    val themeMode: String = "amoled",
    val adBlockerEnabled: Boolean = true,
    val backgroundPlayEnabled: Boolean = true,
    val strictAudioFocusLock: Boolean = true,
    val dataSaverEnabled: Boolean = false,
    val gesturesEnabled: Boolean = true,
    val defaultPlaybackSpeed: Float = 1.0f,
    val incognitoPlatforms: List<String> = emptyList(),
    val desktopUaPlatforms: List<String> = emptyList()
)

@JsonClass(generateAdapter = true)
data class OmniStreamBackupPayload(
    val schemaVersion: Int = 1,
    val exportedAtEpochMs: Long = System.currentTimeMillis(),
    val customCategories: List<BackupCategoryDto> = emptyList(),
    val playlists: List<BackupPlaylistDto> = emptyList(),
    val settings: BackupSettingsDto = BackupSettingsDto()
)

class BackupRestoreManager(
    private val context: Context,
    private val repository: OmniStreamRepository,
    private val preferences: AppPreferences
) {
    private val moshi: Moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val adapter = moshi.adapter(OmniStreamBackupPayload::class.java).indent("  ")

    suspend fun exportToUri(destinationUri: Uri): Result<String> = withContext(Dispatchers.IO) {
        try {
            val categories = repository.getAllCategoriesSnapshot().filter { it.isCustom }
            val playlists = repository.getAllPlaylistsSnapshot()
            val allItems = repository.getAllPlaylistItemsSnapshot().groupBy { it.playlistId }
            val settings = preferences.settingsFlow.first()

            val payload = OmniStreamBackupPayload(
                schemaVersion = 1,
                exportedAtEpochMs = System.currentTimeMillis(),
                customCategories = categories.map {
                    BackupCategoryDto(
                        titleHindi = it.titleHindi,
                        titleEnglish = it.titleEnglish,
                        rssUrl = it.rssUrl,
                        accentHex = it.accentHex,
                        sortOrder = it.sortOrder
                    )
                },
                playlists = playlists.map { pl ->
                    BackupPlaylistDto(
                        name = pl.name,
                        description = pl.description,
                        items = allItems[pl.id].orEmpty().map { item ->
                            BackupPlaylistItemDto(
                                title = item.title,
                                url = item.url,
                                platformId = item.platformId,
                                thumbnailUrl = item.thumbnailUrl,
                                isDirectStream = item.isDirectStream
                            )
                        }
                    )
                },
                settings = BackupSettingsDto(
                    themeMode = settings.themeMode.key,
                    adBlockerEnabled = settings.adBlockerEnabled,
                    backgroundPlayEnabled = settings.backgroundPlayEnabled,
                    strictAudioFocusLock = settings.strictAudioFocusLock,
                    dataSaverEnabled = settings.dataSaverEnabled,
                    gesturesEnabled = settings.gesturesEnabled,
                    defaultPlaybackSpeed = settings.defaultPlaybackSpeed,
                    incognitoPlatforms = settings.incognitoPlatforms.toList(),
                    desktopUaPlatforms = settings.desktopUaPlatforms.toList()
                )
            )

            val json = adapter.toJson(payload)
            context.contentResolver.openOutputStream(destinationUri)?.use { out ->
                out.write(json.toByteArray(Charsets.UTF_8))
            } ?: return@withContext Result.failure(IllegalStateException("Unable to open output stream"))

            Result.success(
                "Exported ${payload.playlists.size} playlists & ${payload.customCategories.size} custom categories"
            )
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    suspend fun importFromUri(sourceUri: Uri): Result<String> = withContext(Dispatchers.IO) {
        try {
            val json = context.contentResolver.openInputStream(sourceUri)?.use { input ->
                // Guard against overly huge untrusted backup files (> 5 MB)
                val bytes = input.readBytes()
                if (bytes.size > 5 * 1024 * 1024) {
                    return@withContext Result.failure(IllegalArgumentException("Backup file exceeds 5 MB limit"))
                }
                String(bytes, Charsets.UTF_8)
            } ?: return@withContext Result.failure(IllegalStateException("Unable to read backup file"))

            val payload = adapter.fromJson(json)
                ?: return@withContext Result.failure(IllegalArgumentException("Invalid JSON backup format"))

            val customCategories = payload.customCategories.map {
                CategoryEntity(
                    titleHindi = it.titleHindi.take(60),
                    titleEnglish = it.titleEnglish.take(60),
                    rssUrl = it.rssUrl,
                    iconKey = "custom",
                    accentHex = it.accentHex,
                    isCustom = true,
                    sortOrder = it.sortOrder
                )
            }

            val playlistsWithItems = payload.playlists.map { pl ->
                val playlistEntity = PlaylistEntity(
                    name = pl.name.take(80),
                    description = pl.description.take(200)
                )
                val items = pl.items.mapIndexed { idx, item ->
                    PlaylistItemEntity(
                        playlistId = 0L,
                        title = item.title.take(120),
                        url = item.url,
                        platformId = item.platformId.take(32),
                        thumbnailUrl = item.thumbnailUrl,
                        isDirectStream = item.isDirectStream,
                        orderIndex = idx
                    )
                }
                playlistEntity to items
            }

            repository.replaceCustomDataFromBackup(customCategories, playlistsWithItems)

            preferences.applyRestoredSettings(
                AppSettings(
                    themeMode = AppThemeMode.fromKey(payload.settings.themeMode),
                    adBlockerEnabled = payload.settings.adBlockerEnabled,
                    backgroundPlayEnabled = payload.settings.backgroundPlayEnabled,
                    strictAudioFocusLock = payload.settings.strictAudioFocusLock,
                    dataSaverEnabled = payload.settings.dataSaverEnabled,
                    gesturesEnabled = payload.settings.gesturesEnabled,
                    defaultPlaybackSpeed = payload.settings.defaultPlaybackSpeed,
                    incognitoPlatforms = payload.settings.incognitoPlatforms.toSet(),
                    desktopUaPlatforms = payload.settings.desktopUaPlatforms.toSet()
                )
            )

            Result.success(
                "Restored ${playlistsWithItems.size} playlists, ${customCategories.size} categories & settings"
            )
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }
}
