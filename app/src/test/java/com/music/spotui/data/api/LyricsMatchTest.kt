package com.music.spotui.data.api

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsMatchTest {
    private fun match(candTitle: String, candArtist: String, candDur: Int, dur: Int = 200) =
        LyricsApi.isPlausibleMatch(candTitle, candArtist, candDur, "Stay", "Rihanna", dur)

    @Test
    fun sameTitleByAnotherArtistIsRejected() {
        assertFalse(match("Stay", "周杰伦", 200))
        assertFalse(match("Stay", "", 200))
    }

    @Test
    fun sameSongWithExtrasIsAccepted() {
        assertTrue(match("Stay (feat. Mikky Ekko)", "Rihanna, Mikky Ekko", 201))
    }

    @Test
    fun differentLengthIsRejectedOnlyWhenBothAreKnown() {
        assertFalse(match("Stay", "Rihanna", 260))
        assertTrue(match("Stay", "Rihanna", 0))
        assertTrue(match("Stay", "Rihanna", 260, dur = 0))
    }

    @Test
    fun differentTitleIsRejected() {
        assertFalse(match("Diamonds", "Rihanna", 200))
    }
}
