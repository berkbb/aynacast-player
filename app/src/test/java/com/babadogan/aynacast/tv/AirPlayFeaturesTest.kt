package com.babadogan.aynacast.tv

import com.babadogan.aynacast.tv.engine.AirPlayFeatures
import org.junit.Assert.assertEquals
import org.junit.Test

class AirPlayFeaturesTest {

    @Test
    fun testUrlVideoPlaybackIsAdvertised() {
        // Video casting is implemented, so bits 0 and 4 are set as UxPlay does with -hls.
        assertEquals(AirPlayFeatures.VIDEO, AirPlayFeatures.VALUE and AirPlayFeatures.VIDEO)
        assertEquals(AirPlayFeatures.VIDEO_HTTP_LIVE_STREAMS, AirPlayFeatures.VALUE and AirPlayFeatures.VIDEO_HTTP_LIVE_STREAMS)
    }

    @Test
    fun testScreenMirroringAndAudioStayAdvertised() {
        assertEquals(1L shl 7, AirPlayFeatures.VALUE and (1L shl 7)) // Screen mirroring
        assertEquals(1L shl 9, AirPlayFeatures.VALUE and (1L shl 9)) // Audio
    }

    @Test
    fun testValueMatchesNativeInfoAndTxtFormat() {
        // GET /info in raop_rtsp_server.cpp advertises the same integer.
        assertEquals(130367356919L, AirPlayFeatures.VALUE)
        assertEquals("0x5A7FFFF7,0x1E", AirPlayFeatures.txtValue())
    }
}
