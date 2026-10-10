package lt.a777.muzika

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import lt.a777.muzika.data.Prefs
import lt.a777.muzika.data.Track
import lt.a777.muzika.player.Handoff
import lt.a777.muzika.player.MuzikaPlayer
import lt.a777.muzika.player.REPEAT_ALL
import lt.a777.muzika.player.REPEAT_NONE
import lt.a777.muzika.player.REPEAT_ONE
import lt.a777.muzika.player.Session
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Pause on one device, press play on another, carry on.
 *
 * Asked for: the same song and the same shuffled playlist moving between the
 * desktop and the phone in both directions, with play taking over the other
 * device's session automatically whenever it is the newer one.
 */
@RunWith(RobolectricTestRunner::class)
class HandoffTest {

    private lateinit var folder: File
    private val minute = 60_000L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        Prefs.open(context)
        folder = File(context.cacheDir, "handoff-${System.nanoTime()}").apply { mkdirs() }
        Prefs.updateSyncBackend(Prefs.BACKEND_FOLDER)
        Prefs.updateSyncFolder(folder.path)
        Session.openAt(File(context.cacheDir, "session-${System.nanoTime()}").apply { mkdirs() })
    }

    /** Three-minute tracks, so the arithmetic below is easy to follow. */
    private fun track(n: Int) = Track(id = "v$n", title = "Track $n", artist = "Artist", duration = 180)

    private val queue = (0 until 5).map { track(it) }
    private val shuffled = listOf(3, 0, 4, 1, 2)

    private fun desktopFile(
        updatedAt: Long, playing: Boolean = false, positionMs: Long = 50_000, cursor: Int = 1,
        repeat: Int = REPEAT_NONE, deviceId: String = "desktop-id",
    ) = Handoff.encode(queue, shuffled, cursor, true, repeat, positionMs, 180_000, playing,
        deviceId, "fedora", updatedAt)

    private fun write(text: String) = File(folder, Handoff.FILENAME).writeText(text)

    @Test
    fun `a paused session comes across exactly`() {
        val remote = Handoff.decode(desktopFile(updatedAt = 1_000_000), now = 1_000_000 + 10 * minute)!!
        assertEquals("fedora", remote.device)
        assertEquals(shuffled, remote.snapshot.order)
        assertEquals(1, remote.snapshot.cursor)
        assertEquals("v0", remote.snapshot.queue[remote.snapshot.order[1]].id)
        assertTrue(remote.snapshot.shuffle)
        assertEquals(50_000L, remote.snapshot.positionMs)   // paused: no winding forward
    }

    @Test
    fun `a session still playing over there is wound forward to where it has got`() {
        val now = 1_000_000L + 30_000
        val remote = Handoff.decode(desktopFile(updatedAt = 1_000_000, playing = true), now)!!
        assertEquals(1, remote.snapshot.cursor)
        assertEquals(80_000L, remote.snapshot.positionMs)
    }

    @Test
    fun `winding forward crosses into the next tracks of the shuffled order`() {
        // 50 s into a 180 s track, then 7 more minutes: 130 s finish it, the
        // next takes 180 s, and that leaves 110 s into the one after.
        val now = 1_000_000L + 7 * minute
        val remote = Handoff.decode(desktopFile(updatedAt = 1_000_000, playing = true), now)!!
        assertEquals(3, remote.snapshot.cursor)
        assertEquals(110_000L, remote.snapshot.positionMs)
    }

    @Test
    fun `winding forward respects repeat`() {
        assertEquals(0 to 10_000L, Handoff.advance(queue, shuffled, 4, 190_000, REPEAT_ALL))
        assertEquals(4 to 10_000L, Handoff.advance(queue, shuffled, 4, 190_000, REPEAT_ONE))
        assertEquals(4 to 0L, Handoff.advance(queue, shuffled, 4, 190_000, REPEAT_NONE))
    }

    @Test
    fun `a writer that died mid-song is not wound forward for hours`() {
        val now = 1_000_000L + Handoff.MAX_EXTRAPOLATE_MS + 1
        val remote = Handoff.decode(desktopFile(updatedAt = 1_000_000, playing = true), now)!!
        assertEquals(1, remote.snapshot.cursor)
        assertEquals(50_000L, remote.snapshot.positionMs)
    }

    @Test
    fun `only a newer session from another device is taken over`() {
        val now = System.currentTimeMillis()
        write(desktopFile(updatedAt = now - minute))
        assertNotNull(Handoff.newerThan(localChangedAt = now - 2 * minute, now = now))
        assertNull(Handoff.newerThan(localChangedAt = now, now = now))

        // This device's own write is never something to take over.
        write(desktopFile(updatedAt = now - minute, deviceId = Prefs.deviceId))
        assertNull(Handoff.newerThan(localChangedAt = 0, now = now))
    }

    @Test
    fun `nothing is read or written without a sync folder`() {
        write(desktopFile(updatedAt = System.currentTimeMillis()))
        Prefs.updateSyncBackend(Prefs.BACKEND_DROPBOX)
        assertNull(Handoff.newerThan(0))
        Prefs.updateSyncBackend(Prefs.BACKEND_FOLDER)
    }

    @Test
    fun `the file written is the one documented for the desktop to read`() {
        val text = Handoff.encode(queue, shuffled, 2, true, REPEAT_ALL, 12_345, 180_000, true,
            "phone-id", "Pixel 7 Pro", 1_790_000_000_000)
        val json = JSONObject(text)
        assertEquals("muzika-nowplaying", json.getString("format"))
        assertEquals(1_790_000_000.0, json.getDouble("updated_at"), 0.001)
        assertEquals("all", json.getString("repeat"))
        assertEquals("v4", json.getString("track_id"))
        assertEquals("Artist", json.getJSONArray("queue").getJSONObject(0).getString("artist"))
        assertTrue(json.getBoolean("playing"))
    }

    @Test
    fun `garbage and other formats are ignored`() {
        assertNull(Handoff.decode("{nope", 0))
        assertNull(Handoff.decode("""{"format":"muzika-library","queue":[],"order":[]}""", 0))
    }

    @Test
    fun `the player takes over a newer session paused, and says where it came from`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        MuzikaPlayer.resetForTest()
        MuzikaPlayer.restore(context)
        // Newer than anything this device can have done - a clock running ahead.
        write(desktopFile(updatedAt = System.currentTimeMillis() + minute, cursor = 3, positionMs = 42_000))

        assertTrue(MuzikaPlayer.followOtherDevice(announce = true))

        assertEquals("v1", MuzikaPlayer.current?.id)
        assertEquals(shuffled.map { "v$it" }, MuzikaPlayer.queueView.map { it.id })
        assertEquals(3, MuzikaPlayer.queueIndex)
        assertTrue(MuzikaPlayer.shuffle)
        assertEquals(42_000L, MuzikaPlayer.positionMs)
        assertFalse(MuzikaPlayer.isPlaying)
        assertTrue(MuzikaPlayer.notice!!.contains("fedora"))

        // Taken over once, even though its clock keeps it "newer" than us.
        assertFalse(MuzikaPlayer.followOtherDevice())
        Session.flush()
    }
}
