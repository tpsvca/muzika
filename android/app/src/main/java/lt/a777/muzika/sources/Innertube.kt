package lt.a777.muzika.sources

import lt.a777.muzika.data.Track
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * YouTube Music's own InnerTube API, called the way its website calls it.
 *
 * Only browse/search/next are used. Those answer anonymously; the player
 * endpoint does not, which is why streams still go through NewPipeExtractor.
 * No key of the user's is involved anywhere - the API key below is the public
 * one the web client ships with.
 */
object Innertube {
    private const val BASE = "https://music.youtube.com/youtubei/v1"
    private const val KEY = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30"
    private const val CLIENT_VERSION = "1.20250915.01.00"
    /** Enough for a grid card on a phone; detail pages ask for more. */
    private const val THUMB_SIZE = 256
    private const val COVER_SIZE = 544

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"

    const val SONG = "song"
    const val VIDEO = "video"
    const val ALBUM = "album"
    const val ARTIST = "artist"
    const val PLAYLIST = "playlist"

    /** Search filters, as the web client encodes them. */
    const val F_SONGS = "EgWKAQIIAWoMEA4QChADEAQQCRAF"
    const val F_VIDEOS = "EgWKAQIQAWoMEA4QChADEAQQCRAF"
    const val F_ALBUMS = "EgWKAQIYAWoMEA4QChADEAQQCRAF"
    const val F_ARTISTS = "EgWKAQIgAWoMEA4QChADEAQQCRAF"
    const val F_PLAYLISTS = "EgeKAQQoAEABagwQDhAKEAMQBBAJEAU="

    /** One row anywhere in the app: a song, or something you can open. */
    data class Item(
        val kind: String,
        val id: String,
        val title: String,
        val subtitle: String = "",
        val thumb: String? = null,
        val duration: Int = 0,
        val album: String? = null,
        val artistId: String? = null,
    ) {
        val playable: Boolean get() = kind == SONG || kind == VIDEO

        fun toTrack() = Track(
            id = id, title = title, artist = subtitle.substringBefore(" • ").trim(),
            duration = duration, thumb = thumb, source = Sources.YOUTUBE,
            url = "https://www.youtube.com/watch?v=$id", album = album,
        )
    }

    data class Shelf(val title: String, val items: List<Item>)
    data class Mood(val title: String, val params: String, val section: String)
    data class Page(val title: String, val subtitle: String, val thumb: String?,
                    val tracks: List<Track>, val shelves: List<Shelf> = emptyList())

    // ------------------------------------------------------------- transport

    @Volatile private var visitorId: String? = null
    /** Set as soon as one attempt has been made, successful or not. */
    @Volatile private var visitorResolved = false

    /**
     * A visitor id is optional - every endpoint here answers without one - but
     * it costs nothing to carry when we have it.
     *
     * Getting it means downloading the YouTube Music homepage, which is close
     * to half a megabyte. This used to happen on *every single request*,
     * because the key was being looked up as `VISITOR_DATA` when the page
     * actually spells it `visitorData`, so nothing was ever cached: each call
     * paid ~1.7s and 476KB before it even started. One attempt is made now, in
     * the background, and a failure is remembered so it is never retried.
     */
    private fun visitor(): String? = visitorId

    private fun resolveVisitorOnce() {
        if (visitorResolved) return
        synchronized(this) {
            if (visitorResolved) return
            visitorResolved = true
        }
        runCatching {
            val request = Request.Builder().url("https://music.youtube.com")
                .addHeader("User-Agent", UA).addHeader("Accept-Language", "en-US,en;q=0.9").build()
            NpeDownloader.client.newCall(request).execute().use { response ->
                val html = response.body?.string() ?: return@use
                visitorId = Regex("\"visitorData\"\\s*:\\s*\"([^\"]+)\"")
                    .find(html)?.groupValues?.get(1)
            }
        }
    }

    /** Called once at startup so the first search never waits for it. */
    fun warmUp() {
        Thread { resolveVisitorOnce() }.apply { isDaemon = true }.start()
    }

