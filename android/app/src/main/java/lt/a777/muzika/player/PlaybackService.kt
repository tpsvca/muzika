package lt.a777.muzika.player

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import lt.a777.muzika.ui.MainActivity

/**
 * Hosts the media session, which is what gives the notification, the media
 * chip in the shade, lock-screen controls and hardware media keys - the
 * Android equivalent of MPRIS.
 */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    companion object {
        /**
         * Whether the service is alive. Android refuses a foreground-service
         * start from a backgrounded app, so asking for one we do not need is
         * not harmless - it throws, and it spends the app's one allowance.
         */
        @Volatile
        var running: Boolean = false
            private set
    }

    override fun onCreate() {
        super.onCreate()
        running = true
        MuzikaPlayer.attach(this)
        val player = MuzikaPlayer.exo ?: return

        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

        session = MediaSession.Builder(this, QueuePlayer(player))
            .setSessionActivity(openApp)
            .build()
            // Registering the session is what makes the notification appear.
            // A session is only added automatically when a MediaController
            // connects, and nothing here uses one - the UI drives the player
            // directly - so without this the service never goes foreground and
            // the system shows no media controls at all.
            .also { addSession(it) }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** Come back if the system kills us while there is still a queue. */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    /** Swiping the app away should not strand a silent service. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
            return
        }
        // Still playing: hand back to Media3 rather than swallowing the call,
        // so it keeps its own foreground bookkeeping straight.
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        running = false
        session?.run { release() }
        session = null
        super.onDestroy()
    }
}
