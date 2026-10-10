package lt.a777.muzika.player

import android.util.Log
import lt.a777.muzika.data.Prefs
import lt.a777.muzika.data.Syncthing
import lt.a777.muzika.data.Track
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Carrying what is playing between devices: pause on the desktop, press play
 * on the phone, and the same song carries on from the same spot, in the same
 * shuffled order - and the other way round.
 *
 * It is one more file in the folder the library already syncs through,
 * `muzika-nowplaying.json`, so it needs nothing the library sync does not.
 * Kept apart from the library file because it changes at every track and
 * every pause; folding it in would rewrite the library constantly and turn
 * every listening session into sync conflicts.
 *
 * Every device writes it when its own playback changes. Pressing play takes
 * it over when it is newer than anything this device did itself - the rule
 * asked for is simply "the newest session wins". Format: docs/sync-format.md.
 */
object Handoff {
    private const val TAG = "Handoff"
    const val FILENAME = "muzika-nowplaying.json"
    private const val FORMAT = "muzika-nowplaying"
    private const val VERSION = 1

    /**
     * How long a session reported as still playing is assumed to have kept
     * playing. A writer that is killed mid-song never says it stopped, and
     * without a bound its position would be wound forward through the whole
     * queue days later.
     */
    const val MAX_EXTRAPOLATE_MS = 2L * 60 * 60 * 1000

    data class Remote(val snapshot: Session.Snapshot, val device: String, val deviceId: String)

    /** Only the folder backend has a folder to put it in. */
    private fun file(): File? =
        if (Prefs.syncBackend == Prefs.BACKEND_FOLDER) File(Prefs.syncFolder, FILENAME) else null

    // ------------------------------------------------------------------ write

    fun publish(
        queue: List<Track>, order: List<Int>, cursor: Int, shuffle: Boolean, repeat: Int,
        positionMs: Long, durationMs: Long, playing: Boolean,
    ) {
        val target = file() ?: return
        val now = System.currentTimeMillis()
        val text = encode(queue, order, cursor, shuffle, repeat, positionMs, durationMs, playing,
            Prefs.deviceId, Prefs.deviceName, now)
        Session.enqueue {
            if (target.parentFile?.isDirectory == true) {
                Session.writeAtomically(target, text)
                // Otherwise Syncthing on Android may not notice it for an hour.
                Syncthing.scanAsync(target)
            }
        }
    }

    // ------------------------------------------------------------------- read

    /** What was last parsed, keyed by the file's timestamp and size. */
    private var cachedKey: Pair<Long, Long>? = null
    private var cachedText: String? = null

    /**
     * Another device's session, when it is newer than [localChangedAt] - the
     * last time this device's own playback changed. Asked on every play
     * press and widget redraw, so the file is only re-read when it changed.
     */
    fun newerThan(localChangedAt: Long, now: Long = System.currentTimeMillis()): Remote? {
        val source = file() ?: return null
        val text = try {
            if (!source.isFile) return null
            val key = source.lastModified() to source.length()
            if (key != cachedKey) {
                cachedText = source.readText()
                cachedKey = key
            }
            cachedText ?: return null
        } catch (e: Exception) {
            Log.w(TAG, "could not read $FILENAME: ${e.message}")
            return null
        }
        val remote = decode(text, now) ?: return null
        if (remote.deviceId == Prefs.deviceId) return null
        return remote.takeIf { it.snapshot.savedAt > localChangedAt }
    }

    // ---------------------------------------------------------------- format

