package com.hhst.youtubelite.nativebrowse

import java.net.URI
import java.util.Locale

/** Pure YouTube URL routing for the native shell. */
object NativeUrlPolicy {
    const val HOME_URL = "https://m.youtube.com"

    private val allowedHosts = setOf(
        "youtube.com",
        "youtu.be",
        "youtube.googleapis.com",
        "googlevideo.com",
        "ytimg.com",
        "googleusercontent.com",
        "apis.google.com",
        "gstatic.com",
    )

    fun isAllowedUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        return try {
            val uri = URI(url)
            uri.scheme.equals("https", ignoreCase = true) && uri.userInfo == null &&
                uri.port in setOf(-1, 443) && isAllowedHost(uri.host)
        } catch (_: Exception) { false }
    }

    private fun isAllowedHost(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        val normalized = host.lowercase(Locale.ROOT)
        return allowedHosts.any { domain ->
            normalized == domain || normalized.endsWith(".$domain")
        }
    }
}

/** Maps URLs to routing kinds. */
object NativePageKind {
    const val HOME = "home"
    const val SHORTS = "shorts"
    const val WATCH = "watch"
    const val SUBSCRIPTIONS = "subscriptions"
    const val LIBRARY = "library"
    val NAV: Set<String> = setOf(HOME, SHORTS, SUBSCRIPTIONS, LIBRARY)

    fun isPlayerSurface(kind: String?): Boolean = kind == WATCH

    fun isShorts(url: String?): Boolean = of(url) == SHORTS

    fun of(url: String?): String {
        if (url.isNullOrBlank()) return "unknown"
        return try {
            val uri = URI(url)
            val host = uri.host ?: return "unknown"
            val path = uri.path
            val segments = if (path.isNullOrEmpty()) {
                emptyList()
            } else {
                path.split('/').filter { it.isNotEmpty() }
            }
            fromHost(host, segments)
        } catch (_: Exception) {
            "unknown"
        }
    }

    private fun fromHost(host: String, segments: List<String>): String {
        val lowerHost = host.lowercase(Locale.ROOT)
        if (lowerHost == "youtu.be") {
            return if (segments.isEmpty()) "unknown" else WATCH
        }
        if (!isYoutubeHost(lowerHost)) return "unknown"
        if (segments.isEmpty()) return HOME

        val first = segments[0].lowercase(Locale.ROOT)
        if (first.startsWith("@")) return "@"

        return when (first) {
            "shorts" -> SHORTS
            "watch" -> WATCH
            "live" -> WATCH
            "embed" -> WATCH
            "channel" -> "channel"
            "gaming" -> "gaming"
            "select_site" -> "select_site"
            "results" -> "searching"
            "feed" -> if (segments.size > 1) {
                when (segments[1].lowercase(Locale.ROOT)) {
                    "subscriptions" -> SUBSCRIPTIONS
                    "library" -> LIBRARY
                    "history" -> "history"
                    "channels" -> "channels"
                    "playlists" -> "playlists"
                    else -> segments.joinToString("/")
                }
            } else {
                segments.joinToString("/")
            }
            else -> segments.joinToString("/")
        }
    }

    private fun isYoutubeHost(host: String): Boolean =
        host == "youtube.com" || host.endsWith(".youtube.com")
}
