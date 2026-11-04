package com.hhst.youtubelite.nativebrowse

data class NativeVideoItem(
    val videoId: String,
    val title: String,
    val author: String? = null,
    val durationSeconds: Long = 0L,
    val viewCount: Long = -1L,
    val thumbnailUrl: String? = null,
    val url: String = "https://m.youtube.com/watch?v=$videoId",
    val isLive: Boolean = false,
    val isShort: Boolean = false,
)

data class NativeChannelItem(
    val channelId: String,
    val name: String,
    val avatarUrl: String? = null,
    val subscriberCount: String? = null,
    val url: String = "https://m.youtube.com/channel/$channelId",
)

data class NativePlaylistItem(
    val playlistId: String,
    val title: String,
    val author: String? = null,
    val videoCount: Long = -1L,
    val thumbnailUrl: String? = null,
    val url: String = "https://m.youtube.com/playlist?list=$playlistId",
)

sealed interface NativeBrowseItem {
    data class Video(val item: NativeVideoItem) : NativeBrowseItem
    data class Channel(val item: NativeChannelItem) : NativeBrowseItem
    data class Playlist(val item: NativePlaylistItem) : NativeBrowseItem
}

data class NativeBrowsePage(
    val items: List<NativeBrowseItem> = emptyList(),
    val nextPage: String? = null,
    val title: String? = null,
)

enum class NativeBrowseKind {
    HOME, TRENDING, SEARCH, CHANNEL, PLAYLIST, SUBSCRIPTIONS, LIBRARY
}

data class NativeComment(
    val commentId: String,
    val author: String,
    val avatarUrl: String? = null,
    val text: String,
    val likeCount: Int = -1,
    val publishedText: String? = null,
    val pinned: Boolean = false,
    val authorVerified: Boolean = false,
)

data class NativeCommentsState(
    val videoId: String = "",
    val items: List<NativeComment> = emptyList(),
    val nextPage: String? = null,
    val isLoading: Boolean = false,
    val isAppending: Boolean = false,
    val error: String? = null,
    val disabled: Boolean = false,
)

data class NativeSubscription(
    val channelId: String,
    val name: String,
    val avatarUrl: String? = null,
)

data class NativeBrowseState(
    val kind: NativeBrowseKind = NativeBrowseKind.HOME,
    val query: String = "",
    val channelId: String? = null,
    val playlistId: String? = null,
    val page: NativeBrowsePage = NativeBrowsePage(),
    val isLoading: Boolean = false,
    val isAppending: Boolean = false,
    val error: String? = null,
    val searchInput: String = "",
)