    private fun call(endpoint: String, body: JSONObject): JSONObject {
        body.put(
            "context", JSONObject().put(
                "client", JSONObject()
                    .put("clientName", "WEB_REMIX")
                    .put("clientVersion", CLIENT_VERSION)
                    .put("hl", "en").put("gl", "US")
            ).put("user", JSONObject())
        )
        val builder = Request.Builder()
            .url("$BASE/$endpoint?key=$KEY&prettyPrint=false")
            .addHeader("User-Agent", UA)
            .addHeader("Origin", "https://music.youtube.com")
            .addHeader("Referer", "https://music.youtube.com/")
            .addHeader("Accept-Language", "en-US,en;q=0.9")
            .addHeader("X-YouTube-Client-Name", "67")
            .addHeader("X-YouTube-Client-Version", CLIENT_VERSION)
        visitor()?.let { builder.addHeader("X-Goog-Visitor-Id", it) }
        val request = builder
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        NpeDownloader.client.newCall(request).execute().use { response ->
            return JSONObject(response.body?.string() ?: "{}")
        }
    }

    private fun browse(browseId: String, params: String? = null): JSONObject =
        call("browse", JSONObject().put("browseId", browseId)
            .also { if (params != null) it.put("params", params) })

    // ----------------------------------------------------------- json helpers

    private fun JSONObject?.o(key: String): JSONObject? = this?.optJSONObject(key)
    private fun JSONObject?.a(key: String): JSONArray? = this?.optJSONArray(key)
    private fun JSONArray?.o(index: Int): JSONObject? = this?.optJSONObject(index)
    private fun JSONArray.objects(): List<JSONObject> =
        (0 until length()).mapNotNull { optJSONObject(it) }

    /** Concatenated text of a `{ runs: [...] }` node. */
    private fun runs(node: JSONObject?): String =
        node.a("runs")?.objects()?.joinToString("") { it.optString("text") } ?: ""

    private fun runList(node: JSONObject?): List<JSONObject> =
        node.a("runs")?.objects() ?: emptyList()

    /**
     * Artwork at a size worth downloading.
     *
     * YouTube serves whatever dimensions the URL asks for, so asking for 544px
     * everywhere meant list rows 56dp tall pulling roughly four times the
     * pixels they can show - about a megabyte per search instead of a tenth of
     * that, plus the decode cost.
     */
    private fun thumb(node: JSONObject?, size: Int = THUMB_SIZE): String? {
        val list = node.o("musicThumbnailRenderer").o("thumbnail").a("thumbnails")
            ?: node.a("thumbnails") ?: return null
        val best = list.objects().maxByOrNull { it.optInt("width") } ?: return null
        val url = best.optString("url").ifEmpty { return null }
        return url.replace(Regex("=w\\d+-h\\d+"), "=w$size-h$size")
            .replace(Regex("=s\\d+"), "=s$size")
    }

    private fun sections(response: JSONObject): List<JSONObject> {
        val contents = response.o("contents")
        contents.o("singleColumnBrowseResultsRenderer").a("tabs").o(0)
            .o("tabRenderer").o("content").o("sectionListRenderer").a("contents")
            ?.let { return it.objects() }
        contents.o("twoColumnBrowseResultsRenderer")
            .o("secondaryContents").o("sectionListRenderer").a("contents")
            ?.let { return it.objects() }
        contents.o("tabbedSearchResultsRenderer").a("tabs").o(0)
            .o("tabRenderer").o("content").o("sectionListRenderer").a("contents")
            ?.let { return it.objects() }
        return emptyList()
    }

    private fun parseDuration(text: String): Int {
        val parts = text.trim().split(":").mapNotNull { it.toIntOrNull() }
        if (parts.isEmpty() || parts.size > 3) return 0
        return parts.fold(0) { total, part -> total * 60 + part }
    }

    // ------------------------------------------------------------ item parsing

