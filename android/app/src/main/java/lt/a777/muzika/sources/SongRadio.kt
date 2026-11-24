package lt.a777.muzika.sources

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import lt.a777.muzika.data.Track
import java.text.Normalizer

/**
 * Song radio: what plays after a song you looked up and heard out.
 *
 * Searching for one song and listening to all of it says something a tap on a
 * playlist does not: this is the sound wanted right now. What used to follow
 * was the next search result - usually the same song again, live or
 * remastered.
 *
 * The radio that follows instead is built from two things:
 *
 * - **the song** - YouTube Music's own radio for it, which is where style,
 *   mood and feeling come from: it is picked to sit next to this one track;
 * - **the band** - more by the same artist, threaded through so the radio
 *   keeps returning to who it started from.
 *
 * The rules are the desktop's (`muzika/radio.py`); keep the two in step.
 */
object SongRadio {
    /** How much of a song has to be heard to count as played to the end. */
    const val HEARD_FRACTION = 0.5
    /** Every Nth place in the radio goes to the seed's own artist... */
    const val ARTIST_EVERY = 4
    /** ...up to this many times, so it stays a radio and not a discography. */
    const val ARTIST_MAX = 8
    const val LIMIT = 50
    const val SOURCE_PREFIX = "songradio:"

    private val BRACKETS = Regex("[(\\[][^)\\]]*[)\\]]")
    private val SUFFIX = Regex(
        "\\s+-\\s+(live|remaster(ed)?|edit|radio edit|bonus track|mono|stereo|acoustic|" +
            "single version|album version|demo)\\b.*$", RegexOption.IGNORE_CASE)
    private val MARKS = Regex("\\p{M}+")
    private val NOT_PLAIN = Regex("[^a-z0-9]+")

    private fun plain(text: String): String =
        NOT_PLAIN.replace(
            MARKS.replace(Normalizer.normalize(text, Normalizer.Form.NFKD), "").lowercase(), "")

    /**
     * Identifies a song regardless of which recording of it this is: "Numb",
     * "Numb - Live" and "Numb (Remastered)" are one song as far as a radio is
     * concerned - hearing it three times is not variety.
     */
    fun songKey(track: Track): Pair<String, String> {
        val title = SUFFIX.replace(BRACKETS.replace(track.title, " "), "")
        return plain(title).ifEmpty { plain(track.title) } to plain(track.primaryArtist)
    }

    /**
     * Whether the song was listened to, not just reached the end of: dragging
     * the seek bar to the last second ends a track too.
     */
    fun heardEnough(heardMs: Long, durationMs: Long): Boolean =
        if (durationMs <= 0) heardMs >= 60_000 else heardMs >= durationMs * HEARD_FRACTION

    fun isSearchSource(source: String?): Boolean =
        source != null && (source == "search" || source.startsWith("search:"))

    /**
     * Thread the artist's songs through the song's radio.
     *
     * The seed itself is left out - it has just been heard - and so is every
     * other recording of it. Order is kept: [similar] arrives in the order
     * YouTube Music would play it, and the caller shuffles [byArtist].
     */
    fun blend(seed: Track, similar: List<Track>, byArtist: List<Track>,
              limit: Int = LIMIT): List<Track> {
        val seenIds = hashSetOf(seed.id)
        val seenKeys = hashSetOf(songKey(seed))
        fun fresh(tracks: List<Track>) = tracks.filter { track ->
            track.id.isNotEmpty() && seenIds.add(track.id) && seenKeys.add(songKey(track))
        }
        // The artist's songs claim their ids first, so the same track turning
        // up in the song's radio as well is not played twice.
        val artistPool = ArrayDeque(fresh(byArtist).take(ARTIST_MAX))
        val similarPool = ArrayDeque(fresh(similar))

        val result = ArrayList<Track>()
        while (result.size < limit && (similarPool.isNotEmpty() || artistPool.isNotEmpty())) {
            // Places 1, 5, 9... (counting from 0): the first thing heard is the
            // closest match to the song, the second is the band again.
            val wantsArtist = result.size % ARTIST_EVERY == 1
            if (artistPool.isNotEmpty() && (wantsArtist || similarPool.isEmpty())) {
                result.add(artistPool.removeFirst())
            } else if (similarPool.isNotEmpty()) {
                result.add(similarPool.removeFirst())
            }
        }
        return result
    }

    /**
     * Build the radio for [seed]. Empty when the song cannot be placed in the
     * catalogue at all - the player then carries on as it would have.
     */
    suspend fun build(seed: Track): List<Track> = withContext(Dispatchers.IO) {
        // One search answers two questions: who the artist is (the radio's
        // rows do not say), and - for a SoundCloud, Bandcamp or local song,
        // which has no radio of its own - where the same song is in the
        // catalogue.
        val wanted = songKey(seed)
        val match = Innertube.search("${seed.primaryArtist} ${seed.title}".trim(), Innertube.F_SONGS)
            .firstOrNull { it.playable && songKey(it.toTrack()) == wanted }
        val videoId = if (seed.source == Sources.YOUTUBE) seed.id else match?.id
            ?: return@withContext emptyList()

        val similar = Innertube.radio(videoId, LIMIT)
        val byArtist = match?.artistId
            ?.let { runCatching { Innertube.artistSongs(it, maxAlbums = 3) }.getOrDefault(emptyList()) }
            .orEmpty().shuffled()
        blend(seed, similar, byArtist)
    }
}
