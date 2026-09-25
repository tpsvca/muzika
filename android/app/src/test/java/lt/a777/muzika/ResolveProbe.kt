package lt.a777.muzika

import lt.a777.muzika.data.Track
import lt.a777.muzika.sources.Innertube
import lt.a777.muzika.sources.Sources
import org.junit.Test
import kotlin.system.measureTimeMillis

/** Times the wait between tapping a song and audio starting. */
class ResolveProbe {
    @Test fun howLongDoesATrackTakeToStart() {
        // The app does this in Application.onCreate, which unit tests skip.
        org.schabi.newpipe.extractor.NewPipe.init(
            lt.a777.muzika.sources.NpeDownloader,
            org.schabi.newpipe.extractor.localization.Localization("en", "US"),
        )
        val songs = Innertube.search("metallica", Innertube.F_SONGS)
            .filter { it.playable }.take(4).map { it.toTrack() }
        println("\n=== stream resolution (tap -> playable URL) ===")
        songs.forEach { track ->
            var url: String? = null
            val ms = measureTimeMillis { url = Sources.resolve(track) }
            println("  %-34s %6d ms  %s".format(
                track.title.take(32), ms, if (url != null) "ok" else "FAILED"))
        }
        println("\n=== second resolve of the same track (is anything cached?) ===")
        val first = songs.first()
        repeat(2) {
            val ms = measureTimeMillis { Sources.resolve(first) }
            println("  %-34s %6d ms".format(first.title.take(32), ms))
        }
    }
}
