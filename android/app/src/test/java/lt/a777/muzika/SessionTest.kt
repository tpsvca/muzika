package lt.a777.muzika

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import lt.a777.muzika.data.Track
import lt.a777.muzika.player.MuzikaPlayer
import lt.a777.muzika.player.REPEAT_ALL
import lt.a777.muzika.player.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Resuming yesterday's queue from the widget.
 *
 * The bug these pin: the queue lived only in memory, so once Android reaped
 * the paused app overnight, play on the widget found nothing loaded and opened
 * the app on Home. Asked for: the same song, the same playlist and the same
 * shuffle, for at least a week after stopping.
 */
@RunWith(RobolectricTestRunner::class)
class SessionTest {

    private lateinit var folder: File
    private val day = 24L * 60 * 60 * 1000

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        folder = File(context.cacheDir, "session-${System.nanoTime()}").apply { mkdirs() }
        Session.openAt(folder)
    }

    private fun track(n: Int) = Track(id = "v$n", title = "Track $n", artist = "Artist $n", duration = 200)

    private val queue = (0 until 6).map { track(it) }
    private val shuffled = listOf(4, 1, 5, 0, 3, 2)

    private fun saved(cursor: Int, trackId: String?, savedAt: Long, positionMs: Long = 95_000) =
        Session.decode(
            Session.encodeQueue(queue, shuffled, true, "local:7", savedAt),
            Session.encodePosition(trackId, cursor, positionMs, 200_000, REPEAT_ALL, savedAt),
            now = savedAt,
        )

    @Test
    fun `the shuffled order, track and spot come back exactly`() {
        val snapshot = saved(cursor = 2, trackId = "v5", savedAt = 1_000)!!
        assertEquals(queue, snapshot.queue)
        assertEquals(shuffled, snapshot.order)
        assertEquals(2, snapshot.cursor)
        assertEquals("v5", snapshot.queue[snapshot.order[snapshot.cursor]].id)
        assertTrue(snapshot.shuffle)
        assertEquals(REPEAT_ALL, snapshot.repeat)
        assertEquals("local:7", snapshot.source)
        assertEquals(95_000L, snapshot.positionMs)
        assertEquals(200_000L, snapshot.durationMs)
    }

    @Test
    fun `every track field survives, so a non-YouTube track can still be resolved`() {
        val odd = Track(
            id = "bc1", title = "T", artist = "A", duration = 3, thumb = null,
            source = "bandcamp", url = "https://x.bandcamp.com/track/t", album = "Al", trackNumber = 4,
        )
        val text = Session.encodeQueue(listOf(odd), listOf(0), false, null, 0)
        val back = Session.decode(text, null, now = 0)!!
        assertEquals(odd, back.queue.single())
        assertNull(back.source)
    }

    @Test
    fun `a week later it still resumes, past the limit it does not`() {
        val text = Session.encodeQueue(queue, shuffled, true, null, 0)
        val position = Session.encodePosition("v5", 2, 95_000, 200_000, 0, 0)
        assertNotNull(Session.decode(text, position, now = 7 * day))
        assertNotNull(Session.decode(text, position, now = Session.MAX_AGE_MS))
        assertNull(Session.decode(text, position, now = Session.MAX_AGE_MS + 1))
    }

    @Test
    fun `listening refreshes the age, not only editing the queue`() {
        val text = Session.encodeQueue(queue, shuffled, true, null, 0)
        val position = Session.encodePosition("v5", 2, 0, 0, 0, savedAt = 20 * day)
        assertNotNull(Session.decode(text, position, now = 45 * day))
    }

    @Test
    fun `a position saved for an older queue follows its track, not its index`() {
        // The queue was rewritten but the process died before the position followed.
        val text = Session.encodeQueue(queue, shuffled, true, null, 0)
        val position = Session.encodePosition("v3", 0, 60_000, 200_000, 0, 0)
        val snapshot = Session.decode(text, position, now = 0)!!
        assertEquals(4, snapshot.cursor)          // where v3 sits in the shuffled order
        assertEquals(60_000L, snapshot.positionMs)
    }

    @Test
    fun `a position for a track no longer queued starts the queue from the top`() {
        val text = Session.encodeQueue(queue, shuffled, true, null, 0)
        val position = Session.encodePosition("gone", 3, 60_000, 200_000, 0, 0)
        val snapshot = Session.decode(text, position, now = 0)!!
        assertEquals(0, snapshot.cursor)
        assertEquals(0L, snapshot.positionMs)     // that spot belonged to another song
    }

    @Test
    fun `damaged or empty files restore nothing rather than crash`() {
        assertNull(Session.decode("{not json", null, now = 0))
        assertNull(Session.decode(Session.encodeQueue(emptyList(), emptyList(), false, null, 0), null, 0))
        val text = Session.encodeQueue(queue, listOf(9, -1, 2, 2), false, null, 0)
        assertEquals(listOf(2), Session.decode(text, "garbage", now = 0)!!.order)
    }

    @Test
    fun `written to disk and read back`() {
        Session.saveQueue(queue, shuffled, true, "local:7")
        Session.savePosition(queue[0], 3, 42_000, 200_000, 0)
        Session.flush()
        val snapshot = Session.load()!!
        assertEquals(shuffled, snapshot.order)
        assertEquals(3, snapshot.cursor)
        assertEquals(42_000L, snapshot.positionMs)

        Session.clear()
        Session.flush()
        assertNull(Session.load())
    }

    @Test
    fun `the player comes back paused on the saved track, ready to resume`() {
        Session.saveQueue(queue, shuffled, true, "local:7")
        Session.savePosition(queue[0], 3, 42_000, 200_000, REPEAT_ALL)
        Session.flush()

        MuzikaPlayer.restore(ApplicationProvider.getApplicationContext())

        assertEquals("v0", MuzikaPlayer.current?.id)
        assertEquals(shuffled.map { "v$it" }, MuzikaPlayer.queueView.map { it.id })
        assertEquals(3, MuzikaPlayer.queueIndex)
        assertTrue(MuzikaPlayer.shuffle)
        assertEquals(REPEAT_ALL, MuzikaPlayer.repeat)
        assertEquals("local:7", MuzikaPlayer.source)
        assertEquals(42_000L, MuzikaPlayer.positionMs)
        assertEquals(false, MuzikaPlayer.isPlaying)
    }
}
