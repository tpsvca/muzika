package lt.a777.muzika.player

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
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

/** How long a track may fail to advance before it is treated as stalled. */
private const val STALL_TIMEOUT_MS = 12_000L

/** A pause, a skip and a resume in quick succession write the handoff file once. */
private const val HANDOFF_SETTLE_MS = 1_500L

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

    /**
     * Which [order] index is already lined up inside ExoPlayer as the item
     * after the current one, or null when nothing is queued.
     *
     * Keeping the next track in ExoPlayer's own playlist is not an
     * optimisation, it is what keeps playback alive in the background. If the
     * player is allowed to reach STATE_ENDED, Media3 immediately drops the
     * service out of the foreground, and Android then refuses to let a
     * backgrounded app start a foreground service again
     * (ForegroundServiceStartNotAllowedException, "BFGS denied"). Playback
     * carries on unprotected until the process is reaped - the "music stopped
     * and never came back" bug. Handing the next item to ExoPlayer before the
     * current one ends means the player goes READY -> BUFFERING -> READY and
     * never sits in ENDED at all.
     */
    private var queuedNext: Int? = null

    /** Guards the one automatic retry of a failed track. */
    private var retriedCurrent = false
    /**
     * Whether you want sound right now - as opposed to whether there is any.
     *
     * Every recovery path used to end in play(): the retry after a stream
     * error, the skip after a second one, a stream that finished resolving.
     * So a player left paused, whose dead stream URL failed in the background
     * (YouTube answers 403 once a URL goes stale), started playing the next
     * song by itself - in a pocket, hours later. With handoff that also made
     * it the "newest" session and stole it from the desktop.
     *
     * Set by play and by starting something; cleared by every pause - this
     * app's, the notification's, unplugged headphones, a lost audio focus.
     * Nothing automatic may make a sound unless it is set.
     */
    private var wantsPlay = false
    /** Stall detection: the last position we saw, and when it stopped moving. */
    private var lastPositionSeen = -1L
    private var stalledSince = 0L
    private var recovering = false
    /** Counts tick() passes, so the widget is refreshed far less often. */
    private var widgetTicks = 0
    /** Counts tick() passes between saves of the position. */
    private var saveTicks = 0

    /** Whether the saved session has been read back yet; it only ever is once. */
    private var restored = false
    /**
     * Where a restored track should start. Nothing is loaded into ExoPlayer on
     * restore - that means a network round trip for a stream nobody asked for
     * yet - so the position waits here until play is pressed.
     */
    private var resumeAtMs = 0L
    /**
     * When this device's own playback last changed. Another device's session
     * is taken over only when it is newer than this - see [followOtherDevice].
     */
    private var localChangedAt = 0L
    /**
     * Which remote session was last taken over, so it is never taken twice.
     * The timestamp comparison alone is not enough: with the other device's
     * clock running ahead, its session stays "newer" than anything done here,
     * and every widget redraw would take it over again from the top.
     */
    private var adoptedRemote: String? = null
    /** The pending write of the handoff file, so a burst of changes writes once. */
    private var handoffJob: kotlinx.coroutines.Job? = null

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
    /** Something worth saying that is not an error - shown once, then cleared. */
    var notice by mutableStateOf<String?>(null)
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

    /**
     * True when a restored track is showing but nothing is loaded to play it:
     * play has to resolve a stream first rather than just un-pause.
     */
    private val needsLoad: Boolean
        get() = current != null && (exo?.mediaItemCount ?: 0) == 0

    /**
     * Read back what was playing before the process died, once.
     *
     * Needs no ExoPlayer, so the widget can call it to show the right track
     * after a reboot without starting a player just to draw itself.
     */
    fun restore(appContext: Context) {
        if (!::context.isInitialized) context = appContext.applicationContext
        if (restored) return
        restored = true
        Session.open(context)
        if (queue.isNotEmpty()) return
        Session.load()?.let { applySnapshot(it) }
        // Whatever this device last did, another may have played since.
        followOtherDevice()
    }

    /** Back to a fresh process, for tests that share this singleton. */
    internal fun resetForTest() {
        restored = false; adoptedRemote = null; localChangedAt = 0L; wantsPlay = false
        retriedCurrent = false; recovering = false; loading = false; errorText = null
        queue = emptyList(); order = mutableListOf(); cursor = -1
        current = null; source = null; notice = null; resumeAtMs = 0
    }

    /** Takes on a saved session, paused, with nothing loaded until play. */
    private fun applySnapshot(saved: Session.Snapshot) {
        queue = saved.queue
        order = saved.order.toMutableList()
        cursor = saved.cursor
        shuffle = saved.shuffle
        repeat = saved.repeat
        source = saved.source
        resumeAtMs = saved.positionMs
        positionMs = saved.positionMs
        durationMs = saved.durationMs
        queueView = order.mapNotNull { queue.getOrNull(it) }
        queueIndex = cursor
        current = order.getOrNull(cursor)?.let { queue.getOrNull(it) }
        localChangedAt = saved.savedAt
    }

    /**
     * Take over another device's session when it is newer than anything done
     * here: pause on the desktop, press play on the phone, and the phone
     * carries on with the same song, spot and shuffled order.
     *
     * Never while this device is playing - that is its own, newer session.
     * Asked before play, skip and back act, when the app comes to the front,
     * and when the widget redraws, so what is shown is what play will do.
     *
     * Returns whether it took anything over.
     */
    fun followOtherDevice(announce: Boolean = false): Boolean {
        if (!::context.isInitialized || isPlaying || loading) return false
        val remote = Handoff.newerThan(localChangedAt) ?: return false
        val stamp = "${remote.deviceId}@${remote.snapshot.savedAt}"
        if (stamp == adoptedRemote) return false
        adoptedRemote = stamp
        dropQueuedNext()
        resolveToken++          // a resolve still in flight belongs to the old queue
        exo?.stop()
        exo?.clearMediaItems()
        applySnapshot(remote.snapshot)
        exo?.repeatMode =
            if (repeat == REPEAT_ONE) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        // Kept locally, but not written back to the handoff file: nothing has
        // happened here yet, and claiming the session would only bounce it.
        saveQueue(handoff = false)
        savePosition(handoff = false)
        notifyWidget()
        if (announce) {
            notice = "Picked up \u201c${current?.title.orEmpty()}\u201d from ${remote.device}"
        }
        return true
    }

    fun attach(appContext: Context) {
        if (exo != null) return
        context = appContext.applicationContext
        exo = ExoPlayer.Builder(context)
            // Streaming needs the CPU and the Wi-Fi radio kept awake. Without
            // this the stream simply stops mid-song once the device dozes, and
            // never comes back - the WAKE_LOCK permission was declared in the
            // manifest but nothing was ever using it.
            .setWakeMode(C.WAKE_MODE_NETWORK)
            // Let Media3 handle audio focus: duck for a notification, pause for
            // a call, resume afterwards.
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            // Pause when headphones are unplugged rather than playing out loud.
            .setHandleAudioBecomingNoisy(true)
            .build().also { player ->
            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) next(user = false)
                    if (state == Player.STATE_READY) {
                        durationMs = player.duration.coerceAtLeast(0)
                        retriedCurrent = false
                    }
                    // Buffering is shown, so a stalled stream never looks like
                    // it is happily playing.
                    loading = state == Player.STATE_BUFFERING
                }

                override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                    if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) return
                    val advanced = queuedNext ?: return
                    queuedNext = null
                    cursor = advanced
                    publishQueue()
                    // The finished track is still sitting at index 0.
                    if (player.currentMediaItemIndex == 1) player.removeMediaItem(0)
                    order.getOrNull(cursor)?.let { queue.getOrNull(it) }?.let { track ->
                        scope.launch(Dispatchers.IO) { Store.recordPlay(track) }
                    }
                    prefetchNext()
                }

                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    // Pauses that do not come through pause(): headphones
                    // unplugged, audio focus lost for good.
                    if (!playWhenReady) wantsPlay = false
                }

                override fun onIsPlayingChanged(playing: Boolean) {
                    isPlaying = playing
                    notifyWidget()
                    // Pausing is the moment most likely to be the last one
                    // before the process is reaped, so the exact spot is kept.
                    savePosition()
                }

                override fun onPlayerError(error: PlaybackException) {
                    onStreamError(player.currentPosition)
                }
            })
        }
        restore(context)
        if (repeat == REPEAT_ONE) exo?.repeatMode = Player.REPEAT_MODE_ONE
        tick()
    }

    private fun tick() {
        scope.launch {
            while (true) {
                exo?.takeUnless { needsLoad }?.let { player ->
                    val position = player.currentPosition.coerceAtLeast(0)
                    positionMs = position
                    if (player.duration > 0) durationMs = player.duration
                    watchForStall(position)
                    // The widget's progress bar has to be pushed to the
                    // launcher; every 500ms would be wasteful, so it moves in
                    // ~3s steps, which is under half a percent of a typical
                    // track. Costs nothing when no widget is placed.
                    if (isPlaying && ++widgetTicks >= 6) {
                        widgetTicks = 0
                        notifyWidget()
                    }
                    // Every ten seconds while playing, so a process killed
                    // mid-song resumes close to where it was.
                    if (isPlaying && ++saveTicks >= 20) {
                        saveTicks = 0
                        // Local only: other devices work out the position
                        // from when the track started, so a write every ten
                        // seconds would only churn the synced folder.
                        savePosition(handoff = false)
                    }
                }
                kotlinx.coroutines.delay(500)
            }
        }
    }

    /**
     * A stream can die without ExoPlayer ever reporting an error: the socket
     * goes half-open, the buffer drains, and the player sits there believing it
     * is still playing while the position never moves again. That is what makes
     * it look like the music simply stopped and will not come back.
     *
     * So the position is watched directly, and a track that has not advanced
     * while it should be playing is reloaded from where it stopped.
     */
    private fun watchForStall(position: Long) {
        if (!isPlaying || recovering) {
            stalledSince = 0L
            lastPositionSeen = position
            return
        }
        if (position != lastPositionSeen) {
            lastPositionSeen = position
            stalledSince = 0L
            return
        }
        val now = System.currentTimeMillis()
        if (stalledSince == 0L) {
            stalledSince = now
            return
        }
        if (now - stalledSince >= STALL_TIMEOUT_MS) {
            stalledSince = 0L
            reloadCurrent(position)
        }
    }

    /**
     * Make sure the playback service is running and allowed to go foreground.
     *
     * It used to be started only from MainActivity.onCreate. Resuming the app
     * does not call onCreate, so once Android reaped the service it never came
     * back: audio kept playing from the app process with no foreground service
     * protecting it, and the process was killed sooner or later with no way to
     * recover. Starting it whenever playback begins is what keeps the process
     * alive for as long as there is sound.
     *
     * It can only ever start the service, never promote one that is already
     * running: from the background Android denies that outright. Staying in
     * the foreground once there is what [queuedNext] is for.
     */
    private fun ensureService() {
        if (!::context.isInitialized || PlaybackService.running) return
        runCatching {
            ContextCompat.startForegroundService(
                context, Intent(context, PlaybackService::class.java))
        }
    }

    /**
     * Work out the next track's stream while this one plays, so pressing skip
     * - or simply reaching the end - does not mean waiting a second or two for
     * an extractor.
     */
    private fun prefetchNext() {
        // Two ahead, not one: skipping twice in a row is common, and the
        // second skip used to wait for an extractor all over again.
        val upcoming = (1..2).mapNotNull { step ->
            order.getOrNull(cursor + step)?.let { queue.getOrNull(it) }
        }
        val follows = followingIndex()
        if (upcoming.isEmpty() && follows == null) return
        scope.launch {
            withContext(Dispatchers.IO) { upcoming.forEach { Sources.prefetch(it) } }
            lineUpNext()
        }
    }

    /** The [order] index that should play after the current one, if any. */
    private fun followingIndex(): Int? = when {
        repeat == REPEAT_ONE -> null
        cursor + 1 < order.size -> cursor + 1
        repeat == REPEAT_ALL && order.isNotEmpty() -> 0
        else -> null
    }

    /**
     * Hand the next track to ExoPlayer so it can cross over on its own.
     *
     * Only ever uses a stream we already hold: going to the network here would
     * put the wait back where the gap used to be. If nothing is cached yet the
     * STATE_ENDED fallback in the listener still moves things along.
     */
    private fun lineUpNext() {
        val player = exo ?: return
        if (queuedNext != null || player.mediaItemCount != 1) return
        val index = followingIndex() ?: return
        val track = order.getOrNull(index)?.let { queue.getOrNull(it) } ?: return
        val url = Sources.cachedUrl(track) ?: return
        player.addMediaItem(mediaItem(track, url))
        queuedNext = index
    }

    /** Forget anything lined up - the queue underneath it has moved. */
    private fun dropQueuedNext() {
        queuedNext = null
        exo?.let { player ->
            while (player.mediaItemCount > player.currentMediaItemIndex + 1) {
                player.removeMediaItem(player.mediaItemCount - 1)
            }
        }
    }

    /** One place that builds the media item, so recovery cannot drift from it. */
    private fun mediaItem(track: Track, url: String): MediaItem = MediaItem.Builder()
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

    /**
     * A stream failed. While playing, a dropped connection should not cost you
     * the song: try it once more with a freshly resolved URL, and only move on
     * if that fails too. While paused, do nothing you would hear - see
     * [wantsPlay] - and only [park].
     */
    internal fun onStreamError(atMs: Long) {
        if (!wantsPlay) { park(atMs); return }
        if (!retriedCurrent) {
            retriedCurrent = true
            reloadCurrent(atMs)
            return
        }
        errorText = "Could not play “${current?.title.orEmpty()}”"
        next(user = false)
    }

    /**
     * Drop a dead stream but keep the song and the spot. Same state as a track
     * restored after a restart: shown, paused, with nothing loaded, so play
     * resolves a fresh URL and carries on from exactly here.
     */
    private fun park(atMs: Long) {
        dropQueuedNext()
        resolveToken++          // a resolve still in flight would start playback
        recovering = false
        exo?.stop()
        exo?.clearMediaItems()
        resumeAtMs = atMs.coerceAtLeast(0)
        positionMs = resumeAtMs
        retriedCurrent = false
        loading = false
        // Locally only: nothing happened that another device should hear of.
        savePosition(handoff = false)
        notifyWidget()
    }

    /** Re-resolve the stream and pick up where it stopped. */
    private fun reloadCurrent(resumeAt: Long) {
        val track = order.getOrNull(cursor)?.let { queue.getOrNull(it) } ?: return
        if (recovering) return
        recovering = true
        loading = true
        val token = resolveToken
        scope.launch {
            val url = withContext(Dispatchers.IO) { Sources.resolve(track) }
            if (token != resolveToken) return@launch
            recovering = false
            if (!wantsPlay) {
                // Paused while it was being fetched again.
                park(resumeAt)
                return@launch
            }
            if (url == null) {
                loading = false
                errorText = "Lost the stream for “${track.title}”"
                next(user = false)
                return@launch
            }
            queuedNext = null
            exo?.setMediaItem(mediaItem(track, url), resumeAt)
            exo?.prepare()
            exo?.play()
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
        saveQueue()
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
        saveQueue()
        publishQueue()
        if (cursor < 0) { cursor = 0; loadCurrent() } else prefetchNext()
    }

    fun playNext(track: Track) {
        queue = queue + track
        order.add((cursor + 1).coerceAtMost(order.size), queue.size - 1)
        saveQueue()
        publishQueue()
    }

    fun jumpTo(position: Int) {
        if (position in order.indices) { cursor = position; loadCurrent() }
    }

    fun removeAt(position: Int) {
        if (position !in order.indices) return
        dropQueuedNext()
        order.removeAt(position)
        if (position < cursor) cursor--
        else if (position == cursor) { cursor--; next() }
        saveQueue()
        publishQueue()
    }

    fun clear() {
        exo?.stop(); exo?.clearMediaItems(); queuedNext = null
        queue = emptyList(); order = mutableListOf(); cursor = -1
        current = null; isPlaying = false; source = null; resumeAtMs = 0
        publishQueue()
        Session.clear()
    }

    private fun publishQueue() {
        queueView = order.mapNotNull { queue.getOrNull(it) }
        queueIndex = cursor
        current = order.getOrNull(cursor)?.let { queue.getOrNull(it) }
        notifyWidget()
        savePosition()
    }

    /** The queue and its order - only when they change, since they can be large. */
    private fun saveQueue(handoff: Boolean = true) {
        if (!::context.isInitialized) return
        localChangedAt = System.currentTimeMillis()
        Session.saveQueue(queue, order.toList(), shuffle, source)
        if (handoff) scheduleHandoff()
    }

    /** Which track and how far into it; cheap, so it is saved often. */
    private fun savePosition(handoff: Boolean = true) {
        if (!::context.isInitialized || queue.isEmpty()) return
        localChangedAt = System.currentTimeMillis()
        Session.savePosition(current, cursor, currentPosition(), durationMs, repeat)
        if (handoff) scheduleHandoff()
    }

    private fun currentPosition(): Long =
        (if (needsLoad) resumeAtMs else exo?.currentPosition ?: positionMs).coerceAtLeast(0)

    /**
     * Tell the other devices what is playing, a moment after it settles.
     *
     * Skipped while a stream is still resolving: the player is then still on
     * the previous track, and its position would be written against the new
     * one. Sound starting fires this again with the right numbers.
     */
    private fun scheduleHandoff() {
        handoffJob?.cancel()
        handoffJob = scope.launch {
            kotlinx.coroutines.delay(HANDOFF_SETTLE_MS)
            if (queue.isEmpty() || loading) return@launch
            Handoff.publish(queue, order.toList(), cursor, shuffle, repeat,
                currentPosition(), durationMs, playing = isPlaying)
        }
    }

    /** Keeps the home-screen widget in step with whatever is playing. */
    private fun notifyWidget() {
        if (!::context.isInitialized) return
        runCatching { NowPlayingWidget.refresh(context) }
    }

    // -------------------------------------------------------------- transport

    fun toggle() {
        // Pressed again while a song is still being fetched: that is a pause.
        if (isPlaying || (loading && wantsPlay)) pause() else play()
    }

    /** Un-pause - or, for a track restored from an earlier run, fetch it and pick up where it was. */
    fun play() {
        followOtherDevice(announce = true)
        if (needsLoad) { loadCurrent(resumeAtMs); return }
        wantsPlay = true
        ensureService(); exo?.play()
    }
    fun pause() { wantsPlay = false; exo?.pause() }

    fun next(user: Boolean = true) {
        if (user && !isPlaying) followOtherDevice(announce = true)
        if (order.isEmpty()) return
        if (repeat == REPEAT_ONE && !user) { exo?.seekTo(0); exo?.play(); return }
        when {
            cursor + 1 < order.size -> { cursor++; loadCurrent() }
            repeat == REPEAT_ALL -> { cursor = 0; loadCurrent() }
            else -> { exo?.pause() }
        }
    }

    fun previous() {
        if (!isPlaying) followOtherDevice(announce = true)
        if (needsLoad && resumeAtMs > 5000) { loadCurrent(); return }
        if ((exo?.currentPosition ?: 0) > 5000) { exo?.seekTo(0); return }
        if (cursor > 0) { cursor--; loadCurrent() } else exo?.seekTo(0)
    }

    fun seekTo(ms: Long) {
        // Nothing loaded yet: remember the spot for when play is pressed.
        if (needsLoad) {
            resumeAtMs = ms.coerceAtLeast(0); positionMs = resumeAtMs; savePosition(); return
        }
        exo?.seekTo(ms.coerceAtLeast(0))
    }

    /** Named to avoid clashing with the JVM setter of the `shuffle` property. */
    fun applyShuffle(enabled: Boolean) {
        if (enabled == shuffle) return
        dropQueuedNext()
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
        saveQueue()
        publishQueue()
    }

    fun cycleRepeat() {
        repeat = (repeat + 1) % 3
        savePosition()
        // Repeat-one is handed to ExoPlayer rather than done by hand: looping
        // through STATE_ENDED would drop the service out of the foreground on
        // every single pass. See [queuedNext].
        exo?.repeatMode =
            if (repeat == REPEAT_ONE) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        if (repeat == REPEAT_ONE) dropQueuedNext() else prefetchNext()
    }

    // --------------------------------------------------------------- loading

    private fun loadCurrent(startAtMs: Long = 0L) {
        val track = order.getOrNull(cursor)?.let { queue.getOrNull(it) } ?: return
        // Every load is meant to be heard - pausing before it arrives says otherwise.
        wantsPlay = true
        // Saved as the position while the stream resolves, so dying in that
        // second or two does not lose a restored spot.
        resumeAtMs = startAtMs
        publishQueue()
        loading = true
        resolveToken++
        val token = resolveToken

        scope.launch {
            val url = withContext(Dispatchers.IO) { Sources.resolve(track) }
            if (token != resolveToken) return@launch
            loading = false
            if (!wantsPlay) {
                // Paused while it was being fetched: stay on it, silent.
                park(startAtMs)
                return@launch
            }
            if (url == null) {
                errorText = "No stream for “${track.title}”"
                next(user = false)
                return@launch
            }
            ensureService()
            queuedNext = null
            exo?.setMediaItem(mediaItem(track, url), startAtMs)
            exo?.prepare()
            exo?.play()
            withContext(Dispatchers.IO) { Store.recordPlay(track) }
            prefetchNext()
        }
    }
}
