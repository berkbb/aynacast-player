package com.babadogan.aynacast.tv

import com.babadogan.aynacast.tv.session.VideoCastSession
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class VideoCastSessionTest {

    private val playing = VideoCastSession.PlaybackInfo(
        state = VideoCastSession.State.PLAYING,
        durationSec = 100.0,
        positionSec = 10.0,
        rate = 1f,
        readyToPlay = true
    )

    @Before
    fun setUp() = VideoCastSession.stop()

    @After
    fun tearDown() = VideoCastSession.stop()

    private fun currentSequence(): Long = VideoCastSession.media.value!!.sequence

    @Test
    fun testEveryPlayGetsANewSequenceAndStartsLoading() {
        VideoCastSession.play("https://media.example/a.mp4", 12.5)
        val first = currentSequence()
        assertEquals(VideoCastSession.State.LOADING, VideoCastSession.playback.value.state)
        assertEquals(12.5, VideoCastSession.media.value!!.startPositionSec, 0.0)

        VideoCastSession.play("https://media.example/a.mp4", -3.0) // same URL again
        assertNotEquals(first, currentSequence())
        assertEquals(0.0, VideoCastSession.media.value!!.startPositionSec, 0.0)
    }

    @Test
    fun testReportsFromAPreviousPlayerAreIgnored() {
        VideoCastSession.play("https://media.example/a.mp4", 0.0)
        val old = currentSequence()
        VideoCastSession.play("https://media.example/b.mp4", 0.0)
        val current = currentSequence()

        VideoCastSession.reportPlayback(old, playing)
        assertEquals(VideoCastSession.State.LOADING, VideoCastSession.playback.value.state)

        VideoCastSession.reportPlayback(current, playing)
        assertEquals(playing, VideoCastSession.playback.value)
    }

    @Test
    fun testClosingPlayerCannotStopTheCastThatReplacedIt() {
        VideoCastSession.play("https://media.example/a.mp4", 0.0)
        val old = currentSequence()
        VideoCastSession.play("https://media.example/b.mp4", 0.0)

        VideoCastSession.stop(old) // the old screen's onDestroy
        assertNotNull(VideoCastSession.media.value)
        assertEquals("https://media.example/b.mp4", VideoCastSession.media.value!!.url)

        VideoCastSession.stop(currentSequence()) // end of media / Back on the current screen
        assertNull(VideoCastSession.media.value)
        assertEquals(VideoCastSession.State.STOPPED, VideoCastSession.playback.value.state)
    }

    @Test
    fun testReportsAfterStopAreIgnored() {
        VideoCastSession.play("https://media.example/a.mp4", 0.0)
        val sequence = currentSequence()
        VideoCastSession.stop() // the sender stopped
        VideoCastSession.reportPlayback(sequence, playing) // a report already on its way
        assertEquals(VideoCastSession.State.STOPPED, VideoCastSession.playback.value.state)
    }

    @Test
    fun testControlsArriveAsStateAndResetForEachPlay() {
        VideoCastSession.play("https://media.example/a.mp4", 0.0)
        val sequence = currentSequence()
        // Sent before any player screen listens: kept as state, so nothing is lost.
        VideoCastSession.seek(42.5)
        VideoCastSession.setRate(0f)
        val controls = VideoCastSession.controls.value
        assertEquals(sequence, controls.sequence)
        assertEquals(42.5, controls.seekPositionSec, 0.0)
        assertEquals(0f, controls.rate)
        // The same rate again (e.g. play after a pause done with the TV remote) is a new command.
        VideoCastSession.setRate(0f)
        assertNotEquals(controls.rateId, VideoCastSession.controls.value.rateId)

        VideoCastSession.seek(-1.0)
        val again = VideoCastSession.controls.value
        assertNotEquals(controls.seekId, again.seekId) // a repeated /scrub is a new seek
        assertEquals(0.0, again.seekPositionSec, 0.0)

        VideoCastSession.play("https://media.example/b.mp4", 0.0)
        val fresh = VideoCastSession.controls.value
        assertEquals(currentSequence(), fresh.sequence)
        assertEquals(0L, fresh.seekId)
        assertEquals(0L, fresh.rateId)
    }
}
