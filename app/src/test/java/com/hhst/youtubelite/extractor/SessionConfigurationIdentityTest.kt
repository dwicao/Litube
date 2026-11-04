package com.hhst.youtubelite.extractor

import com.grack.nanojson.JsonObject
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class SessionConfigurationIdentityTest {
    @Test fun sessionHashKeepsItsStableEncoding() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", YoutubeSessionProvider.digest("abc"))
    }

    @Test fun pageRedirectUsesOnlyTheFrozenCookieForTheActualOrigin() {
        val target = YoutubeSessionProvider.pageRedirect("https://www.youtube.com/watch?v=fixture", "https://m.youtube.com/watch?v=fixture")
        val cookies = mapOf("https://www.youtube.com" to "www-snapshot", "https://m.youtube.com" to "mobile-snapshot")
        val request = YoutubeSessionProvider.pageRequest(target, "fixture-UA", cookies)
        assertEquals("mobile-snapshot", request.header("Cookie"))
        assertNull(request.header("Authorization"))
        assertEquals("https://www.youtube.com/watch?redirect=1", YoutubeSessionProvider.pageRedirect("https://www.youtube.com/watch", "?redirect=1"))
    }

    @Test fun pageRedirectRejectsForeignOrInsecureOrigins() {
        for (target in listOf("https://www.youtube.com.attacker.invalid/watch", "https://www.google.com/", "http://www.youtube.com/",
            "https://account@www.youtube.com/", "https://www.youtube.com:444/", "//attacker.invalid/")) {
            try {
                YoutubeSessionProvider.pageRedirect("https://www.youtube.com/watch", target)
                fail("Unexpected allowed redirect")
            } catch (_: IOException) { }
        }
    }

    @Test fun videoAndLiveHintsDoNotChangeTheAccountConfiguration() {
        val first = JsonObject(mapOf("VISITOR_DATA" to "visitor", "_VIDEO_ID" to "first", "_IS_LIVE" to true))
        val second = JsonObject(mapOf("VISITOR_DATA" to "visitor", "_VIDEO_ID" to "second", "_IS_LIVE" to false))
        assertEquals(YoutubeSessionProvider.configurationIdentity(first), YoutubeSessionProvider.configurationIdentity(second))
    }

    @Test fun identityAndExperimentChangesInvalidateTheSharedConfiguration() {
        val initial = JsonObject(mapOf("VISITOR_DATA" to "visitor", "LOGGED_IN" to true))
        for ((field, value) in mapOf("VISITOR_DATA" to "different", "SESSION_INDEX" to 1,
            "DATASYNC_ID" to "another-account", "LOGGED_IN" to false, "DELEGATED_SESSION_ID" to "another-channel",
            "PLAYER_JS_URL" to "another-player",
            "EXPERIMENT_FLAGS" to JsonObject(mapOf("tokenBinding" to true)))) {
            val changed = JsonObject(initial).apply { put(field, value) }
            assertNotEquals(field, YoutubeSessionProvider.configurationIdentity(initial), YoutubeSessionProvider.configurationIdentity(changed))
        }
    }
}
