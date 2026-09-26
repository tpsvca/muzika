package lt.a777.muzika

import lt.a777.muzika.data.Track
import lt.a777.muzika.lyrics.Lyrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Live tests against the real lyrics providers, like [InnertubeTest]: the
 * failure they exist to catch is a provider changing shape, which a mock
 * cannot see.
 *
 * The case that matters is a track whose title and artist are not the tidy
 * ones a lyrics database indexes. That is the difference between two devices
 * playing the same song - one from a fresh search, one restored from the
 * synced library - and it used to decide whether the words could follow along.
 */
@RunWith(RobolectricTestRunner::class)
class LyricsTest {

    private val tidy = Track(
        id = "lyrics-tidy", title = "Whiskey In The Jar",
        artist = "Metallica", duration = 305,
    )

    /**
     * A track as the library can hold it: no artist, no duration.
     *
     * That is not hypothetical - a row restored from the synced library or
     * read off a badly tagged file arrives exactly like this, while the same
     * song played straight from a search arrives with both. Two devices, two
     * different lookups, and only one of them used to find timings.
     */
    private val messy = Track(
        id = "lyrics-messy", title = "Redneck", artist = "", duration = 0,
    )

    @Before
    fun clearCache() {
        Lyrics.forget(tidy)
        Lyrics.forget(messy)
    }

    @Test
    fun `finds synced lyrics for a tidy title`() {
        val result = Lyrics.fetch(tidy)
        assertNotNull("no lyrics at all for a very well known song", result)
        assertTrue("expected timings, got a plain wall of text", result!!.synced)
        assertTrue(result.lines!!.size > 5)
    }

    @Test
    fun `still finds synced lyrics when the title is noisy and the artist is missing`() {
        val result = Lyrics.fetch(messy)
        assertNotNull(result)
        // The regression: an untimed hit on an early variant used to be
        // returned outright, so the pane could not follow the song.
        assertTrue("settled for untimed lyrics while timed ones exist", result!!.synced)
    }

    @Test
    fun `timings are in order and start at a sane point`() {
        val lines = Lyrics.fetch(tidy)?.lines
        assertNotNull(lines)
        assertEquals(lines!!.sortedBy { it.atMs }, lines)
        assertTrue("first line lands after the song ends", lines.first().atMs < 305_000)
    }

    /**
     * Stored the way a library row often is: the artist inside the title,
     * after the dash, and no artist field at all. Only "Artist - Song" used to
     * be tried, so this was searched for as a song called "Beth Hart" - which
     * finds nothing, while the timed words sit there under the obvious reading.
     */
    @Test
    fun `reads Song - Artist the right way round`() {
        val reversed = Track(
            id = "lyrics-reversed", title = "I'd Rather Go Blind - Beth Hart",
            artist = "", duration = 0,
        )
        Lyrics.forget(reversed)
        val result = Lyrics.fetch(reversed)
        assertNotNull("found nothing for a song LRCLIB has timed", result)
        assertTrue("settled for untimed lyrics", result!!.synced)
    }

    @Test
    fun `a second look is served from cache`() {
        val first = Lyrics.fetch(tidy)
        val second = Lyrics.fetch(tidy)
        assertSame("re-entering the tab refetched from the network", first, second)
    }

    @Test
    fun `forgetting a track makes the next look go back out`() {
        val first = Lyrics.fetch(tidy)
        Lyrics.forget(tidy)
        val second = Lyrics.fetch(tidy)
        assertNotNull(second)
        // Equal content, but a fresh object: proof the network was consulted.
        assertTrue(first !== second)
    }

    @Test
    fun `a track with no lyrics is remembered as having none`() {
        val nothing = Track(
            id = "lyrics-absent",
            title = "qqzzx untitled test tone 40718", artist = "qqzzx", duration = 91,
        )
        Lyrics.forget(nothing)
        val first = Lyrics.fetch(nothing)
        val started = System.currentTimeMillis()
        val second = Lyrics.fetch(nothing)
        val elapsed = System.currentTimeMillis() - started
        assertEquals(first, second)
        assertTrue("a miss was not cached: second look took ${elapsed}ms", elapsed < 100)
    }
}
