package lt.a777.muzika.data

import android.os.Build
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Library sync through one file in a folder something else already syncs.
 *
 * Syncthing, Dropbox, Nextcloud and OpenCloud all sync folders, so there is no
 * per-service code here at all - just muzika-library.json, the same file the
 * desktop player reads and writes.
 */
object Sync {
    private const val TAG = "Sync"
    private const val FORMAT = "muzika-library"
    private const val VERSION = 1
    const val FILENAME = "muzika-library.json"

    /** Where the folder-syncing client drops the file on this device. */
    val defaultFile get() = File(Prefs.syncFolder, FILENAME)

    data class Report(
        val ok: Boolean,
        val playlistsAdded: Int = 0,
        val playlistsUpdated: Int = 0,
        val tracksAdded: Int = 0,
        val favouritesAdded: Int = 0,
        val savedAdded: Int = 0,
        val reason: String = "",
    ) {
        fun describe(): String = when {
            !ok -> reason
            playlistsAdded + playlistsUpdated + tracksAdded + favouritesAdded +
                savedAdded == 0 -> "Already up to date"
            else -> buildList {
                if (playlistsAdded > 0) add("$playlistsAdded new playlists")
                if (playlistsUpdated > 0) add("$playlistsUpdated updated")
                if (tracksAdded > 0) add("$tracksAdded tracks")
                if (favouritesAdded > 0) add("$favouritesAdded favourites")
                if (savedAdded > 0) add("$savedAdded saved items")
            }.joinToString(", ")
        }
    }

    // ---------------------------------------------------------------- export

    private fun trackJson(track: Track): JSONObject = JSONObject()
        .put("id", track.id)
        .put("title", track.title)
        .put("artist", track.artist)
        .put("duration", track.duration)
        .put("thumb", track.thumb ?: JSONObject.NULL)
        .also {
            if (track.source != Sources_YOUTUBE) {
                it.put("source", track.source)
                it.put("url", track.url ?: JSONObject.NULL)
            }
            if (!track.album.isNullOrEmpty()) it.put("album", track.album)
        }

    private const val Sources_YOUTUBE = "youtube"

    private fun trackFrom(entry: JSONObject): Track = Track(
        id = entry.optString("id"),
        title = entry.optString("title"),
        artist = entry.optString("artist"),
        duration = entry.optInt("duration"),
        thumb = entry.optString("thumb").ifEmpty { null }?.takeIf { it != "null" },
        source = entry.optString("source").ifEmpty { Sources_YOUTUBE },
        url = entry.optString("url").ifEmpty { null }?.takeIf { it != "null" },
        album = entry.optString("album").ifEmpty { null }?.takeIf { it != "null" },
    )

    fun buildPayload(): JSONObject {
        val playlists = JSONArray()
        for (playlist in Store.playlists()) {
            val tracks = JSONArray()
            Store.playlistTracks(playlist.id).forEach { tracks.put(trackJson(it)) }
            playlists.put(
                JSONObject()
                    .put("name", playlist.name)
                    .put("updated_at", playlist.updatedAt)
                    .put("tracks", tracks)
            )
        }
        val favourites = JSONArray()
        Store.favourites().forEach { favourites.put(trackJson(it)) }

        // Saved albums, artists and playlists, the same shape the desktop writes.
        val saved = JSONArray()
        Store.saved().forEach { item ->
            saved.put(
                JSONObject()
                    .put("kind", item.kind)
                    .put("id", item.id)
                    .put("title", item.title)
                    .put("subtitle", item.subtitle)
                    .put("thumb", item.thumb ?: JSONObject.NULL)
            )
        }

        return JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("updated_at", System.currentTimeMillis() / 1000.0)
            .put("device", Build.MODEL ?: "android")
            .put("playlists", playlists)
            .put("favourites", favourites)
            .put("library", saved)
    }

