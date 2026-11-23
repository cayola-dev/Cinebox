package com.ashe.cinemabox.player

import com.ashe.cinemabox.library.FileProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpeedMeterTest {
    private fun progress(downloaded: Long, active: Boolean = true) =
        FileProgress(0, downloaded, 100_000_000, downloaded, active, false)

    @Test fun averagesOverTheLastTenSeconds() {
        val meter = SpeedMeter()
        meter.add(progress(0), 0)
        meter.add(progress(1_000_000), 1_000)
        assertNull(meter.bytesPerSecond)
        meter.add(progress(2_000_000), 2_000)
        assertEquals(1_000_000.0, meter.bytesPerSecond!!, 1.0)
        // A fast burst later only counts within the last 10 s.
        meter.add(progress(2_000_000), 12_000)
        meter.add(progress(32_000_000), 14_000)
        assertEquals(15_000_000.0, meter.bytesPerSecond!!, 1.0)
    }

    @Test fun idleWindowDoesNotLowerTheMeasuredSpeed() {
        val meter = SpeedMeter()
        meter.add(progress(0), 0)
        meter.add(progress(4_000_000), 2_000)
        meter.add(progress(4_000_000, active = false), 30_000)
        assertEquals(2_000_000.0, meter.bytesPerSecond!!, 1.0)
        meter.add(progress(4_000_000), 31_000)
        assertEquals(2_000_000.0, meter.bytesPerSecond!!, 1.0)
    }
}
