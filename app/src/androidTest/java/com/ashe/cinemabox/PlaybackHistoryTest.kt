package com.ashe.cinemabox

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ashe.cinemabox.library.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaybackHistoryTest {
    @Test fun progressFavoritesAndCountsSurviveRecreationAndStayAccountScoped() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val account = -System.nanoTime()
        val item = MediaItem(-7, 42, MediaKind.Video, 1, "Test fixture", 120, 0, 100, null, null)
        val history = PlaybackHistory(context, account)
        history.save(item, 45_000, 120_000)
        history.favorite(item)
        history.played(item)
        withTimeout(10_000) { history.records.first { it.singleOrNull()?.plays == 1 } }
        val recreated = PlaybackHistory(context, account)
        val record = withTimeout(10_000) { recreated.records.first { it.isNotEmpty() }.single() }
        assertEquals(45_000L, resumePosition(record))
        assertTrue(record.favorite)
        assertEquals(1, record.plays)
        val other = PlaybackHistory(context, account - 1)
        other.save(item, 8_000, 120_000)
        val isolated = withTimeout(10_000) { other.records.first { it.isNotEmpty() }.single() }
        assertFalse(isolated.favorite)
        assertEquals(0, isolated.plays)
        assertEquals(8_000L, isolated.positionMs)
        assertEquals(45_000L, recreated.record(item)?.positionMs)
    }
}
