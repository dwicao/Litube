package com.hhst.youtubelite.ui.browser

import androidx.lifecycle.ViewModel
import com.hhst.youtubelite.nativebrowse.NativePageKind as PageKind
import com.hhst.youtubelite.browser.Tab
import com.hhst.youtubelite.browser.TabController
import com.hhst.youtubelite.browser.TabState
import com.hhst.youtubelite.nativebrowse.NativeUrlPolicy as UrlPolicy
import com.hhst.youtubelite.core.Constants
import com.hhst.youtubelite.diagnostics.AppLog
import com.hhst.youtubelite.extension.ExtensionManager
import com.hhst.youtubelite.extension.PreferenceKeys
import com.hhst.youtubelite.extractor.VideoId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Native tab routing. Back: sheet -> mini/player -> pop -> exit. */
class BrowserViewModel(
    private val extensionManager: ExtensionManager,
) : ViewModel() {

    private val tabs = TabController()

    /** Player surface is showing; wired by the screen. */
    var isPlayerShowing: () -> Boolean = { false }

    /** Watch playback active; the mini-player should suspend instead of hiding. */
    var onWatchSuspended: (() -> Unit)? = null

    /** A watch target opened; show its native surface. */
    var onWatchOpened: ((url: String?) -> Unit)? = null

    /** Shorts feed left; release playback ownership. */
    var onShortsClosed: (() -> Unit)? = null
    var onShortsOpened: (() -> Unit)? = null
    var onTabSwitched: (() -> Unit)? = null
    var onPageBack: (() -> Unit)? = null

    private val _uiState = MutableStateFlow(BrowserUiState.from(tabs.state()))
    val uiState: StateFlow<BrowserUiState> = _uiState.asStateFlow()

    /** Active-tab history back; always false in native mode. */
    var canGoBack: Boolean
        get() = false
        set(_) { }

    fun openTab(url: String) {
        if (!UrlPolicy.isAllowedUrl(url)) return
        val kind = PageKind.of(url)
        if (kind == "unknown") return

        val previousActive = _uiState.value.activeId
        val suspendWatch = shouldSuspendWatch(targetKind = kind)
        val (state, _) = tabs.openTab(url, suspendWatch)
        if (suspendWatch) onWatchSuspended?.invoke()
        if (PageKind.isPlayerSurface(kind)) onWatchOpened?.invoke(url)
        val switched = state.activeId != previousActive
        publish(state, switched = switched, forward = if (switched) true else null)
    }

    /** System Back: suspend watch -> pop tab -> finish. */
    fun onBack(): BackResult {
        if (shouldSuspendWatch()) {
            val state = tabs.suspendActiveWatch() ?: return BackResult.Finish
            onWatchSuspended?.invoke()
            publish(state, switched = true, forward = false)
            return BackResult.Handled
        }
        if (PageKind.isShorts(_uiState.value.url)) {
            onShortsClosed?.invoke()
            val state = tabs.pop() ?: return BackResult.Finish
            publish(state, switched = true, forward = false)
            if (PageKind.isPlayerSurface(PageKind.of(state.active?.url)))
                onWatchOpened?.invoke(state.active?.url)
            return BackResult.Handled
        }
        if (tabs.canPop()) {
            val state = tabs.pop() ?: return BackResult.Finish
            when {
                PageKind.isPlayerSurface(state.active?.kind) ->
                    onWatchOpened?.invoke(state.active?.url)
            }
            publish(state, switched = true, forward = false)
            return BackResult.Handled
        }
        return BackResult.Finish
    }

    /** Mini-player restore: revive the suspended watch tab as-is. */
    fun restoreWatchTab(playerUrl: String) {
        val state = tabs.restoreSuspendedWatch()
        if (state == null) {
            openTab(playerUrl)
            return
        }
        onWatchOpened?.invoke(playerUrl)
        publish(state, switched = true, forward = true)
    }

    fun closeWatchTab() {
        val suspended = uiState.value.suspendedWatchId != null
        val state = when {
            suspended -> tabs.dropSuspendedWatch()
            PageKind.of(uiState.value.url) == Constants.PAGE_WATCH -> tabs.pop() ?: return
            else -> return
        }
        publish(state, switched = !suspended, forward = if (suspended) null else false)
    }

    /** True when leaving the active watch tab should suspend it. */
    private fun shouldSuspendWatch(targetKind: String? = null): Boolean {
        val active = _uiState.value.tabs.find { it.id == _uiState.value.activeId } ?: return false
        if (active.kind != Constants.PAGE_WATCH) return false
        if (targetKind == Constants.PAGE_SHORTS || PageKind.isPlayerSurface(targetKind)) return false
        if (!extensionManager.isEnabled(PreferenceKeys.ENABLE_IN_APP_MINI_PLAYER)) return false
        return isPlayerShowing()
    }

    private fun publish(
        state: TabState,
        switched: Boolean,
        forward: Boolean? = null,
    ) {
        if (switched) onTabSwitched?.invoke()
        _uiState.update {
            it.copy(
                tabs = state.tabs,
                activeId = state.activeId,
                url = state.active?.url ?: it.url,
                isLoading = false,
                progress = 0f,
                isRefreshing = false,
                canGoBack = false,
                canPop = state.tabs.size > 1,
                suspendedWatchId = state.suspendedWatchId,
                forward = forward ?: it.forward,
            )
        }
    }
}

/** Outcome of [BrowserViewModel.onBack] for the host to apply. */
enum class BackResult {
    /** Tab popped, or its watch playback was suspended instead; no further action needed. */
    Handled,
    /** Nothing left to go back to; host should leave the app. */
    Finish,
}

data class BrowserUiState(
    val tabs: List<Tab>,
    val activeId: Long,
    val url: String,
    val isLoading: Boolean = false,
    val progress: Float = 0f,
    val isRefreshing: Boolean = false,
    val canGoBack: Boolean = false,
    val canPop: Boolean = false,
    /** Suspended watch tab behind the mini-player; kept alive without a host. */
    val suspendedWatchId: Long? = null,
    /** Slide direction for the next tab transition. */
    val forward: Boolean = true,
) {
    companion object {
        fun from(state: TabState): BrowserUiState {
            val active = state.active
            return BrowserUiState(
                tabs = state.tabs,
                activeId = state.activeId,
                url = active?.url.orEmpty(),
                canPop = state.tabs.size > 1,
                suspendedWatchId = state.suspendedWatchId,
            )
        }
    }
}
