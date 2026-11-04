package com.hhst.youtubelite.player

import android.content.ContextWrapper
import android.view.SurfaceView
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import com.hhst.youtubelite.cast.CastController
import com.hhst.youtubelite.cast.CastUiState
import com.hhst.youtubelite.extension.ExtensionManager
import com.hhst.youtubelite.extension.MemoryPrefStore
import com.hhst.youtubelite.extension.PreferenceKeys
import com.hhst.youtubelite.extractor.VideoId
import com.hhst.youtubelite.player.engine.CastSource
import com.hhst.youtubelite.player.engine.LoopMode
import com.hhst.youtubelite.player.engine.PlaybackApi
import com.hhst.youtubelite.player.engine.PlaybackFailureReport
import com.hhst.youtubelite.player.engine.PlaybackSnapshot
import com.hhst.youtubelite.player.service.PlaybackCommandRouter
import com.hhst.youtubelite.player.surface.GestureMath.GestureZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModelTest {

    private lateinit var engine: FakeEngine
    private lateinit var viewModel: PlayerViewModel
    private lateinit var queue: QueueRepository
    private lateinit var preferences: ExtensionManager
    private lateinit var cast: CastController
    private var previousCommandHandler: PlaybackCommandRouter.Handler? = null

    @Before
    fun setMainDispatcher() {
        previousCommandHandler = PlaybackCommandRouter.handler
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun resetMainDispatcher() {
        if (::viewModel.isInitialized) viewModel.viewModelScope.cancel()
        PlaybackCommandRouter.handler = previousCommandHandler
        Dispatchers.resetMain()
    }

    @Test
    fun gesturePrefKey_picksEmbeddedOrFullscreen() {
        assertEquals(
            PreferenceKeys.GESTURE_TAP_WINDOWED,
            PlayerViewModel.gesturePrefKey(GestureZone.TAP, fullscreen = false),
        )
        assertEquals(
            PreferenceKeys.GESTURE_TAP_FULLSCREEN,
            PlayerViewModel.gesturePrefKey(GestureZone.TAP, fullscreen = true),
        )
        assertEquals(
            PreferenceKeys.GESTURE_SEEK_WINDOWED,
            PlayerViewModel.gesturePrefKey(GestureZone.SEEK, fullscreen = false),
        )
        assertEquals(
            PreferenceKeys.GESTURE_FULLSCREEN_FULLSCREEN,
            PlayerViewModel.gesturePrefKey(GestureZone.FULLSCREEN_SWIPE, fullscreen = true),
        )
    }

    @Test
    fun queueNav_activeQueueOwnsNav() {
        val nav = PlayerViewModel.queueNav(
            queueActive = true,
            queueHasNext = true,
            inQueueAndHasPrevious = false,
        )
        assertEquals(PlayerViewModel.QueueNav(next = true, previous = false), nav)
    }

    @Test
    fun queueNav_activeQueueWithoutNextHasNoNext() {
        val nav = PlayerViewModel.queueNav(
            queueActive = true,
            queueHasNext = false,
            inQueueAndHasPrevious = false,
        )
        assertEquals(PlayerViewModel.QueueNav(next = false, previous = false), nav)
    }

    @Test
    fun queueNav_withoutQueue_hasNoNav() {
        val nav = PlayerViewModel.queueNav(
            queueActive = false,
            queueHasNext = true,
            inQueueAndHasPrevious = true,
        )
        assertEquals(PlayerViewModel.QueueNav(next = false, previous = false), nav)
    }

    @Test
    fun loadSettledFor_whenNewVideoPrepared() {
        val snapshot = PlaybackSnapshot(videoId = "abc", prepared = true)
        assertTrue(PlayerViewModel.loadSettledFor(snapshot, "abc"))
    }

    @Test
    fun castHandoff_neverCastsPreviousVideo() {
        val snapshot = PlaybackSnapshot(videoId = "old", prepared = true)
        assertFalse(PlayerViewModel.loadSettledFor(snapshot, "new"))
    }

    @Test
    fun castHandoff_readyOnExtractionError() {
        val snapshot = PlaybackSnapshot(videoId = "abc", error = "extract failed")
        assertTrue(PlayerViewModel.loadSettledFor(snapshot, "abc"))
    }

    @Test
    fun castHandoff_staleErrorDoesNotSettleNewVideo() {
        val snapshot = PlaybackSnapshot(videoId = "old", error = "extract failed")
        assertFalse(PlayerViewModel.loadSettledFor(snapshot, "new"))
    }

    @Test
    fun castHandoff_neverWithoutVideoId() {
        val snapshot = PlaybackSnapshot(videoId = "abc", prepared = true)
        assertFalse(PlayerViewModel.loadSettledFor(snapshot, null))
    }

    @Test
    fun shouldExpandMiniOnReturn_watchOnly() {
        assertFalse(
            PlayerViewModel.shouldExpandMiniOnReturn(
                "https://m.youtube.com/shorts/aaaaaaaaaaa",
            ),
        )
        assertTrue(
            PlayerViewModel.shouldExpandMiniOnReturn(
                "https://m.youtube.com/watch?v=watchAAAAAA",
            ),
        )
        assertFalse(PlayerViewModel.shouldExpandMiniOnReturn("https://m.youtube.com/"))
    }

    private val watchId = "watchAAAAA1"
    private val shortsId = "shortszzzz1"
    private val watchUrl = "https://www.youtube.com/watch?v=$watchId"
    private val shortsUrl = "https://m.youtube.com/shorts/$shortsId"

    private fun bindViewModel() {
        engine = FakeEngine()
        val cache = MemJsonCache()
        cast = CastController(ContextWrapper(null), OkHttpClient())
        queue = QueueRepository(cache)
        preferences = ExtensionManager(MemoryPrefStore())
        viewModel = PlayerViewModel(
            engine,
            queue,
            preferences,
            cache,
            cast,
        )
    }

    @Test
    fun compactLayout_collapsesOnlyWhenFullscreenOff() {
        bindViewModel()
        viewModel.playNativeVideo(watchUrl)
        engine.settle(watchId, positionMs = 42_000L)
        viewModel.setCompactPlayer(true)
        assertTrue(viewModel.isPlayerCompact())
        assertFalse(viewModel.uiState.value.mini)
        assertEquals(42_000L, engine.snapshot.value.positionMs)
        assertEquals(listOf(watchUrl), engine.playCalls)
        assertTrue(engine.snapshot.value.isPlaying)

        viewModel.setFullscreen(true)
        assertFalse(viewModel.isPlayerCompact())
        viewModel.setFullscreen(false)
        assertTrue(viewModel.isPlayerCompact())
        viewModel.setCompactPlayer(false)
        assertFalse(viewModel.isPlayerCompact())
        assertTrue(engine.snapshot.value.isPlaying)

        viewModel.setCompactPlayer(true)
        viewModel.enterMiniPlayer()
        assertFalse(viewModel.isPlayerCompact())
        viewModel.onMiniClose()
        assertFalse(viewModel.isPlayerCompact())
    }

    @Test
    fun shortsPausesLongVideoPreservesPosition() {
        bindViewModel()
        viewModel.playNativeVideo(watchUrl)
        engine.settle(watchId, positionMs = 42_000L)
        viewModel.onShortsOpened()
        assertFalse(viewModel.uiState.value.visible)
        viewModel.playNativeVideo(shortsUrl)
        assertFalse(viewModel.uiState.value.visible)
        viewModel.onShortsClosed()
        viewModel.onReturnToWatch(shortsUrl)
        assertFalse(viewModel.uiState.value.visible)
        viewModel.onMiniClose()
        viewModel.playNativeVideo(watchUrl)
        engine.settle(watchId, positionMs = 42_000L)
        assertTrue(viewModel.uiState.value.visible)
        assertEquals(42_000L, engine.snapshot.value.positionMs)
    }

    @Test
    fun hidePlayer_hidesCurrentPlayback() {
        bindViewModel()
        viewModel.playNativeVideo(watchUrl)
        engine.settle(watchId)
        viewModel.setPlayerLayout(10, 180)
        assertEquals(180, viewModel.uiState.value.pageHeightDp)
        viewModel.hidePlayer()
        assertFalse(viewModel.uiState.value.visible)
    }

    @Test
    fun onReturnToWatch_shortsCannotRestoreSurface() {
        bindViewModel()
        viewModel.playNativeVideo(watchUrl)
        engine.settle(watchId)
        viewModel.playNativeVideo(shortsUrl)
        assertFalse(viewModel.uiState.value.visible)
        viewModel.onReturnToWatch(shortsUrl)
        assertFalse(viewModel.uiState.value.visible)
    }

    @Test
    fun recastPublishedSource_withoutSessionIsNoOp() {
        bindViewModel()
        engine.onCastManifestReload?.invoke()
        assertTrue(engine.playCalls.isEmpty())
    }

    @Test
    fun onMiniClose_cancelsLongVideoWithoutRestoringIt() {
        bindViewModel()
        viewModel.playNativeVideo(watchUrl)
        engine.settle(watchId)
        viewModel.enterMiniPlayer()
        viewModel.onMiniClose()
        assertFalse(viewModel.uiState.value.visible)
        assertEquals(listOf(watchUrl), engine.playCalls)
    }

    @Test fun pipDismissalStopsLocalPlaybackWithEitherBackgroundPreference() {
        bindViewModel()
        for (enabled in listOf(false, true)) {
            preferences.setEnabled(PreferenceKeys.ENABLE_BACKGROUND_PLAY, enabled)
            viewModel.playNativeVideo(watchUrl)
            engine.settle(watchId)
            viewModel.onPipDismissed()
            assertFalse(viewModel.uiState.value.visible)
            assertFalse(engine.snapshot.value.isPlaying)
        }
        assertEquals(2, engine.stopCalls)
    }

    @Test fun pipDismissalKeepsTheConnectedCastAndOnlyPausesLocalPlayback() {
        bindViewModel()
        viewModel.playNativeVideo(watchUrl)
        engine.settle(watchId)
        @Suppress("UNCHECKED_CAST")
        val state = cast.javaClass.getDeclaredField("_state").apply { isAccessible = true }
            .get(cast) as MutableStateFlow<CastUiState>
        state.value = state.value.copy(linkConnected = true, linkUrl = "http://127.0.0.1/player")
        viewModel.onPipDismissed()
        assertTrue(cast.state.value.linkConnected)
        assertEquals("http://127.0.0.1/player", cast.state.value.linkUrl)
        assertEquals(0, engine.stopCalls)
        assertFalse(engine.snapshot.value.isPlaying)
        assertFalse(viewModel.uiState.value.visible)
    }

    @Test fun queueAdvancePlaysNextQueueItem() {
        bindViewModel()
        queue.setEnabled(true)
        queue.add(QueueItem(watchId, watchUrl, "First"))
        queue.add(QueueItem("bbbbbbbbbbb", VideoId.watchUrl("bbbbbbbbbbb"), "Second"))
        viewModel.playNativeVideo(watchUrl)
        engine.settle(watchId)
        engine.finish()
        assertEquals(VideoId.watchUrl("bbbbbbbbbbb"), engine.playCalls.last())
    }

    private class FakeEngine : PlaybackApi {
        private val _snapshot = MutableStateFlow(PlaybackSnapshot())
        override val snapshot: StateFlow<PlaybackSnapshot> = _snapshot.asStateFlow()
        override val lastFailureReport: PlaybackFailureReport? = null

        val playCalls = mutableListOf<String>()
        var stopCalls = 0

        fun finish() {
            _snapshot.value = _snapshot.value.copy(ended = true, isPlaying = false)
            onEnded?.invoke()
        }

        fun settle(videoId: String, positionMs: Long = 0L) {
            _snapshot.value = _snapshot.value.copy(
                videoId = videoId,
                positionMs = positionMs,
                prepared = true,
                error = null,
                isPlaying = true,
                ended = false,
            )
        }

        override var onEnded: (() -> Unit)? = null
        override var onCastError: (() -> Unit)? = null
        override var onCastManifestReload: (() -> Unit)? = null

        override fun play(urlOrId: String) {
            playCalls += urlOrId
            _snapshot.value = _snapshot.value.copy(
                videoId = VideoId.parse(urlOrId),
                url = urlOrId,
                prepared = false,
                isPlaying = false,
            )
        }
        override fun retry() = Unit
        override fun stop() { stopCalls++; _snapshot.value = PlaybackSnapshot() }
        override fun setVideoSurface(surface: SurfaceView?) = Unit
        override fun playOrPause() = Unit
        override fun play() = Unit
        override fun pause() { _snapshot.value = _snapshot.value.copy(isPlaying = false) }
        override fun cancelPending(pausePlayback: Boolean) {
            _snapshot.value = _snapshot.value.copy(isPlaying = false, prepared = false)
        }

        override fun pauseLocal() {
            _snapshot.value = _snapshot.value.copy(isPlaying = false)
        }

        override fun markReceiverIdentity(videoId: String, title: String, author: String?) = Unit
        override fun seekTo(positionMs: Long) { _snapshot.value = _snapshot.value.copy(positionMs = positionMs, ended = false) }
        override fun seekRelative(offsetMs: Long) = Unit
        override fun setSpeed(speed: Float) = Unit
        override fun setSpeedHold(active: Boolean) = Unit
        override fun setLoopMode(mode: LoopMode) { _snapshot.value = _snapshot.value.copy(loopMode = mode) }
        override fun setQuality(label: String?) = Unit
        override fun setSubtitle(trackKey: String?): Boolean = true
        override fun setAudioTrack(trackKey: String?) = Unit
        override fun currentCastSource(): CastSource? = null
        override fun refreshCastUrl(token: String, videoId: String?, itag: Int?): String? = null
        override fun attachCastPlayer(delegate: Player?): Boolean = false
        override fun setQueueNavigation(hasNext: Boolean, hasPrevious: Boolean) = Unit
        override fun cancelSponsorSkip() = Unit
        override fun skipSponsorNow() = Unit
        override fun skipSponsorChipSegment() = Unit
    }
}
