package lt.a777.muzika.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.widget.RemoteViews
import coil.ImageLoader
import coil.request.ImageRequest
import coil.transform.RoundedCornersTransformation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import lt.a777.muzika.R
import lt.a777.muzika.player.MuzikaPlayer
import lt.a777.muzika.ui.MainActivity

/**
 * The home-screen widget: what is playing, and the three controls that matter.
 *
 * Artwork is fetched asynchronously and pushed as a second update, because a
 * widget update has to return promptly and cannot block on the network.
 */
class NowPlayingWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        manager: AppWidgetManager,
        ids: IntArray,
    ) = render(context, manager, ids)

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_TOGGLE -> withPlayer(context) { MuzikaPlayer.toggle() }
            ACTION_NEXT -> withPlayer(context) { MuzikaPlayer.next() }
            ACTION_PREVIOUS -> withPlayer(context) { MuzikaPlayer.previous() }
            else -> super.onReceive(context, intent)
        }
        if (intent.action in CONTROL_ACTIONS) refresh(context)
    }

    /**
     * A control is only meaningful once something is loaded. When the process
     * was not even running there is no queue to act on, so open the app
     * instead of silently doing nothing.
     */
    private fun withPlayer(context: Context, action: () -> Unit) {
        if (MuzikaPlayer.current != null) action() else openApp(context)
    }

    private fun openApp(context: Context) {
        context.startActivity(
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    companion object {
        const val ACTION_TOGGLE = "lt.a777.muzika.widget.TOGGLE"
        const val ACTION_NEXT = "lt.a777.muzika.widget.NEXT"
        const val ACTION_PREVIOUS = "lt.a777.muzika.widget.PREVIOUS"
        private val CONTROL_ACTIONS = setOf(ACTION_TOGGLE, ACTION_NEXT, ACTION_PREVIOUS)

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

        // A widget update rebuilds the whole RemoteViews, so the artwork has
        // to be re-applied every time or it reverts to the placeholder. It is
        // cached here and only refetched when the track actually changes -
        // otherwise the periodic progress updates would hammer the network.
        private var artUrl: String? = null
        private var artBitmap: Bitmap? = null

        /** Called whenever the player changes, so the widget tracks playback. */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(
                ComponentName(context, NowPlayingWidget::class.java)
            )
            if (ids.isEmpty()) return
            render(context, manager, ids)
        }

        private fun intent(context: Context, action: String): PendingIntent =
            PendingIntent.getBroadcast(
                context, action.hashCode(),
                Intent(context, NowPlayingWidget::class.java).setAction(action),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        private fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val track = MuzikaPlayer.current
            val views = RemoteViews(context.packageName, R.layout.widget_now_playing)

            views.setTextViewText(R.id.widget_title, track?.title ?: "Muzika")
            views.setTextViewText(
                R.id.widget_artist,
                track?.artist?.ifEmpty { null } ?: "Nothing playing"
            )
            views.setImageViewResource(
                R.id.widget_toggle,
                if (MuzikaPlayer.isPlaying) R.drawable.ic_widget_pause
                else R.drawable.ic_widget_play,
            )
            views.setContentDescription(
                R.id.widget_toggle,
                if (MuzikaPlayer.isPlaying) "Pause" else "Play",
            )
            val artwork = track?.thumb
            val cached = artBitmap.takeIf { artwork != null && artwork == artUrl }
            if (cached != null) views.setImageViewBitmap(R.id.widget_art, cached)
            else views.setImageViewResource(R.id.widget_art, R.drawable.ic_widget_note)

            // Scaled to a fixed 1000 steps rather than seconds, so the bar does
            // not jump when a track of a different length starts.
            val duration = MuzikaPlayer.durationMs
            val progress = if (duration > 0)
                (MuzikaPlayer.positionMs * 1000 / duration).toInt().coerceIn(0, 1000) else 0
            views.setProgressBar(R.id.widget_progress, 1000, progress, false)

            views.setOnClickPendingIntent(
                R.id.widget_root,
                PendingIntent.getActivity(
                    context, 0,
                    Intent(context, MainActivity::class.java)
                        .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            views.setOnClickPendingIntent(R.id.widget_toggle, intent(context, ACTION_TOGGLE))
            views.setOnClickPendingIntent(R.id.widget_next, intent(context, ACTION_NEXT))
            views.setOnClickPendingIntent(R.id.widget_previous, intent(context, ACTION_PREVIOUS))

            manager.updateAppWidget(ids, views)

            if (artwork != null && cached == null) {
                scope.launch { loadArtwork(context, manager, ids, artwork) }
            }
        }

        private suspend fun loadArtwork(
            context: Context,
            manager: AppWidgetManager,
            ids: IntArray,
            url: String,
        ) {
            val request = ImageRequest.Builder(context)
                .data(url)
                .size(256, 256)
                .transformations(RoundedCornersTransformation(24f))
                .allowHardware(false)   // RemoteViews needs a real, readable bitmap
                .build()
            val drawable = ImageLoader(context).execute(request).drawable ?: return
            val bitmap = (drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
                ?: return
            val safe = if (Build.VERSION.SDK_INT >= 26 &&
                bitmap.config == Bitmap.Config.HARDWARE
            ) bitmap.copy(Bitmap.Config.ARGB_8888, false) else bitmap
            artUrl = url
            artBitmap = safe
            val views = RemoteViews(context.packageName, R.layout.widget_now_playing)
            views.setImageViewBitmap(R.id.widget_art, safe)
            manager.partiallyUpdateAppWidget(ids, views)
        }
    }
}