    /** A row in a list: search hit, album track, playlist track. */
    private fun parseRow(renderer: JSONObject): Item? {
        val flex = renderer.a("flexColumns")?.objects()
            ?.map { it.o("musicResponsiveListItemFlexColumnRenderer").o("text") } ?: return null
        val title = runs(flex.getOrNull(0)).ifEmpty { return null }

        var videoId = renderer.o("playlistItemData")?.optString("videoId")?.ifEmpty { null }
        if (videoId == null) {
            videoId = runList(flex.getOrNull(0)).firstNotNullOfOrNull {
                it.o("navigationEndpoint").o("watchEndpoint")?.optString("videoId")?.ifEmpty { null }
            } ?: renderer.o("overlay").o("musicItemThumbnailOverlayRenderer").o("content")
                .o("musicPlayButtonRenderer").o("playNavigationEndpoint").o("watchEndpoint")
                ?.optString("videoId")?.ifEmpty { null }
        }

        val detailRuns = runList(flex.getOrNull(1))
        val detail = detailRuns.map { it.optString("text") }
        // YTM separates fields with " • "; the trailing one is a duration for songs.
        // YTM emits its separators as their own runs (" \u2022 ", " & "), which are
        // not fields; the trailing real field is a duration for songs.
        val fields = detail.filterNot {
            it.isBlank() || it.trim() in setOf("\u2022", "&", ",", "\u00b7")
        }
        val fixed = renderer.a("fixedColumns").o(0)
            .o("musicResponsiveListItemFixedColumnRenderer").o("text")
        val durationText = runs(fixed).ifEmpty {
            fields.lastOrNull()?.takeIf { Regex("^\\d+(:\\d+)+$").matches(it.trim()) } ?: ""
        }
        val duration = parseDuration(durationText)
        val artistId = detailRuns.firstNotNullOfOrNull {
            it.o("navigationEndpoint").o("browseEndpoint")?.optString("browseId")
                ?.takeIf { id -> id.startsWith("UC") }
        }

        val image = thumb(renderer.o("thumbnail"))
        val browseId = renderer.o("navigationEndpoint").o("browseEndpoint")?.optString("browseId")

        if (videoId != null && videoId.isNotEmpty()) {
            val album = detailRuns.firstNotNullOfOrNull {
                it.o("navigationEndpoint").o("browseEndpoint")?.optString("browseId")
                    ?.takeIf { id -> id.startsWith("MPRE") }?.let { _ -> it.optString("text") }
            }
            // Drop the duration and the "Song"/"Video" type word from the subtitle.
            val subtitle = fields
                .filterNot { Regex("^\\d+(:\\d+)+$").matches(it.trim()) }
                .filterNot { it == "Song" || it == "Video" }
                .joinToString(" • ")
            return Item(
                kind = SONG, id = videoId, title = title,
                subtitle = subtitle.ifEmpty { runs(flex.getOrNull(1)) },
                thumb = image, duration = duration, album = album, artistId = artistId,
            )
        }
        if (browseId.isNullOrEmpty()) return null
        val kind = when {
            browseId.startsWith("MPRE") -> ALBUM
            browseId.startsWith("UC") -> ARTIST
            else -> PLAYLIST
        }
        val subtitle = fields.filterNot { it == "Album" || it == "Artist" || it == "Playlist" || it == "EP" || it == "Single" }
            .joinToString(" • ")
        return Item(kind, browseId, title, subtitle.ifEmpty { runs(flex.getOrNull(1)) }, image)
    }

    /** A card in a carousel or grid. */
    private fun parseCard(renderer: JSONObject): Item? {
        val title = runs(renderer.o("title")).ifEmpty { return null }
        // The subtitle leads with the item's own type ("Playlist \u2022 YouTube Music"),
        // which the card already says by its shape. Drop it.
        val subtitle = runs(renderer.o("subtitle"))
            .removePrefixWord("Playlist").removePrefixWord("Album")
            .removePrefixWord("Single").removePrefixWord("EP")
            .removePrefixWord("Artist").trim(' ', '\u2022')
        val image = thumb(renderer.o("thumbnailRenderer"))
        val endpoint = renderer.o("navigationEndpoint")

        val browse = endpoint.o("browseEndpoint")
        val browseId = browse?.optString("browseId")?.ifEmpty { null }
        if (browseId != null) {
            val pageType = browse.o("browseEndpointContextSupportedConfigs")
                .o("browseEndpointContextMusicConfig")?.optString("pageType") ?: ""
            val kind = when {
                pageType.contains("ARTIST") || browseId.startsWith("UC") -> ARTIST
                pageType.contains("ALBUM") || browseId.startsWith("MPRE") -> ALBUM
                else -> PLAYLIST
            }
            return Item(kind, browseId, title, subtitle, image)
        }
        val videoId = endpoint.o("watchEndpoint")?.optString("videoId")?.ifEmpty { null }
            ?: return null
        return Item(SONG, videoId, title, subtitle, image)
    }

