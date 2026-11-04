package com.hhst.youtubelite.ui.browser

import com.hhst.youtubelite.extension.ExtensionManager
import com.hhst.youtubelite.extension.PrefStore
import com.hhst.youtubelite.extension.PreferenceKeys
import com.hhst.youtubelite.nativebrowse.NativePageKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserViewModelTest {

    @Test fun searchResultsSuspendWatchAndBackRestoresIt() {
        val model = vm(miniEnabled = true, playerShowing = true)
        val watch = "https://m.youtube.com/watch?v=aaaaaaaaaaa"
        model.openTab(watch)
        val watchId = model.uiState.value.activeId
        var mini = false
        var restored: String? = null
        model.onWatchSuspended = { mini = true }
        model.onWatchOpened = { restored = it }
        model.openTab("https://m.youtube.com/results?search_query=music")
        assertTrue(mini)
        assertEquals("searching", activeKind(model))
        assertEquals(watchId, model.uiState.value.suspendedWatchId)
        assertEquals(BackResult.Handled, model.onBack())
        assertEquals("home", activeKind(model))
        model.restoreWatchTab(watch)
        assertEquals(watchId, model.uiState.value.activeId)
        assertEquals(watch, restored)
        assertNull(model.uiState.value.suspendedWatchId)
    }

    private fun vm(miniEnabled: Boolean = false, playerShowing: Boolean = false): BrowserViewModel {
        val store = object : PrefStore {
            private val values = mutableMapOf<String, Boolean>()
            private var version = 0L
            override fun contains(key: String): Boolean = key in values
            override fun getBool(key: String, default: Boolean): Boolean = values[key] ?: default
            override fun putBool(key: String, value: Boolean) {
                values[key] = value
            }
            override fun getLong(key: String, default: Long): Long = version
            override fun putLong(key: String, value: Long) {
                version = value
            }
        }
        val manager = ExtensionManager(store).apply {
            setEnabled(PreferenceKeys.ENABLE_IN_APP_MINI_PLAYER, miniEnabled)
        }
        return BrowserViewModel(manager).apply {
            isPlayerShowing = { playerShowing }
        }
    }

    @Test
    fun openTab_rejectsDisallowedUrl() {
        val vm = vm()
        vm.openTab("https://evil.example/phish")

        assertEquals(1, vm.uiState.value.tabs.size)
        assertEquals(NativePageKind.HOME, activeKind(vm))
    }

    @Test
    fun openTab_opensWatchForward() {
        val vm = vm()
        vm.openTab("https://m.youtube.com/watch?v=1")

        assertEquals(NativePageKind.WATCH, activeKind(vm))
        assertTrue(vm.uiState.value.forward)
        assertTrue(vm.uiState.value.canPop)
    }

    @Test
    fun openTab_reusesNavAndUpdatesUrl() {
        val vm = vm()
        vm.openTab("https://m.youtube.com/shorts/a")
        val id = vm.uiState.value.activeId

        vm.openTab("https://m.youtube.com/shorts/b")

        assertEquals(id, vm.uiState.value.activeId)
        assertEquals("https://m.youtube.com/shorts/b", vm.uiState.value.url)
    }

    @Test
    fun onBack_popBeforeFinish() {
        val vm = vm()
        vm.openTab("https://m.youtube.com/watch?v=1")
        assertTrue(vm.uiState.value.canPop)

        assertEquals(BackResult.Handled, vm.onBack())
        assertEquals(NativePageKind.HOME, activeKind(vm))

        assertEquals(BackResult.Finish, vm.onBack())
    }

    @Test
    fun onBack_onWatch_withMini_suspendsInsteadOfPopping() {
        val vm = vm(miniEnabled = true, playerShowing = true)
        vm.openTab("https://m.youtube.com/watch?v=1")
        var suspended = 0
        vm.onWatchSuspended = { suspended++ }

        assertEquals(BackResult.Handled, vm.onBack())

        assertEquals(NativePageKind.HOME, activeKind(vm))
        assertTrue(vm.uiState.value.suspendedWatchId != null)
        assertEquals(1, suspended)
    }

    @Test
    fun openTab_awayFromWatch_withMini_suspendsWatch() {
        val vm = vm(miniEnabled = true, playerShowing = true)
        vm.openTab("https://m.youtube.com/watch?v=1")

        vm.openTab("https://m.youtube.com/@channel")

        assertEquals("@", activeKind(vm))
        assertTrue(vm.uiState.value.suspendedWatchId != null)
    }

    private fun activeKind(vm: BrowserViewModel): String? =
        vm.uiState.value.tabs.find { it.id == vm.uiState.value.activeId }?.kind
}
