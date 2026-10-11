package com.babadogan.aynacast.tv.pairing

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Server side of AirPlay on-screen PIN pairing (/pair-setup-pin), matching UxPlay's "-pin":
 * SRP-6a with SHA-1 over the RFC 5054 2048-bit group, Apple's 40-byte session key, and an
 * AES-128-GCM exchange of Ed25519 public keys. One instance handles one pairing attempt.
 *
 * Kept free of Android APIs so it can be unit tested on the JVM.
 */
class PinPairingServer(
    private val pin: String,
    private val random: (Int) -> ByteArray = ::secureRandomBytes
) {

    companion object {
        private const val N_HEX =
            "AC6BDB41324A9A9BF166DE5E1389582FAF72B6651987EE07FC3192943DB56050A37329CBB4A099ED8193E0757767A13DD52312AB4B03310D" +
            "CD7F48A9DA04FD50E8083969EDB767B0CF6095179A163AB3661A05FBD5FAAAE82918A9962F0B93B855F97993EC975EEAA80D740ADBF4FF74" +
            "7359D041D5C33EA71D281E446B14773BCA97B43A23FB801676BD207A436C6481F1D2B9078717461A5B9D32E688F87748544523B524B0D57D" +
            "5EA77A2775D2ECFA032CFBDBF52FB3786160279004E57AE6AF874E7303CE53299CCC041C7BC308D82A5698F3A8D0C38271AE35F8E9DBFBB6" +
            "94B5C803D89F7AE435DE236D525F54759B65E372FCD68EF20FA7111F9E4AFF73"
        val N = BigInteger(N_HEX, 16)
        val G: BigInteger = BigInteger.valueOf(2)
        private const val N_BYTES = 256
        private const val SALT_BYTES = 16
        private const val PRIVATE_KEY_BYTES = 32

        private val secureRandom = SecureRandom()
        fun secureRandomBytes(count: Int): ByteArray = ByteArray(count).also { secureRandom.nextBytes(it) }

        fun sha1(vararg parts: ByteArray): ByteArray = digest("SHA-1", *parts)
        fun sha512(vararg parts: ByteArray): ByteArray = digest("SHA-512", *parts)

        private fun digest(algorithm: String, vararg parts: ByteArray): ByteArray {
            val md = MessageDigest.getInstance(algorithm)
            parts.forEach { md.update(it) }
            return md.digest()
        }

        /** Big-endian bytes without leading zeros (OpenSSL BN_bn2bin). */
        fun minimal(value: BigInteger): ByteArray {
            val bytes = value.toByteArray()
            val start = bytes.indexOfFirst { it != 0.toByte() }.let { if (it < 0) bytes.size - 1 else it }
            return bytes.copyOfRange(start, bytes.size)
        }

        /** Big-endian bytes left-padded with zeros to [length] (RFC 5054 PAD()). */
        fun pad(value: BigInteger, length: Int = N_BYTES): ByteArray {
            val bytes = minimal(value)
            return ByteArray(length - bytes.size) + bytes
        }

        fun unsigned(bytes: ByteArray) = BigInteger(1, bytes)
    }

    class Step1(val salt: ByteArray, val serverPublicKey: ByteArray)
    class Step3(val encryptedServerKey: ByteArray, val authTag: ByteArray, val clientEd25519Key: ByteArray)

    private var user: ByteArray = ByteArray(0)
    private var salt = ByteArray(0)
    private var verifier = BigInteger.ZERO
    private var b = BigInteger.ZERO
    private var serverPublic = BigInteger.ZERO
    private var sessionKey: ByteArray? = null

    /** Step 1: {method:"pin", user} -> {pk: B, salt}. */
    fun start(userName: String): Step1 {
        user = userName.toByteArray(Charsets.UTF_8)
        salt = random(SALT_BYTES)
        val x = unsigned(sha1(salt, sha1(user, ":".toByteArray(), pin.toByteArray(Charsets.US_ASCII))))
        verifier = G.modPow(x, N)
        b = unsigned(random(PRIVATE_KEY_BYTES))
        val k = unsigned(sha1(minimal(N), pad(G)))
        serverPublic = k.multiply(verifier).add(G.modPow(b, N)).mod(N)
        sessionKey = null
        return Step1(salt, minimal(serverPublic))
    }

    /** Step 2: {pk: A, proof: M1} -> M2, or null when the PIN was wrong. */
    fun verify(clientPublicBytes: ByteArray, clientProof: ByteArray): ByteArray? {
        if (serverPublic == BigInteger.ZERO) return null
        val a = unsigned(clientPublicBytes)
        if (a.mod(N) == BigInteger.ZERO) return null

        val u = unsigned(sha1(pad(a), pad(serverPublic)))
        val s = a.multiply(verifier.modPow(u, N)).modPow(b, N)
        val sBytes = minimal(s)
        // Apple's session key: SHA1(S | 00000000) || SHA1(S | 00000001).
        val key = sha1(sBytes, byteArrayOf(0, 0, 0, 0)) + sha1(sBytes, byteArrayOf(0, 0, 0, 1))

        val hn = sha1(minimal(N))
        val hg = sha1(minimal(G))
        val hnXorHg = ByteArray(hn.size) { (hn[it].toInt() xor hg[it].toInt()).toByte() }
        val expected = sha1(hnXorHg, sha1(user), salt, minimal(a), minimal(serverPublic), key)
        if (!MessageDigest.isEqual(expected, clientProof)) return null

        sessionKey = key
        return sha1(minimal(a), clientProof, key)
    }

    /**
     * Step 3: {epk, authTag} -> {epk, authTag}. Decrypts the client's Ed25519 public key and encrypts
     * ours. Returns null when the authentication tag does not verify.
     */
    fun exchangeKeys(encryptedClientKey: ByteArray, authTag: ByteArray, serverEd25519Key: ByteArray): Step3? {
        val key = sessionKey ?: return null
        val aesKey = sha512("Pair-Setup-AES-Key".toByteArray(), key).copyOf(16)
        val iv = sha512("Pair-Setup-AES-IV".toByteArray(), key).copyOf(16)

        iv[15] = (iv[15] + 1).toByte()
        val clientKey = try {
            gcm(Cipher.DECRYPT_MODE, aesKey, iv).doFinal(encryptedClientKey + authTag)
        } catch (e: Exception) {
            return null
        }

        iv[15] = (iv[15] + 1).toByte() // "previously undocumented additional nonce" (UxPlay)
        val sealed = gcm(Cipher.ENCRYPT_MODE, aesKey, iv).doFinal(serverEd25519Key)
        return Step3(
            encryptedServerKey = sealed.copyOfRange(0, sealed.size - 16),
            authTag = sealed.copyOfRange(sealed.size - 16, sealed.size),
            clientEd25519Key = clientKey
        )
    }

    private fun gcm(mode: Int, key: ByteArray, iv: ByteArray): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        }
}
