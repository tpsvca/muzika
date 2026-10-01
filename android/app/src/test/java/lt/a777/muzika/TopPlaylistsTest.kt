package lt.a777.muzika

import androidx.test.core.app.ApplicationProvider
import lt.a777.muzika.data.Store
import lt.a777.muzika.data.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Which playlists the widget's second row offers.
 *
 * "Most played" is counted from the listening history rather than from how
 * recently a playlist was touched, so a playlist you actually listen to beats
 * one you made and forgot.
 */
@RunWith(RobolectricTestRunner::class)
class TopPlaylistsTest {

    @Before
    fun setUp() {
        Store.open(ApplicationProvider.getApplicationContext())
        Store.playlists().forEach { Store.deletePlaylist(it.id) }
        Store.clearHistory()
    }

    private fun track(n: Int) = Track(id = "v$n", title = "Track $n", artist = "A", duration = 100)

    @Test
    fun `a playlist you listen to outranks one you only edited`() {
        val listened = Store.createPlaylist("Listened")
        val ignored = Store.createPlaylist("Ignored")   // created later, so newer
        Store.addToPlaylist(listened, track(1))
        Store.addToPlaylist(ignored, track(2))
        repeat(5) { Store.recordPlay(track(1)) }

        val top = Store.topPlaylists(4)
        assertEquals("Listened", top.first().name)
    }

    @Test
    fun `playlists with no plays still appear, newest first`() {
        val older = Store.createPlaylist("Older")
        val newer = Store.createPlaylist("Newer")
        Store.addToPlaylist(older, track(1))
        Store.addToPlaylist(newer, track(2))

        val names = Store.topPlaylists(4).map { it.name }
        assertTrue("a fresh library must not give an empty row", names.isNotEmpty())
        assertEquals(listOf("Newer", "Older"), names)
    }

    @Test
    fun `an empty playlist is not offered`() {
        val full = Store.createPlaylist("Has songs")
        Store.createPlaylist("Empty")
        Store.addToPlaylist(full, track(1))

        assertEquals(listOf("Has songs"), Store.topPlaylists(4).map { it.name })
    }

    @Test
    fun `no more than the slots available`() {
        repeat(6) { index ->
            val id = Store.createPlaylist("P$index")
            Store.addToPlaylist(id, track(index))
        }
        assertEquals(4, Store.topPlaylists(4).size)
    }

    @Test
    fun `an empty library gives an empty row rather than failing`() {
        assertTrue(Store.topPlaylists(4).isEmpty())
    }
}
