package lt.a777.muzika.sources

import lt.a777.muzika.data.Store
import lt.a777.muzika.data.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * What to put on the home page.
 *
 * There is no account and no profile on a server anywhere, so "your taste" is
 * worked out here, from this device's own history: the artists you actually
 * play seed YouTube Music's radios, and those radios are the mixes you see.
 */
object Discover {

    /** A ready-to-play set of tracks with a cover, built for this listener. */
    data class Mix(
        val id: String,
        val title: String,
        val subtitle: String,
        val thumb: String?,
        val tracks: List<Track>,
    )

    data class Feed(
        val mixes: List<Mix> = emptyList(),
        val fresh: List<Innertube.Item> = emptyList(),
        val newAlbums: List<Innertube.Item> = emptyList(),
        val similarArtists: List<Innertube.Item> = emptyList(),
        val charts: List<Innertube.Shelf> = emptyList(),
        val seededFrom: List<String> = emptyList(),
    ) {
        val isEmpty: Boolean
            get() = mixes.isEmpty() && fresh.isEmpty() && newAlbums.isEmpty() &&
                similarArtists.isEmpty() && charts.isEmpty()
    }

    private const val FRESH_MS = 30 * 60 * 1000L

    @Volatile private var cache: Feed? = null
    @Volatile private var cachedAt = 0L
    @Volatile private var cachedSeeds: List<String> = emptyList()

    /**
     * [force] refetches even when the cache is warm; otherwise the feed is
     * reused for half an hour, or until what you listen to has moved on.
     */
    suspend fun feed(force: Boolean = false): Feed {
        val seeds = withContext(Dispatchers.IO) { Store.favouriteArtists(6) }
        val warm = cache
        if (!force && warm != null &&
            System.currentTimeMillis() - cachedAt < FRESH_MS &&
            seeds == cachedSeeds
        ) return warm

        val built = build(seeds)
        cache = built
        cachedAt = System.currentTimeMillis()
        cachedSeeds = seeds
        return built
    }

    fun invalidate() { cache = null }

    private suspend fun build(seeds: List<String>): Feed = coroutineScope {
        val history = withContext(Dispatchers.IO) { Store.mostPlayed(60) }

        // One radio per artist, so four mixes do not all come back the same.
        val seedTracks = mutableListOf<Track>()
        val usedArtists = mutableSetOf<String>()
        for (track in history) {
            val artist = track.primaryArtist.lowercase()
            if (artist.isEmpty() || !usedArtists.add(artist)) continue
            seedTracks.add(track)
            if (seedTracks.size == 4) break
        }

        val mixJobs = seedTracks.map { seed ->
            async(Dispatchers.IO) {
                val tracks = Innertube.radio(seed.id)
                if (tracks.size < 5) null else Mix(
                    id = "mix:${seed.id}",
                    title = "${seed.primaryArtist.ifEmpty { seed.title }} radio",
                    subtitle = "Because you played ${seed.title}",
                    thumb = seed.thumb ?: tracks.firstOrNull()?.thumb,
                    tracks = tracks,
                )
            }
        }

        val freshJobs = seeds.take(3).map { artist ->
            async(Dispatchers.IO) {
                Innertube.search(artist, Innertube.F_PLAYLISTS)
                    .filter { it.kind == Innertube.PLAYLIST }.take(4)
            }
        }
        val albumJobs = seeds.take(4).map { artist ->
            async(Dispatchers.IO) {
                Innertube.search(artist, Innertube.F_ALBUMS)
                    .filter { it.kind == Innertube.ALBUM }.take(3)
            }
        }
        val similarJob = seeds.firstOrNull()?.let { artist ->
            async(Dispatchers.IO) {
                val found = Innertube.search(artist, Innertube.F_ARTISTS)
                    .firstOrNull { it.kind == Innertube.ARTIST } ?: return@async emptyList()
                val page = Innertube.artist(found.id) ?: return@async emptyList()
                page.shelves
                    .firstOrNull { it.title.contains("also like", true) }
                    ?.items.orEmpty()
                    .filter { it.kind == Innertube.ARTIST }
                    .take(10)
            }
        }
        // Only worth a request when there is nothing personal to show yet.
        val chartsJob = if (seeds.isEmpty()) async(Dispatchers.IO) {
            Innertube.homeShelves().take(4)
        } else null

        Feed(
            mixes = mixJobs.mapNotNull { it.await() },
            fresh = freshJobs.flatMap { it.await() }.distinctBy { it.id },
            newAlbums = albumJobs.flatMap { it.await() }.distinctBy { it.id },
            similarArtists = similarJob?.await().orEmpty(),
            charts = chartsJob?.await().orEmpty(),
            seededFrom = seeds,
        )
    }

    /** "Play something" when the queue is empty: a radio off a recent track. */
    suspend fun quickMix(): List<Track> = withContext(Dispatchers.IO) {
        val seed = Store.mostPlayed(10).randomOrNull()
            ?: Store.history(10).randomOrNull()
            ?: Store.favourites().randomOrNull()
            ?: return@withContext emptyList()
        Innertube.radio(seed.id).ifEmpty { listOf(seed) }
    }
}
