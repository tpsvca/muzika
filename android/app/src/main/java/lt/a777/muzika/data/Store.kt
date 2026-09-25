package lt.a777.muzika.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Local library: playlists, favourites and history.
 *
 * Deliberately the same shape as the desktop player's SQLite store so the two
 * can be reasoned about together; the sync file is what actually travels.
 */
object Store {
    private lateinit var helper: Helper
    private val db: SQLiteDatabase get() = helper.writableDatabase

    fun open(context: Context) {
        helper = Helper(context.applicationContext)
        db // force creation
    }

    private class Helper(context: Context) :
        SQLiteOpenHelper(context, "muzika.db", null, 4) {
        override fun onCreate(d: SQLiteDatabase) {
            d.execSQL(
                """CREATE TABLE playlists (
                     id INTEGER PRIMARY KEY AUTOINCREMENT,
                     name TEXT NOT NULL,
                     created_at REAL NOT NULL,
                     updated_at REAL NOT NULL)"""
            )
            d.execSQL(
                """CREATE TABLE playlist_tracks (
                     playlist_id INTEGER NOT NULL,
                     video_id TEXT NOT NULL,
                     title TEXT NOT NULL,
                     subtitle TEXT,
                     thumb TEXT,
                     duration INTEGER DEFAULT 0,
                     source TEXT,
                     url TEXT,
                     album TEXT,
                     position INTEGER NOT NULL,
                     added_at REAL NOT NULL,
                     PRIMARY KEY (playlist_id, video_id))"""
            )
            d.execSQL(
                """CREATE TABLE favourites (
                     video_id TEXT PRIMARY KEY,
                     title TEXT NOT NULL,
                     subtitle TEXT,
                     thumb TEXT,
                     duration INTEGER DEFAULT 0,
                     source TEXT,
                     url TEXT,
                     album TEXT,
                     added_at REAL NOT NULL)"""
            )
            d.execSQL(SAVED_TABLE)
            d.execSQL(LOCAL_TABLE)
            d.execSQL(
                """CREATE TABLE history (
                     video_id TEXT NOT NULL,
                     title TEXT NOT NULL,
                     subtitle TEXT,
                     thumb TEXT,
                     duration INTEGER DEFAULT 0,
                     source TEXT,
                     url TEXT,
                     album TEXT,
                     played_at REAL NOT NULL)"""
            )
        }

        override fun onUpgrade(d: SQLiteDatabase, old: Int, new: Int) {
            if (old < 4) runCatching { d.execSQL(LOCAL_TABLE) }
            if (old < 3) runCatching { d.execSQL(SAVED_TABLE) }
            if (old < 2) {
                for (table in listOf("playlist_tracks", "favourites", "history")) {
                    runCatching { d.execSQL("ALTER TABLE $table ADD COLUMN album TEXT") }
                }
            }
        }
    }

    /**
     * Albums, artists and playlists saved from the catalogue - the same shape
     * the desktop player keeps, so the two can be merged by the sync file.
     */
    private const val SAVED_TABLE =
        """CREATE TABLE IF NOT EXISTS saved (
             kind     TEXT NOT NULL,
             item_id  TEXT NOT NULL,
             title    TEXT NOT NULL,
             subtitle TEXT,
             thumb    TEXT,
             added_at REAL NOT NULL,
             PRIMARY KEY (kind, item_id))"""

    /** Audio files found in the synced music folder. */
    private const val LOCAL_TABLE =
        """CREATE TABLE IF NOT EXISTS local_tracks (
             path     TEXT PRIMARY KEY,
             title    TEXT NOT NULL,
             artist   TEXT,
             album    TEXT,
             track_no INTEGER DEFAULT 0,
             duration INTEGER DEFAULT 0,
             thumb    TEXT)"""

    private fun now() = System.currentTimeMillis() / 1000.0

    private fun values(track: Track): ContentValues = ContentValues().apply {
        put("video_id", track.id)
        put("title", track.title)
        put("subtitle", track.artist)
        put("thumb", track.thumb)
        put("duration", track.duration)
        put("source", track.source)
        put("url", track.url)
        put("album", track.album)
    }

