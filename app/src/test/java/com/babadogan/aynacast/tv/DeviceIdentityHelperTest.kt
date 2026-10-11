package com.babadogan.aynacast.tv

import com.babadogan.aynacast.tv.util.DeviceIdentityHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun testPlaceholderAndEmptyMacsAreRejected() {
        assertFalse(DeviceIdentityHelper.isUsableMac(null))
        assertFalse(DeviceIdentityHelper.isUsableMac(ByteArray(6)))
        assertFalse(DeviceIdentityHelper.isUsableMac(byteArrayOf(0x02, 0, 0, 0, 0, 0)))
        assertFalse(DeviceIdentityHelper.isUsableMac(byteArrayOf(1, 2, 3)))
        assertTrue(DeviceIdentityHelper.isUsableMac(byteArrayOf(0x00, 0x11, 0x22, 0x33, 0x44, 0x55)))
    }

    @Test
    fun testMacFormatting() {
        val mac = byteArrayOf(0x00, 0x1A, 0xBF.toByte(), 0x33, 0x44, 0xFF.toByte())
        assertEquals("00:1A:BF:33:44:FF", DeviceIdentityHelper.formatMac(mac))
    }

    @Test
    fun testDerivedMacIsStableLocallyAdministeredUnicast() {
        val androidId = "a1b2c3d4e5f60718"
        val first = DeviceIdentityHelper.deriveMacFromId(androidId)
        assertEquals(first, DeviceIdentityHelper.deriveMacFromId(androidId))
        assertEquals("A2:B2:C3:D4:E5:F6", first)
        val firstOctet = first.substring(0, 2).toInt(16)
        assertEquals(0, firstOctet and 0x01)
        assertEquals(0x02, firstOctet and 0x02)
        assertEquals("02:00:00:00:00:00", DeviceIdentityHelper.deriveMacFromId(""))
    }

    @Test
    fun testDeviceNameFitsMdnsLabel() {
        assertEquals("Salon TV", DeviceIdentityHelper.sanitizeDeviceName("  Salon TV \n"))
        assertEquals("Android TV", DeviceIdentityHelper.sanitizeDeviceName("   "))
        val longTurkish = "Çok Uzun İsimli Oturma Odası Televizyonu Şükrü Ağabeyin Evi"
        val sanitized = DeviceIdentityHelper.sanitizeDeviceName(longTurkish)
        assertTrue(sanitized.toByteArray(Charsets.UTF_8).size <= DeviceIdentityHelper.MAX_DEVICE_NAME_BYTES)
        assertTrue(longTurkish.startsWith(sanitized))
        val raopName = "AABBCCDDEEFF@$sanitized"
        assertTrue(raopName.toByteArray(Charsets.UTF_8).size <= 63)
    }
}
