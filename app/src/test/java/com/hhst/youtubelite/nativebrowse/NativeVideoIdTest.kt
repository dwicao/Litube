package com.hhst.youtubelite.nativebrowse

import com.hhst.youtubelite.extractor.VideoId
import org.junit.Assert.assertEquals
import org.junit.Test

class NativeVideoIdTest {

    @Test fun watchUrlsResolvesToIdAndCanonicalUrl() {
        val id = VideoId.parse("https://m.youtube.com/watch?v=dQw4w9WgXcQ&pp=abc")
        assertEquals("dQw4w9WgXcQ", id)
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", VideoId.watchUrl(id!!))
    }

    @Test fun shortsUrlsResolvesToId() {
        assertEquals("dQw4w9WgXcQ", VideoId.parse("https://m.youtube.com/shorts/dQw4w9WgXcQ"))
    }
}
