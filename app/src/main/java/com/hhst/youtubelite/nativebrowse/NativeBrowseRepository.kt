package com.hhst.youtubelite.nativebrowse

import com.hhst.youtubelite.diagnostics.DiagnosticContext
import com.hhst.youtubelite.extractor.HttpDownloader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.ListExtractor
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.comments.CommentsInfoItem
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType

class NativeBrowseRepository(
    private val downloader: HttpDownloader,
) {
    init {
        runCatching { org.schabi.newpipe.extractor.NewPipe.init(downloader) }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val youtube = ServiceList.YouTube

    private val _state = MutableStateFlow(NativeBrowseState(isLoading = true))
    val state: StateFlow<NativeBrowseState> = _state.asStateFlow()

    private val _commentsState = MutableStateFlow(NativeCommentsState())
    val commentsState: StateFlow<NativeCommentsState> = _commentsState.asStateFlow()

    private var continuation: Page? = null
    private var activeExtractor: ListExtractor<out InfoItem>? = null
    private var commentsExtractor: org.schabi.newpipe.extractor.comments.CommentsExtractor? = null
    private var commentsContinuation: Page? = null

    fun load(kind: NativeBrowseKind, query: String = "", channelId: String? = null, playlistId: String? = null) {
        continuation = null
        activeExtractor = null
        _state.update {
            it.copy(kind = kind, query = query, channelId = channelId, playlistId = playlistId,
                page = NativeBrowsePage(), isLoading = true, isAppending = false, error = null)
        }
        scope.launch(Dispatchers.IO) {
            val result = runCatching { fetchFirst(kind, query, channelId, playlistId) }
            withContext(Dispatchers.Main) {
                result.onSuccess { (items, next, title) ->
                    _state.update { it.copy(page = NativeBrowsePage(items, next?.url, title), isLoading = false) }
                }.onFailure { failure ->
                    _state.update { it.copy(isLoading = false, error = failure.message ?: "Load failed") }
                }
            }
        }
    }

    fun loadMore() {
        val current = _state.value
        if (current.isLoading || current.isAppending || current.page.nextPage == null) return
        _state.update { it.copy(isAppending = true, error = null) }
        scope.launch(Dispatchers.IO) {
            val result = runCatching { fetchMore() }
            withContext(Dispatchers.Main) {
                result.onSuccess { (items, next) ->
                    _state.update {
                        it.copy(page = it.page.copy(items = it.page.items + items, nextPage = next?.url), isAppending = false)
                    }
                }.onFailure { failure ->
                    _state.update { it.copy(isAppending = false, error = failure.message ?: "Load more failed") }
                }
            }
        }
    }

    fun retry() {
        val current = _state.value
        if (current.page.items.isEmpty()) load(current.kind, current.query, current.channelId, current.playlistId)
        else loadMore()
    }

    fun updateSearchInput(text: String) {
        _state.update { it.copy(searchInput = text) }
    }

    fun loadComments(videoId: String) {
        commentsExtractor = null
        commentsContinuation = null
        _commentsState.value = NativeCommentsState(videoId = videoId, isLoading = true)
        scope.launch(Dispatchers.IO) {
            val result = runCatching {
                val extractor = youtube.getCommentsExtractor("https://www.youtube.com/watch?v=$videoId")
                    ?: throw java.io.IOException("Comments not supported")
                extractor.fetchPage()
                commentsExtractor = extractor
                val initial = extractor.initialPage
                commentsContinuation = initial.nextPage
                initial.items.map { it.toNativeComment() } to initial.nextPage
            }
            withContext(Dispatchers.Main) {
                result.onSuccess { (items, next) ->
                    _commentsState.update { it.copy(items = items, nextPage = next?.url, isLoading = false) }
                }.onFailure { failure ->
                    val disabled = failure.message?.contains("disabled", ignoreCase = true) == true
                    _commentsState.update { it.copy(isLoading = false, error = failure.message, disabled = disabled) }
                }
            }
        }
    }

    fun loadMoreComments() {
        val current = _commentsState.value
        if (current.isLoading || current.isAppending || current.nextPage == null) return
        _commentsState.update { it.copy(isAppending = true) }
        scope.launch(Dispatchers.IO) {
            val result = runCatching {
                val extractor = commentsExtractor ?: throw java.io.IOException("No comments session")
                val page = commentsContinuation ?: throw java.io.IOException("No more pages")
                val next = extractor.getPage(page)
                commentsContinuation = next.nextPage
                next.items.map { it.toNativeComment() } to next.nextPage
            }
            withContext(Dispatchers.Main) {
                result.onSuccess { (items, next) ->
                    _commentsState.update { it.copy(items = it.items + items, nextPage = next?.url, isAppending = false) }
                }.onFailure { failure ->
                    _commentsState.update { it.copy(isAppending = false, error = failure.message) }
                }
            }
        }
    }

    fun clearComments() {
        commentsExtractor = null
        commentsContinuation = null
        _commentsState.value = NativeCommentsState()
    }

    private data class FetchResult(val items: List<NativeBrowseItem>, val next: Page?, val title: String? = null)

    private fun fetchFirst(kind: NativeBrowseKind, query: String, channelId: String?, playlistId: String?): FetchResult {
        return when (kind) {
            NativeBrowseKind.SEARCH -> {
                val extractor = youtube.getSearchExtractor(query)
                extractor.fetchPage()
                activeExtractor = extractor
                val initial = extractor.initialPage
                continuation = initial.nextPage
                FetchResult(initial.items.mapNotNull { item -> item.toNative() }, continuation, null)
            }
            NativeBrowseKind.TRENDING, NativeBrowseKind.HOME -> {
                val extractor = runCatching {
                    youtube.kioskList.getExtractorById("Trending", null)
                }.getOrNull() ?: youtube.kioskList.defaultKioskExtractor
                extractor.fetchPage()
                val initial = extractor.initialPage
                continuation = initial.nextPage
                FetchResult(initial.items.mapNotNull { item -> (item as InfoItem).toNative() }, continuation,
                    if (kind == NativeBrowseKind.HOME) null else runCatching { extractor.name }.getOrNull())
            }
            NativeBrowseKind.SUBSCRIPTIONS, NativeBrowseKind.LIBRARY -> {
                FetchResult(emptyList(), null, null)
            }
            NativeBrowseKind.CHANNEL -> {
                val id = channelId ?: return FetchResult(emptyList(), null, null)
                val extractor = youtube.getChannelExtractor("https://www.youtube.com/channel/$id")
                extractor.fetchPage()
                activeExtractor = null
                val displayName = runCatching { extractor.name }.getOrElse { id }
                val headerItems = buildList {
                    add(NativeBrowseItem.Channel(NativeChannelItem(
                        channelId = id,
                        name = displayName,
                        avatarUrl = runCatching { extractor.avatars }.getOrNull()?.lastOrNull()?.url,
                        subscriberCount = runCatching { extractor.subscriberCount }.getOrNull()
                            ?.takeIf { it >= 0 }?.toString(),
                    )))
                }
                FetchResult(headerItems, null, displayName)
            }
            NativeBrowseKind.PLAYLIST -> {
                val id = playlistId ?: return FetchResult(emptyList(), null, null)
                val extractor = youtube.getPlaylistExtractor("https://www.youtube.com/playlist?list=$id")
                extractor.fetchPage()
                activeExtractor = extractor
                val initial = extractor.initialPage
                continuation = initial.nextPage
                FetchResult(initial.items.mapNotNull { it.toNative() }, continuation,
                    runCatching { extractor.name }.getOrNull())
            }
        }
    }

    private fun fetchMore(): FetchResult {
        val extractor = activeExtractor ?: return FetchResult(emptyList(), null)
        val page = continuation ?: return FetchResult(emptyList(), null)
        val infoItemsPage = extractor.getPage(page)
        continuation = infoItemsPage.nextPage
        return FetchResult(infoItemsPage.items.mapNotNull { it.toNative() }, continuation)
    }

    private fun InfoItem.toNative(): NativeBrowseItem? {
        return when (this) {
            is StreamInfoItem -> {
                val streamUrl = url
                val parsedId = com.hhst.youtubelite.extractor.VideoId.parse(streamUrl) ?: streamUrl
                NativeBrowseItem.Video(NativeVideoItem(
                    videoId = parsedId,
                    title = name,
                    author = uploaderName,
                    durationSeconds = duration,
                    viewCount = viewCount,
                    thumbnailUrl = thumbnails.lastOrNull()?.url,
                    url = com.hhst.youtubelite.extractor.VideoId.watchUrl(
                        com.hhst.youtubelite.extractor.VideoId.parse(streamUrl) ?: parsedId,
                    ),
                    isLive = streamType == StreamType.LIVE_STREAM || streamType == StreamType.AUDIO_LIVE_STREAM,
                    isShort = streamUrl.contains("/shorts/") || isShortFormContent,
                ))
            }
            is ChannelInfoItem -> NativeBrowseItem.Channel(NativeChannelItem(
                channelId = url.substringAfterLast("/"),
                name = name,
                avatarUrl = thumbnails.lastOrNull()?.url,
                subscriberCount = subscriberCount.takeIf { it >= 0 }?.toString(),
                url = url,
            ))
            is PlaylistInfoItem -> NativeBrowseItem.Playlist(NativePlaylistItem(
                playlistId = if (url.contains("list=")) url.substringAfter("list=").substringBefore("&") else url,
                title = name,
                author = uploaderName,
                videoCount = streamCount,
                thumbnailUrl = thumbnails.lastOrNull()?.url,
                url = url,
            ))
            else -> null
        }
    }

    private fun CommentsInfoItem.toNativeComment(): NativeComment = NativeComment(
        commentId = commentId.orEmpty().ifBlank { url },
        author = uploaderName.orEmpty(),
        avatarUrl = uploaderAvatars.lastOrNull()?.url,
        text = commentText.content.orEmpty(),
        likeCount = likeCount,
        publishedText = textualUploadDate,
        pinned = isPinned,
        authorVerified = isUploaderVerified,
    )

    fun diagnosticContext(): DiagnosticContext = DiagnosticContext()
}