    private fun Cursor.toTrack() = Track(
        id = getString(getColumnIndexOrThrow("video_id")),
        title = getString(getColumnIndexOrThrow("title")),
        artist = getString(getColumnIndexOrThrow("subtitle")) ?: "",
        duration = getInt(getColumnIndexOrThrow("duration")),
        thumb = getString(getColumnIndexOrThrow("thumb")),
        source = getString(getColumnIndexOrThrow("source")) ?: "youtube",
        url = getString(getColumnIndexOrThrow("url")),
        album = runCatching { getString(getColumnIndexOrThrow("album")) }.getOrNull(),
    )

    private fun <T> query(sql: String, args: Array<String>? = null, map: (Cursor) -> T): List<T> {
        val out = mutableListOf<T>()
        db.rawQuery(sql, args).use { c -> while (c.moveToNext()) out.add(map(c)) }
        return out
    }

    // ------------------------------------------------------------ favourites

    fun isFavourite(id: String): Boolean =
        db.rawQuery("SELECT 1 FROM favourites WHERE video_id = ?", arrayOf(id))
            .use { it.moveToNext() }

    fun toggleFavourite(track: Track): Boolean {
        if (isFavourite(track.id)) {
            db.delete("favourites", "video_id = ?", arrayOf(track.id))
            return false
        }
        db.insertWithOnConflict("favourites", null,
            values(track).apply { put("added_at", now()) },
            SQLiteDatabase.CONFLICT_REPLACE)
        return true
    }

