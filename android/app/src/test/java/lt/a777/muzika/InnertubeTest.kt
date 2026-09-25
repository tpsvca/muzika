package lt.a777.muzika

import lt.a777.muzika.sources.Innertube
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hits YouTube Music for real. These exist because the parsers are the part
 * most likely to break silently - a shape change shows up as an empty screen
 * on the phone otherwise.
 */
class InnertubeTest {

    @Test fun songsSearchReturnsPlayableTracks() {
        val songs = Innertube.search("metallica", Innertube.F_SONGS)
        println("songs=${songs.size} first=${songs.firstOrNull()}")
        assertTrue("no songs", songs.size >= 5)
        val first = songs.first()
        assertTrue("not playable", first.playable)
        assertTrue("no id", first.id.length == 11)
        assertTrue("no title", first.title.isNotEmpty())
        assertTrue("no duration", songs.count { it.duration > 0 } >= 5)
        assertTrue("no artist", songs.count { it.subtitle.isNotEmpty() } >= 5)
        assertTrue("no artwork", songs.count { !it.thumb.isNullOrEmpty() } >= 5)
    }

    @Test fun albumsArtistsAndPlaylistsAreTypedCorrectly() {
        val albums = Innertube.search("metallica", Innertube.F_ALBUMS)
        val artists = Innertube.search("metallica", Innertube.F_ARTISTS)
        val playlists = Innertube.search("metallica", Innertube.F_PLAYLISTS)
        println("albums=${albums.size} ${albums.firstOrNull()}")
        println("artists=${artists.size} ${artists.firstOrNull()}")
        println("playlists=${playlists.size} ${playlists.firstOrNull()}")
        assertTrue("albums", albums.count { it.kind == Innertube.ALBUM } >= 3)
        assertTrue("artists", artists.count { it.kind == Innertube.ARTIST } >= 3)
        assertTrue("playlists", playlists.count { it.kind == Innertube.PLAYLIST } >= 3)
    }

    @Test fun albumPageHasTracks() {
        val id = Innertube.search("master of puppets", Innertube.F_ALBUMS)
            .first { it.kind == Innertube.ALBUM }.id
        val page = Innertube.album(id)
        println("album=${page?.title} tracks=${page?.tracks?.size} first=${page?.tracks?.firstOrNull()}")
        assertTrue("no page", page != null)
        assertTrue("no tracks", (page?.tracks?.size ?: 0) >= 5)
        assertTrue("no cover", !page?.thumb.isNullOrEmpty())
        assertTrue("tracks lack artist", page!!.tracks.count { it.artist.isNotEmpty() } >= 5)
        assertTrue("tracks lack album", page.tracks.all { !it.album.isNullOrEmpty() })
        assertTrue("tracks lack duration", page.tracks.count { it.duration > 0 } >= 5)
    }

    @Test fun playlistPageHasTracks() {
        val id = Innertube.search("rock classics", Innertube.F_PLAYLISTS)
            .first { it.kind == Innertube.PLAYLIST }.id
        val page = Innertube.playlist(id)
        println("playlist=${page?.title} tracks=${page?.tracks?.size} first=${page?.tracks?.firstOrNull()}")
        assertTrue("no tracks", (page?.tracks?.size ?: 0) >= 5)
    }

    @Test fun artistPageHasTopSongsAndShelves() {
        val id = Innertube.search("metallica", Innertube.F_ARTISTS)
            .first { it.kind == Innertube.ARTIST }.id
        val page = Innertube.artist(id)
        println("artist=${page?.title} top=${page?.tracks?.size} shelves=${page?.shelves?.map { it.title }}")
        assertTrue("no top songs", (page?.tracks?.size ?: 0) >= 3)
        assertTrue("no shelves", (page?.shelves?.size ?: 0) >= 2)
        assertTrue("no related artists",
            page!!.shelves.any { it.title.contains("also like", true) })
    }

    @Test fun radioReturnsAQueue() {
        val seed = Innertube.search("enter sandman", Innertube.F_SONGS).first().id
        val radio = Innertube.radio(seed)
        println("radio=${radio.size} first=${radio.firstOrNull()}")
        assertTrue("radio too short", radio.size >= 10)
        assertTrue("radio lacks artwork", radio.count { !it.thumb.isNullOrEmpty() } >= 10)
        assertTrue("radio lacks artists", radio.count { it.artist.isNotEmpty() } >= 10)
    }

    @Test fun moodsAndOneCategoryLoad() {
        val moods = Innertube.moods()
        println("moods=${moods.size} ${moods.take(5).map { it.title }} sections=${moods.map { it.section }.distinct()}")
        assertTrue("no moods", moods.size >= 10)
        val shelves = Innertube.moodShelves(moods.first().params)
        println("mood '${moods.first().title}' shelves=${shelves.map { "${it.title}:${it.items.size}" }}")
        assertTrue("mood empty", shelves.sumOf { it.items.size } >= 5)
    }
}
