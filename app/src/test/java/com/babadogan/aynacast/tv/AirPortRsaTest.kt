package com.babadogan.aynacast.tv

import com.babadogan.aynacast.tv.engine.AirPortRsa
import java.math.BigInteger
import java.security.KeyFactory
import java.security.spec.MGF1ParameterSpec
import java.security.spec.RSAPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AirPortRsaTest {

    private val publicExponent = BigInteger.valueOf(65537)
    private val publicKey = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(AirPortRsa.MODULUS, publicExponent))

    @Test
    fun testDecryptsAnOaepWrappedAesKey() {
        // What a classic AirPlay sender puts in ANNOUNCE "rsaaeskey".
        val aesKey = ByteArray(16) { (it * 7 + 3).toByte() }
        val cipher = Cipher.getInstance("RSA/ECB/OAEPPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE, publicKey,
            OAEPParameterSpec("SHA-1", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT)
        )
        val wrapped = cipher.doFinal(aesKey)
        assertArrayEquals(aesKey, AirPortRsa.apply(AirPortRsa.MODE_DECRYPT_KEY, wrapped))
    }

    @Test
    fun testChallengeSignatureVerifiesWithThePublicKey() {
        // 16-byte challenge + IPv4 address + MAC, padded to 32 bytes (shairport-sync apple_challenge).
        val challenge = ByteArray(32) { if (it < 26) (it + 1).toByte() else 0 }
        val signature = AirPortRsa.apply(AirPortRsa.MODE_SIGN_CHALLENGE, challenge)
        assertNotNull(signature)
        assertEquals(256, signature!!.size)
        val recovered = BigInteger(1, signature).modPow(publicExponent, AirPortRsa.MODULUS).toByteArray()
        // 00 01 FF..FF 00 || challenge; BigInteger drops the leading zero byte.
        assertEquals(255, recovered.size)
        assertEquals(1, recovered[0].toInt())
        assertArrayEquals(challenge, recovered.copyOfRange(recovered.size - 32, recovered.size))
    }

    @Test
    fun testRejectsGarbage() {
        assertNull(AirPortRsa.apply(AirPortRsa.MODE_DECRYPT_KEY, ByteArray(256) { 0x11 }))
        assertNull(AirPortRsa.apply(AirPortRsa.MODE_DECRYPT_KEY, ByteArray(16)))
        assertNull(AirPortRsa.apply(7, ByteArray(16)))
    }
}
