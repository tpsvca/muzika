package lt.a777.muzika

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import lt.a777.muzika.data.Prefs
import lt.a777.muzika.data.Track
import lt.a777.muzika.player.MuzikaPlayer
import lt.a777.muzika.player.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * A paused player must never start playing by itself.
 *
 * The bug: left paused on "cardigan", the test phone was found 25 minutes
 * later on the next song. Its stream URL had gone stale in the background,
 * and every recovery path - the retry, the skip after a second failure -
 * ended in play(). In a pocket that is music out of nowhere; with handoff it
 * also made the phone the "newest" session and stole it from the desktop.
 */
@RunWith(RobolectricTestRunner::class)
class PausedErrorTest {

    private val queue = (0 until 4).map { Track(id = "v$it", title = "Track $it", duration = 200) }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        Prefs.open(context)
        // No sync folder, so no other device's session can get in the way.
        Prefs.updateSyncFolder(File(context.cacheDir, "none-${System.nanoTime()}").path)
        Session.openAt(File(context.cacheDir, "session-${System.nanoTime()}").apply { mkdirs() })
        Session.saveQueue(queue, listOf(2, 0, 3, 1), true, null)
        Session.savePosition(queue[0], 1, 30_000, 200_000, 0)
        Session.flush()
        MuzikaPlayer.resetForTest()
        MuzikaPlayer.restore(context)
        assertEquals("v0", MuzikaPlayer.current?.id)
    }

    @Test
    fun `a stream that dies while paused leaves the song, the spot and the silence alone`() {
        MuzikaPlayer.onStreamError(73_000)

        assertEquals("v0", MuzikaPlayer.current?.id)     // not skipped
        assertEquals(1, MuzikaPlayer.queueIndex)
        assertEquals(73_000L, MuzikaPlayer.positionMs)    // where it died, ready for play
        assertFalse(MuzikaPlayer.loading)                 // nothing being fetched behind your back
        assertFalse(MuzikaPlayer.isPlaying)
        assertNull(MuzikaPlayer.errorText)                // and nothing to complain about
    }

    @Test
    fun `while playing, a failure is retried once before moving on`() {
        MuzikaPlayer.play()                               // wants sound; fetching begins
        MuzikaPlayer.onStreamError(10_000)
        assertEquals("v0", MuzikaPlayer.current?.id)      // first failure: same song again
        assertNull(MuzikaPlayer.errorText)

        MuzikaPlayer.onStreamError(10_000)
        assertNotNull(MuzikaPlayer.errorText)             // second: give up on it
        assertEquals("v3", MuzikaPlayer.current?.id)      // and move on, in shuffled order
    }

    @Test
    fun `pausing while a song is still being fetched keeps it from starting`() {
        MuzikaPlayer.play()
        MuzikaPlayer.toggle()                             // pressed again before sound arrived
        MuzikaPlayer.onStreamError(5_000)                 // and then the fetch fails anyway
        assertEquals("v0", MuzikaPlayer.current?.id)
        assertNull(MuzikaPlayer.errorText)
        assertFalse(MuzikaPlayer.loading)
    }
}
