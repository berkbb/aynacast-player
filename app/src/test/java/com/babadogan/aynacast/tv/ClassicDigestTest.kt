package com.babadogan.aynacast.tv

import com.babadogan.aynacast.tv.pairing.ClassicDigest
import com.babadogan.aynacast.tv.pairing.ClassicDigest.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

class ClassicDigestTest {

    // Digest the Mac sends for the password "4821", computed independently (Python hashlib).
    private val answer = ClassicDigest.Answer(
        username = "iTunes", realm = "raop", nonce = "9f2c4e1a0b7d6e5f8a9b0c1d2e3f4a5b",
        method = "ANNOUNCE", uri = "rtsp://192.168.1.20/3413821438",
        response = "838cc5b38b106dbacd9cdbf46d613bee"
    )
    private val noAnswer = answer.copy(response = "")

    @Test
    fun computesTheDigestResponse() {
        assertEquals(answer.response, ClassicDigest.response("4821", answer))
    }

    @Test
    fun aPairedSenderMustProveItsPassword() {
        // Its DACP-ID picked the password; without an answer it is only challenged.
        assertEquals(Decision.CHALLENGE, ClassicDigest.decide("4821", null, noAnswer))
        assertEquals(Decision.PAIRED_PASSWORD, ClassicDigest.decide("4821", null, answer))
        assertEquals(Decision.PAIRED_PASSWORD, ClassicDigest.decide("4821", null, answer.copy(response = answer.response.uppercase())))
        assertEquals(Decision.WRONG, ClassicDigest.decide("1111", null, answer))
    }

    @Test
    fun anAnswerToAnotherNonceIsWrong() {
        // A Digest seen on one connection, replayed against the next connection's nonce.
        assertEquals(Decision.WRONG, ClassicDigest.decide("4821", null, answer.copy(nonce = "0".repeat(32))))
    }

    @Test
    fun theCodeOnScreenPairsOrRepairs() {
        assertEquals(Decision.CODE_ON_SCREEN, ClassicDigest.decide(null, "4821", answer))
        // The Mac lost its saved password and typed the new code.
        assertEquals(Decision.CODE_ON_SCREEN, ClassicDigest.decide("1111", "4821", answer))
        assertEquals(Decision.WRONG, ClassicDigest.decide(null, "1111", answer))
    }

    @Test
    fun anUnknownSenderGetsACode() {
        assertEquals(Decision.NEEDS_CODE, ClassicDigest.decide(null, null, noAnswer))
        assertEquals(Decision.NEEDS_CODE, ClassicDigest.decide(null, "4821", noAnswer))
        // No code shown and no password: nothing to guess, so not counted as a wrong code.
        assertEquals(Decision.NEEDS_CODE, ClassicDigest.decide(null, null, answer))
    }
}
