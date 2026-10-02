package lt.a777.muzika

import kotlinx.coroutines.runBlocking
import lt.a777.muzika.sources.Innertube
import org.junit.Test

/** Do the catalogue paths actually return artwork? Counts, not assertions. */
class ArtworkProbe {
    private fun report(label: String, tracks: List<lt.a777.muzika.data.Track>) {
        val withArt = tracks.count { !it.thumb.isNullOrBlank() }
        println("  %-26s %3d tracks, %3d with artwork".format(label, tracks.size, withArt))
    }

    @Test
    fun `artwork coverage across the catalogue paths`() = runBlocking {
        val hits = Innertube.search("P!nk", Innertube.F_ARTISTS)
        val artist = hits.firstOrNull()?.id
        println("  artist id: $artist")
        report("search songs", Innertube.search("P!nk", Innertube.F_SONGS).mapNotNull { it.toTrackOrNull() })
        if (artist != null) {
            val page = Innertube.artist(artist)
            report("artist page tracks", page?.tracks.orEmpty())
            val seed = page?.tracks?.firstOrNull()?.id
            if (seed != null) report("radio", Innertube.radio(seed))
            report("artistSongs", Innertube.artistSongs(artist))
        }
    }
}

private fun Innertube.Item.toTrackOrNull() = runCatching { toTrack() }.getOrNull()
