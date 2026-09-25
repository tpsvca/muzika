package lt.a777.muzika.data

import android.media.MediaMetadataRetriever
import android.util.Log
import java.io.File

/**
 * Audio files sitting in the synced folder.
 *
 * The desktop copies music into `<sync folder>/Music`, whichever service the
 * user runs carries it here, and this indexes what landed. A track's identity
 * is its path *relative to that folder*, which is exactly what the desktop
 * uses - so a playlist built on either device resolves on the other.
 */
object LocalMusic {
    private const val TAG = "LocalMusic"
    const val SOURCE = "local"
    const val SUBDIR = "Music"

    private val AUDIO = setOf(
        "mp3", "flac", "m4a", "aac", "ogg", "oga", "opus",
        "wav", "wma", "aiff", "aif", "alac", "mka",
    )

    /** Where the files land on this device. */
    fun root(): File = File(Prefs.syncFolder, SUBDIR)

    fun absolute(track: Track): File? {
        val relative = track.url ?: return null
        if (relative.startsWith("/")) return File(relative).takeIf { it.exists() }
        return File(root(), relative).takeIf { it.exists() }
    }

    private fun walk(base: File): List<File> {
        val skip = setOf(".stversions", ".stfolder", ".dropbox.cache")
        return base.walkTopDown()
            .onEnter { !it.name.startsWith(".") && it.name !in skip }
            .filter { it.isFile && !it.name.startsWith(".") }
            .filter { it.extension.lowercase() in AUDIO }
            .toList()
    }

    /**
     * Read one file's tags, falling back to its path when it has none -
     * the near-universal layout is <artist>/<album>/<nn> <title>.
     */
    private fun read(file: File, base: File): Track? {
        val relative = file.relativeToOrNull(base)?.path ?: return null
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var duration = 0
        var number = 0

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
            album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
            duration = (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L).let { (it / 1000).toInt() }
            number = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
                ?.substringBefore('/')?.trim()?.toIntOrNull() ?: 0
        } catch (e: Exception) {
            Log.d(TAG, "no tags for ${file.name}: ${e.message}")
        } finally {
            runCatching { retriever.release() }
        }

        // Counting from the end keeps this right however deep the library sits:
        // the shared layout is <library>/<artist>/<album>/<file>.
        val parts = relative.split(File.separator)
        if (title.isNullOrBlank()) {
            val stem = file.nameWithoutExtension
            val head = stem.substringBefore(' ')
            title = (if (head.trim('-', '.').toIntOrNull() != null && stem.contains(' '))
                stem.substringAfter(' ') else stem).replace('_', ' ').trim()
        }
        if (artist.isNullOrBlank()) {
            artist = if (parts.size >= 3) parts[parts.size - 3].replace('_', ' ') else ""
        }
        if (album.isNullOrBlank()) {
            album = if (parts.size >= 2) parts[parts.size - 2].replace('_', ' ') else ""
        }

        return Track(
            id = "local:$relative",
            title = title.ifBlank { file.name },
            artist = artist,
            duration = duration,
            // Artwork beside the songs is the common case; embedded art is read
            // lazily by the player rather than copied out for every file.
            thumb = folderCover(file)?.let { "file://$it" },
            source = SOURCE,
            url = relative,
            album = album,
            trackNumber = number,
        )
    }

    private fun folderCover(file: File): String? {
        val parent = file.parentFile ?: return null
        for (name in listOf("cover", "folder", "front", "album", "albumart")) {
            for (suffix in listOf("jpg", "jpeg", "png", "webp")) {
                val candidate = File(parent, "$name.$suffix")
                if (candidate.exists()) return candidate.absolutePath
            }
        }
        return null
    }

    /** Index everything under the music folder. Safe to call repeatedly. */
    fun scan(): List<Track> {
        val base = root()
        if (!base.isDirectory) return emptyList()
        val found = walk(base).mapNotNull { read(it, base) }
        Log.i(TAG, "indexed ${found.size} files under $base")
        return found
    }

    /** Rescan and write the result into the store. Returns how many there are. */
    fun refresh(): Int {
        val tracks = scan()
        Store.replaceLocalTracks(tracks)
        return tracks.size
    }
}
