package com.hhst.youtubelite.ui.about

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {

    @Test fun stripsTagPrefix() {
        assertEquals("3.0.3", "v3.0.3".removePrefix("v"))
    }

    @Test fun stableVersionComparison() {
        assertTrue(StableVersion.isNewer("3.0.3", "3.0.4"))
        assertTrue(!StableVersion.isNewer("3.0.4", "3.0.3"))
        assertTrue(StableVersion.isDevelopment("4.0.0-dev"))
    }
}
