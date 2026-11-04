package com.hhst.youtubelite.player.engine

import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackFailureReportTest {

    @Test fun chainsRootCausesWithHttpStatus() {
        val http = org.schabi.newpipe.extractor.services.youtube.streams.StreamHttpException("player", 403, 0)
        val wrapped = java.io.IOException("SESSION_CHANGED_OR_CANCELLED", http)
        val report = PlaybackFailureReport.build(videoId = "abc123", failure = wrapped)
        assertTrue(report.fullText().contains("videoId=abc123"))
        assertTrue(report.causes.any { it.contains("403") })
        assertTrue(report.shortMessage("Could not play this video").startsWith("Could not play this video ("))
    }
}
