package lt.a777.muzika

import android.app.job.JobScheduler
import android.appwidget.AppWidgetManager
import android.content.Context
import android.provider.MediaStore
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import lt.a777.muzika.data.Prefs
import lt.a777.muzika.data.Track
import lt.a777.muzika.player.Handoff
import lt.a777.muzika.player.HandoffJob
import lt.a777.muzika.player.MuzikaPlayer
import lt.a777.muzika.player.Session
import lt.a777.muzika.widget.NowPlayingWidget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File

/**
 * The widget follows the desktop while the app is not running.
 *
 * Reported: paused on the phone, the desktop played on for several songs, and
 * the widget and the shade still said "Rusty Cage" until the app was opened.
 * Only the open app ever looked at the handoff file.
 */
@RunWith(RobolectricTestRunner::class)
class HandoffJobTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val scheduler = context.getSystemService(JobScheduler::class.java)
    private lateinit var folder: File

    private val phoneQueue = listOf(Track(id = "rc", title = "Rusty Cage", artist = "Johnny Cash", duration = 170))
    private val desktopQueue = (0 until 3).map { Track(id = "d$it", title = "Desktop $it", artist = "Band", duration = 200) }

    @Before
    fun setUp() {
        Prefs.open(context)
        folder = File(context.cacheDir, "sync-${System.nanoTime()}").apply { mkdirs() }
        Prefs.updateSyncBackend(Prefs.BACKEND_FOLDER)
        Prefs.updateSyncFolder(folder.path)
        Session.openAt(File(context.cacheDir, "session-${System.nanoTime()}").apply { mkdirs() })
        scheduler.cancelAll()
    }

    private fun placeWidget(): Int =
        shadowOf(AppWidgetManager.getInstance(context))
            .createWidget(NowPlayingWidget::class.java, R.layout.widget_now_playing)

    @Test
    fun `no widget, no jobs`() {
        HandoffJob.schedule(context)
        assertNull(scheduler.getPendingJob(HandoffJob.CONTENT_JOB))
        assertNull(scheduler.getPendingJob(HandoffJob.PERIODIC_JOB))
    }

    @Test
    fun `a placed widget gets a job for every change in shared storage, and a periodic one`() {
        placeWidget()
        HandoffJob.schedule(context)

        val content = scheduler.getPendingJob(HandoffJob.CONTENT_JOB)
        assertNotNull(content)
        val uri = content!!.triggerContentUris!!.single()
        assertEquals(MediaStore.Files.getContentUri("external"), uri.uri)
        val periodic = scheduler.getPendingJob(HandoffJob.PERIODIC_JOB)
        assertTrue(periodic!!.isPeriodic)
    }

    @Test
    fun `with the Dropbox backend there is no file to watch`() {
        placeWidget()
        Prefs.updateSyncBackend(Prefs.BACKEND_DROPBOX)
        HandoffJob.schedule(context)
        assertNull(scheduler.getPendingJob(HandoffJob.CONTENT_JOB))
        Prefs.updateSyncBackend(Prefs.BACKEND_FOLDER)
    }

    @Test
    fun `a widget redraw in a fresh process shows what the desktop has moved on to`() {
        // This phone last played Rusty Cage, paused.
        Session.saveQueue(phoneQueue, listOf(0), false, null)
        Session.savePosition(phoneQueue[0], 0, 60_000, 170_000, 0)
        Session.flush()
        // The desktop wrote later: a different queue, paused on its second song.
        File(folder, Handoff.FILENAME).writeText(
            Handoff.encode(desktopQueue, listOf(0, 1, 2), 1, false, 0, 30_000, 200_000, false,
                "desktop-id", "fedora", System.currentTimeMillis() + 60_000)
        )
        MuzikaPlayer.resetForTest()     // the process the job wakes has nothing in memory

        val id = placeWidget()

        val view = shadowOf(AppWidgetManager.getInstance(context)).getViewFor(id)
        assertEquals("Desktop 1", view.findViewById<TextView>(R.id.widget_title).text.toString())
        assertEquals("d1", MuzikaPlayer.current?.id)
        Session.flush()
    }
}
