package lt.a777.muzika

import androidx.test.core.app.ApplicationProvider
import lt.a777.muzika.data.Prefs
import lt.a777.muzika.data.Store
import lt.a777.muzika.data.Sync
import lt.a777.muzika.data.Track
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The database and merge rules, against a real SQLite.
 *
 * Each case here is a bug that either happened or would have been silent:
 * a sync emptying a playlist, favourites being replaced instead of unioned,
 * and the schema migration running against an existing database.
 */
@RunWith(RobolectricTestRunner::class)
class StoreSyncTest {

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        Prefs.open(context)
        Store.open(context)
        Store.playlists().forEach { Store.deletePlaylist(it.id) }
        Store.favourites().forEach { Store.unsave("x", it.id); Store.toggleFavourite(it) }
        Store.clearHistory()
    }

    private fun track(id: String, title: String = "T", artist: String = "A") =
        Track(id = id, title = title, artist = artist, duration = 1)

    private fun payload(
        playlists: JSONArray = JSONArray(),
        favourites: JSONArray = JSONArray(),
        library: JSONArray = JSONArray(),
    ) = JSONObject()
        .put("format", "muzika-library").put("version", 1)
        .put("updated_at", 0).put("device", "test")
        .put("playlists", playlists).put("favourites", favourites)
        .put("library", library)

    @Test fun importNeverEmptiesAPlaylist() {
        val id = Store.createPlaylist("Keepers")
        Store.addManyToPlaylist(id, listOf(track("a"), track("b")))

        val remote = JSONArray().put(
            JSONObject().put("name", "Keepers").put("updated_at", 0)
                .put("tracks", JSONArray()))
        Sync.apply(payload(playlists = remote))

        assertEquals(listOf("a", "b"), Store.playlistTracks(id).map { it.id })
    }

    @Test fun favouritesAreUnioned() {
        Store.addFavourite(track("mine"))
        val remote = JSONArray().put(
            JSONObject().put("id", "theirs").put("title", "T")
                .put("artist", "A").put("duration", 0))
        Sync.apply(payload(favourites = remote))
        assertEquals(setOf("mine", "theirs"), Store.favourites().map { it.id }.toSet())
    }

    @Test fun savedItemsAreUnionedOnKindAndId() {
        Store.save(Store.Saved("album", "MPRE1", "One", "", null))
        val remote = JSONArray().put(
            JSONObject().put("kind", "album").put("id", "MPRE2")
                .put("title", "Two").put("subtitle", ""))
        Sync.apply(payload(library = remote))
        assertEquals(setOf("MPRE1", "MPRE2"), Store.saved("album").map { it.id }.toSet())
    }

    @Test fun importIsIdempotent() {
        val remote = JSONArray().put(
            JSONObject().put("name", "P").put("updated_at", 0).put("tracks",
                JSONArray().put(JSONObject().put("id", "x").put("title", "X")
                    .put("artist", "A").put("duration", 0))))
        Sync.apply(payload(playlists = remote))
        Sync.apply(payload(playlists = remote))
        assertEquals(1, Store.playlists().count { it.name == "P" })
    }

    @Test fun exportThenImportPreservesEverything() {
        val id = Store.createPlaylist("Round trip")
        Store.addManyToPlaylist(id, listOf(track("a"), track("b")))
        Store.addFavourite(track("fav"))
        Store.save(Store.Saved("artist", "UC1", "Band", "", null))

        val json = Sync.buildPayload()
        assertEquals("muzika-library", json.optString("format"))
        assertEquals(1, json.getJSONArray("playlists").length())
        assertEquals(1, json.getJSONArray("favourites").length())
        assertEquals(1, json.getJSONArray("library").length())

        // Re-applying our own export must change nothing.
        val report = Sync.apply(json)
        assertTrue(report.ok)
        assertEquals(listOf("a", "b"), Store.playlistTracks(id).map { it.id })
    }

    @Test fun albumColumnSurvivesAndRoundTrips() {
        val id = Store.createPlaylist("Albums")
        Store.addManyToPlaylist(id, listOf(
            track("a").copy(album = "Master of Puppets")))
        assertEquals("Master of Puppets", Store.playlistTracks(id).first().album)
        val json = Sync.buildPayload()
        val first = json.getJSONArray("playlists").getJSONObject(0)
            .getJSONArray("tracks").getJSONObject(0)
        assertEquals("Master of Puppets", first.optString("album"))
    }
}
