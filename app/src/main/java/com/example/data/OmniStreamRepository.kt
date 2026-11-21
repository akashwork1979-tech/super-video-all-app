package com.example.data

import kotlinx.coroutines.flow.Flow

class OmniStreamRepository(
    private val database: PlaylistDatabase
) {
    private val categoryDao = database.categoryDao()
    private val playlistDao = database.playlistDao()
    private val downloadDao = database.downloadDao()
    private val feedCacheDao = database.feedCacheDao()
    private val rssRepository = RssFeedRepository(feedCacheDao)

    val categories: Flow<List<CategoryEntity>> = categoryDao.getAllCategories()
    val playlists: Flow<List<PlaylistEntity>> = playlistDao.getAllPlaylists()
    val allPlaylistItems: Flow<List<PlaylistItemEntity>> = playlistDao.getAllPlaylistItems()
    val downloads: Flow<List<DownloadRecordEntity>> = downloadDao.getAllDownloads()

    fun getItemsForPlaylist(playlistId: Long): Flow<List<PlaylistItemEntity>> =
        playlistDao.getItemsForPlaylist(playlistId)

    fun getCachedFeedForCategory(categoryId: Long): Flow<List<FeedItemCacheEntity>> =
        feedCacheDao.getFeedForCategory(categoryId)

    suspend fun ensureSeeded() {
        PlaylistDatabase.seedInitialData(database)
    }

    suspend fun refreshFeed(category: CategoryEntity): Result<List<FeedItemCacheEntity>> =
        rssRepository.refreshCategoryFeed(category)

    suspend fun addCategory(
        titleHindi: String,
        titleEnglish: String,
        rssUrl: String,
        accentHex: Long = 0xFF00E5FF
    ): Long {
        val currentCount = categoryDao.getAllCategoriesSnapshot().size
        return categoryDao.insert(
            CategoryEntity(
                titleHindi = titleHindi.trim(),
                titleEnglish = titleEnglish.trim(),
                rssUrl = rssUrl.trim(),
                iconKey = "custom",
                accentHex = accentHex,
                isCustom = true,
                sortOrder = currentCount
            )
        )
    }

    suspend fun updateCategory(category: CategoryEntity) {
        categoryDao.update(category)
    }

    suspend fun deleteCategory(category: CategoryEntity) {
        categoryDao.delete(category)
    }

    suspend fun createPlaylist(name: String, description: String): Long =
        playlistDao.insertPlaylist(
            PlaylistEntity(name = name.trim(), description = description.trim())
        )

    suspend fun deletePlaylist(playlist: PlaylistEntity) {
        playlistDao.deletePlaylist(playlist)
    }

    suspend fun addItemToPlaylist(
        playlistId: Long,
        title: String,
        url: String,
        platformId: String,
        thumbnailUrl: String = "",
        isDirectStream: Boolean = false
    ): Long {
        val currentItems = playlistDao.getAllPlaylistItemsSnapshot()
            .filter { it.playlistId == playlistId }
        return playlistDao.insertItem(
            PlaylistItemEntity(
                playlistId = playlistId,
                title = title.trim().ifBlank { url },
                url = url.trim(),
                platformId = platformId,
                thumbnailUrl = thumbnailUrl,
                isDirectStream = isDirectStream,
                orderIndex = currentItems.size
            )
        )
    }

    suspend fun removePlaylistItem(itemId: Long) {
        playlistDao.deleteItemById(itemId)
    }

    suspend fun getAllCategoriesSnapshot(): List<CategoryEntity> =
        categoryDao.getAllCategoriesSnapshot()

    suspend fun getAllPlaylistsSnapshot(): List<PlaylistEntity> =
        playlistDao.getAllPlaylistsSnapshot()

    suspend fun getAllPlaylistItemsSnapshot(): List<PlaylistItemEntity> =
        playlistDao.getAllPlaylistItemsSnapshot()

    suspend fun replaceCustomDataFromBackup(
        customCategories: List<CategoryEntity>,
        playlistsWithItems: List<Pair<PlaylistEntity, List<PlaylistItemEntity>>>
    ) {
        categoryDao.deleteCustomCategories()
        for (cat in customCategories) {
            if (RssFeedRepository.isSafeHttpUrl(cat.rssUrl)) {
                categoryDao.insert(cat.copy(id = 0L, isCustom = true))
            }
        }
        if (playlistsWithItems.isNotEmpty()) {
            playlistDao.clearAllPlaylists()
            for ((playlist, items) in playlistsWithItems) {
                val newPlaylistId = playlistDao.insertPlaylist(playlist.copy(id = 0L))
                items.forEachIndexed { idx, item ->
                    if (RssFeedRepository.isSafeHttpUrl(item.url)) {
                        playlistDao.insertItem(
                            item.copy(
                                id = 0L,
                                playlistId = newPlaylistId,
                                orderIndex = idx
                            )
                        )
                    }
                }
            }
        }
    }

    suspend fun insertDownloadRecord(record: DownloadRecordEntity): Long =
        downloadDao.insert(record)

    suspend fun updateDownloadRecord(record: DownloadRecordEntity) {
        downloadDao.update(record)
    }

    suspend fun deleteDownloadRecord(record: DownloadRecordEntity) {
        downloadDao.delete(record)
    }
}
