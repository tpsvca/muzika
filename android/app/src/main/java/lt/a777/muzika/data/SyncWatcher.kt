package lt.a777.muzika.data

import android.os.Build
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File

/**
 * Notice that the sync client has brought in a new library file, now.
 *
 * The desktop player has watched its copy of the file from the start, so an
 * edit made on the phone shows up there within a second or two. The phone only
 * ever read the file once, when Root first composed, which in practice means
 * once per launch - so a playlist changed on the desktop sat there unseen until
 * the app was killed and reopened. That is the gap this closes.
 *
 * Two mechanisms, because neither alone is enough:
 *
 *  - A [FileObserver] on the *folder*. The file has to be watched through its
 *    directory rather than by itself: Syncthing writes a temporary file and
 *    renames it into place, so the inode the app would have been watching is
 *    not the inode that ends up holding the library. Watching the directory and
 *    filtering on the name catches the rename, which is the event that matters.
 *
 *  - A slow poll of the file's size and timestamp while the app is in front.
 *    `/storage/emulated/0` is a FUSE mount on modern Android and inotify there
 *    does not reliably report writes made by a *different* app, which is
 *    exactly the case here - Syncthing is the writer. When the observer does
 *    fire, the reload is immediate and the poll never comes into it; when it
 *    does not, this bounds the delay to a few seconds instead of a relaunch.
 *
 * Both funnel into one place that reloads only when the file's fingerprint has
 * actually moved, so duplicate events and a quiet file both cost nothing.
 */
object SyncWatcher {
    private const val TAG = "SyncWatcher"

    /** A sync client can touch the file several times as it lands. */
    private const val SETTLE_MS = 700L

    /** Backstop for FUSE swallowing inotify events; a stat call is cheap. */
    private const val POLL_MS = 4_000L

    private val handler = Handler(Looper.getMainLooper())

    private var observer: FileObserver? = null
    private var watching: String? = null
    private var onChange: (() -> Unit)? = null

    /**
     * Size and timestamp of the file as this app last saw it, and which file
     * that was. The path matters: pointed at a different sync folder, the old
     * reading says nothing about the new file and has to be taken again.
     */
    private var fingerprint: Pair<Long, Long>? = null
    private var fingerprintOf: String? = null

    private val reload = Runnable { fire() }
    private val poll = object : Runnable {
        override fun run() {
            if (onChange == null) return
            if (Prefs.syncFolder != watching) arm()   // Settings moved the folder
            check()
            handler.postDelayed(this, POLL_MS)
        }
    }

    private fun stamp(file: File): Pair<Long, Long>? =
        if (file.isFile) file.lastModified() to file.length() else null

    /**
     * Start watching, and take the file as it stands as the baseline.
     *
     * Root imports once on launch anyway, so whatever is on disk at this
     * moment has already been read; only a later change is news.
     */
    fun start(onChange: () -> Unit) {
        this.onChange = onChange
        val file = Sync.defaultFile
        if (fingerprintOf != file.path) {
            fingerprint = stamp(file)
            fingerprintOf = file.path
        }
        arm()
        handler.removeCallbacks(poll)
        handler.postDelayed(poll, POLL_MS)
    }

    /** Stop everything; nothing should tick while the app is in the background. */
    fun stop() {
        onChange = null
        handler.removeCallbacks(poll)
        handler.removeCallbacks(reload)
        observer?.stopWatching()
        observer = null
        watching = null
    }

    /**
     * Record that *we* just wrote the file, so it is not read back as news.
     *
     * Re-seeding the fingerprint rather than ignoring events for a few seconds
     * leaves no window in which a genuine incoming change could be mistaken for
     * our own write and dropped.
     */
    fun noteOwnWrite() {
        val file = Sync.defaultFile
        fingerprint = stamp(file)
        fingerprintOf = file.path
    }

    /** Has the file moved since we last looked? Call on resume, and on a tick. */
    fun check() {
        val now = stamp(Sync.defaultFile) ?: return
        if (now == fingerprint) return
        handler.removeCallbacks(reload)
        handler.postDelayed(reload, SETTLE_MS)
    }

    private fun fire() {
        val callback = onChange ?: return
        val now = stamp(Sync.defaultFile) ?: return
        if (now == fingerprint) return
        fingerprint = now
        Log.d(TAG, "library file changed, reloading")
        callback()
    }

    private fun arm() {
        observer?.stopWatching()
        observer = null
        // Recorded before the early exits, so a folder that cannot be watched
        // is not retried on every poll tick.
        watching = Prefs.syncFolder
        if (Prefs.syncBackend != Prefs.BACKEND_FOLDER) return

        val folder = File(Prefs.syncFolder)
        if (!folder.isDirectory) return

        // CREATE and MOVED_TO cover a file written in place and one renamed
        // into place; CLOSE_WRITE covers an in-place rewrite being finished.
        val mask = FileObserver.CREATE or FileObserver.MOVED_TO or FileObserver.CLOSE_WRITE
        observer = newObserver(folder, mask) { name ->
            if (name == null || name == Sync.FILENAME) check()
        }
        runCatching { observer?.startWatching() }
            .onFailure { Log.w(TAG, "cannot watch ${folder.path}", it) }
    }

    /** The File constructor arrived in API 29 and the app supports API 26. */
    private fun newObserver(folder: File, mask: Int, onEvent: (String?) -> Unit): FileObserver =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            object : FileObserver(folder, mask) {
                override fun onEvent(event: Int, path: String?) = onEvent(path)
            }
        } else {
            @Suppress("DEPRECATION")
            object : FileObserver(folder.path, mask) {
                override fun onEvent(event: Int, path: String?) = onEvent(path)
            }
        }
}