    private fun String.removePrefixWord(word: String): String =
        if (startsWith("$word \u2022 ")) removePrefix("$word \u2022 ") else this

    private fun shelfItems(section: JSONObject): List<Item> {
        val out = mutableListOf<Item>()
        for (key in listOf("musicShelfRenderer", "musicPlaylistShelfRenderer")) {
            section.o(key).a("contents")?.objects()?.forEach { entry ->
                entry.o("musicResponsiveListItemRenderer")?.let { parseRow(it)?.let(out::add) }
            }
        }
        for (key in listOf("gridRenderer", "musicCarouselShelfRenderer",
                           "musicImmersiveCarouselShelfRenderer")) {
            val shelf = section.o(key) ?: continue
            (shelf.a("items") ?: shelf.a("contents"))?.objects()?.forEach { entry ->
                entry.o("musicTwoRowItemRenderer")?.let { parseCard(it)?.let(out::add) }
                entry.o("musicResponsiveListItemRenderer")?.let { parseRow(it)?.let(out::add) }
            }
        }
        return out
    }

    private fun shelfTitle(section: JSONObject): String {
        for (key in listOf("musicShelfRenderer", "musicPlaylistShelfRenderer")) {
            section.o(key)?.let { return runs(it.o("title")) }
        }
        section.o("musicCarouselShelfRenderer").o("header")
            .o("musicCarouselShelfBasicHeaderRenderer")?.let { return runs(it.o("title")) }
        section.o("gridRenderer").o("header").o("gridHeaderRenderer")
            ?.let { return runs(it.o("title")) }
        return ""
    }

    private fun shelves(response: JSONObject): List<Shelf> =
        sections(response).mapNotNull { section ->
            val items = shelfItems(section)
            if (items.isEmpty()) null else Shelf(shelfTitle(section), items)
        }

    // -------------------------------------------------------------- public API

    fun search(query: String, filter: String?): List<Item> = runCatching {
        val body = JSONObject().put("query", query)
        if (filter != null) body.put("params", filter)
        sections(call("search", body)).flatMap { shelfItems(it) }
    }.getOrDefault(emptyList())

    /** Search shelves kept separate, so "All" can show songs, albums and artists apart. */
    fun searchShelves(query: String): List<Shelf> = runCatching {
        shelves(call("search", JSONObject().put("query", query)))
            .filter { it.items.isNotEmpty() }
    }.getOrDefault(emptyList())

    fun moods(): List<Mood> = runCatching {
        val out = mutableListOf<Mood>()
        for (section in sections(browse("FEmusic_moods_and_genres"))) {
            val grid = section.o("gridRenderer") ?: continue
            val heading = runs(grid.o("header").o("gridHeaderRenderer").o("title"))
            grid.a("items")?.objects()?.forEach { entry ->
                val button = entry.o("musicNavigationButtonRenderer") ?: return@forEach
                val title = runs(button.o("buttonText"))
                val params = button.o("clickCommand").o("browseEndpoint")?.optString("params")
                if (title.isNotEmpty() && !params.isNullOrEmpty()) {
                    out.add(Mood(title, params, heading))
                }
            }
        }
        out
    }.getOrDefault(emptyList())

    fun moodShelves(params: String): List<Shelf> = runCatching {
        shelves(browse("FEmusic_moods_and_genres_category", params))
    }.getOrDefault(emptyList())

    fun homeShelves(): List<Shelf> = runCatching { shelves(browse("FEmusic_home")) }
        .getOrDefault(emptyList())

