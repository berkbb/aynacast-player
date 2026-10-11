package com.babadogan.aynacast.tv.engine

/**
 * Minimal H.264 Sequence Parameter Set reader used to size the decoder before the first frame.
 * Free of Android APIs so it can be unit tested on the JVM.
 */
object H264SpsParser {

    /** Returns the cropped (display) width and height encoded in an SPS NAL unit, or null if malformed. */
    fun parseResolution(sps: ByteArray): Pair<Int, Int>? {
        try {
            var offset = 0
            var foundStartCode = false
            while (offset + 3 < sps.size) {
                if (sps[offset].toInt() == 0 && sps[offset + 1].toInt() == 0 &&
                    (sps[offset + 2].toInt() == 1 || (sps[offset + 2].toInt() == 0 && sps[offset + 3].toInt() == 1))) {
                    offset = if (sps[offset + 2].toInt() == 1) offset + 3 else offset + 4
                    foundStartCode = true
                    break
                }
                offset++
            }
            if (!foundStartCode) offset = 0
            if (sps.size - offset < 4) return null

            // Exp-Golomb fields must be read from the RBSP (emulation-prevention bytes removed).
            val rbsp = removeEmulationPrevention(sps, offset + 1)
            val reader = SpsBitReader(rbsp, 0)
            val profileIdc = reader.readBits(8)
            reader.readBits(8)
            reader.readBits(8)
            reader.readUe()

            var chromaFormatIdc = 1
            var separateColorPlaneFlag = 0

            val highProfiles = setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)
            if (profileIdc in highProfiles) {
                chromaFormatIdc = reader.readUe()
                if (chromaFormatIdc == 3) {
                    separateColorPlaneFlag = reader.readBits(1)
                }
                reader.readUe()
                reader.readUe()
                reader.readBits(1)
                if (reader.readBits(1) == 1) {
                    val count = if (chromaFormatIdc != 3) 8 else 12
                    repeat(count) {
                        if (reader.readBits(1) == 1) {
                            reader.skipScalingList(if (it < 6) 16 else 64)
                        }
                    }
                }
            }

            reader.readUe()
            val picOrderCntType = reader.readUe()
            when (picOrderCntType) {
                0 -> reader.readUe()
                1 -> {
                    reader.readBits(1)
                    reader.readSe()
                    reader.readSe()
                    repeat(reader.readUe()) { reader.readSe() }
                }
            }

            reader.readUe()
            reader.readBits(1)

            val picWidthInMbsMinus1 = reader.readUe()
            val picHeightInMapUnitsMinus1 = reader.readUe()
            val frameMbsOnlyFlag = reader.readBits(1)
            if (frameMbsOnlyFlag == 0) {
                reader.readBits(1)
            }
            reader.readBits(1)

            var cropLeft = 0
            var cropRight = 0
            var cropTop = 0
            var cropBottom = 0
            if (reader.readBits(1) == 1) {
                cropLeft = reader.readUe()
                cropRight = reader.readUe()
                cropTop = reader.readUe()
                cropBottom = reader.readUe()
            }

            val codedWidth = (picWidthInMbsMinus1 + 1) * 16
            val codedHeight = (picHeightInMapUnitsMinus1 + 1) * 16 * (2 - frameMbsOnlyFlag)

            val chromaArrayType = if (separateColorPlaneFlag == 1) 0 else chromaFormatIdc
            val subWidthC = if (chromaArrayType in 1..2) 2 else 1
            val subHeightC = if (chromaArrayType == 1) 2 else 1

            val cropUnitX = if (chromaArrayType == 0) 1 else subWidthC
            val cropUnitY = if (chromaArrayType == 0) (2 - frameMbsOnlyFlag) else subHeightC * (2 - frameMbsOnlyFlag)

            val width = codedWidth - (cropLeft + cropRight) * cropUnitX
            val height = codedHeight - (cropTop + cropBottom) * cropUnitY

            return if (width > 0 && height > 0) Pair(width, height) else null
        } catch (e: Exception) {
            return null
        }
    }

    /** Strips H.264 emulation-prevention bytes (00 00 03 -> 00 00) starting at [start]. */
    private fun removeEmulationPrevention(data: ByteArray, start: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(data.size)
        var zeros = 0
        for (i in start until data.size) {
            val b = data[i].toInt() and 0xFF
            if (zeros >= 2 && b == 0x03) {
                zeros = 0
                continue
            }
            out.write(b)
            zeros = if (b == 0) zeros + 1 else 0
        }
        return out.toByteArray()
    }

    private class SpsBitReader(private val data: ByteArray, startOffset: Int) {
        private var bytePos = startOffset
        private var bitPos = 7

        fun readBit(): Int {
            if (bytePos >= data.size) throw IndexOutOfBoundsException("SPS RBSP underflow")
            val bit = (data[bytePos].toInt() ushr bitPos) and 1
            if (--bitPos < 0) { bitPos = 7; bytePos++ }
            return bit
        }

        fun readBits(n: Int): Int {
            var result = 0
            repeat(n) { result = (result shl 1) or readBit() }
            return result
        }

        fun readUe(): Int {
            var leadingZeros = 0
            while (readBit() == 0) {
                if (++leadingZeros > 31) throw ArithmeticException("ue(v) overflow")
            }
            return if (leadingZeros == 0) 0
                   else (1 shl leadingZeros) - 1 + readBits(leadingZeros)
        }

        fun readSe(): Int {
            val k = readUe()
            return if (k == 0) 0 else if (k % 2 == 1) (k + 1) / 2 else -(k / 2)
        }

        fun skipScalingList(size: Int) {
            var lastScale = 8
            var nextScale = 8
            repeat(size) {
                if (nextScale != 0) {
                    val deltaScale = readSe()
                    nextScale = (lastScale + deltaScale + 256) % 256
                }
                lastScale = if (nextScale == 0) lastScale else nextScale
            }
        }
    }
}
