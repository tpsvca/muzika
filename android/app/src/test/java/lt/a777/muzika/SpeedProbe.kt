package lt.a777.muzika

import lt.a777.muzika.sources.Innertube
import lt.a777.muzika.sources.NpeDownloader
import lt.a777.muzika.sources.Sources
import org.junit.Test
import kotlin.system.measureTimeMillis

/** Times the real network paths so slowness can be attributed, not guessed at. */
class SpeedProbe {

    private fun time(label: String, block: () -> Int) {
        val ms = measureTimeMillis { val n = block(); print("  %-34s %5d ms  (%d items)".format(label, 0, n)) }
        println("\r  %-34s %5d ms".format(label, ms))
    }

    @Test fun whereDoesTheTimeGo() {
        // Same warm-up the app now does at startup.
        Innertube.warmUp()
        Thread.sleep(2500)
        println("\n=== does the visitor-id page fetch succeed and get cached? ===")
        var bytes = 0
        val pageMs = measureTimeMillis {
            val req = okhttp3.Request.Builder().url("https://music.youtube.com")
                .addHeader("User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0")
                .build()
            NpeDownloader.client.newCall(req).execute().use { r ->
                val html = r.body?.string() ?: ""
                bytes = html.length
                // The page spells it `visitorData`; probing for `VISITOR_DATA`
                // reported a false negative long after the app itself was fixed.
                val hit = Regex("\"visitorData\"\\s*:\\s*\"([^\"]+)\"").find(html)
                println("  homepage: $bytes chars, visitorData matched = ${hit != null}")
            }
        }
        println("  homepage fetch: $pageMs ms")

        println("\n=== individual calls (first call pays the visitor cost) ===")
        time("innertube songs   (1st)") { Innertube.search("radiohead", Innertube.F_SONGS).size }
        time("innertube songs   (2nd)") { Innertube.search("portishead", Innertube.F_SONGS).size }
        time("innertube artists") { Innertube.search("portishead", Innertube.F_ARTISTS).size }
        time("innertube albums") { Innertube.search("portishead", Innertube.F_ALBUMS).size }
        time("innertube playlists") { Innertube.search("portishead", Innertube.F_PLAYLISTS).size }
        time("soundcloud (NewPipe)") { Sources.searchSoundCloud("portishead").size }
        time("bandcamp") { Sources.searchBandcamp("portishead").size }

        println("\n=== what the Search screen actually does for one query ===")
        val sequential = measureTimeMillis {
            kotlinx.coroutines.runBlocking { Sources.searchAll("massive attack") }
            Innertube.search("massive attack", Innertube.F_ARTISTS)
            Innertube.search("massive attack", Innertube.F_ALBUMS)
            Innertube.search("massive attack", Innertube.F_PLAYLISTS)
        }
        println("  SEQUENTIAL (current behaviour): $sequential ms")

        val parallel = measureTimeMillis {
            listOf(
                Thread { Innertube.search("massive attack", Innertube.F_SONGS) },
                Thread { Sources.searchSoundCloud("massive attack") },
                Thread { Sources.searchBandcamp("massive attack") },
                Thread { Innertube.search("massive attack", Innertube.F_ARTISTS) },
                Thread { Innertube.search("massive attack", Innertube.F_ALBUMS) },
                Thread { Innertube.search("massive attack", Innertube.F_PLAYLISTS) },
            ).onEach { it.start() }.forEach { it.join() }
        }
        println("  PARALLEL  (what it could be):   $parallel ms")
    }
}
