package com.example.web

import androidx.compose.ui.graphics.Color
import com.example.ui.theme.BrandCustom
import com.example.ui.theme.BrandFacebook
import com.example.ui.theme.BrandInstagram
import com.example.ui.theme.BrandReddit
import com.example.ui.theme.BrandSoundCloud
import com.example.ui.theme.BrandX
import com.example.ui.theme.BrandYouTube

enum class StreamPlatform(
    val id: String,
    val label: String,
    val shortLabel: String,
    val homeUrl: String,
    val accentColor: Color,
    val defaultDesktopUa: Boolean = false
) {
    YOUTUBE(
        id = "youtube",
        label = "YouTube",
        shortLabel = "YouTube",
        homeUrl = "https://m.youtube.com",
        accentColor = BrandYouTube
    ),
    X_TWITTER(
        id = "x",
        label = "X (Twitter)",
        shortLabel = "X",
        homeUrl = "https://x.com/explore",
        accentColor = BrandX
    ),
    FACEBOOK(
        id = "facebook",
        label = "Facebook",
        shortLabel = "Facebook",
        homeUrl = "https://m.facebook.com/watch",
        accentColor = BrandFacebook
    ),
    INSTAGRAM(
        id = "instagram",
        label = "Instagram",
        shortLabel = "Instagram",
        homeUrl = "https://www.instagram.com/reels/",
        accentColor = BrandInstagram
    ),
    REDDIT(
        id = "reddit",
        label = "Reddit",
        shortLabel = "Reddit",
        homeUrl = "https://www.reddit.com/r/popular/",
        accentColor = BrandReddit
    ),
    SOUNDCLOUD(
        id = "soundcloud",
        label = "SoundCloud",
        shortLabel = "SoundCloud",
        homeUrl = "https://soundcloud.com/discover",
        accentColor = BrandSoundCloud,
        defaultDesktopUa = true
    ),
    CUSTOM(
        id = "custom",
        label = "Custom URL",
        shortLabel = "Web",
        homeUrl = "https://duckduckgo.com",
        accentColor = BrandCustom
    );

    companion object {
        const val MOBILE_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 15; Pixel 9 Pro) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.6778.135 Mobile Safari/537.36"

        const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.6778.135 Safari/537.36"

        fun fromId(id: String?): StreamPlatform =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: CUSTOM

        fun detectFromUrl(url: String): StreamPlatform {
            val lower = url.lowercase()
            return when {
                lower.contains("youtube.com") || lower.contains("youtu.be") -> YOUTUBE
                lower.contains("x.com") || lower.contains("twitter.com") -> X_TWITTER
                lower.contains("facebook.com") || lower.contains("fb.watch") -> FACEBOOK
                lower.contains("instagram.com") -> INSTAGRAM
                lower.contains("reddit.com") || lower.contains("redd.it") -> REDDIT
                lower.contains("soundcloud.com") -> SOUNDCLOUD
                else -> CUSTOM
            }
        }
    }
}
