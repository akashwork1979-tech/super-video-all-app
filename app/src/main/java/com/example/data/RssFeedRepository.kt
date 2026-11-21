package com.example.data

import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.net.URI
import java.util.concurrent.TimeUnit

class RssFeedRepository(
    private val feedCacheDao: FeedCacheDao
) {
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    suspend fun refreshCategoryFeed(category: CategoryEntity): Result<List<FeedItemCacheEntity>> =
        withContext(Dispatchers.IO) {
            try {
                if (!isSafeHttpUrl(category.rssUrl)) {
                    return@withContext Result.failure(IllegalArgumentException("Unsupported feed URL scheme"))
                }
                val request = Request.Builder()
                    .url(category.rssUrl)
                    .header("User-Agent", "OmniStreamPro/1.0 (Android RSS Aggregator)")
                    .header("Accept", "application/rss+xml, application/atom+xml, application/xml, text/xml")
                    .build()

                val xmlBody = httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext Result.failure(
                            IllegalStateException("Feed HTTP ${response.code}")
                        )
                    }
                    response.body?.string().orEmpty()
                }

                if (xmlBody.isBlank()) {
                    return@withContext Result.failure(IllegalStateException("Empty feed body"))
                }

                val parsed = parseRssOrAtom(
                    xml = xmlBody,
                    categoryId = category.id,
                    fallbackSource = category.titleEnglish
                ).take(25)

                if (parsed.isNotEmpty()) {
                    feedCacheDao.clearCategoryFeed(category.id)
                    feedCacheDao.insertAll(parsed)
                }
                Result.success(parsed)
            } catch (t: Throwable) {
                val cached = feedCacheDao.getFeedSnapshot(category.id)
                if (cached.isNotEmpty()) {
                    Result.success(cached)
                } else {
                    Result.failure(t)
                }
            }
        }

    private fun parseRssOrAtom(
        xml: String,
        categoryId: Long,
        fallbackSource: String
    ): List<FeedItemCacheEntity> {
        val items = mutableListOf<FeedItemCacheEntity>()
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(StringReader(xml))
        }

        var insideItem = false
        var channelTitle = fallbackSource
        var title = ""
        var link = ""
        var description = ""
        var imageUrl = ""
        var publishedAt = ""
        var currentTag = ""

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    val tag = parser.name.lowercase()
                    currentTag = tag
                    if (tag == "item" || tag == "entry") {
                        insideItem = true
                        title = ""
                        link = ""
                        description = ""
                        imageUrl = ""
                        publishedAt = ""
                    } else if (insideItem) {
                        when (tag) {
                            "link" -> {
                                val href = parser.getAttributeValue(null, "href")
                                if (!href.isNullOrBlank() && link.isBlank()) {
                                    link = href.trim()
                                }
                            }
                            "media:thumbnail", "media:content", "enclosure" -> {
                                val urlAttr = parser.getAttributeValue(null, "url")
                                val typeAttr = parser.getAttributeValue(null, "type").orEmpty()
                                if (!urlAttr.isNullOrBlank()) {
                                    if (typeAttr.startsWith("audio/") || typeAttr.startsWith("video/")) {
                                        if (link.isBlank()) link = urlAttr.trim()
                                    } else if (imageUrl.isBlank()) {
                                        imageUrl = urlAttr.trim()
                                    }
                                }
                            }
                        }
                    }
                }

                XmlPullParser.TEXT -> {
                    val text = parser.text?.trim().orEmpty()
                    if (text.isNotEmpty()) {
                        if (!insideItem && currentTag == "title" && channelTitle == fallbackSource) {
                            channelTitle = text.take(40)
                        } else if (insideItem) {
                            when (currentTag) {
                                "title" -> if (title.isBlank()) title = cleanHtml(text)
                                "link" -> if (link.isBlank()) link = text
                                "description", "summary", "media:description", "content:encoded" -> {
                                    if (description.isBlank()) {
                                        if (imageUrl.isBlank()) {
                                            imageUrl = extractFirstImageSrc(text)
                                        }
                                        description = cleanHtml(text).take(220)
                                    }
                                }
                                "pubdate", "published", "updated" -> {
                                    if (publishedAt.isBlank()) publishedAt = text.take(26)
                                }
                            }
                        }
                    }
                }

                XmlPullParser.END_TAG -> {
                    val tag = parser.name.lowercase()
                    if (tag == "item" || tag == "entry") {
                        insideItem = false
                        if (title.isNotBlank() && isSafeHttpUrl(link)) {
                            items.add(
                                FeedItemCacheEntity(
                                    categoryId = categoryId,
                                    title = title,
                                    link = link,
                                    description = description.ifBlank { "Tap to open in Stream Hub or queue in playlist" },
                                    imageUrl = if (isSafeHttpUrl(imageUrl)) imageUrl else "",
                                    sourceName = channelTitle,
                                    publishedAt = publishedAt
                                )
                            )
                        }
                    }
                    currentTag = ""
                }
            }
            eventType = parser.next()
        }
        return items
    }

    private fun cleanHtml(raw: String): String {
        return raw
            .replace(Regex("<[^>]*>"), " ")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&nbsp;", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun extractFirstImageSrc(html: String): String {
        val match = Regex("""<img[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(html)
        return match?.groupValues?.getOrNull(1)?.trim().orEmpty()
    }

    companion object {
        fun isSafeHttpUrl(rawUrl: String?): Boolean {
            if (rawUrl.isNullOrBlank()) return false
            return try {
                val uri = URI(rawUrl.trim())
                val scheme = uri.scheme?.lowercase()
                (scheme == "https" || scheme == "http") && !uri.host.isNullOrBlank()
            } catch (_: Exception) {
                false
            }
        }
    }
}
