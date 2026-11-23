package com.ashe.cinemabox.library

import org.junit.Assert.*
import org.junit.Test

class PlaybackPolicyTest {
    @Test fun resumesOnlyUnfinishedPlayback() {
        val base = PlaybackRecord(1, 2, 3, durationMs = 120_000)
        assertEquals(0L, resumePosition(null))
        assertEquals(0L, resumePosition(base.copy(positionMs = 4_000)))
        assertEquals(65_000L, resumePosition(base.copy(positionMs = 65_000)))
        assertEquals(0L, resumePosition(base.copy(positionMs = 115_000)))
        assertEquals(65_000L, resumePosition(base.copy(positionMs = 65_000, durationMs = 0)))
    }

    @Test fun groupsOnlyExplicitSeriesTags() {
        val video = MediaItem(1, 2, MediaKind.Video, 3, "Video", 10, 0, 100, null, null)
        assertNull(seriesName(video))
        assertNull(seriesName(video.copy(tags = listOf("#accion", "#t1e01"))))
        assertEquals("Planeta Tierra", seriesName(video.copy(tags = listOf("#serie_Planeta_Tierra", "#t1e01"))))
        assertNull(seriesName(video.copy(tags = listOf("#serie_"))))
    }
}
