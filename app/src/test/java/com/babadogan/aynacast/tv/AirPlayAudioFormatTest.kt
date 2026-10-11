package com.babadogan.aynacast.tv

import com.babadogan.aynacast.tv.engine.AirPlayAudioFormat
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AirPlayAudioFormatTest {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun testAacEldConfigForScreenMirroring() {
        // AAC-ELD, 44.1 kHz, stereo, 480 samples/frame — the format iOS/macOS use while mirroring.
        val asc = AirPlayAudioFormat.audioSpecificConfig(AirPlayAudioFormat.CODEC_AAC_ELD, 44100, 2, 480)
        assertArrayEquals(bytes(0xF8, 0xE8, 0x50, 0x00), asc)
    }

    @Test
    fun testAacLcConfig() {
        val asc = AirPlayAudioFormat.audioSpecificConfig(AirPlayAudioFormat.CODEC_AAC_LC, 44100, 2, 1024)
        assertArrayEquals(bytes(0x12, 0x10), asc)
        val asc48k = AirPlayAudioFormat.audioSpecificConfig(AirPlayAudioFormat.CODEC_AAC_LC, 48000, 2, 1024)
        assertArrayEquals(bytes(0x11, 0x90), asc48k)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testPcmHasNoAudioSpecificConfig() {
        AirPlayAudioFormat.audioSpecificConfig(AirPlayAudioFormat.CODEC_PCM_S16, 44100, 2, 352)
    }

    @Test
    fun testCodecClassification() {
        assertTrue(AirPlayAudioFormat.isAac(AirPlayAudioFormat.CODEC_AAC_ELD))
        assertTrue(AirPlayAudioFormat.isAac(AirPlayAudioFormat.CODEC_AAC_LC))
        assertFalse(AirPlayAudioFormat.isAac(AirPlayAudioFormat.CODEC_PCM_S16))
        assertFalse(AirPlayAudioFormat.isAac(AirPlayAudioFormat.CODEC_STOPPED))
    }

    @Test
    fun testVolumeMapping() {
        assertEquals(1f, AirPlayAudioFormat.volumeDbToGain(0f), 1e-6f)
        assertEquals(0.5012f, AirPlayAudioFormat.volumeDbToGain(-6f), 1e-3f)
        assertEquals(0.0316f, AirPlayAudioFormat.volumeDbToGain(-30f), 1e-3f)
        assertEquals(0f, AirPlayAudioFormat.volumeDbToGain(-144f), 0f)
        assertEquals(0f, AirPlayAudioFormat.volumeDbToGain(Float.NaN), 0f)
        assertEquals(1f, AirPlayAudioFormat.volumeDbToGain(3f), 1e-6f)
    }
}
