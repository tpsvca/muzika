package lt.a777.muzika.player

import android.annotation.SuppressLint
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import lt.a777.muzika.data.Store
import lt.a777.muzika.data.Track
import lt.a777.muzika.sources.Sources
import lt.a777.muzika.widget.NowPlayingWidget

const val REPEAT_NONE = 0
const val REPEAT_ALL = 1
const val REPEAT_ONE = 2

/**
 * Playback and the queue.
 *
 * The queue keeps tracks in the order they were added; [order] is the sequence
 * actually played. Shuffling permutes the order, never the queue, so turning
 * shuffle off restores what you had without refetching anything.
 */
@SuppressLint("StaticFieldLeak")
object MuzikaPlayer {
    private lateinit var context: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    var exo: ExoPlayer? = null
        private set

    private var queue: List<Track> = emptyList()
    private var order: MutableList<Int> = mutableListOf()
    private var cursor: Int = -1
    private var resolveToken = 0

    // Observed by Compose
    var current by mutableStateOf<Track?>(null)
        private set
    var isPlaying by mutableStateOf(false)
        private set
    var loading by mutableStateOf(false)
        private set
    var shuffle by mutableStateOf(false)
        private set
    var repeat by mutableIntStateOf(REPEAT_NONE)
        private set
    var positionMs by mutableStateOf(0L)
        private set
    var durationMs by mutableStateOf(0L)
        private set
    var errorText by mutableStateOf<String?>(null)
    var queueView by mutableStateOf<List<Track>>(emptyList())
        private set
    var queueIndex by mutableIntStateOf(-1)
        private set
    // Compose state, not a plain field: the detail pages decide whether to show
    // Play or Pause by comparing against it, and a plain field never invalidates.
    var source by mutableStateOf<String?>(null)
        private set

    /** Whether the queue has somewhere to go - the notification asks too. */
    val hasNext: Boolean
        get() = repeat != REPEAT_NONE || cursor + 1 < order.size
    val hasPrevious: Boolean
        get() = cursor > 0 || (exo?.currentPosition ?: 0) > 5000

    fun attach(appContext: Context) {
        if (exo != null) return
        context = appContext.applicationContext
        exo = ExoPlayer.Builder(context).build().also { player ->
            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) next(user = false)
                    if (state == Player.STATE_READY) durationMs = player.duration.coerceAtLeast(0)
                }

                override fun onIsPlayingChanged(playing: Boolean) {
                    isPlaying = playing
                    notifyWidget()
                }

