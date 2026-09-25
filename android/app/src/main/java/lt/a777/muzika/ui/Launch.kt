package lt.a777.muzika.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/**
 * Where a launcher shortcut wants to land.
 *
 * The activity receives the intent long before the UI is composed, so the
 * request is parked here and the root consumes it once, on the next frame.
 *
 * [version] is what the UI keys its effect on, never the destination itself:
 * consuming has to clear the request, and if that cleared the key then the
 * effect would be cancelled at its first suspension point - which silently
 * broke every destination that had any work to do.
 */
object Launch {
    const val LIKED = "liked"
    const val SHUFFLE = "shuffle"
    const val SEARCH = "search"

    /** Bumped per request; only ever increases, so consuming cannot cancel. */
    var version by mutableIntStateOf(0)
        private set

    private var destination: String? = null

    fun request(value: String?) {
        if (value.isNullOrEmpty()) return
        destination = value
        version++
    }

    fun consume(): String? = destination.also { destination = null }
}