    /** Written to a temp file and renamed, so a sync client never sees a half file. */
    fun export(file: File = defaultFile): Boolean = try {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, ".${file.name}.tmp")
        temp.writeText(buildPayload().toString(1))
        if (file.exists()) file.delete()
        val renamed = temp.renameTo(file)
        if (!renamed) { temp.copyTo(file, overwrite = true); temp.delete() }
        true
    } catch (e: Exception) {
        Log.w(TAG, "export failed", e); false
    }

    // ---------------------------------------------------------------- import

    fun import(file: File = defaultFile): Report {
        if (!file.exists()) return Report(false, reason = "No library file at ${file.path}")
        val payload = try {
            JSONObject(file.readText())
        } catch (e: Exception) {
            return Report(false, reason = "Could not read the library file")
        }
        if (payload.optString("format") != FORMAT) {
            return Report(false, reason = "Not a Muzika library file")
        }
        return apply(payload)
    }

    fun apply(payload: JSONObject): Report {
        val existing = Store.playlists().associateBy { it.name.lowercase() }
        var added = 0
        var updated = 0
        var tracksAdded = 0

        val playlists = payload.optJSONArray("playlists") ?: JSONArray()
        for (i in 0 until playlists.length()) {
            val remote = playlists.getJSONObject(i)
            val name = remote.optString("name").trim()
            if (name.isEmpty()) continue
            val remoteTracks = remote.optJSONArray("tracks") ?: JSONArray()
            val tracks = (0 until remoteTracks.length())
                .map { trackFrom(remoteTracks.getJSONObject(it)) }
                .filter { it.id.isNotEmpty() }

            val local = existing[name.lowercase()]
            if (local == null) {
                val id = Store.createPlaylist(name)
                tracksAdded += Store.addManyToPlaylist(id, tracks)
                added++
                continue
            }

            val localIds = Store.playlistTracks(local.id).map { it.id }
            if (localIds == tracks.map { it.id }) continue

            // The newer side wins rather than merging into something neither
            // device chose; favourites below are unioned instead.
            if (remote.optDouble("updated_at", 0.0) > local.updatedAt) {
                localIds.forEach { Store.removeFromPlaylist(local.id, it) }
                tracksAdded += Store.addManyToPlaylist(local.id, tracks)
            } else {
                tracksAdded += Store.addManyToPlaylist(local.id, tracks)
            }
            updated++
        }

        var favourites = 0
        val remoteFavourites = payload.optJSONArray("favourites") ?: JSONArray()
        for (i in 0 until remoteFavourites.length()) {
            val track = trackFrom(remoteFavourites.getJSONObject(i))
            if (track.id.isEmpty() || Store.isFavourite(track.id)) continue
            Store.addFavourite(track)
            favourites++
        }

        // Saved items are unioned: unsaving on one device must not delete here.
        var savedAdded = 0
        val remoteSaved = payload.optJSONArray("library") ?: JSONArray()
        for (i in 0 until remoteSaved.length()) {
            val entry = remoteSaved.getJSONObject(i)
            val kind = entry.optString("kind")
            val id = entry.optString("id")
            if (kind.isEmpty() || id.isEmpty() || Store.isSaved(kind, id)) continue
            Store.save(
                Store.Saved(
                    kind = kind, id = id,
                    title = entry.optString("title"),
                    subtitle = entry.optString("subtitle"),
                    thumb = entry.optString("thumb").ifEmpty { null }?.takeIf { it != "null" },
                )
            )
            savedAdded++
        }

        return Report(true, added, updated, tracksAdded, favourites, savedAdded)
    }

    /** Import whatever is there, then write our merged state back. */
    fun sync(file: File = defaultFile): Report {
        val report = import(file)
        export(file)
        return report
    }

    // --------------------------------------------------------------- dropbox

    private fun syncDropbox(): Report {
        val remote = try {
            Dropbox.download()
        } catch (e: Exception) {
            return Report(false, reason = e.message ?: "Dropbox sync failed")
        }
        val report = if (remote == null) Report(true) else {
            val payload = try { JSONObject(remote) } catch (e: Exception) { null }
            when {
                payload == null -> Report(false, reason = "Dropbox file is not readable")
                payload.optString("format") != FORMAT ->
                    Report(false, reason = "Not a Muzika library file")
                else -> apply(payload)
            }
        }
        if (!report.ok) return report
        return try {
            Dropbox.upload(buildPayload().toString(1))
            report
        } catch (e: Exception) {
            Report(false, reason = e.message ?: "Dropbox upload failed")
        }
    }

    /** Sync through whichever backend settings picked. */
    fun syncNow(): Report = when (Prefs.syncBackend) {
        Prefs.BACKEND_DROPBOX -> syncDropbox()
        else -> sync()
    }

    /** Push local state out without pulling, after an edit. */
    fun push() {
        if (Prefs.syncBackend == Prefs.BACKEND_DROPBOX) {
            runCatching { Dropbox.upload(buildPayload().toString(1)) }
        } else {
            export()
        }
    }
}
