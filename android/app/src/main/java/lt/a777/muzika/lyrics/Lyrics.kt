package lt.a777.muzika.lyrics

import android.util.Base64
import android.util.Log
import lt.a777.muzika.data.Track
import lt.a777.muzika.sources.NpeDownloader
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

data class LyricLine(val atMs: Int, val text: String)

data class LyricsResult(
    val lines: List<LyricLine>?,   // null when the source has no timings
    val text: String,
    val provider: String,
) {
    val synced: Boolean get() = !lines.isNullOrEmpty()
}

/**
 * Lyrics from several key-less sources.
 *
 * Synced lyrics from *any* provider beat plain text from a nearer one - a wall
 * of untimed text cannot follow the song, so plain is the last resort.
 */
object Lyrics {
    private const val TAG = "Lyrics"
    private val CREDIT = Regex(
        """^\s*(?:作词|作曲|编曲|制作|录音|混音|by|lyrics?\s*by|composed?\s*by|arranged?\s*by)\s*[:：]""",
        RegexOption.IGNORE_CASE
    )

    fun fetch(track: Track): LyricsResult? {
        val plain = mutableListOf<LyricsResult>()
        for (provider in listOf(::lrclib, ::netease, ::kugou)) {
            val result = try {
                provider(track)
            } catch (e: Exception) {
                Log.d(TAG, "provider failed: ${e.message}"); null
            }
            if (result != null && result.synced) return result
            if (result != null) plain.add(result)
        }
        return plain.firstOrNull()
    }

    // ------------------------------------------------------------- providers

    private fun lrclib(track: Track): LyricsResult? {
        for ((title, artist) in variants(track)) {
            val params = buildString {
                append("track_name=").append(enc(title))
                if (artist.isNotEmpty()) append("&artist_name=").append(enc(artist))
                if (track.duration > 0) append("&duration=").append(track.duration)
            }
            get("https://lrclib.net/api/get?$params")?.let { body ->
                format(JSONObject(body), "LRCLIB")?.let { return it }
            }
            val search = get(
                "https://lrclib.net/api/search?track_name=${enc(title)}" +
                    if (artist.isNotEmpty()) "&artist_name=${enc(artist)}" else ""
            ) ?: continue
            val array = JSONArray(search)
            for (i in 0 until minOf(array.length(), 6)) {
                val entry = array.getJSONObject(i)
                if (track.duration > 0 &&
                    Math.abs(entry.optInt("duration") - track.duration) > 20
                ) continue
                format(entry, "LRCLIB")?.let { return it }
            }
        }
        return null
    }

    private fun format(entry: JSONObject, provider: String): LyricsResult? {
        if (entry.optBoolean("instrumental")) return null
        val synced = entry.optString("syncedLyrics")
        val plain = entry.optString("plainLyrics")
        val lines = parseLrc(synced)
        if (lines.isNotEmpty()) {
            return LyricsResult(lines, lines.joinToString("\n") { it.text }, provider)
        }
        if (plain.isNotBlank()) return LyricsResult(null, plain, provider)
        return null
    }

    private fun netease(track: Track): LyricsResult? {
        for ((title, artist) in variants(track)) {
            val query = enc("$title $artist".trim())
            val search = get("https://music.163.com/api/search/get?s=$query&type=1&limit=5",
                referer = "https://music.163.com/") ?: continue
            val songs = JSONObject(search).optJSONObject("result")?.optJSONArray("songs")
                ?: continue
            for (i in 0 until songs.length()) {
                val song = songs.getJSONObject(i)
                if (track.duration > 0 &&
                    Math.abs(song.optInt("duration") / 1000 - track.duration) > 20
                ) continue
                val body = get(
                    "https://music.163.com/api/song/lyric?id=${song.optLong("id")}&lv=1&kv=1&tv=-1",
                    referer = "https://music.163.com/"
                ) ?: continue
                val lrc = JSONObject(body).optJSONObject("lrc")?.optString("lyric") ?: ""
                fromLrc(lrc, "NetEase")?.let { return it }
            }
        }
        return null
    }

