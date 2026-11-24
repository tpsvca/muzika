package lt.a777.muzika

import lt.a777.muzika.data.Track
import lt.a777.muzika.sources.SongRadio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A song found by searching and heard out is followed by a radio built from
 * it - its sound, and more by its band. These are the blending rules, the same
 * ones as the desktop's `test_song_radio.py`; keep the two in step.
 */
class SongRadioTest {

    private fun t(id: String, title: String, artist: String = "Someone") =
        Track(id = id, title = title, artist = artist, duration = 200)

    private val seed = t("seed", "Numb", "Gary Clark Jr.")

    private fun ids(tracks: List<Track>) = tracks.map { it.id }

    @Test fun theSongItselfAndOtherRecordingsOfItAreLeftOut() {
        val similar = listOf(seed, t("live", "Numb - Live", "Gary Clark Jr."),
            t("rm", "Numb (Remastered 2020)", "Gary Clark Jr."),
            t("a", "Thickfreakness", "The Black Keys"))
        assertEquals(listOf("a"), ids(SongRadio.blend(seed, similar, emptyList())))
    }

    @Test fun theBandComesBackEveryFourthSongStartingSecond() {
        val similar = (0 until 12).map { t("s$it", "Similar $it", "Band $it") }
        val byArtist = (0 until 3).map { t("a$it", "Own $it", "Gary Clark Jr.") }
        val result = ids(SongRadio.blend(seed, similar, byArtist))
        assertEquals(listOf("s0", "a0", "s1", "s2", "s3", "a1", "s4", "s5", "s6", "a2"),
            result.take(10))
        assertEquals(15, result.size)
    }

    @Test fun theBandNeverTakesOverTheRadio() {
        val similar = (0 until 60).map { t("s$it", "Similar $it", "Band $it") }
        val byArtist = (0 until 40).map { t("a$it", "Own $it", "Gary Clark Jr.") }
        val result = SongRadio.blend(seed, similar, byArtist)
        assertEquals(SongRadio.LIMIT, result.size)
        assertEquals(SongRadio.ARTIST_MAX, result.count { it.id.startsWith("a") })
    }

    @Test fun aSongInBothListsPlaysOnce() {
        val shared = t("x", "Bright Lights", "Gary Clark Jr.")
        val result = SongRadio.blend(seed, listOf(shared, t("s", "Other", "Band")), listOf(shared))
        assertEquals(1, result.count { it.id == "x" })
    }

    @Test fun withNoRadioOfItsOwnTheBandStillPlays() {
        val byArtist = (0 until 3).map { t("a$it", "Own $it", "Gary Clark Jr.") }
        assertEquals(listOf("a0", "a1", "a2"), ids(SongRadio.blend(seed, emptyList(), byArtist)))
    }

    @Test fun heardMeansListenedToNotDraggedToTheEnd() {
        assertTrue(SongRadio.heardEnough(100_000, 200_000))
        assertFalse(SongRadio.heardEnough(20_000, 200_000))
        assertTrue(SongRadio.heardEnough(61_000, 0))
        assertFalse(SongRadio.heardEnough(5_000, 0))
    }

    @Test fun whichQueuesCountAsASearch() {
        assertTrue(SongRadio.isSearchSource("search"))
        assertTrue(SongRadio.isSearchSource("search:soundcloud"))
        assertFalse(SongRadio.isSearchSource(null))
        assertFalse(SongRadio.isSearchSource("playlist:3"))
        assertFalse(SongRadio.isSearchSource(SongRadio.SOURCE_PREFIX + "seed"))
    }

    @Test fun accentsAndBracketsDoNotMakeADifferentSong() {
        assertEquals(SongRadio.songKey(t("1", "Nothing but a Cliché", "Black Joe Lewis & The Honeybears")),
            SongRadio.songKey(t("2", "Nothing But A Cliche [Official Audio]", "Black Joe Lewis")))
    }
}
