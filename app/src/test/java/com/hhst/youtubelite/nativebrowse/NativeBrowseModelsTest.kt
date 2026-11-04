package com.hhst.youtubelite.nativebrowse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeBrowseModelsTest {

    @Test fun videoItemDefaultsToWatchUrl() {
        val item = NativeVideoItem(videoId = "abc123", title = "Test")
        assertEquals("https://m.youtube.com/watch?v=abc123", item.url)
    }

    @Test fun pageDefaultsToEmpty() {
        val page = NativeBrowsePage()
        assertTrue(page.items.isEmpty())
        assertEquals(null, page.nextPage)
    }

    @Test fun stateDefaultsToHome() {
        val state = NativeBrowseState()
        assertEquals(NativeBrowseKind.HOME, state.kind)
        assertEquals("", state.query)
    }
}
