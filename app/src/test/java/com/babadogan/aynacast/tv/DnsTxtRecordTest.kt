package com.babadogan.aynacast.tv

import com.babadogan.aynacast.tv.util.DnsTxtRecord
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class DnsTxtRecordTest {

    @Test
    fun testEntriesAreLengthPrefixed() {
        val txt = DnsTxtRecord.encode(linkedMapOf("vv" to "2".toByteArray(), "model" to "AppleTV5,3".toByteArray()))
        val expected = byteArrayOf(4) + "vv=2".toByteArray() + byteArrayOf(16) + "model=AppleTV5,3".toByteArray()
        assertArrayEquals(expected, txt)
    }

    @Test
    fun testKeyWithoutValueAndOversizedEntries() {
        val txt = DnsTxtRecord.encode(linkedMapOf("flag" to null, "big" to ByteArray(300)))
        assertArrayEquals(byteArrayOf(4) + "flag".toByteArray(), txt)
    }
}
