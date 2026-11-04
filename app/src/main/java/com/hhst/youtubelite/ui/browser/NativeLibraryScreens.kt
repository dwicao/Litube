package com.hhst.youtubelite.ui.browser

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hhst.youtubelite.nativebrowse.NativeBrowseViewModel
import com.hhst.youtubelite.nativebrowse.NativeSubscription
import com.hhst.youtubelite.nativebrowse.NativeSubscriptionStore
import com.hhst.youtubelite.ui.YoutubeThumb
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

/** Local subscriptions: channels the user followed inside the app. */
@Composable
fun NativeSubscriptionsScreen(
    onPlayVideo: (url: String) -> Unit,
    store: NativeSubscriptionStore = koinInject(),
    browseViewModel: NativeBrowseViewModel = koinViewModel(),
) {
    val subs by store.subscriptions.collectAsStateWithLifecycle()
    val browseState by browseViewModel.state.collectAsStateWithLifecycle()
    if (subs.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                Text("No subscriptions yet", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text("Open a channel from search to subscribe.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(subs, key = { it.channelId }) { sub ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    YoutubeThumb(
                        url = sub.avatarUrl,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp).clip(RoundedCornerShape(24.dp)),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(sub.name, style = MaterialTheme.typography.titleSmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Button(onClick = { browseViewModel.openChannel(sub.channelId) }) {
                        Text("Open")
                    }
                }
            }
        }
    }
}

/** Library: downloads, settings entry points and local state. */
@Composable
fun NativeLibraryScreen(
    onOpenExtension: () -> Unit,
    onOpenDownloads: () -> Unit,
    onAbout: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text("Library", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Button(onClick = onOpenDownloads, modifier = Modifier.fillMaxWidth()) {
                        Text("Downloads")
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onOpenExtension, modifier = Modifier.fillMaxWidth()) {
                        Text("Settings")
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onAbout, modifier = Modifier.fillMaxWidth()) {
                        Text("About")
                    }
                }
            }
        }
    }
}
