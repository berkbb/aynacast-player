package com.babadogan.aynacast.tv.pairing

import java.security.MessageDigest

/**
 * PIN check for classic AirPlay senders (the macOS Music app), which cannot run the SRP exchange:
 * they answer an HTTP Digest challenge with the code typed into their password prompt. Pure and
 * unit-tested; the codes, paired senders and lockout live in [PinPairingManager].
 *
 * A paired sender is recognised by its DACP-ID, but that ID proves nothing: it is a request header
 * the sender chooses, and Macs announce it on the network (`_dacp._tcp`, `iTunes_Ctrl_<DACP-ID>`).
 * It only selects the code the sender paired with, which stays its password: every connection has
 * to prove it again with a Digest answer to a fresh nonce.
 */
object ClassicDigest {

    /** A sender's answer to the challenge; [response] is empty when it sent none. */
    data class Answer(
        val username: String, val realm: String, val nonce: String,
        val method: String, val uri: String, val response: String
    )

    enum class Decision {
        /** Proved the code it paired with. */
        PAIRED_PASSWORD,
        /** Proved the code on screen: it is paired (again) with that code. */
        CODE_ON_SCREEN,
        /** A paired sender sent no answer yet: challenge it; the password it saved answers. */
        CHALLENGE,
        /** An unknown sender sent no answer: challenge it and show a code to type. */
        NEEDS_CODE,
        /** A wrong answer: counts as a wrong code, and a new code goes on screen. */
        WRONG
    }

    /** [pairedPassword]: the code this sender paired with, if any. [codeOnScreen]: the code shown now. */
    fun decide(pairedPassword: String?, codeOnScreen: String?, answer: Answer): Decision = when {
        answer.response.isEmpty() -> if (pairedPassword != null) Decision.CHALLENGE else Decision.NEEDS_CODE
        pairedPassword != null && proves(pairedPassword, answer) -> Decision.PAIRED_PASSWORD
        codeOnScreen != null && proves(codeOnScreen, answer) -> Decision.CODE_ON_SCREEN
        // Nothing to guess against yet: no code on screen and no password for this sender.
        pairedPassword == null && codeOnScreen == null -> Decision.NEEDS_CODE
        else -> Decision.WRONG
    }

    /** RFC 2617 Digest without qop, as classic AirPlay senders compute it. */
    fun response(password: String, answer: Answer): String {
        val ha1 = md5Hex("${answer.username}:${answer.realm}:$password")
        val ha2 = md5Hex("${answer.method}:${answer.uri}")
        return md5Hex("$ha1:${answer.nonce}:$ha2")
    }

    private fun proves(password: String, answer: Answer): Boolean =
        response(password, answer) == answer.response.lowercase()

    private fun md5Hex(text: String): String =
        MessageDigest.getInstance("MD5").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
