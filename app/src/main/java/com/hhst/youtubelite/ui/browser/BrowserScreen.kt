package com.hhst.youtubelite.ui.browser

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.view.SurfaceView
import android.view.View
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hhst.youtubelite.R
import com.hhst.youtubelite.core.HapticsController
import com.hhst.youtubelite.downloader.ui.DownloadUi
import com.hhst.youtubelite.extension.Extension
import com.hhst.youtubelite.extension.ExtensionManager
import com.hhst.youtubelite.extension.PreferenceKeys
import com.hhst.youtubelite.extractor.VideoId
import com.hhst.youtubelite.nativebrowse.NativeBrowseKind
import com.hhst.youtubelite.nativebrowse.NativeBrowseScreen
import com.hhst.youtubelite.nativebrowse.NativeBrowseViewModel
import com.hhst.youtubelite.nativebrowse.NativeCommentsSheet
import com.hhst.youtubelite.nativebrowse.NativeShortsScreen
import com.hhst.youtubelite.nativebrowse.NativeSubscriptionStore
import com.hhst.youtubelite.player.PlayerViewModel
import com.hhst.youtubelite.player.QueueItem
import com.hhst.youtubelite.player.surface.MiniPlayerStore
import com.hhst.youtubelite.player.surface.MiniPlayerWindow
import com.hhst.youtubelite.player.surface.PlayerCallbackBridge
import com.hhst.youtubelite.player.surface.PlayerSurface
import com.hhst.youtubelite.player.surface.PlayerSurfaceCallbacks
import com.hhst.youtubelite.player.surface.PlayerUi
import com.hhst.youtubelite.player.surface.PlayerWindowEffects
import com.hhst.youtubelite.player.surface.PlayerWindowHost
import com.hhst.youtubelite.ui.about.AboutActivity
import com.hhst.youtubelite.ui.extension.ExtensionScreen
import com.hhst.youtubelite.ui.extension.SettingsDataScreen
import kotlinx.coroutines.flow.StateFlow
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

private enum class NativeTab { HOME, SHORTS, SEARCH, SUBSCRIPTIONS, LIBRARY }

