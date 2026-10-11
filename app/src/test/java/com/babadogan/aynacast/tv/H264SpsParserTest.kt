package com.babadogan.aynacast.tv

import com.babadogan.aynacast.tv.engine.H264SpsParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class H264SpsParserTest {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    // High profile 1920x1080 SPS (1088 coded lines cropped by 8), as emitted by x264 / VideoToolbox.
    private val sps1080p = bytes(
        0x67, 0x64, 0x00, 0x28, 0xAC, 0xD9, 0x40, 0x78, 0x02, 0x27, 0xE5, 0x84,
        0x00, 0x00, 0x03, 0x00, 0x04, 0x00, 0x00, 0x03, 0x00, 0xF0, 0x3C, 0x60, 0xC6, 0x58
    )

    @Test
    fun testParses1080pWithCropping() {
        assertEquals(Pair(1920, 1080), H264SpsParser.parseResolution(sps1080p))
    }

    @Test
    fun testParsesWithAnnexBStartCode() {
        assertEquals(Pair(1920, 1080), H264SpsParser.parseResolution(bytes(0, 0, 0, 1) + sps1080p))
    }

    @Test
    fun testRejectsTruncatedSps() {
        assertNull(H264SpsParser.parseResolution(bytes(0x67, 0x64)))
        assertNull(H264SpsParser.parseResolution(ByteArray(0)))
    }
}