    fun addFavourite(track: Track) {
        if (!isFavourite(track.id)) {
            db.insertWithOnConflict("favourites", null,
                values(track).apply { put("added_at", now()) },
                SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    fun favourites(): List<Track> =
        query("SELECT * FROM favourites ORDER BY added_at DESC") { it.toTrack() }

    // --------------------------------------------------------------- history

    fun recordPlay(track: Track) {
        db.insert("history", null, values(track).apply { put("played_at", now()) })
        db.execSQL(
            "DELETE FROM history WHERE rowid NOT IN " +
                "(SELECT rowid FROM history ORDER BY played_at DESC LIMIT 500)"
        )
    }

    fun history(limit: Int = 100): List<Track> = query(
        "SELECT video_id, title, subtitle, thumb, duration, source, url, album, " +
            "MAX(played_at) AS played_at " +
            "FROM history GROUP BY video_id ORDER BY played_at DESC LIMIT $limit"
    ) { it.toTrack() }

    fun clearHistory() { db.delete("history", null, null) }

    // ------------------------------------------------------------- playlists

    data class Playlist(val id: Long, val name: String, val count: Int,
                        val updatedAt: Double, val thumb: String?)

    fun createPlaylist(name: String): Long {
        val clean = name.trim().ifEmpty { "Untitled playlist" }
        return db.insert("playlists", null, ContentValues().apply {
            put("name", clean); put("created_at", now()); put("updated_at", now())
        })
    }

    fun renamePlaylist(id: Long, name: String) {
        if (name.isBlank()) return
        db.update("playlists", ContentValues().apply {
            put("name", name.trim()); put("updated_at", now())
        }, "id = ?", arrayOf(id.toString()))
    }

    fun deletePlaylist(id: Long) {
        db.delete("playlist_tracks", "playlist_id = ?", arrayOf(id.toString()))
        db.delete("playlists", "id = ?", arrayOf(id.toString()))
    }

    fun playlists(): List<Playlist> = query(
        """SELECT p.id, p.name, p.updated_at,
                  (SELECT COUNT(*) FROM playlist_tracks t WHERE t.playlist_id = p.id) AS n,
                  (SELECT t.thumb FROM playlist_tracks t WHERE t.playlist_id = p.id
                   ORDER BY t.position LIMIT 1) AS thumb
           FROM playlists p ORDER BY p.updated_at DESC"""
    ) { c ->
        Playlist(
            c.getLong(0), c.getString(1), c.getInt(3),
            c.getDouble(2), c.getString(4)
        )
    }

    fun playlistTracks(id: Long): List<Track> = query(
        "SELECT * FROM playlist_tracks WHERE playlist_id = ? ORDER BY position",
        arrayOf(id.toString())
    ) { it.toTrack() }

    private fun nextPosition(playlistId: Long): Int =
        db.rawQuery(
            "SELECT COALESCE(MAX(position), -1) + 1 FROM playlist_tracks WHERE playlist_id = ?",
            arrayOf(playlistId.toString())
        ).use { if (it.moveToNext()) it.getInt(0) else 0 }

    /** Returns false when the track is already in the playlist. */
    fun addToPlaylist(playlistId: Long, track: Track): Boolean {
        val exists = db.rawQuery(
            "SELECT 1 FROM playlist_tracks WHERE playlist_id = ? AND video_id = ?",
            arrayOf(playlistId.toString(), track.id)
        ).use { it.moveToNext() }
        if (exists) return false
        db.insert("playlist_tracks", null, values(track).apply {
            put("playlist_id", playlistId)
            put("position", nextPosition(playlistId))
            put("added_at", now())
        })
        touch(playlistId)
        return true
    }

    fun addManyToPlaylist(playlistId: Long, tracks: List<Track>): Int =
        tracks.count { addToPlaylist(playlistId, it) }

    fun removeFromPlaylist(playlistId: Long, trackId: String) {
        db.delete("playlist_tracks", "playlist_id = ? AND video_id = ?",
            arrayOf(playlistId.toString(), trackId))
        renumber(playlistId)
        touch(playlistId)
    }

    fun moveTrack(playlistId: Long, trackId: String, delta: Int) {
        val ids = playlistTracks(playlistId).map { it.id }.toMutableList()
        val index = ids.indexOf(trackId)
        val target = index + delta
        if (index < 0 || target !in ids.indices) return
        ids[index] = ids[target].also { ids[target] = ids[index] }
        ids.forEachIndexed { position, id ->
            db.update("playlist_tracks", ContentValues().apply { put("position", position) },
                "playlist_id = ? AND video_id = ?", arrayOf(playlistId.toString(), id))
        }
        touch(playlistId)
    }

    private fun renumber(playlistId: Long) {
        playlistTracks(playlistId).forEachIndexed { position, track ->
            db.update("playlist_tracks", ContentValues().apply { put("position", position) },
                "playlist_id = ? AND video_id = ?", arrayOf(playlistId.toString(), track.id))
        }
    }

    private fun touch(playlistId: Long) {
        db.update("playlists", ContentValues().apply { put("updated_at", now()) },
            "id = ?", arrayOf(playlistId.toString()))
    }

    // ------------------------------------------------- the library as a whole

    data class ArtistEntry(val name: String, val trackCount: Int, val thumb: String?)
    data class AlbumEntry(val name: String, val artist: String,
                          val trackCount: Int, val thumb: String?)

    private const val EVERYTHING =
        """SELECT video_id, title, subtitle, thumb, duration, source, url, album,
                  MAX(at) AS at FROM (
             SELECT video_id, title, subtitle, thumb, duration, source, url, album,
                    added_at AS at FROM playlist_tracks
             UNION ALL
             SELECT video_id, title, subtitle, thumb, duration, source, url, album,
                    added_at AS at FROM favourites
             UNION ALL
             SELECT video_id, title, subtitle, thumb, duration, source, url, album,
                    played_at AS at FROM history)
           GROUP BY video_id"""

    /** Every track this device knows about: playlists, favourites and history. */
    fun songs(): List<Track> =
        query("$EVERYTHING ORDER BY title COLLATE NOCASE") { it.toTrack() }

    fun artists(): List<ArtistEntry> = songs()
        .filter { it.primaryArtist.isNotEmpty() }
        .groupBy { it.primaryArtist }
        .map { (name, tracks) ->
            ArtistEntry(name, tracks.size, tracks.firstNotNullOfOrNull { it.thumb })
        }
        .sortedWith(compareByDescending<ArtistEntry> { it.trackCount }.thenBy { it.name.lowercase() })

    fun albums(): List<AlbumEntry> = songs()
        .filter { !it.album.isNullOrBlank() }
        .groupBy { it.album!! }
        .map { (name, tracks) ->
            AlbumEntry(name, tracks.first().primaryArtist, tracks.size,
                tracks.firstNotNullOfOrNull { it.thumb })
        }
        .sortedBy { it.name.lowercase() }

    fun tracksByArtist(name: String): List<Track> =
        songs().filter { it.primaryArtist.equals(name, ignoreCase = true) }

    fun tracksByAlbum(name: String): List<Track> =
        songs().filter { it.album.equals(name, ignoreCase = true) }

    /**
     * What this device has actually listened to most, newest tie-break first.
     * These are the seeds the home page builds its suggestions from.
     */
    fun mostPlayed(limit: Int = 20): List<Track> = query(
        "SELECT video_id, title, subtitle, thumb, duration, source, url, album, " +
            "COUNT(*) AS plays, MAX(played_at) AS played_at FROM history " +
            "GROUP BY video_id ORDER BY plays DESC, played_at DESC LIMIT $limit"
    ) { it.toTrack() }

    /** Artists ranked by how much they are actually played, then by library size. */
    fun favouriteArtists(limit: Int = 8): List<String> {
        val played = mostPlayed(80).map { it.primaryArtist }.filter { it.isNotEmpty() }
        val ranked = LinkedHashMap<String, Int>()
        played.forEachIndexed { index, artist ->
            ranked[artist] = (ranked[artist] ?: 0) + (80 - index)
        }
        favourites().forEach { track ->
            val artist = track.primaryArtist
            if (artist.isNotEmpty()) ranked[artist] = (ranked[artist] ?: 0) + 40
        }
        artists().take(20).forEach { entry ->
            ranked[entry.name] = (ranked[entry.name] ?: 0) + entry.trackCount
        }
        return ranked.entries.sortedByDescending { it.value }.take(limit).map { it.key }
    }

    // ---------------------------------------------------- saved from the catalogue

    data class Saved(val kind: String, val id: String, val title: String,
                     val subtitle: String, val thumb: String?)

    fun isSaved(kind: String, id: String): Boolean =
        db.rawQuery("SELECT 1 FROM saved WHERE kind = ? AND item_id = ?", arrayOf(kind, id))
            .use { it.moveToNext() }

    fun save(item: Saved) {
        db.insertWithOnConflict("saved", null, ContentValues().apply {
            put("kind", item.kind); put("item_id", item.id); put("title", item.title)
            put("subtitle", item.subtitle); put("thumb", item.thumb); put("added_at", now())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun unsave(kind: String, id: String) {
        db.delete("saved", "kind = ? AND item_id = ?", arrayOf(kind, id))
    }

    /** Returns the state it ended up in, so the caller can report it. */
    fun toggleSaved(item: Saved): Boolean {
        if (isSaved(item.kind, item.id)) { unsave(item.kind, item.id); return false }
        save(item); return true
    }

    fun saved(kind: String? = null): List<Saved> {
        val sql = StringBuilder("SELECT kind, item_id, title, subtitle, thumb FROM saved")
        val args = if (kind != null) { sql.append(" WHERE kind = ?"); arrayOf(kind) } else null
        sql.append(" ORDER BY added_at DESC")
        return query(sql.toString(), args) { c ->
            Saved(c.getString(0), c.getString(1), c.getString(2),
                c.getString(3) ?: "", c.getString(4))
        }
    }

    // ------------------------------------------------------------ local music

    /** Replace the whole index at once, so a rescan picks up deletions too. */
    fun replaceLocalTracks(tracks: List<Track>) {
        db.beginTransaction()
        try {
            db.delete("local_tracks", null, null)
            for (track in tracks) {
                db.insertWithOnConflict("local_tracks", null, ContentValues().apply {
                    put("path", track.url)
                    put("title", track.title)
                    put("artist", track.artist)
                    put("album", track.album)
                    put("track_no", track.trackNumber)
                    put("duration", track.duration)
                    put("thumb", track.thumb)
                }, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun Cursor.toLocalTrack() = Track(
        id = "local:" + getString(getColumnIndexOrThrow("path")),
        title = getString(getColumnIndexOrThrow("title")),
        artist = getString(getColumnIndexOrThrow("artist")) ?: "",
        duration = getInt(getColumnIndexOrThrow("duration")),
        thumb = getString(getColumnIndexOrThrow("thumb")),
        source = "local",
        url = getString(getColumnIndexOrThrow("path")),
        album = getString(getColumnIndexOrThrow("album")),
        trackNumber = getInt(getColumnIndexOrThrow("track_no")),
    )

    fun localTracks(): List<Track> = query(
        "SELECT * FROM local_tracks " +
            "ORDER BY artist COLLATE NOCASE, album COLLATE NOCASE, track_no, title"
    ) { it.toLocalTrack() }

    fun localCount(): Int =
        db.rawQuery("SELECT COUNT(*) FROM local_tracks", null)
            .use { if (it.moveToNext()) it.getInt(0) else 0 }
}
