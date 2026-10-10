package lt.a777.muzika

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import lt.a777.muzika.data.Prefs
import lt.a777.muzika.data.Syncthing
import lt.a777.muzika.data.Track
import lt.a777.muzika.player.Handoff
import lt.a777.muzika.player.Session
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * A pause on the phone has to reach the desktop in seconds, not at
 * Syncthing's next hourly rescan. Given its API key, Muzika asks it to scan
 * the file it just wrote. Here a stand-in Syncthing API answers on loopback.
 */
@RunWith(RobolectricTestRunner::class)
class SyncthingTest {

    private val scans = CopyOnWriteArrayList<String>()
    private val keysSeen = CopyOnWriteArrayList<String>()
    private val folders = AtomicReference(
        """[{"id":"muzika-lib","label":"Muzika library","path":"/storage/emulated/0/Muzika"}]"""
    )

    /** Just enough HTTP/1.1 for one request per connection. */
    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val port get() = server.localPort

    private fun answer(method: String, target: String, headers: Map<String, String>): Pair<Int, String> {
        val key = headers["x-api-key"] ?: ""
        return when {
            target.startsWith("/rest/config/folders") -> {
                keysSeen += key
                if (key == "right-key") 200 to folders.get() else 403 to ""
            }
            target.startsWith("/rest/db/scan") && method == "POST" && key == "right-key" -> {
                scans += target.substringAfter('?')
                200 to ""
            }
            else -> 403 to ""
        }
    }

    private fun serve() {
        while (!server.isClosed) {
            val socket = runCatching { server.accept() }.getOrNull() ?: return
            socket.use {
                val reader = it.getInputStream().bufferedReader()
                val (method, target) = reader.readLine().split(" ").let { parts -> parts[0] to parts[1] }
                val headers = generateSequence { reader.readLine()?.takeIf { line -> line.isNotEmpty() } }
                    .associate { line -> line.substringBefore(':').lowercase() to line.substringAfter(':').trim() }
                val length = headers["content-length"]?.toIntOrNull() ?: 0
                repeat(length) { reader.read() }
                val (code, body) = answer(method, target, headers)
                val bytes = body.toByteArray()
                it.getOutputStream().apply {
                    write(("HTTP/1.1 $code X\r\nContent-Type: application/json\r\n" +
                        "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray())
                    write(bytes)
                    flush()
                }
            }
        }
    }

    @Before
    fun setUp() {
        thread(isDaemon = true) { serve() }
        Syncthing.addresses = listOf("http://127.0.0.1:$port")
        Syncthing.resetForTest()

        val context = ApplicationProvider.getApplicationContext<Context>()
        Prefs.open(context)
        Prefs.updateSyncBackend(Prefs.BACKEND_FOLDER)
        Prefs.updateSyncFolder("/storage/emulated/0/Muzika")
        Prefs.updateSyncthingKey("right-key")
    }

    @After
    fun tearDown() {
        server.close()
        Prefs.updateSyncthingKey("")
    }

    @Test
    fun `the file just written is the one asked for, in the folder Syncthing shares`() {
        assertTrue(Syncthing.scan(File("/storage/emulated/0/Muzika/muzika-nowplaying.json")))
        assertEquals(listOf("folder=muzika-lib&sub=muzika-nowplaying.json"), scans)
    }

    @Test
    fun `a sync folder inside a bigger Syncthing folder is scanned by its path in there`() {
        folders.set("""[{"id":"phone","path":"/sdcard/"}]""")
        Prefs.updateSyncFolder("/storage/emulated/0/Muzika")
        assertTrue(Syncthing.scan(File("/storage/emulated/0/Muzika/muzika-nowplaying.json")))
        assertEquals(listOf("folder=phone&sub=Muzika%2Fmuzika-nowplaying.json"), scans)
    }

    @Test
    fun `without a key nothing is sent at all`() {
        Prefs.updateSyncthingKey("")
        assertFalse(Syncthing.scan())
        assertTrue(keysSeen.isEmpty())
        assertEquals("No API key set", Syncthing.describe())
    }

    @Test
    fun `the settings check says what went wrong`() {
        Prefs.updateSyncthingKey("wrong-key")
        assertEquals("Syncthing refused the API key", Syncthing.describe())

        Prefs.updateSyncthingKey("right-key")
        Prefs.updateSyncFolder("/storage/emulated/0/Elsewhere")
        assertEquals("Syncthing does not share /storage/emulated/0/Elsewhere", Syncthing.describe())

        Prefs.updateSyncFolder("/storage/emulated/0/Muzika")
        Syncthing.describe().let { assertTrue(it, it.startsWith("Connected")) }

        // A port nothing listens on. (Closing the stand-in server is not enough:
        // a socket closed while its thread waits in accept() can still take one more.)
        val nobody = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        Syncthing.addresses = listOf("http://127.0.0.1:$nobody")
        Syncthing.describe().let { assertTrue(it, it.contains("not running")) }
    }

    @Test
    fun `pausing writes the handoff file and asks Syncthing to send it`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val folder = File(context.cacheDir, "sync-${System.nanoTime()}").apply { mkdirs() }
        folders.set("""[{"id":"muzika-lib","path":"${folder.path}"}]""")
        Prefs.updateSyncFolder(folder.path)
        Session.openAt(File(context.cacheDir, "session-${System.nanoTime()}").apply { mkdirs() })

        val queue = (0 until 3).map { Track(id = "v$it", title = "Track $it", duration = 180) }
        Handoff.publish(queue, listOf(2, 0, 1), 1, true, 0, 42_000, 180_000, playing = false)
        Session.flush()

        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (scans.isEmpty() && System.nanoTime() < deadline) Thread.sleep(20)
        assertTrue(File(folder, Handoff.FILENAME).isFile)
        assertEquals(listOf("folder=muzika-lib&sub=muzika-nowplaying.json"), scans)
    }
}
