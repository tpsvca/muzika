package lt.a777.muzika.player

import android.content.Context
import android.util.Log
import lt.a777.muzika.data.Track
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * What was playing, kept on disk so it outlives the process.
 *
 * The queue lived only in memory. Android reaps a paused music app overnight,
 * and the next morning the widget still showed yesterday's track - the
 * launcher keeps the last thing it was given - but pressing play found nothing
 * loaded and opened the app on Home instead. Saving the queue, the shuffled
 * order and the position is what lets play carry on from the same spot.
 *
 * Two files, because they change at very different rates. The queue can be
 * hundreds of tracks and only changes when you start something or edit it; the
 * position changes every few seconds and is a few bytes.
 */
object Session {
    private const val TAG = "Session"
    private const val QUEUE_FILE = "session-queue.json"
    private const val POSITION_FILE = "session-position.json"
    private const val VERSION = 1

    /**
     * How long a paused session stays resumable. Asked for: at least a week.
     * Thirty days, because a month-old queue is still a better answer to
     * "play" than nothing at all.
     */
    const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000

    data class Snapshot(
        val queue: List<Track>,
        val order: List<Int>,
        val cursor: Int,
        val shuffle: Boolean,
        val repeat: Int,
        val source: String?,
        val positionMs: Long,
        val durationMs: Long,
        val savedAt: Long,
    )

    private var dir: File? = null

    /**
     * Every write goes through one thread, in the order it was asked for, so
     * an older position can never land on top of a newer one.
     */
    private val writer = Executors.newSingleThreadExecutor { Thread(it, "muzika-session") }

    fun open(context: Context) {
        if (dir == null) dir = context.applicationContext.filesDir
    }

    /** For tests, which need a folder of their own. */
    internal fun openAt(folder: File) { dir = folder }

    // ------------------------------------------------------------------ write

    fun saveQueue(queue: List<Track>, order: List<Int>, shuffle: Boolean, source: String?) {
        val folder = dir ?: return
        val now = System.currentTimeMillis()
        writer.execute { writeAtomically(File(folder, QUEUE_FILE), encodeQueue(queue, order, shuffle, source, now)) }
    }

    fun savePosition(track: Track?, cursor: Int, positionMs: Long, durationMs: Long, repeat: Int) {
        val folder = dir ?: return
        val now = System.currentTimeMillis()
        val text = encodePosition(track?.id, cursor, positionMs, durationMs, repeat, now)
        writer.execute { writeAtomically(File(folder, POSITION_FILE), text) }
    }

    fun clear() {
        val folder = dir ?: return
        writer.execute {
            File(folder, QUEUE_FILE).delete()
            File(folder, POSITION_FILE).delete()
        }
    }

    /** Blocks until everything asked for so far is on disk. Tests only. */
    internal fun flush() { writer.submit {}.get() }

    /** Written beside the target and renamed over it, so a kill mid-write leaves the old file. */
    private fun writeAtomically(target: File, text: String) {
        try {
            val temp = File(target.parentFile, target.name + ".tmp")
            temp.writeText(text)
            if (!temp.renameTo(target)) {
                target.delete()
                temp.renameTo(target)
            }
        } catch (e: Exception) {
            Log.w(TAG, "could not save ${target.name}: ${e.message}")
        }
    }

    // ------------------------------------------------------------------- read

    /** The saved session, or null when there is none, it is unreadable, or it is too old. */
    fun load(now: Long = System.currentTimeMillis()): Snapshot? {
        val folder = dir ?: return null
        val queueText = File(folder, QUEUE_FILE).takeIf { it.isFile }?.readTextOrNull() ?: return null
        val positionText = File(folder, POSITION_FILE).takeIf { it.isFile }?.readTextOrNull()
        return decode(queueText, positionText, now)
    }

    private fun File.readTextOrNull(): String? = runCatching { readText() }.getOrNull()

