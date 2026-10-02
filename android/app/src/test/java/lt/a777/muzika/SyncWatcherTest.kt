package lt.a777.muzika

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import android.os.Looper
import lt.a777.muzika.data.Prefs
import lt.a777.muzika.data.Store
import lt.a777.muzika.data.Sync
import lt.a777.muzika.data.SyncWatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File

/**
 * Noticing a library file that somebody else has rewritten.
 *
 * The bug these pin: the phone read the sync file once, when Root first
 * composed, so a playlist changed on the desktop was invisible until the app
 * was killed and reopened. Syncthing had already delivered the file - the app
 * simply never looked again.
 *
 * The decision being guarded is how "changed" is decided. It is the file's
 * timestamp and length, re-seeded whenever this app writes the file itself. The
 * tempting alternative - ignore events for a few seconds after our own write -
 * has a window in which a real incoming change arrives during the grace period
 * and is thrown away, which is the failure that would look exactly like the
 * original bug.
 */
@RunWith(RobolectricTestRunner::class)
class SyncWatcherTest {

    private lateinit var folder: File
    private var reloads = 0

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        Store.open(context)
        Prefs.open(context)
        folder = File(context.cacheDir, "watch-${System.nanoTime()}").apply { mkdirs() }
        Prefs.updateSyncFolder(folder.path)
        Prefs.updateSyncBackend(Prefs.BACKEND_FOLDER)
        reloads = 0
    }

    @After
    fun tearDown() {
        SyncWatcher.stop()
    }

    /** A library file as another device would leave it, with a given playlist. */
    private fun writeRemote(playlist: String, tracks: Int) {
        Store.playlists().forEach { Store.deletePlaylist(it.id) }
        val id = Store.createPlaylist(playlist)
        repeat(tracks) { n ->
            Store.addToPlaylist(id, Track(n))
        }
        val payload = Sync.buildPayload()
        // Written straight to disk, bypassing Sync.export, because this stands
        // in for Syncthing - a different process - not for us.
        File(folder, Sync.FILENAME).writeText(payload.toString(1))
        Store.playlists().forEach { Store.deletePlaylist(it.id) }
    }

    private fun Track(n: Int) = lt.a777.muzika.data.Track(
        id = "vid%05d".format(n),
        title = "Track $n",
        artist = "Artist",
        duration = 200,
        thumb = null,
    )

    /** Let the settle delay and any poll tick elapse. */
    private fun settle() {
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2))
    }

    private fun watch() = SyncWatcher.start { reloads++ }

    @Test
    fun `a file rewritten by another device is noticed without a restart`() {
        writeRemote("From desktop", 3)
        watch()                        // baseline: what Root already imported
        settle()
        assertEquals("nothing changed yet", 0, reloads)

        writeRemote("From desktop", 40)  // the desktop adds tracks
        SyncWatcher.check()
        settle()
        assertEquals("the change should have been picked up", 1, reloads)
    }

    @Test
    fun `our own write is not read back as somebody else's change`() {
        writeRemote("Mine", 2)
        watch()
        settle()

        Store.createPlaylist("Made here")
        Sync.export()                  // what Sync.push does after a local edit
        SyncWatcher.check()
        settle()
        assertEquals("exporting must not look like an incoming change", 0, reloads)
    }

    @Test
    fun `a real change arriving right after our own write is still seen`() {
        writeRemote("Mine", 2)
        watch()
        settle()

        Sync.export()                  // our write, re-seeding the fingerprint
        writeRemote("From desktop", 30) // and the other device, immediately after
        SyncWatcher.check()
        settle()
        assertEquals("a grace period would have swallowed this", 1, reloads)
    }

    @Test
    fun `repeated events for one change reload once`() {
        writeRemote("From desktop", 3)
        watch()
        settle()

        writeRemote("From desktop", 30)
        repeat(5) { SyncWatcher.check() }   // a sync client touching the file
        settle()
        assertEquals(1, reloads)
    }

    @Test
    fun `nothing fires once watching has stopped`() {
        writeRemote("From desktop", 3)
        watch()
        settle()
        SyncWatcher.stop()

        writeRemote("From desktop", 30)
        SyncWatcher.check()
        settle()
        assertEquals("a backgrounded app must not be doing this work", 0, reloads)
    }

    @Test
    fun `a missing file is not a change`() {
        watch()
        settle()
        assertEquals(0, reloads)
        SyncWatcher.check()
        settle()
        assertEquals(0, reloads)
    }
}