    internal fun encode(
        queue: List<Track>, order: List<Int>, cursor: Int, shuffle: Boolean, repeat: Int,
        positionMs: Long, durationMs: Long, playing: Boolean,
        deviceId: String, device: String, now: Long,
    ): String = JSONObject()
        .put("format", FORMAT)
        .put("version", VERSION)
        .put("device_id", deviceId)
        .put("device", device)
        .put("updated_at", now / 1000.0)
        .put("playing", playing)
        .put("position_ms", positionMs.coerceAtLeast(0))
        .put("duration_ms", durationMs.coerceAtLeast(0))
        .put("cursor", cursor)
        .put("track_id", order.getOrNull(cursor)?.let { queue.getOrNull(it) }?.id ?: JSONObject.NULL)
        .put("shuffle", shuffle)
        .put("repeat", REPEAT_NAMES.getOrElse(repeat) { "none" })
        .put("queue", JSONArray().also { array -> queue.forEach { array.put(Session.trackJson(it)) } })
        .put("order", JSONArray(order))
        .toString()

    internal fun decode(text: String, now: Long): Remote? = try {
        val saved = JSONObject(text)
        if (saved.optString("format") != FORMAT) {
            null
        } else {
            val tracksJson = saved.getJSONArray("queue")
            val queue = (0 until tracksJson.length()).map { Session.trackFrom(tracksJson.getJSONObject(it)) }
            val orderJson = saved.getJSONArray("order")
            val order = (0 until orderJson.length())
                .map { orderJson.getInt(it) }
                .filter { it in queue.indices }
                .distinct()
            val updatedAt = (saved.optDouble("updated_at", 0.0) * 1000).toLong()
            if (queue.isEmpty() || order.isEmpty() || now - updatedAt > Session.MAX_AGE_MS) {
                null
            } else {
                // Same rule as the local session: trust the cursor only where
                // its track actually is.
                val trackId = saved.optString("track_id").takeIf { it.isNotEmpty() && it != "null" }
                val stored = saved.optInt("cursor", 0)
                val cursor = when {
                    trackId == null -> stored.takeIf { it in order.indices }
                    order.getOrNull(stored)?.let { queue[it].id } == trackId -> stored
                    else -> order.indexOfFirst { queue[it].id == trackId }.takeIf { it >= 0 }
                }
                val repeat = REPEAT_NAMES.indexOf(saved.optString("repeat")).coerceAtLeast(0)
                var position = if (cursor != null) saved.optLong("position_ms").coerceAtLeast(0) else 0
                var at = cursor ?: 0
                // Still playing over there: it has moved on since it wrote.
                val elapsed = now - updatedAt
                if (cursor != null && saved.optBoolean("playing") && elapsed in 1..MAX_EXTRAPOLATE_MS) {
                    val moved = advance(queue, order, at, position + elapsed, repeat)
                    at = moved.first
                    position = moved.second
                }
                Remote(
                    snapshot = Session.Snapshot(
                        queue = queue,
                        order = order,
                        cursor = at,
                        shuffle = saved.optBoolean("shuffle"),
                        repeat = repeat,
                        source = null,   // page ids are per device; nothing here can match one
                        positionMs = position,
                        durationMs = if (at == cursor) saved.optLong("duration_ms") else 0,
                        savedAt = updatedAt,
                    ),
                    device = saved.optString("device").ifEmpty { "another device" },
                    deviceId = saved.optString("device_id"),
                )
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "$FILENAME unreadable: ${e.message}")
        null
    }

    /**
     * Where a queue that kept playing has got to: walks forward through the
     * order by each track's length. Stops on a track of unknown length,
     * since there is no telling how far past it playback went.
     */
    internal fun advance(
        queue: List<Track>, order: List<Int>, cursor: Int, positionMs: Long, repeat: Int,
    ): Pair<Int, Long> {
        var at = cursor
        var position = positionMs
        if (repeat == REPEAT_ONE) {
            val length = queue[order[at]].duration * 1000L
            return at to if (length > 0) position % length else position
        }
        while (true) {
            val length = queue[order[at]].duration * 1000L
            if (length <= 0 || position < length) return at to position
            position -= length
            at = when {
                at + 1 < order.size -> at + 1
                repeat == REPEAT_ALL -> 0
                else -> return at to 0L     // ran off the end: it stopped there
            }
        }
    }

    private val REPEAT_NAMES = listOf("none", "all", "one")   // REPEAT_NONE, REPEAT_ALL, REPEAT_ONE
}
