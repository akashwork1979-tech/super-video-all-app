package com.example.download

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.data.DownloadRecordEntity
import com.example.data.PlaylistDatabase
import com.example.data.RssFeedRepository
import kotlinx.coroutines.delay
import java.io.File

data class DetectedStream(
    val url: String,
    val title: String,
    val mediaType: String, // "VIDEO", "AUDIO", "HLS"
    val mimeType: String,
    val detectedAtMs: Long = System.currentTimeMillis()
)

object DownloadHelper {

    private const val SUBFOLDER = "OmniStream"
    private const val PROGRESS_WORK_NAME = "omnistream_download_progress_poller"

    fun detectMediaType(url: String): Pair<String, String>? {
        val clean = url.substringBefore('?').substringBefore('#').lowercase()
        return when {
            clean.endsWith(".mp4") || clean.endsWith(".webm") || clean.endsWith(".mkv") ->
                "VIDEO" to "video/mp4"
            clean.endsWith(".mp3") || clean.endsWith(".m4a") || clean.endsWith(".ogg") ||
                clean.endsWith(".aac") || clean.endsWith(".wav") ->
                "AUDIO" to "audio/mpeg"
            clean.endsWith(".m3u8") ->
                "HLS" to "application/vnd.apple.mpegurl"
            else -> null
        }
    }

    fun sanitizeFileName(rawTitle: String, extension: String): String {
        val safeBase = rawTitle
            .replace(Regex("[^a-zA-Z0-9._\\- ]"), "_")
            .replace("..", "_")
            .trim()
            .take(56)
            .ifBlank { "omnistream_media_${System.currentTimeMillis()}" }
        val cleanExt = extension.trimStart('.').lowercase().take(6).ifBlank { "mp4" }
        return "$safeBase.$cleanExt"
    }

    suspend fun enqueueDownload(
        context: Context,
        url: String,
        title: String,
        audioOnly: Boolean
    ): Result<Long> {
        if (!RssFeedRepository.isSafeHttpUrl(url)) {
            return Result.failure(IllegalArgumentException("Only HTTP/HTTPS media URLs are supported"))
        }

        val detected = detectMediaType(url)
        val mimeType = if (audioOnly) "audio/mpeg" else (detected?.second ?: "video/mp4")
        val ext = when {
            audioOnly -> "mp3"
            detected?.first == "HLS" -> "m3u8"
            detected?.first == "AUDIO" -> "mp3"
            else -> "mp4"
        }
        val fileName = sanitizeFileName(title, ext)

        return try {
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle(title.ifBlank { fileName })
                .setDescription("OmniStream Pro • /Downloads/$SUBFOLDER/")
                .setMimeType(mimeType)
                .setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)
                .setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS,
                    "$SUBFOLDER${File.separator}$fileName"
                )

            val systemId = dm.enqueue(request)
            val db = PlaylistDatabase.getInstance(context)
            val recordId = db.downloadDao().insert(
                DownloadRecordEntity(
                    systemDownloadId = systemId,
                    title = title.ifBlank { fileName },
                    sourceUrl = url,
                    fileName = fileName,
                    mimeType = mimeType,
                    isAudioOnly = audioOnly,
                    status = "RUNNING",
                    progressPercent = 0
                )
            )
            scheduleProgressWorker(context)
            Result.success(recordId)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    suspend fun cancelOrPauseDownload(context: Context, record: DownloadRecordEntity) {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        if (record.systemDownloadId > 0L) {
            try {
                dm.remove(record.systemDownloadId)
            } catch (_: Exception) {
            }
        }
        val db = PlaylistDatabase.getInstance(context)
        db.downloadDao().update(
            record.copy(
                systemDownloadId = -1L,
                status = "PAUSED"
            )
        )
    }

    suspend fun retryOrResumeDownload(context: Context, record: DownloadRecordEntity): Result<Long> {
        val db = PlaylistDatabase.getInstance(context)
        db.downloadDao().delete(record)
        return enqueueDownload(
            context = context,
            url = record.sourceUrl,
            title = record.title,
            audioOnly = record.isAudioOnly
        )
    }

    fun openDownloadedFile(context: Context, record: DownloadRecordEntity): Boolean {
        val uriString = record.localUri
        if (uriString.isBlank()) return false
        return try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(uriString), record.mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun scheduleProgressWorker(context: Context) {
        val request = OneTimeWorkRequestBuilder<DownloadProgressWorker>().build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            PROGRESS_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }
}

class DownloadProgressWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val dm = applicationContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val dao = PlaylistDatabase.getInstance(applicationContext).downloadDao()

        repeat(180) {
            val active = dao.getActiveDownloads().filter { it.systemDownloadId > 0L }
            if (active.isEmpty()) return Result.success()

            var anyStillRunning = false
            for (item in active) {
                val query = DownloadManager.Query().setFilterById(item.systemDownloadId)
                dm.query(query)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val statusIdx = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                        val downloadedIdx =
                            cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                        val totalIdx = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                        val uriIdx = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)

                        val sysStatus = if (statusIdx >= 0) cursor.getInt(statusIdx) else 0
                        val downloaded = if (downloadedIdx >= 0) cursor.getLong(downloadedIdx) else 0L
                        val total = if (totalIdx >= 0) cursor.getLong(totalIdx) else 0L
                        val localUri = if (uriIdx >= 0) cursor.getString(uriIdx).orEmpty() else ""
                        val progress = if (total > 0L) ((downloaded * 100L) / total).toInt().coerceIn(0, 100) else 0

                        val mappedStatus = when (sysStatus) {
                            DownloadManager.STATUS_SUCCESSFUL -> "COMPLETED"
                            DownloadManager.STATUS_FAILED -> "FAILED"
                            DownloadManager.STATUS_PAUSED -> "PAUSED"
                            DownloadManager.STATUS_PENDING -> {
                                anyStillRunning = true
                                "PENDING"
                            }
                            DownloadManager.STATUS_RUNNING -> {
                                anyStillRunning = true
                                "RUNNING"
                            }
                            else -> "FAILED"
                        }

                        dao.updateProgress(
                            id = item.id,
                            status = mappedStatus,
                            progress = if (mappedStatus == "COMPLETED") 100 else progress,
                            downloaded = downloaded,
                            total = total,
                            localUri = localUri
                        )
                    } else {
                        dao.updateProgress(
                            id = item.id,
                            status = "CANCELLED",
                            progress = item.progressPercent,
                            downloaded = item.downloadedBytes,
                            total = item.totalBytes,
                            localUri = item.localUri
                        )
                    }
                }
            }

            if (!anyStillRunning) return Result.success()
            delay(1500L)
        }
        return Result.success()
    }
}