    /** An album page: its header plus its tracks, which carry no artwork of their own. */
    fun album(browseId: String): Page? = runCatching {
        val response = browse(browseId)
        val header = response.o("contents").o("twoColumnBrowseResultsRenderer")
            .a("tabs").o(0).o("tabRenderer").o("content").o("sectionListRenderer")
            .a("contents").o(0).o("musicResponsiveHeaderRenderer")
        val title = runs(header.o("title")).ifEmpty { "Album" }
        val artist = runs(header.o("straplineTextOne"))
        val cover = thumb(header.o("thumbnail"), COVER_SIZE)
        val tracks = sections(response).flatMap { shelfItems(it) }
            .filter { it.playable }
            .map { it.toTrack().copy(artist = it.subtitle.ifEmpty { artist },
                                     thumb = it.thumb ?: cover, album = title) }
        Page(title, listOf(runs(header.o("subtitle")), artist)
            .filter { it.isNotEmpty() }.joinToString(" • "), cover, tracks)
    }.getOrNull()

    fun playlist(playlistId: String): Page? = runCatching {
        val id = if (playlistId.startsWith("VL")) playlistId else "VL$playlistId"
        val response = browse(id)
        val header = response.o("contents").o("twoColumnBrowseResultsRenderer")
            .a("tabs").o(0).o("tabRenderer").o("content").o("sectionListRenderer")
            .a("contents").o(0).o("musicResponsiveHeaderRenderer")
        val tracks = sections(response).flatMap { shelfItems(it) }
            .filter { it.playable }.map { it.toTrack() }
        Page(
            runs(header.o("title")).ifEmpty { "Playlist" },
            runs(header.o("straplineTextOne")),
            thumb(header.o("thumbnail"), COVER_SIZE) ?: tracks.firstOrNull()?.thumb,
            tracks,
        )
    }.getOrNull()

    fun artist(channelId: String): Page? = runCatching {
        val response = browse(channelId)
        val header = sections(response).firstNotNullOfOrNull {
            it.o("musicImmersiveHeaderRenderer") ?: it.o("musicVisualHeaderRenderer")
        } ?: response.o("header").o("musicImmersiveHeaderRenderer")
        val all = shelves(response)
        val top = all.firstOrNull { it.title.contains("song", true) }
        Page(
            title = runs(header.o("title")).ifEmpty { top?.items?.firstOrNull()?.subtitle ?: "Artist" },
            subtitle = runs(header.o("subscriptionButton").o("subscribeButtonRenderer")
                .o("longSubscriberCountText")),
            thumb = thumb(header.o("thumbnail"), COVER_SIZE) ?: top?.items?.firstOrNull()?.thumb,
            tracks = top?.items?.filter { it.playable }?.map { it.toTrack() } ?: emptyList(),
            shelves = all.filter { it !== top },
        )
    }.getOrNull()

    /**
     * YouTube Music's radio for one song: fifty tracks picked to sit next to it.
     * This is what "made for you" is built from - no account, no profile.
     */
    fun radio(videoId: String, limit: Int = 50): List<Track> = runCatching {
        val response = call("next", JSONObject()
            .put("videoId", videoId)
            .put("playlistId", "RDAMVM$videoId")
            .put("isAudioOnly", true)
            .put("params", "wAEB"))
        val panel = response.o("contents")
            .o("singleColumnMusicWatchNextResultsRenderer").o("tabbedRenderer")
            .o("watchNextTabbedResultsRenderer").a("tabs").o(0).o("tabRenderer")
            .o("content").o("musicQueueRenderer").o("content").o("playlistPanelRenderer")
        panel.a("contents")?.objects()?.mapNotNull { entry ->
            val video = entry.o("playlistPanelVideoRenderer") ?: return@mapNotNull null
            val id = video.optString("videoId").ifEmpty { return@mapNotNull null }
            Track(
                id = id,
                title = runs(video.o("title")),
                artist = runs(video.o("longBylineText")).substringBefore(" • "),
                duration = parseDuration(runs(video.o("lengthText"))),
                thumb = thumb(video.o("thumbnail")),
                source = Sources.YOUTUBE,
                url = "https://www.youtube.com/watch?v=$id",
            )
        }?.take(limit) ?: emptyList()
    }.getOrDefault(emptyList())
}
