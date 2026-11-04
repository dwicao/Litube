package com.hhst.youtubelite.nativebrowse

import androidx.lifecycle.ViewModel
import com.hhst.youtubelite.extractor.VideoId
import kotlinx.coroutines.flow.StateFlow

class NativeBrowseViewModel(
    repository: NativeBrowseRepository? = null,
) : ViewModel() {
    private val repo = repository ?: NativeBrowseRepository()
    val state: StateFlow<NativeBrowseState> = repo.state

    init {
        repo.load(NativeBrowseKind.TRENDING)
    }

    fun openTrending() = repo.load(NativeBrowseKind.TRENDING)

    fun search(query: String) {
        if (query.isBlank()) return
        repo.load(NativeBrowseKind.SEARCH, query = query)
    }

    fun openChannel(channelIdOrUrl: String) {
        val id = channelIdOrUrl.substringAfterLast("/").substringBefore("?")
        repo.load(NativeBrowseKind.CHANNEL, channelId = id)
    }

    fun openPlaylist(playlistIdOrUrl: String) {
        val id = if (playlistIdOrUrl.contains("list=")) playlistIdOrUrl.substringAfter("list=").substringBefore("&")
        else playlistIdOrUrl
        repo.load(NativeBrowseKind.PLAYLIST, playlistId = id)
    }

    fun openUrl(url: String): Boolean {
        val videoId = VideoId.parse(url)
        if (videoId != null) return false
        return when {
            url.contains("/playlist?list=") -> { openPlaylist(url); true }
            url.contains("/channel/") || url.contains("/@") || url.contains("/c/") || url.contains("/user/") -> {
                openChannel(url); true
            }
            url.contains("search_query=") || url.contains("/results") -> {
                search(url.substringAfter("search_query=").substringBefore("&")); true
            }
            else -> false
        }
    }

    fun loadMore() = repo.loadMore()
    fun retry() = repo.retry()
    fun updateSearchInput(text: String) = repo.updateSearchInput(text)
}
