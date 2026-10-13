package com.babadogan.aynacast.tv.engine

import java.math.BigInteger
import java.security.MessageDigest

/**
 * RSA with the AirPort Express key, for classic AirPlay (RAOP) senders such as the macOS Music app.
 *
 * Those senders authenticate the receiver with an RSA challenge (OPTIONS "Apple-Challenge") and send
 * the audio AES key wrapped with RSA-OAEP (ANNOUNCE "rsaaeskey"); the Music app on macOS only
 * offers FairPlay version 2 otherwise, which no open-source receiver can handle. The key is the
 * long-public one shairport-sync uses (common.c "super_secret_key").
 *
 * Plain BigInteger arithmetic: the operations are tiny and run once per connection.
 */
object AirPortRsa {

    /** [AirPlayNativeBridge.AirPlayNativeListener.onRsa] modes. */
    const val MODE_SIGN_CHALLENGE = 0
    const val MODE_DECRYPT_KEY = 1

    private const val KEY_BYTES = 256
    private const val SHA1_BYTES = 20

    internal val MODULUS = BigInteger(
            "e7d744f2a2e2788b6c1f55a08eb70544a8fa7945aa8be6c62ce5f51cbdd4dc68" +
            "42fe3d1083dd2edec1bfd4252dc02e6f398bdf0e6148ea84855e2e442da6d626" +
            "64f674a1f304929ade4f6893ef2df6e711a8c77a0d91c9d980822e50d12922af" +
            "ea40ea9f0e14c0f76938c5f3882fc0323dd9fe55155f51bb5921c201629fd733" +
            "52d5e2efaabf9ba048d7b813a2b6767f6c3ccf1eb4ce673d037b0d2ea30c5fff" +
            "eb06f8d08adde409571a9c689fef10728855dd8cfb9a8bef5c8943ef3b5faa15" +
            "dde698beddf3599603eb3e6f61372bb628f6559f599a78bf500687aa7f4976c0" +
            "562d412956f8989e18a6355bd81597825e0fc875343ec782117625cdbf98447b",
        16
    )
    private val PRIVATE_EXPONENT = BigInteger(
            "e5f00c72f577d604b9a4ce4122aa84b01743ec995acfcc7f4ab27c0b187f9066" +
            "5be359df1259818deeed79d3b1ef845e4ddddac9a155373b5e270d8e1315001a" +
            "2e527d54cdf9000a5768bc98d4446b37bbbd00b29dd8b53062133b2a6e77f4ee" +
            "32505622904da720fb1c12c03996da713a0506098edbedecf936d0fa9cbd5929" +
            "abb0eda35799502f9894dcb8fc569a892d17780324a2b6c3166e346709134b85" +
            "4041b867706b58fef2a0db922b77628b68e69693c7af43bf2a73d0b732377a0b" +
            "a17b44f051e9bf79849dcb3332571fd8a70933c2d60bdec479934a3daca40bb6" +
            "f2f37c0a9d07106eadc8b369a03f2f41c880098e8add46240dac68cc5354f361",
        16
    )

    fun apply(mode: Int, input: ByteArray): ByteArray? = when (mode) {
        MODE_SIGN_CHALLENGE -> sign(input)
        MODE_DECRYPT_KEY -> decryptOaep(input)
        else -> null
    }

    /** PKCS#1 v1.5 type-1 padding of the raw challenge buffer (no hash), then the private key. */
    fun sign(data: ByteArray): ByteArray? {
        if (data.size > KEY_BYTES - 11) return null
        val padded = ByteArray(KEY_BYTES)
        padded[1] = 0x01
        val separator = KEY_BYTES - data.size - 1
        for (i in 2 until separator) padded[i] = 0xFF.toByte()
        System.arraycopy(data, 0, padded, separator + 1, data.size)
        return privateOp(padded)
    }

    /** RSA-OAEP (SHA-1, MGF1-SHA-1, empty label) decryption. */
    fun decryptOaep(cipherText: ByteArray): ByteArray? {
        if (cipherText.size != KEY_BYTES) return null
        val em = privateOp(cipherText) ?: return null
        if (em[0] != 0.toByte()) return null
        val maskedSeed = em.copyOfRange(1, 1 + SHA1_BYTES)
        val maskedDb = em.copyOfRange(1 + SHA1_BYTES, KEY_BYTES)
        val seed = xor(maskedSeed, mgf1(maskedDb, SHA1_BYTES))
        val db = xor(maskedDb, mgf1(seed, maskedDb.size))
        val labelHash = MessageDigest.getInstance("SHA-1").digest(ByteArray(0))
        if (!db.copyOfRange(0, SHA1_BYTES).contentEquals(labelHash)) return null
        var i = SHA1_BYTES
        while (i < db.size && db[i] == 0.toByte()) i++
        if (i >= db.size || db[i] != 0x01.toByte()) return null
        return db.copyOfRange(i + 1, db.size)
    }

    private fun privateOp(input: ByteArray): ByteArray? {
        val m = BigInteger(1, input)
        if (m >= MODULUS) return null
        val out = m.modPow(PRIVATE_EXPONENT, MODULUS).toByteArray()
        // BigInteger is big-endian with an optional sign byte; left-pad / trim to the key size.
        return when {
            out.size == KEY_BYTES -> out
            out.size > KEY_BYTES -> out.copyOfRange(out.size - KEY_BYTES, out.size)
            else -> ByteArray(KEY_BYTES - out.size) + out
        }
    }

    private fun mgf1(seed: ByteArray, length: Int): ByteArray {
        val sha1 = MessageDigest.getInstance("SHA-1")
        val out = ByteArray(length)
        var counter = 0
        var offset = 0
        while (offset < length) {
            sha1.update(seed)
            sha1.update(byteArrayOf((counter ushr 24).toByte(), (counter ushr 16).toByte(), (counter ushr 8).toByte(), counter.toByte()))
            val block = sha1.digest()
            val take = minOf(block.size, length - offset)
            System.arraycopy(block, 0, out, offset, take)
            offset += take
            counter++
        }
        return out
    }

    private fun xor(a: ByteArray, b: ByteArray): ByteArray = ByteArray(a.size) { (a[it].toInt() xor b[it].toInt()).toByte() }
}
