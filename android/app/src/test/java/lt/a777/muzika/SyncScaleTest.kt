package lt.a777.muzika

import androidx.test.core.app.ApplicationProvider
import lt.a777.muzika.data.Store
import lt.a777.muzika.data.Sync
import lt.a777.muzika.data.Track
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * What a large library costs the phone.
 *
 * The sync file holds every playlist track in one JSON document, which is
 * fine for a few hundred and worth knowing the shape of for a few thousand.
 * These are timings, not assertions about speed - a test that fails because
 * CI was busy is worse than no test - except for the one guard that matters:
 * the file must stay linear in the number of tracks rather than blowing up.
 */
@RunWith(RobolectricTestRunner::class)
class SyncScaleTest {

    private lateinit var folder: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        Store.open(context)
        folder = File(context.cacheDir, "syncscale").apply { mkdirs() }
    }

    private fun track(n: Int) = Track(
        id = "vid%08d".format(n),
        title = "Track Number $n (Remastered)",
        artist = "Artist ${n % 900}",
        duration = 180 + n % 200,
        thumb = "https://lh3.googleusercontent.com/abcdefghijklmnop%08d=w256-h256".format(n),
    )

    /** Start from an empty library; there is no wipe helper on Store. */
    private fun clear() {
        Store.playlists().forEach { Store.deletePlaylist(it.id) }
        Store.clearHistory()
    }

    private fun fill(playlists: Int, perPlaylist: Int) {
        repeat(playlists) { p ->
            val id = Store.createPlaylist("Playlist $p")
            (0 until perPlaylist).forEach { i -> Store.addToPlaylist(id, track(p * perPlaylist + i)) }
        }
    }

    @Test
    fun `the sync file stays proportional to the library`() {
        val sizes = mutableListOf<Pair<Int, Long>>()
        for ((playlists, per) in listOf(5 to 20, 10 to 50, 20 to 100)) {
            clear()
            fill(playlists, per)
            val file = File(folder, "lib-${playlists}x$per.json")

            val built = System.nanoTime()
            val payload = Sync.buildPayload()
            val buildMs = (System.nanoTime() - built) / 1_000_000

            val wrote = System.nanoTime()
            file.writeText(payload.toString())
            val writeMs = (System.nanoTime() - wrote) / 1_000_000

            clear()
            val read = System.nanoTime()
            Sync.import(file)
            val importMs = (System.nanoTime() - read) / 1_000_000

            val tracks = playlists * per
            sizes += tracks to file.length()
            println(
                "  %6d tracks  %8.2f KB  build %4dms  write %4dms  import %5dms"
                    .format(tracks, file.length() / 1024.0, buildMs, writeMs, importMs)
            )
        }

        // The guard: bytes per track must not grow with the library. Anything
        // superlinear here means the format has started repeating itself.
        val perTrack = sizes.map { (tracks, bytes) -> bytes.toDouble() / tracks }
        val worst = perTrack.max() / perTrack.min()
        println("  bytes per track: ${perTrack.map { "%.0f".format(it) }}  spread ${"%.2f".format(worst)}x")
        assert(worst < 1.5) { "cost per track is not linear: $perTrack" }
    }
}
