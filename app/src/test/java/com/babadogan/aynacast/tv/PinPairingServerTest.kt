package com.babadogan.aynacast.tv

import com.babadogan.aynacast.tv.pairing.PinPairingServer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Cross-checks the SRP-6a / AES-GCM PIN pairing server against vectors produced by an independent
 * Python client (tools/native-host-tests/srp_client.py), which derives the session key with the
 * client-side formula S = (B - k*g^x)^(a + u*x) instead of the server's (A * v^u)^b.
 */
class PinPairingServerTest {

    private val user = "AA:BB:CC:DD:EE:FF"
    private val pin = "4821"
    private val salt = ByteArray(16) { it.toByte() }
    private val b = ByteArray(32) { 0x11 }
    private val clientEd25519 = ByteArray(32) { (100 + it).toByte() }
    private val serverEd25519 = ByteArray(32) { (200 + it).toByte() }

    private val B = "109d801cfab5ed53f7469ef55b1f9acae652a2617413538f6894bb5723ca80373048132b10f91d51ada617f035a46610a53f0996ce7c715fdcbfe0d35ffcc68d3e6dad633bee93559e5895549a21238d2613096f7b7572ec11269109b1abea8352893e6d4fd62a139c83fac6ced1cfacaac30cefbfd151db288962c59f71dab231a4d522defc05f3ea9426aedc2975bab3b040c770346142798f1e940862826c9b3c2d0c0d9ebfa2a0fb77c5e918feef34ceaa58e17501d728d1c4e34609265b656a2b70e73a004d84a41daec28d068c2d472a4abf7821ee15114c4b651f50fe8d8d0f98731a832d81ff0e31c52d0f09d48c067ef8924bacc2b606be9480adef"
    private val A = "2e232249863342cb942b7847f943a3c7a6e9bb6631d0f26e38bc43760b4e6fb47efb6c94b1b477e85e78fdf59070054f778e94ec70b66ac9b7da0860b57ad20a03737d0f6921a5bc79f9d3d1d961741305faf098e37e002a0e28eeb029054191286bdb1b4ee2e2d833dfc493b778c01e876356830f555362776e15b85048a4fe3836fbeb89433f3d29125ce191dbce2c56ea2a6aa61251b760949592637a5a514ad56f79372ec17debebb9d0a934f2c9680b707c7ba3ae5d92a947da3fdf246dcf609ce311b671a5c9e41f7ad6931235bfb8ad06fa2713a295974b55a9e058788a11e3f672f467a2602b34e6d6f6182ef71b2073f7b88c16327cab003e20cad0"
    private val M1 = "3bcc1016999ae2a9fcf3eb0f2d1effa78cb2e593"
    private val M2 = "e8b45a19adf0f0c075fd98a18f8375c12cdef931"
    private val CLIENT_EPK = "8b146475f9a5370dfcdd8f80830e9e23192304e6103105e0e4c1c1ef9590b514"
    private val CLIENT_TAG = "b42b24fa48667b37d8ed55f4324f41ba"
    private val SERVER_EPK = "51f70a1dbac79930dedb7dae984ae06bc1ef6ac444de2e02ca707826b1689914"
    private val SERVER_TAG = "d26cb6be33fd82b275e0c7bf72436a19"
    private val M1_WRONG_PIN = "78cc2f7766183e82c5ecb2c57f8b25d0cf9589d7"

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun server(): PinPairingServer {
        val randoms = ArrayDeque(listOf(salt, b))
        return PinPairingServer(pin) { size -> randoms.removeFirst().also { check(it.size == size) } }
    }

    @Test
    fun testFullPairingMatchesIndependentClient() {
        val server = server()
        val step1 = server.start(user)
        assertArrayEquals(salt, step1.salt)
        assertArrayEquals(hex(B), step1.serverPublicKey)

        assertArrayEquals(hex(M2), server.verify(hex(A), hex(M1)))

        val step3 = server.exchangeKeys(hex(CLIENT_EPK), hex(CLIENT_TAG), serverEd25519)
        assertNotNull(step3)
        assertArrayEquals(clientEd25519, step3!!.clientEd25519Key)
        assertArrayEquals(hex(SERVER_EPK), step3.encryptedServerKey)
        assertArrayEquals(hex(SERVER_TAG), step3.authTag)
    }

    @Test
    fun testWrongPinIsRejected() {
        val server = server()
        server.start(user)
        assertNull(server.verify(hex(A), hex(M1_WRONG_PIN)))
        // Without a verified proof there is no session key for step 3.
        assertNull(server.exchangeKeys(hex(CLIENT_EPK), hex(CLIENT_TAG), serverEd25519))
    }

    @Test
    fun testTamperedAuthTagIsRejected() {
        val server = server()
        server.start(user)
        server.verify(hex(A), hex(M1))
        val tag = hex(CLIENT_TAG).also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertNull(server.exchangeKeys(hex(CLIENT_EPK), tag, serverEd25519))
    }

    @Test
    fun testZeroClientKeyIsRejected() {
        val server = server()
        server.start(user)
        assertNull(server.verify(PinPairingServer.minimal(PinPairingServer.N), hex(M1)))
    }
}
