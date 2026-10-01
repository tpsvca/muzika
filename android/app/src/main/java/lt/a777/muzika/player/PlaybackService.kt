package lt.a777.muzika.player

import android.app.PendingIntent
import android.content.Intent
import android.view.KeyEvent
import androidx.core.content.IntentCompat
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
            .setCallback(ButtonCallback)
            .setSessionActivity(openApp)
            .build()
            // Registering the session is what makes the notification appear.
            // A session is only added automatically when a MediaController
            // connects, and nothing here uses one - the UI drives the player
            // directly - so without this the service never goes foreground and
            // the system shows no media controls at all.
            .also { addSession(it) }
    }

    /**
     * Skip buttons from a car, a headset or a Bluetooth remote.
     *
     * These arrive as media-button key events. Media3 decides whether to act
     * on one by consulting the commands it has cached for the player, and our
     * queue is not ExoPlayer's - [QueuePlayer] advertises the skip commands,
     * but nothing ever tells Media3 they appeared, so the event was dropped
     * before the player saw it. Verified on a phone: sending
     * KEYCODE_MEDIA_NEXT changed nothing and QueuePlayer was never called.
     *
     * Handling the event here sidesteps that bookkeeping entirely.
     */
    private object ButtonCallback : MediaSession.Callback {
        override fun onMediaButtonEvent(
            session: MediaSession,
            controllerInfo: MediaSession.ControllerInfo,
            intent: Intent,
        ): Boolean {
            val event = IntentCompat.getParcelableExtra(
                intent, Intent.EXTRA_KEY_EVENT, KeyEvent::class.java
            ) ?: return false
            if (event.action != KeyEvent.ACTION_DOWN) return false
            return when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_NEXT -> { MuzikaPlayer.next(); true }
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { MuzikaPlayer.previous(); true }
                else -> false
            }
        }
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
