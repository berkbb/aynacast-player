package com.babadogan.aynacast.tv.engine

import kotlin.math.pow

/**
 * Pure helpers describing the audio formats an AirPlay sender negotiates.
 * Kept free of Android APIs so they can be unit tested on the JVM.
 */
object AirPlayAudioFormat {

    const val CODEC_STOPPED = -1
    const val CODEC_PCM_S16 = 0
    const val CODEC_AAC_LC = 4
    const val CODEC_AAC_ELD = 8

    /** AirPlay reports -144 dB for mute and -30..0 dB for the volume slider. */
    private const val MUTE_THRESHOLD_DB = -100f

    private val SAMPLE_RATES = intArrayOf(
        96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350
    )

    private const val AOT_AAC_LC = 2
    private const val AOT_ER_AAC_ELD = 39

    fun isAac(codec: Int): Boolean = codec == CODEC_AAC_LC || codec == CODEC_AAC_ELD

    /**
     * Builds the MPEG-4 AudioSpecificConfig ("csd-0") for the AAC flavours AirPlay uses:
     * AAC-ELD (mirroring, 480 samples/frame) and AAC-LC (audio streaming, 1024 samples/frame).
     */
    fun audioSpecificConfig(codec: Int, sampleRate: Int, channels: Int, samplesPerFrame: Int): ByteArray {
        require(isAac(codec)) { "Not an AAC codec: $codec" }
        val writer = BitWriter()
        val objectType = if (codec == CODEC_AAC_ELD) AOT_ER_AAC_ELD else AOT_AAC_LC

        if (objectType >= 31) {
            writer.write(31, 5)
            writer.write(objectType - 32, 6)
        } else {
            writer.write(objectType, 5)
        }

        val frequencyIndex = SAMPLE_RATES.indexOf(sampleRate)
        if (frequencyIndex >= 0) {
            writer.write(frequencyIndex, 4)
        } else {
            writer.write(0xF, 4)
            writer.write(sampleRate, 24)
        }
        writer.write(channels, 4)

        if (objectType == AOT_ER_AAC_ELD) {
            // ELDSpecificConfig
            writer.write(if (samplesPerFrame == 480) 1 else 0, 1) // frameLengthFlag (480 vs 512)
            writer.write(0, 3) // section / scalefactor / spectral data resilience flags
            writer.write(0, 1) // ldSbrPresentFlag
            writer.write(0, 4) // ELDEXT_TERM
            writer.write(0, 2) // epConfig
        } else {
            // GASpecificConfig
            writer.write(if (samplesPerFrame == 960) 1 else 0, 1) // frameLengthFlag (960 vs 1024)
            writer.write(0, 1) // dependsOnCoreCoder
            writer.write(0, 1) // extensionFlag
        }
        return writer.toByteArray()
    }

    /** Converts the sender's dB volume into an AudioTrack gain in 0..1. */
    fun volumeDbToGain(volumeDb: Float): Float {
        if (volumeDb.isNaN() || volumeDb <= MUTE_THRESHOLD_DB) return 0f
        return 10f.pow(volumeDb.coerceAtMost(0f) / 20f).coerceIn(0f, 1f)
    }

    private class BitWriter {
        private val bytes = ArrayList<Byte>()
        private var current = 0
        private var bitCount = 0

        fun write(value: Int, bits: Int) {
            for (i in bits - 1 downTo 0) {
                current = (current shl 1) or ((value ushr i) and 1)
                if (++bitCount == 8) {
                    bytes.add(current.toByte())
                    current = 0
                    bitCount = 0
                }
            }
        }

        fun toByteArray(): ByteArray {
            if (bitCount > 0) {
                bytes.add((current shl (8 - bitCount)).toByte())
                current = 0
                bitCount = 0
            }
            return bytes.toByteArray()
        }
    }
}
