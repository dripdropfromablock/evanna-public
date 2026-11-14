package com.evanna.player.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CarouselTextTest {
    @Test fun theAlbumArtistIsLeftOffTheirOwnTracks() {
        assertNull(trackGuests("Yeat", "Yeat"))
        assertNull(trackGuests(" yeat ", "Yeat"))
        assertNull(trackGuests(null, "Yeat"))
        assertNull(trackGuests("", "Yeat"))
    }

    @Test fun guestsShowAsFeaturing() {
        assertEquals("feat. Gunna", trackGuests("Yeat, Gunna", "Yeat"))
        assertEquals("feat. Kanye West", trackGuests("Playboi Carti & Kanye West", "Playboi Carti"))
        assertEquals("feat. Future", trackGuests("Yeat feat. Future", "Yeat"))
    }

    @Test fun someoneElsesTrackShowsTheirName() {
        assertEquals("Drake", trackGuests("Drake", "Yeat"))
        assertEquals("Yeatman", trackGuests("Yeatman", "Yeat"))  // a longer name, not a guest
        assertEquals("Gunna", trackGuests("Gunna", null))
    }

    @Test fun theInfoLineNamesTheYearSongsAndLength() {
        assertEquals("2023 · 22 songs · 1 h 12 min", albumInfoLine(2023, 22, (72 * 60 + 10) * 1000L))
        assertEquals("1 song · 3 min", albumInfoLine(null, 1, 185_000L))
        assertEquals("2020 · 12 songs", albumInfoLine(2020, 12, null))
        assertEquals("2020 · 12 songs", albumInfoLine(2020, 12, 0L))
    }

    @Test fun lengthsRoundToTheMinute() {
        assertEquals("38 min", albumLength(38 * 60_000L + 20_000))
        assertEquals("39 min", albumLength(38 * 60_000L + 40_000))
        assertEquals("1 h", albumLength(60 * 60_000L))
        assertEquals("2 h 5 min", albumLength(125 * 60_000L))
    }
}
