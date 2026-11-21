package com.example.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val titleHindi: String,
    val titleEnglish: String,
    val rssUrl: String,
    val iconKey: String = "rss",
    val accentHex: Long = 0xFF00E5FF,
    val isCustom: Boolean = false,
    val sortOrder: Int = 0
)

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val description: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "playlist_items",
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["playlistId"])]
)
data class PlaylistItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val playlistId: Long,
    val title: String,
    val url: String,
    val platformId: String,
    val thumbnailUrl: String = "",
    val isDirectStream: Boolean = false,
    val orderIndex: Int = 0,
    val addedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "downloads")
data class DownloadRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val systemDownloadId: Long = -1L,
    val title: String,
    val sourceUrl: String,
    val fileName: String,
    val mimeType: String,
    val isAudioOnly: Boolean = false,
    val status: String = "PENDING", // PENDING, RUNNING, PAUSED, COMPLETED, FAILED, CANCELLED
    val progressPercent: Int = 0,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val localUri: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "feed_cache",
    indices = [Index(value = ["categoryId"])]
)
data class FeedItemCacheEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val categoryId: Long,
    val title: String,
    val link: String,
    val description: String,
    val imageUrl: String,
    val sourceName: String,
    val publishedAt: String,
    val cachedAt: Long = System.currentTimeMillis()
)

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories ORDER BY sortOrder ASC, id ASC")
    fun getAllCategories(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories ORDER BY sortOrder ASC, id ASC")
    suspend fun getAllCategoriesSnapshot(): List<CategoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(category: CategoryEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(categories: List<CategoryEntity>)

    @Update
    suspend fun update(category: CategoryEntity)

    @Delete
    suspend fun delete(category: CategoryEntity)

    @Query("DELETE FROM categories WHERE isCustom = 1")
    suspend fun deleteCustomCategories()
}

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY createdAt DESC")
    fun getAllPlaylists(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists ORDER BY createdAt DESC")
    suspend fun getAllPlaylistsSnapshot(): List<PlaylistEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylist(playlist: PlaylistEntity): Long

    @Delete
    suspend fun deletePlaylist(playlist: PlaylistEntity)

    @Query("SELECT * FROM playlist_items WHERE playlistId = :playlistId ORDER BY orderIndex ASC, addedAt ASC")
    fun getItemsForPlaylist(playlistId: Long): Flow<List<PlaylistItemEntity>>

    @Query("SELECT * FROM playlist_items ORDER BY playlistId ASC, orderIndex ASC")
    fun getAllPlaylistItems(): Flow<List<PlaylistItemEntity>>

    @Query("SELECT * FROM playlist_items ORDER BY playlistId ASC, orderIndex ASC")
    suspend fun getAllPlaylistItemsSnapshot(): List<PlaylistItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItem(item: PlaylistItemEntity): Long

    @Delete
    suspend fun deleteItem(item: PlaylistItemEntity)

    @Query("DELETE FROM playlist_items WHERE id = :itemId")
    suspend fun deleteItemById(itemId: Long)

    @Query("DELETE FROM playlists")
    suspend fun clearAllPlaylists()
}

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    fun getAllDownloads(): Flow<List<DownloadRecordEntity>>

    @Query("SELECT * FROM downloads WHERE status IN ('PENDING', 'RUNNING', 'PAUSED')")
    suspend fun getActiveDownloads(): List<DownloadRecordEntity>

    @Query("SELECT * FROM downloads WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): DownloadRecordEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: DownloadRecordEntity): Long

    @Update
    suspend fun update(record: DownloadRecordEntity)

    @Query(
        "UPDATE downloads SET status = :status, progressPercent = :progress, " +
            "downloadedBytes = :downloaded, totalBytes = :total, localUri = :localUri WHERE id = :id"
    )
    suspend fun updateProgress(
        id: Long,
        status: String,
        progress: Int,
        downloaded: Long,
        total: Long,
        localUri: String
    )

    @Delete
    suspend fun delete(record: DownloadRecordEntity)
}

@Dao
interface FeedCacheDao {
    @Query("SELECT * FROM feed_cache WHERE categoryId = :categoryId ORDER BY id ASC")
    fun getFeedForCategory(categoryId: Long): Flow<List<FeedItemCacheEntity>>

    @Query("SELECT * FROM feed_cache WHERE categoryId = :categoryId ORDER BY id ASC")
    suspend fun getFeedSnapshot(categoryId: Long): List<FeedItemCacheEntity>

    @Query("DELETE FROM feed_cache WHERE categoryId = :categoryId")
    suspend fun clearCategoryFeed(categoryId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<FeedItemCacheEntity>)
}

@Database(
    entities = [
        CategoryEntity::class,
        PlaylistEntity::class,
        PlaylistItemEntity::class,
        DownloadRecordEntity::class,
        FeedItemCacheEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class PlaylistDatabase : RoomDatabase() {
    abstract fun categoryDao(): CategoryDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun downloadDao(): DownloadDao
    abstract fun feedCacheDao(): FeedCacheDao

    companion object {
        @Volatile
        private var INSTANCE: PlaylistDatabase? = null

        fun getInstance(context: Context): PlaylistDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context.applicationContext).also { INSTANCE = it }
            }
        }

        private fun buildDatabase(appContext: Context): PlaylistDatabase {
            return Room.databaseBuilder(
                appContext,
                PlaylistDatabase::class.java,
                "omnistream_pro_db"
            )
                .addCallback(object : Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        super.onCreate(db)
                        CoroutineScope(Dispatchers.IO).launch {
                            seedInitialData(getInstance(appContext))
                        }
                    }
                })
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
        }

        suspend fun seedInitialData(database: PlaylistDatabase) {
            val existing = database.categoryDao().getAllCategoriesSnapshot()
            if (existing.isEmpty()) {
                database.categoryDao().insertAll(defaultCategories())
            }
            val playlists = database.playlistDao().getAllPlaylistsSnapshot()
            if (playlists.isEmpty()) {
                val starterId = database.playlistDao().insertPlaylist(
                    PlaylistEntity(
                        name = "Daily Mix • Cross-Platform",
                        description = "Queue YouTube, SoundCloud, X & public audio streams seamlessly"
                    )
                )
                database.playlistDao().insertItem(
                    PlaylistItemEntity(
                        playlistId = starterId,
                        title = "YouTube Trending Studio",
                        url = "https://m.youtube.com/feed/trending",
                        platformId = "youtube",
                        isDirectStream = false,
                        orderIndex = 0
                    )
                )
                database.playlistDao().insertItem(
                    PlaylistItemEntity(
                        playlistId = starterId,
                        title = "SoundCloud Discover Charts",
                        url = "https://soundcloud.com/discover",
                        platformId = "soundcloud",
                        isDirectStream = false,
                        orderIndex = 1
                    )
                )
            }
        }

        fun defaultCategories(): List<CategoryEntity> = listOf(
            CategoryEntity(
                titleHindi = "ताज़ा समाचार",
                titleEnglish = "Breaking News",
                rssUrl = "https://feeds.bbci.co.uk/hindi/rss.xml",
                iconKey = "news",
                accentHex = 0xFFFF4D6D,
                sortOrder = 0
            ),
            CategoryEntity(
                titleHindi = "मनोरंजन जगत",
                titleEnglish = "Entertainment",
                rssUrl = "https://www.bollywoodhungama.com/rss/news.xml",
                iconKey = "entertainment",
                accentHex = 0xFFE1306C,
                sortOrder = 1
            ),
            CategoryEntity(
                titleHindi = "तकनीकी जगत",
                titleEnglish = "Technology",
                rssUrl = "https://feeds.feedburner.com/TechCrunch/",
                iconKey = "tech",
                accentHex = 0xFF00E5FF,
                sortOrder = 2
            ),
            CategoryEntity(
                titleHindi = "व्यक्ति विशेष",
                titleEnglish = "Biographies / Interviews",
                rssUrl = "https://feeds.npr.org/1001/rss.xml",
                iconKey = "biography",
                accentHex = 0xFF9D7BFF,
                sortOrder = 3
            ),
            CategoryEntity(
                titleHindi = "खेल",
                titleEnglish = "Sports",
                rssUrl = "https://feeds.bbci.co.uk/sport/rss.xml",
                iconKey = "sports",
                accentHex = 0xFF10B981,
                sortOrder = 4
            ),
            CategoryEntity(
                titleHindi = "राजनीति",
                titleEnglish = "Politics",
                rssUrl = "https://www.thehindu.com/news/national/feeder/default.rss",
                iconKey = "politics",
                accentHex = 0xFFF59E0B,
                sortOrder = 5
            ),
            CategoryEntity(
                titleHindi = "भक्ति और संगीत",
                titleEnglish = "Music & Devotional",
                rssUrl = "https://www.youtube.com/feeds/videos.xml?channel_id=UC-9-kyTW8ZkZNDHQJ6FgpwQ",
                iconKey = "music",
                accentHex = 0xFFFF7A00,
                sortOrder = 6
            )
        )
    }
}