    internal fun encodeQueue(
        queue: List<Track>, order: List<Int>, shuffle: Boolean, source: String?, savedAt: Long,
    ): String = JSONObject()
        .put("version", VERSION)
        .put("savedAt", savedAt)
        .put("shuffle", shuffle)
        .put("source", source ?: JSONObject.NULL)
        .put("queue", JSONArray().also { array -> queue.forEach { array.put(trackJson(it)) } })
        .put("order", JSONArray(order))
        .toString()

    internal fun encodePosition(
        trackId: String?, cursor: Int, positionMs: Long, durationMs: Long, repeat: Int, savedAt: Long,
    ): String = JSONObject()
        .put("trackId", trackId ?: JSONObject.NULL)
        .put("cursor", cursor)
        .put("positionMs", positionMs)
        .put("durationMs", durationMs)
        .put("repeat", repeat)
        .put("savedAt", savedAt)
        .toString()

    /**
     * The two files are written separately, so they can disagree - the queue
     * replaced but the process killed before the position followed. The
     * position therefore names its track, and is only trusted where that
     * track actually sits; otherwise the track is looked up by id, and failing
     * that the queue starts from its first entry.
     */
    internal fun decode(queueText: String, positionText: String?, now: Long): Snapshot? = try {
        val saved = JSONObject(queueText)
        val tracksJson = saved.getJSONArray("queue")
        val queue = (0 until tracksJson.length()).map { trackFrom(tracksJson.getJSONObject(it)) }
        val orderJson = saved.getJSONArray("order")
        val order = (0 until orderJson.length())
            .map { orderJson.getInt(it) }
            .filter { it in queue.indices }
            .distinct()
        val position = positionText?.let { runCatching { JSONObject(it) }.getOrNull() }
        val savedAt = maxOf(saved.optLong("savedAt"), position?.optLong("savedAt") ?: 0L)

        if (queue.isEmpty() || order.isEmpty() || now - savedAt > MAX_AGE_MS) {
            null
        } else {
            val trackId = position?.optString("trackId")?.takeIf { it.isNotEmpty() && it != "null" }
            val storedCursor = position?.optInt("cursor", -1) ?: -1
            val cursor = when {
                trackId == null -> storedCursor.takeIf { it in order.indices }
                order.getOrNull(storedCursor)?.let { queue[it].id } == trackId -> storedCursor
                else -> order.indexOfFirst { queue[it].id == trackId }.takeIf { it >= 0 }
            }
            Snapshot(
                queue = queue,
                order = order,
                cursor = cursor ?: 0,
                shuffle = saved.optBoolean("shuffle"),
                repeat = position?.optInt("repeat") ?: 0,
                source = saved.optString("source").takeIf { it.isNotEmpty() && it != "null" },
                // A position belongs to one track; if that track was lost, so is it.
                positionMs = if (cursor != null) position?.optLong("positionMs")?.coerceAtLeast(0) ?: 0 else 0,
                durationMs = if (cursor != null) position?.optLong("durationMs")?.coerceAtLeast(0) ?: 0 else 0,
                savedAt = savedAt,
            )
        }
    } catch (e: Exception) {
        Log.w(TAG, "saved session unreadable: ${e.message}")
        null
    }

    // Every field, unlike the sync format: this copy has to play, not just list.
    private fun trackJson(track: Track): JSONObject = JSONObject()
        .put("id", track.id)
        .put("title", track.title)
        .put("artist", track.artist)
        .put("duration", track.duration)
        .put("thumb", track.thumb ?: JSONObject.NULL)
        .put("source", track.source)
        .put("url", track.url ?: JSONObject.NULL)
        .put("album", track.album ?: JSONObject.NULL)
        .put("trackNumber", track.trackNumber)

    private fun trackFrom(entry: JSONObject): Track = Track(
        id = entry.getString("id"),
        title = entry.optString("title"),
        artist = entry.optString("artist"),
        duration = entry.optInt("duration"),
        thumb = entry.optNullableString("thumb"),
        source = entry.optNullableString("source") ?: "youtube",
        url = entry.optNullableString("url"),
        album = entry.optNullableString("album"),
        trackNumber = entry.optInt("trackNumber"),
    )

    private fun JSONObject.optNullableString(key: String): String? =
        if (isNull(key)) null else optString(key).ifEmpty { null }
}
