package com.hhst.youtubelite.nativebrowse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hhst.youtubelite.R
import com.hhst.youtubelite.ui.YoutubeThumb
import org.koin.androidx.compose.koinViewModel

@Composable
fun NativeBrowseScreen(
    viewModel: NativeBrowseViewModel = koinViewModel(),
    onPlayVideo: (url: String) -> Unit,
    onOpenChannel: (channelId: String) -> Unit = {},
    onOpenPlaylist: (playlistId: String) -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    Column(Modifier.fillMaxSize()) {
        SearchBar(
            input = state.searchInput,
            onInput = viewModel::updateSearchInput,
            onSearch = viewModel::search,
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (state.isLoading && state.items.isEmpty()) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            } else if (state.error != null && state.items.isEmpty()) {
                ErrorView(state.error.orEmpty(), viewModel::retry, Modifier.align(Alignment.Center))
            } else {
                val items = state.items
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    state.page.title?.let { title ->
                        item(key = "header") {
                            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(8.dp))
                        }
                    }
                    items(items, key = { it.stableKey() }) { item ->
                        when (item) {
                            is NativeBrowseItem.Video -> VideoCard(item.item, onPlayVideo)
                            is NativeBrowseItem.Channel -> ChannelRow(item.item) { onOpenChannel(item.item.channelId) }
                            is NativeBrowseItem.Playlist -> PlaylistCard(item.item) { onOpenPlaylist(item.item.playlistId) }
                        }
                    }
                    if (state.isAppending) {
                        item(key = "appending") {
                            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(Modifier.size(28.dp))
                            }
                        }
                    }
                    if (state.page.nextPage != null && !state.isAppending) {
                        item(key = "loadmore") {
                            LaunchedEffect(state.page.nextPage) { viewModel.loadMore() }
                        }
                    }
                }
            }
        }
    }
}

private val NativeBrowseState.items: List<NativeBrowseItem> get() = page.items

private fun NativeBrowseItem.stableKey(): String = when (this) {
    is NativeBrowseItem.Video -> "v:${item.videoId}"
    is NativeBrowseItem.Channel -> "c:${item.channelId}"
    is NativeBrowseItem.Playlist -> "p:${item.playlistId}"
}

@Composable
private fun SearchBar(input: String, onInput: (String) -> Unit, onSearch: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        TextField(
            value = input,
            onValueChange = onInput,
            modifier = Modifier.weight(1f),
            placeholder = { Text(stringResource(R.string.app_name)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch(input) }),
        )
        Spacer(Modifier.width(8.dp))
        IconButton(onClick = { onSearch(input) }) {
            Icon(painterResource(R.drawable.ic_more), contentDescription = stringResource(R.string.app_name))
        }
    }
}

@Composable
private fun VideoCard(item: NativeVideoItem, onPlay: (String) -> Unit) {
    Card(Modifier.fillMaxWidth().clickable { onPlay(item.url) }) {
        Column(Modifier.padding(8.dp)) {
            YoutubeThumb(
                url = item.thumbnailUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)),
            )
            Spacer(Modifier.height(8.dp))
            Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            item.author?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (item.durationSeconds > 0) {
                Text(formatDuration(item.durationSeconds), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ChannelRow(item: NativeChannelItem, onOpen: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        YoutubeThumb(
            url = item.avatarUrl,
            contentDescription = null,
            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(24.dp)),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            item.subscriberCount?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun PlaylistCard(item: NativePlaylistItem, onOpen: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            YoutubeThumb(
                url = item.thumbnailUrl,
                contentDescription = null,
                modifier = Modifier.size(96.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                item.author?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun ErrorView(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(message, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(12.dp))
        Button(onClick = onRetry) { Text(stringResource(R.string.retry)) }
    }
}

private fun formatDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
