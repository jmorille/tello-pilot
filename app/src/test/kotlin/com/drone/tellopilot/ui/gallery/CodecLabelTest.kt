package com.drone.tellopilot.ui.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CodecLabelTest {

    @Test
    fun `known codecs get their usual name`() {
        assertEquals("H.264", codecLabel("video/avc"))
        assertEquals("HEVC", codecLabel("video/hevc"))
        assertEquals("AV1", codecLabel("video/av01"))
    }

    @Test
    fun `other codecs are shown from their mime type`() {
        assertEquals("X-VND.ON2.VP9", codecLabel("video/x-vnd.on2.vp9"))
        assertEquals("MP4V-ES", codecLabel("video/mp4v-es"))
    }

    @Test
    fun `unknown codec gives no label`() {
        assertNull(codecLabel(null))
    }
}
