package lt.a777.muzika.sources

import android.util.Log
import lt.a777.muzika.data.Prefs
import lt.a777.muzika.data.Track
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem

/**
 * Every source behind one interface: search returns [Track]s, and [resolve]
 * turns one into a playable URL. No accounts, no API keys anywhere.
 */
object Sources {
    private const val TAG = "Sources"

    const val YOUTUBE = "youtube"
    const val SOUNDCLOUD = "soundcloud"
    const val BANDCAMP = "bandcamp"
    const val LOCAL = "local"

    val LABELS = mapOf(
        YOUTUBE to "YouTube", SOUNDCLOUD to "SoundCloud", BANDCAMP to "Bandcamp",
        LOCAL to "On this device",
    )

    // ------------------------------------------------------------- searching

    fun searchYouTube(query: String, limit: Int = 20): List<Track> = try {
        val extractor = ServiceList.YouTube.getSearchExtractor(query)
        extractor.fetchPage()
        extractor.initialPage.items
            .filterIsInstance<StreamInfoItem>()
            .take(limit)
            .map { item ->
                Track(
                    id = videoId(item.url) ?: item.url,
                    title = item.name ?: "",
                    artist = item.uploaderName ?: "",
                    duration = item.duration.toInt().coerceAtLeast(0),
                    thumb = item.thumbnails.lastOrNull()?.url,
                    source = YOUTUBE,
                    url = item.url,
                )
            }
    } catch (e: Exception) {
        Log.w(TAG, "youtube search failed", e); emptyList()
    }

    fun searchSoundCloud(query: String, limit: Int = 15): List<Track> = try {
        val extractor = ServiceList.SoundCloud.getSearchExtractor(query)
        extractor.fetchPage()
        extractor.initialPage.items
            .filterIsInstance<StreamInfoItem>()
            .take(limit)
            .map { item ->
                Track(
                    id = "sc:${item.url}",
                    title = item.name ?: "",
                    artist = item.uploaderName ?: "SoundCloud",
                    duration = item.duration.toInt().coerceAtLeast(0),
                    thumb = item.thumbnails.lastOrNull()?.url,
                    source = SOUNDCLOUD,
                    url = item.url,
                )
            }
    } catch (e: Exception) {
        Log.w(TAG, "soundcloud search failed: ${e.message}"); emptyList()
    }

    /** Bandcamp's own autocomplete endpoint - the one its website calls. */
    fun searchBandcamp(query: String, limit: Int = 15): List<Track> = try {
        val payload = JSONObject()
            .put("search_text", query)
            .put("search_filter", "t")
            .put("full_page", false)
            .toString()
        val request = okhttp3.Request.Builder()
            .url("https://bandcamp.com/api/bcsearch_public_api/1/autocomplete_elastic")
            .addHeader("User-Agent", NpeDownloader.USER_AGENT)
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
        NpeDownloader.client.newCall(request).execute().use { response ->
            val root = JSONObject(response.body?.string() ?: "{}")
            val results = root.optJSONObject("auto")?.optJSONArray("results")
            val out = mutableListOf<Track>()
            if (results != null) {
                for (i in 0 until results.length()) {
                    val entry = results.getJSONObject(i)
                    if (entry.optString("type") != "t") continue
                    val url = entry.optString("item_url_path").ifEmpty {
                        entry.optString("item_url_root")
                    }
                    if (url.isEmpty()) continue
                    out.add(
                        Track(
                            id = "bc:$url",
                            title = entry.optString("name"),
                            artist = entry.optString("band_name").ifEmpty { "Bandcamp" },
                            thumb = entry.optString("img").ifEmpty { null },
                            source = BANDCAMP,
                            url = url,
                        )
                    )
                    if (out.size >= limit) break
                }
            }
            out
        }
    } catch (e: Exception) {
        Log.w(TAG, "bandcamp search failed", e); emptyList()
    }

    /**
     * Songs from every enabled source. YouTube goes through YouTube Music's own
     * search, which knows about albums and durations; NewPipe is the fallback
     * when that answers with nothing.
     */
    fun searchAll(query: String): Map<String, List<Track>> {
        val out = linkedMapOf<String, List<Track>>()
        val music = Innertube.search(query, Innertube.F_SONGS)
            .filter { it.playable }.map { it.toTrack() }
        (music.ifEmpty { searchYouTube(query) }).takeIf { it.isNotEmpty() }
            ?.let { out[YOUTUBE] = it }
        if (Prefs.sourceSoundCloud) {
            searchSoundCloud(query).takeIf { it.isNotEmpty() }?.let { out[SOUNDCLOUD] = it }
        }
        if (Prefs.sourceBandcamp) {
            searchBandcamp(query).takeIf { it.isNotEmpty() }?.let { out[BANDCAMP] = it }
        }
        return out
    }

    /** Albums, artists or playlists - YouTube Music is the only source with them. */
    fun searchCatalogue(query: String, filter: String): List<Innertube.Item> =
        Innertube.search(query, filter)

    // ------------------------------------------------------------- resolving

    /** Page URL for a track, rebuilding YouTube's from the id when needed. */
    private fun pageUrl(track: Track): String =
        track.url ?: "https://www.youtube.com/watch?v=${track.id}"

    /**
     * Highest-bitrate audio stream URL. NewPipeExtractor handles YouTube and
     * SoundCloud; Bandcamp pages carry their stream URL in the page JSON.
     */
    fun resolve(track: Track): String? = try {
        // A file on disk needs no extractor at all.
        if (track.source == LOCAL) {
            lt.a777.muzika.data.LocalMusic.absolute(track)?.let { "file://${it.absolutePath}" }
        } else if (track.source == BANDCAMP) resolveBandcamp(pageUrl(track))
        else {
            val info = StreamInfo.getInfo(pageUrl(track))
            info.audioStreams.maxByOrNull { it.averageBitrate }?.content
        }
    } catch (e: Exception) {
        Log.w(TAG, "resolve failed for ${track.title}: ${e.message}")
        null
    }

    private fun resolveBandcamp(pageUrl: String): String? {
        val request = okhttp3.Request.Builder()
            .url(pageUrl)
            .addHeader("User-Agent", NpeDownloader.USER_AGENT)
            .build()
        NpeDownloader.client.newCall(request).execute().use { response ->
            val html = response.body?.string() ?: return null
            // Bandcamp embeds the stream url as "mp3-128":"https://..."
            val match = Regex("\"mp3-128\"\\s*:\\s*\"(https?:[^\"]+)\"").find(html)
                ?: return null
            return match.groupValues[1].replace("\\/", "/")
        }
    }

    private fun videoId(url: String?): String? {
        if (url == null) return null
        return Regex("[?&]v=([A-Za-z0-9_-]{11})").find(url)?.groupValues?.get(1)
            ?: Regex("youtu\\.be/([A-Za-z0-9_-]{11})").find(url)?.groupValues?.get(1)
    }
}
