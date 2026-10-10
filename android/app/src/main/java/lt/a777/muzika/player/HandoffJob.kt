package lt.a777.muzika.player

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import lt.a777.muzika.data.Prefs
import lt.a777.muzika.widget.NowPlayingWidget

/**
 * Brings the widget up to date with another device's session while the app
 * is not running.
 *
 * The player looks at the handoff file every few seconds, but only while its
 * process is alive and not frozen - and a paused app in the background is
 * neither for long. Then nothing read the file until the app was opened, so
 * the widget stayed on "Rusty Cage" while the desktop played on.
 *
 * Two jobs, so that Android wakes the app instead of it having to stay up:
 *
 *  - [CONTENT_JOB] fires when shared storage changes. From Android 11 every
 *    file written there - Syncthing renaming the handoff file into place
 *    included - goes through MediaStore, which is what this listens to. It
 *    fires once per change and has to be scheduled again each time.
 *  - [PERIODIC_JOB], every 15 minutes (Android's shortest), for the versions
 *    and sync clients the first one does not hear.
 *
 * Both only while a widget is placed: with no widget there is nothing on
 * screen to keep current, and opening the app catches up by itself.
 */
class HandoffJob : JobService() {

    private val handler = Handler(Looper.getMainLooper())

    override fun onStartJob(params: JobParameters): Boolean {
        val again = params.jobId == CONTENT_JOB
        if (!wanted(this)) {
            cancel(this)
            return false
        }
        MuzikaPlayer.restore(this)
        val took = MuzikaPlayer.followOtherDevice()
        if (took) Log.d(TAG, "took over ${MuzikaPlayer.current?.title} (job ${params.jobId})")
        if (!took) {
            // Scheduled once this run is over: scheduling the same id while
            // it runs would stop it.
            if (again) handler.post { schedule(this) }
            return false
        }
        // The widget fetches the new artwork after drawing the text; stay up
        // for it, or the process can be frozen with the old cover showing.
        handler.postDelayed({
            jobFinished(params, false)
            if (again) handler.post { schedule(this) }
        }, ARTWORK_GRACE_MS)
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        handler.removeCallbacksAndMessages(null)
        // A content job stopped early is simply scheduled again.
        if (params.jobId == CONTENT_JOB) handler.post { schedule(this) }
        return false
    }

    companion object {
        private const val TAG = "HandoffJob"
        const val CONTENT_JOB = 7701
        const val PERIODIC_JOB = 7702
        private const val ARTWORK_GRACE_MS = 4_000L
        private const val PERIOD_MS = 15L * 60 * 1000

        private fun wanted(context: Context): Boolean {
            if (Prefs.syncBackend != Prefs.BACKEND_FOLDER) return false
            val manager = AppWidgetManager.getInstance(context) ?: return false
            return manager.getAppWidgetIds(
                ComponentName(context, NowPlayingWidget::class.java)
            ).isNotEmpty()
        }

        /**
         * Make sure both jobs are scheduled - or neither, when there is no
         * widget. Cheap to call often: a job already pending is left alone,
         * so the periodic one's clock is not reset by every call.
         */
        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            if (!wanted(context)) {
                cancel(context)
                return
            }
            val service = ComponentName(context, HandoffJob::class.java)
            runCatching {
                if (scheduler.getPendingJob(CONTENT_JOB) == null) {
                    scheduler.schedule(
                        JobInfo.Builder(CONTENT_JOB, service)
                            .addTriggerContentUri(
                                JobInfo.TriggerContentUri(
                                    MediaStore.Files.getContentUri("external"),
                                    JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS,
                                )
                            )
                            // Syncthing writes a temporary file and renames
                            // it: wait for the burst to end, but not long.
                            .setTriggerContentUpdateDelay(1_000)
                            .setTriggerContentMaxDelay(5_000)
                            .build()
                    )
                }
                if (scheduler.getPendingJob(PERIODIC_JOB) == null) {
                    scheduler.schedule(
                        JobInfo.Builder(PERIODIC_JOB, service)
                            .setPeriodic(PERIOD_MS)
                            .build()
                    )
                }
            }.onFailure { Log.w(TAG, "could not schedule", it) }
        }

        fun cancel(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            scheduler.cancel(CONTENT_JOB)
            scheduler.cancel(PERIODIC_JOB)
        }
    }
}