/** Native browse shell: bottom tabs over NewPipe-backed lists, player overlay on top. */
@Composable
fun BrowserScreen(
    viewModel: BrowserViewModel = koinViewModel(),
    playerViewModel: PlayerViewModel = koinInject(),
    extensionManager: ExtensionManager = koinInject(),
    miniPlayerStore: MiniPlayerStore = koinInject(),
    subscriptionStore: NativeSubscriptionStore = koinInject(),
    browseViewModel: NativeBrowseViewModel = koinViewModel(),
    sharedUrl: StateFlow<String?>? = null,
    pendingPlaylistUrl: StateFlow<String?>? = null,
    inPipFlow: StateFlow<Boolean>? = null,
    onSharedUrlConsumed: () -> Unit = {},
    onPendingPlaylistConsumed: () -> Unit = {},
    onPlayerActiveChanged: (active: Boolean, playing: Boolean, width: Int, height: Int) -> Unit = { _, _, _, _ -> },
) {
    val playerState by playerViewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }

    var currentTab by remember { mutableStateOf(NativeTab.HOME) }
    var showExtension by remember { mutableStateOf(false) }
    var showSettingsData by remember { mutableStateOf(false) }
    var showComments by remember { mutableStateOf(false) }
    var mediaMenuUrl by remember { mutableStateOf<String?>(null) }
    var inPip by remember { mutableStateOf(inPipFlow?.value == true) }
    LaunchedEffect(inPipFlow) {
        inPipFlow?.collect {
            inPip = it
            if (it) {
                showExtension = false
                showSettingsData = false
                showComments = false
                mediaMenuUrl = null
            }
        }
    }
    val audioManager = remember {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    val onOpenExtension = remember { { showExtension = true } }
    val onOpenDownloads = remember(context) { { DownloadUi.openManager(context) } }
    val onAbout = remember(context) {
        { context.startActivity(Intent(context, AboutActivity::class.java)) }
    }

    LaunchedEffect(sharedUrl) {
        sharedUrl?.collect { url ->
            if (url != null) {
                handleSharedUrl(url, browseViewModel, playerViewModel, { currentTab = it })
                onSharedUrlConsumed()
            }
        }
    }

    val haptics: HapticsController = koinInject()

    val navigateBack: () -> Unit = {
        val ps = playerViewModel.uiState.value
        when (PlayerUi.nextBackStep(ps.locked, ps.fullscreen)) {
            PlayerUi.BackStep.Unlock -> playerViewModel.setLocked(false)
            PlayerUi.BackStep.ExitFullscreen -> playerViewModel.setFullscreen(false)
            PlayerUi.BackStep.BrowserBack -> {
                if (showComments) showComments = false
                else if (mediaMenuUrl != null) mediaMenuUrl = null
                else if (showExtension || showSettingsData) {
                    showExtension = false
                    showSettingsData = false
                } else if (playerState.visible && !playerState.mini) {
                    playerViewModel.onLeaveWatch()
                } else {
                    activity?.finish()
                }
            }
        }
    }
    val handleBack: () -> Unit = navigateBack
    BackHandler(enabled = !showExtension && !showSettingsData) { handleBack() }

    DisposableEffect(playerViewModel, viewModel) {
        playerViewModel.onRestoreWatch = { playerViewModel.onReturnToWatch(it) }
        playerViewModel.onWatchClosed = { }
        viewModel.onWatchSuspended = { playerViewModel.enterMiniPlayer() }
        viewModel.onWatchOpened = { playerViewModel.onReturnToWatch(it) }
        viewModel.onShortsClosed = { playerViewModel.onShortsClosed() }
        viewModel.onShortsOpened = { playerViewModel.onShortsOpened() }
        viewModel.onTabSwitched = { haptics.perform(HapticsController.Event.TAB) }
        onDispose {
            playerViewModel.onRestoreWatch = null
            playerViewModel.onWatchClosed = null
            viewModel.onWatchSuspended = null
            viewModel.onWatchOpened = null
            viewModel.onShortsClosed = null
            viewModel.onShortsOpened = null
            viewModel.onTabSwitched = null
        }
    }
    LaunchedEffect(playerState.visible) { playerViewModel.setVideoVisible(playerState.visible) }
    LaunchedEffect(activity) {
        playerViewModel.initializeCast()
    }

    val windowHost = remember(activity, audioManager, playerViewModel) {
        PlayerWindowHost(
            activity = activity,
            audioManager = audioManager,
            back = { handleBack() },
            pipAvailable = { playerViewModel.uiState.value.pipAvailable },
            sharePayload = {
                val snapshot = playerViewModel.uiState.value
                snapshot.url?.let { it to snapshot.title }
            },
            videoSize = {
                val snapshot = playerViewModel.uiState.value
                snapshot.videoWidth to snapshot.videoHeight
            },
            castLinkUrl = { playerViewModel.currentCastLink() },
            edgeSlider = { volume, percent ->
                playerViewModel.onEdgeSlider(volume, percent)
            },
        )
    }
    val playerCallbacks = remember(playerViewModel, windowHost) {
        PlayerCallbackBridge(playerViewModel, windowHost)
    }
    val onActiveChanged = rememberUpdatedState(onPlayerActiveChanged)

    val immersive = playerState.fullscreen && playerState.visible && !playerState.mini && !inPip
    PlayerWindowEffects(
        activity, windowHost, immersive, playerState.videoWidth, playerState.videoHeight,
    )
    LaunchedEffect(
        playerState.visible, playerState.mini, playerState.isPlaying,
        playerState.loading, playerState.casting,
        playerState.videoWidth, playerState.videoHeight, windowHost,
    ) {
        onActiveChanged.value(
            playerState.visible && !playerState.mini && !playerState.casting,
            playerState.isPlaying || playerState.loading,
            playerState.videoWidth,
            playerState.videoHeight,
        )
        if (!playerState.visible) windowHost.restoreBrightness()
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val layoutDir = LocalLayoutDirection.current
        val leftInsetDp = with(density) {
            WindowInsets.safeDrawing.getLeft(this, layoutDir).toDp().value.toInt()
        }
        val rightInsetDp = with(density) {
            WindowInsets.safeDrawing.getRight(this, layoutDir).toDp().value.toInt()
        }
        val topInsetDp = with(density) { WindowInsets.safeDrawing.getTop(this).toDp().value.toInt() }
        val bottomInsetDp = with(density) { WindowInsets.safeDrawing.getBottom(this).toDp().value.toInt() }
        val availableWidthDp = (maxWidth.value.toInt() - leftInsetDp - rightInsetDp).coerceAtLeast(1)
        val availableHeightDp = (maxHeight.value.toInt() - topInsetDp - bottomInsetDp).coerceAtLeast(1)
        val pageBounds = PlayerUi.embeddedBounds(playerState, availableWidthDp)
        val layoutMini = playerState.visible && !playerState.mini &&
            !playerState.fullscreen && !inPip &&
            PlayerUi.useLandscapeMiniPlayer(availableWidthDp, availableHeightDp, pageBounds.height)
        SideEffect { playerViewModel.setCompactPlayer(layoutMini) }
        val layoutMiniState = rememberUpdatedState(layoutMini)
        val surfaceCallbacks = remember(playerCallbacks, playerViewModel) {
            object : PlayerSurfaceCallbacks by playerCallbacks {
                override fun onMiniRestore() {
                    if (layoutMiniState.value) playerViewModel.onFullscreenToggle()
                    else playerCallbacks.onMiniRestore()
                }
            }
        }
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
            bottomBar = {
                if (!inPip && !playerState.fullscreen) {
                    NavigationBar {
                        NavigationBarItem(
                            selected = currentTab == NativeTab.HOME,
                            onClick = {
                                currentTab = NativeTab.HOME
                                browseViewModel.openHome()
                                haptics.perform(HapticsController.Event.TAB)
                            },
                            icon = { Icon(painterResource(R.drawable.ic_play), null) },
                            label = { Text(stringResource(R.string.app_name)) },
                        )
                        NavigationBarItem(
                            selected = currentTab == NativeTab.SHORTS,
                            onClick = {
                                currentTab = NativeTab.SHORTS
                                playerViewModel.onShortsOpened()
                                haptics.perform(HapticsController.Event.TAB)
                            },
                            icon = { Icon(painterResource(R.drawable.ic_replay), null) },
                            label = { Text("Shorts") },
                        )
                        NavigationBarItem(
                            selected = currentTab == NativeTab.SEARCH,
                            onClick = {
                                currentTab = NativeTab.SEARCH
                                haptics.perform(HapticsController.Event.TAB)
                            },
                            icon = { Icon(painterResource(R.drawable.ic_more), null) },
                            label = { Text("Search") },
                        )
                        NavigationBarItem(
                            selected = currentTab == NativeTab.SUBSCRIPTIONS,
                            onClick = {
                                currentTab = NativeTab.SUBSCRIPTIONS
                                haptics.perform(HapticsController.Event.TAB)
                            },
                            icon = { Icon(painterResource(R.drawable.ic_queue), null) },
                            label = { Text("Subs") },
                        )
                        NavigationBarItem(
                            selected = currentTab == NativeTab.LIBRARY,
                            onClick = {
                                currentTab = NativeTab.LIBRARY
                                haptics.perform(HapticsController.Event.TAB)
                            },
                            icon = { Icon(painterResource(R.drawable.ic_settings), null) },
                            label = { Text("You") },
                        )
                    }
                }
            },
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                when (currentTab) {
                    NativeTab.HOME -> NativeBrowseScreen(
                        viewModel = browseViewModel,
                        onPlayVideo = { url -> playerViewModel.playNativeVideo(url) },
                    )
                    NativeTab.SHORTS -> NativeShortsScreen(
                        viewModel = browseViewModel,
                        onPlayVideo = { url -> playerViewModel.playNativeVideo(url) },
                    )
                    NativeTab.SEARCH -> NativeBrowseScreen(
                        viewModel = browseViewModel,
                        onPlayVideo = { url -> playerViewModel.playNativeVideo(url) },
                    )
                    NativeTab.SUBSCRIPTIONS -> NativeSubscriptionsScreen(
                        onPlayVideo = { url -> playerViewModel.playNativeVideo(url) },
                    )
                    NativeTab.LIBRARY -> NativeLibraryScreen(
                        onOpenExtension = onOpenExtension,
                        onOpenDownloads = onOpenDownloads,
                        onAbout = onAbout,
                    )
                }
            }
        }

        if (playerState.visible) {
            val navigationBottomDp = with(density) {
                WindowInsets.navigationBars.getBottom(this).toDp().value.toInt()
            }
            val isMini = (playerState.mini || layoutMini) && !inPip && !playerState.fullscreen
            val playerSurface: @Composable (Modifier) -> Unit = { surfaceModifier ->
                var modalSurface by remember { mutableStateOf<SurfaceView?>(null) }
                SideEffect { modalSurface?.visibility = View.VISIBLE }
                Box(surfaceModifier) {
                    PlayerSurface(
                        state = if (layoutMini) playerState.copy(mini = true, locked = false) else playerState,
                        callbacks = surfaceCallbacks,
                        onAttachSurface = { modalSurface = it; playerViewModel.attachSurface(it) },
                        onDetachSurface = { if (modalSurface === it) modalSurface = null; playerViewModel.detachSurface(it) },
                        zoneEnabled = playerViewModel::gestureEnabled,
                        pip = inPip,
                        onRefreshCastState = playerViewModel::refreshCastState,
                        positionState = playerViewModel.positionState,
                        bufferedPositionState = playerViewModel.bufferedPositionState,
                        gestureState = playerViewModel.gestureState,
                        hintState = playerViewModel.hintState,
                        subtitleCuesState = playerViewModel.subtitleCuesState,
                        modifier = Modifier.fillMaxSize(),
                        managedByHost = true,
                    )
                }
            }
            MiniPlayerWindow(
                screenWidthDp = availableWidthDp,
                bottomInsetDp = navigationBottomDp,
                store = miniPlayerStore,
                showStroke = playerState.controlsVisible,
                onBackgroundTap = playerViewModel::onToggleControls,
                onDismiss = {
                    playerViewModel.onMiniClose()
                    playerViewModel.onShortsClosed()
                    if (currentTab == NativeTab.SHORTS) currentTab = NativeTab.HOME
                },
                mini = isMini,
                fillsWindow = inPip || playerState.fullscreen,
                fullscreenSwipeEnabled = true,
                embeddedTopDp = PlayerUi.playerTopOffsetDp(false, pageBounds.top, topInsetDp),
                embeddedHeightDp = pageBounds.height,
                embeddedLeftDp = pageBounds.left,
                embeddedWidthDp = pageBounds.width,
                topInsetDp = topInsetDp,
                modifier = (if (inPip || playerState.fullscreen) Modifier else Modifier.padding(start = leftInsetDp.dp, end = rightInsetDp.dp))
                    .graphicsLayer { alpha = 1f },
            ) {
                playerSurface(Modifier.fillMaxSize())
            }
        }

        if (showExtension && !inPip) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing),
            ) {
                ExtensionScreen(
                    onClose = { showExtension = false },
                    onNavigate = { id ->
                        if (id == Extension.NAV_DATA) {
                            showExtension = false
                            showSettingsData = true
                        }
                        if (id == Extension.NAV_DOWNLOADS) {
                            showExtension = false
                            DownloadUi.openManager(context)
                        }
                        if (id == Extension.NAV_NATIVE_BROWSE) {
                            showExtension = false
                            currentTab = NativeTab.HOME
                        }
                    },
                )
            }
        }

        if (showComments && !inPip) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing),
            ) {
                androidx.activity.compose.BackHandler { showComments = false }
                NativeCommentsSheet(
                    viewModel = browseViewModel,
                    onClose = { showComments = false },
                )
            }
        }

        if (showSettingsData && !inPip) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing),
            ) {
                SettingsDataScreen(
                    onClose = { showSettingsData = false },
                    onReloadPages = {
                        showSettingsData = false
                        browseViewModel.openHome()
                    },
                )
            }
        }

        mediaMenuUrl?.let { url ->
            val videoId = VideoId.parse(url)
            if (videoId != null) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { mediaMenuUrl = null },
                    confirmButton = {
                        androidx.compose.material3.TextButton(onClick = {
                            playerViewModel.onQueueAdd(
                                QueueItem(
                                    videoId = videoId,
                                    url = url,
                                    title = videoId,
                                ),
                            )
                            mediaMenuUrl = null
                            Toast.makeText(context, context.getString(R.string.queue_item_added), Toast.LENGTH_SHORT).show()
                        }) { Text("Queue") }
                    },
                    dismissButton = {
                        androidx.compose.material3.TextButton(onClick = { mediaMenuUrl = null }) { Text("Close") }
                    },
                    title = { Text(videoId) },
                    text = { Text(url) },
                )
            } else {
                mediaMenuUrl = null
            }
        }
    }
}

private fun handleSharedUrl(
    url: String,
    browseViewModel: NativeBrowseViewModel,
    playerViewModel: PlayerViewModel,
    setTab: (NativeTab) -> Unit,
) {
    val videoId = VideoId.parse(url)
    if (videoId != null) {
        playerViewModel.playNativeVideo(url)
        return
    }
    if (browseViewModel.openUrl(url)) {
        setTab(NativeTab.SEARCH)
    } else {
        browseViewModel.search(url)
        setTab(NativeTab.SEARCH)
    }
}

private fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

private val ExitHintIntervalMs = 2000L
private var lastExitHintAt = 0L