    private fun kugou(track: Track): LyricsResult? {
        for ((title, artist) in variants(track)) {
            val query = enc("$title $artist".trim())
            val search = get(
                "http://mobilecdn.kugou.com/api/v3/search/song?format=json&keyword=$query&page=1&pagesize=5"
            ) ?: continue
            val songs = JSONObject(search).optJSONObject("data")?.optJSONArray("info") ?: continue
            for (i in 0 until songs.length()) {
                val song = songs.getJSONObject(i)
                if (track.duration > 0 &&
                    Math.abs(song.optInt("duration") - track.duration) > 20
                ) continue
                val candidates = get(
                    "http://krcs.kugou.com/search?ver=1&man=yes&client=mobi&hash=${song.optString("hash")}"
                )?.let { JSONObject(it).optJSONArray("candidates") } ?: continue
                for (j in 0 until minOf(candidates.length(), 3)) {
                    val candidate = candidates.getJSONObject(j)
                    val payload = get(
                        "http://lyrics.kugou.com/download?ver=1&client=pc" +
                            "&id=${candidate.optString("id")}" +
                            "&accesskey=${candidate.optString("accesskey")}&fmt=lrc&charset=utf8"
                    )?.let { JSONObject(it).optString("content") } ?: continue
                    if (payload.isEmpty()) continue
                    val lrc = String(Base64.decode(payload, Base64.DEFAULT))
                    fromLrc(lrc, "KuGou")?.let { return it }
                }
            }
        }
        return null
    }

    // --------------------------------------------------------------- helpers

    private fun fromLrc(lrc: String, provider: String): LyricsResult? {
        val lines = parseLrc(lrc)
        if (lines.isEmpty()) return null
        return LyricsResult(lines, lines.joinToString("\n") { it.text }, provider)
    }

    private fun parseLrc(text: String): List<LyricLine> {
        if (text.isBlank()) return emptyList()
        val stamp = Regex("""\[(\d+):(\d+)(?:[.:](\d+))?]""")
        val out = mutableListOf<LyricLine>()
        for (raw in text.lines()) {
            val stamps = stamp.findAll(raw).toList()
            if (stamps.isEmpty()) continue
            val body = raw.replace(Regex("""\[[^]]*]"""), "").trim()
            if (body.isEmpty() || CREDIT.containsMatchIn(body)) continue
            for (match in stamps) {
                val (m, s, frac) = match.destructured
                val hundredths = (frac.ifEmpty { "0" }).padEnd(2, '0').take(2).toInt()
                out.add(LyricLine(m.toInt() * 60000 + s.toInt() * 1000 + hundredths * 10, body))
            }
        }
        return out.sortedBy { it.atMs }
    }

    /** YouTube titles carry noise no lyrics database will match. */
    private fun variants(track: Track): List<Pair<String, String>> {
        val title = track.title
        val artist = track.artist.split(",").firstOrNull()?.trim() ?: ""
        val bare = title.replace(Regex("""[(\[][^)\]]*[)\]]"""), "").trim(' ', '-', '–', '—')
        val out = linkedSetOf<Pair<String, String>>()
        fun add(t: String, a: String) { if (t.isNotBlank()) out.add(t.trim() to a.trim()) }
        add(title, artist)
        add(bare, artist)
        for (dash in listOf(" - ", " – ")) {
            if (bare.contains(dash)) {
                val (left, right) = bare.split(dash, limit = 2)
                add(right, artist.ifEmpty { left })
                add(right, left)
            }
        }
        add(bare.replace(Regex("""(?i)\s*\bfeat\.?\b.*$"""), ""), artist)
        add(bare, "")
        return out.toList()
    }

    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")

    private fun get(url: String, referer: String? = null): String? = try {
        val builder = okhttp3.Request.Builder().url(url)
            .addHeader("User-Agent", NpeDownloader.USER_AGENT)
        if (referer != null) builder.addHeader("Referer", referer)
        NpeDownloader.client.newCall(builder.build()).execute().use { response ->
            if (response.isSuccessful) response.body?.string() else null
        }
    } catch (e: Exception) {
        null
    }
}
