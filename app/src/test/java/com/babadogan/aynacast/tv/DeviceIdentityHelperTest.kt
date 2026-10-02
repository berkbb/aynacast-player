package com.babadogan.aynacast.tv

import com.babadogan.aynacast.tv.util.DeviceIdentityHelper
import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceIdentityHelperTest {

    @Test
    fun testRaopMacPrefixFormatting() {
        val macWithColons = "00:11:22:33:44:55"
        val prefix = DeviceIdentityHelper.getRaopMacPrefix(macWithColons)
        assertEquals("001122334455", prefix)
    }

    @Test
    fun testRaopMacPrefixFormattingWithDashes() {
        val macWithDashes = "AA-BB-CC-DD-EE-FF"
        val prefix = DeviceIdentityHelper.getRaopMacPrefix(macWithDashes)
        assertEquals("AABBCCDDEEFF", prefix)
    }
}
