package com.babadogan.aynacast.tv.session

import android.os.SystemClock

/**
 * Coordinates the full-screen receiver screens. A screen that is showing its closing card
 * reserves the display for that long, so a screen the sender opens right after (iOS returns to
 * mirroring as soon as a cast or a PIN pairing ends) does not cover the card.
 */
object ScreenTransitions {

    @Volatile
    private var outroEndsAtMs = 0L

    /** A closing card is shown for [durationMs], plus the window's close animation. */
    fun beginOutro(durationMs: Long) {
        outroEndsAtMs = SystemClock.uptimeMillis() + durationMs + WINDOW_CLOSE_MS
    }

    /** How long a screen about to open should wait for a closing card to finish (0 = none). */
    fun outroRemainingMs(): Long = (outroEndsAtMs - SystemClock.uptimeMillis()).coerceAtLeast(0L)

    // Matches res/anim/aynacast_screen_exit.xml.
    private const val WINDOW_CLOSE_MS = 250L
}
