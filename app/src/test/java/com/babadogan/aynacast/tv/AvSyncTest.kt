package com.babadogan.aynacast.tv

import com.babadogan.aynacast.tv.engine.AvSync
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AvSyncTest {

    private val ms = 1_000_000L

    @After
    fun tearDown() {
        AvSync.enabled = false
        AvSync.reset()
    }

    @Test
    fun testInactiveUntilEnabledAndVideoSeen() {
        val now = 10_000 * ms
        assertFalse(AvSync.isActive(now))
        AvSync.enabled = true
        assertFalse(AvSync.isActive(now))
        AvSync.reportVideoDecodeDelay(30 * ms, now)
        assertTrue(AvSync.isActive(now))
        assertFalse(AvSync.isActive(now + 3_000 * ms)) // video stopped arriving
    }

    @Test
    fun testLatencyFollowsDecodeDelayWithinBounds() {
        val now = 10_000 * ms
        assertEquals(60 * ms, AvSync.latencyNs()) // floor
        AvSync.reportVideoDecodeDelay(100 * ms, now)
        assertEquals(120 * ms, AvSync.latencyNs()) // delay + 20 ms margin, rises immediately
        AvSync.reportVideoDecodeDelay(20 * ms, now)
        assertTrue(AvSync.latencyNs() in 110 * ms..120 * ms) // decays slowly
        AvSync.reportVideoDecodeDelay(1_000 * ms, now)
        assertEquals(500 * ms, AvSync.latencyNs()) // ceiling
        AvSync.reportVideoDecodeDelay(-5 * ms, now) // unsynced clock: ignored
        assertEquals(500 * ms, AvSync.latencyNs())
    }

    @Test
    fun testLateAudioRaisesLatencyUntilReset() {
        val now = 10_000 * ms
        AvSync.reportVideoDecodeDelay(100 * ms, now)
        AvSync.reportAudioLate(80 * ms) // TV audio output slower than the video path
        assertEquals(200 * ms, AvSync.latencyNs())
        AvSync.reportVideoDecodeDelay(100 * ms, now)
        assertEquals(200 * ms, AvSync.latencyNs()) // kept: the audio path does not get faster
        AvSync.reportAudioLate(-10 * ms) // not late: ignored
        assertEquals(200 * ms, AvSync.latencyNs())
        AvSync.reportAudioLate(10_000 * ms)
        assertEquals(500 * ms, AvSync.latencyNs()) // ceiling
        AvSync.reset()
        assertEquals(60 * ms, AvSync.latencyNs())
    }
}