                override fun onPlayerError(error: PlaybackException) {
                    errorText = "Could not play “${current?.title.orEmpty()}”"
                    next(user = false)
                }
            })
        }
        tick()
    }

    private fun tick() {
        scope.launch {
            while (true) {
                exo?.let {
                    positionMs = it.currentPosition.coerceAtLeast(0)
                    if (it.duration > 0) durationMs = it.duration
                }
                kotlinx.coroutines.delay(500)
            }
        }
    }

    // ------------------------------------------------------------ queue edits

    fun setQueue(tracks: List<Track>, start: Int? = null, shuffled: Boolean? = null,
                 sourceId: String? = null) {
        val playable = tracks.filter { it.id.isNotEmpty() }
        if (playable.isEmpty()) return
        queue = playable
        source = sourceId
        val useShuffle = shuffled ?: shuffle
        shuffle = useShuffle
        order = playable.indices.toMutableList()
        if (useShuffle) {
            order.shuffle()
            if (start != null && start in playable.indices) {
                order.remove(start); order.add(0, start)
            }
            cursor = 0
        } else {
            cursor = start?.takeIf { it in playable.indices } ?: 0
        }
        publishQueue()
        loadCurrent()
    }

    fun append(tracks: List<Track>) {
        val playable = tracks.filter { it.id.isNotEmpty() }
        if (playable.isEmpty()) return
        val base = queue.size
        queue = queue + playable
        val fresh = (base until base + playable.size).toMutableList()
        if (shuffle) fresh.shuffle()
        order.addAll(fresh)
        publishQueue()
        if (cursor < 0) { cursor = 0; loadCurrent() }
    }

    fun playNext(track: Track) {
        queue = queue + track
        order.add((cursor + 1).coerceAtMost(order.size), queue.size - 1)
        publishQueue()
    }

    fun jumpTo(position: Int) {
        if (position in order.indices) { cursor = position; loadCurrent() }
    }

    fun removeAt(position: Int) {
        if (position !in order.indices) return
        order.removeAt(position)
        if (position < cursor) cursor--
        else if (position == cursor) { cursor--; next() }
        publishQueue()
    }

    fun clear() {
        exo?.stop(); exo?.clearMediaItems()
        queue = emptyList(); order = mutableListOf(); cursor = -1
        current = null; isPlaying = false; source = null
        publishQueue()
    }

    private fun publishQueue() {
        queueView = order.mapNotNull { queue.getOrNull(it) }
        queueIndex = cursor
        current = order.getOrNull(cursor)?.let { queue.getOrNull(it) }
        notifyWidget()
    }

    /** Keeps the home-screen widget in step with whatever is playing. */
    private fun notifyWidget() {
        if (!::context.isInitialized) return
        runCatching { NowPlayingWidget.refresh(context) }
    }

    // -------------------------------------------------------------- transport

    fun toggle() { if (isPlaying) exo?.pause() else exo?.play() }
    fun play() { exo?.play() }
    fun pause() { exo?.pause() }

    fun next(user: Boolean = true) {
        if (order.isEmpty()) return
        if (repeat == REPEAT_ONE && !user) { exo?.seekTo(0); exo?.play(); return }
        when {
            cursor + 1 < order.size -> { cursor++; loadCurrent() }
            repeat == REPEAT_ALL -> { cursor = 0; loadCurrent() }
            else -> { exo?.pause() }
        }
    }

    fun previous() {
        if ((exo?.currentPosition ?: 0) > 5000) { exo?.seekTo(0); return }
        if (cursor > 0) { cursor--; loadCurrent() } else exo?.seekTo(0)
    }

    fun seekTo(ms: Long) { exo?.seekTo(ms.coerceAtLeast(0)) }

    /** Named to avoid clashing with the JVM setter of the `shuffle` property. */
    fun applyShuffle(enabled: Boolean) {
        if (enabled == shuffle) return
        shuffle = enabled
        val currentIndex = order.getOrNull(cursor)
        order = if (enabled) {
            val rest = order.filter { it != currentIndex }.toMutableList()
            rest.shuffle()
            (listOfNotNull(currentIndex) + rest).toMutableList()
        } else {
            order.sorted().toMutableList()
        }
        currentIndex?.let { cursor = order.indexOf(it) }
        publishQueue()
    }

    fun cycleRepeat() { repeat = (repeat + 1) % 3 }

    // --------------------------------------------------------------- loading

    private fun loadCurrent() {
        val track = order.getOrNull(cursor)?.let { queue.getOrNull(it) } ?: return
        publishQueue()
        loading = true
        resolveToken++
        val token = resolveToken

        scope.launch {
            val url = withContext(Dispatchers.IO) { Sources.resolve(track) }
            if (token != resolveToken) return@launch
            loading = false
            if (url == null) {
                errorText = "No stream for “${track.title}”"
                next(user = false)
                return@launch
            }
            val item = MediaItem.Builder()
                .setUri(url)
                .setMediaId(track.id)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(track.title)
                        .setDisplayTitle(track.title)
                        .setArtist(track.artist)
                        .setAlbumTitle(track.album)
                        .setAlbumArtist(track.artist)
                        .setIsBrowsable(false)
                        .setIsPlayable(true)
                        .setArtworkUri(track.thumb?.let { android.net.Uri.parse(it) })
                        .build()
                )
                .build()
            exo?.setMediaItem(item)
            exo?.prepare()
            exo?.play()
            withContext(Dispatchers.IO) { Store.recordPlay(track) }
        }
    }
}
