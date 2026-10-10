package lt.a777.muzika.data

import android.util.Log
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import java.io.File
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Ask the Syncthing app on this phone to look at the sync folder now.
 *
 * On Android, Syncthing does not notice when another app changes a file: its
 * watcher is unreliable on shared storage, so a pause written here could sit
 * unsent until the next full rescan - an hour, by default. Its local API takes
 * a "scan this file now" request, and with that the pause reaches the desktop
 * in seconds.
 *
 * The desktop finds the API key in Syncthing's config.xml. Here that file is
 * in the Syncthing app's private storage, so the user pastes the key once in
 * Settings. Without a key nothing is sent and nothing changes.
 *
 * Everything is best-effort and silent: Syncthing stopped by its run
 * conditions, a different sync app, or a folder Syncthing does not share are
 * all normal and cost one failed request at most.
 */
object Syncthing {
    private const val TAG = "Syncthing"

    /** Where the Syncthing apps put their API. Tests point it elsewhere. */
    internal var addresses = listOf("https://127.0.0.1:8384", "http://127.0.0.1:8384")

    /**
     * The GUI serves a certificate it made for itself. Only ever used for the
     * loopback address above, where there is no one in between to trust.
     */
    private val trustSelfSigned = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private val client: OkHttpClient by lazy {
        val tls = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustSelfSigned), SecureRandom())
        }
        OkHttpClient.Builder()
            .sslSocketFactory(tls.socketFactory, trustSelfSigned)
            .hostnameVerifier { host, _ -> host == "127.0.0.1" || host == "localhost" }
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    private val worker = Executors.newSingleThreadExecutor { Thread(it, "syncthing").apply { isDaemon = true } }

    /** The address that answered and the folder id found there, for this key and folder. */
    private data class Found(
        val key: String, val folder: String, val base: String, val id: String, val root: String,
    )
    @Volatile private var found: Found? = null

    /** Ask for [file] (or the whole folder) to be scanned, off the calling thread. */
    fun scanAsync(file: File? = null) {
        if (Prefs.syncthingKey.isEmpty() || Prefs.syncBackend != Prefs.BACKEND_FOLDER) return
        worker.execute { scan(file) }
    }

    /** Same, on this thread. True when Syncthing took the request. */
    fun scan(file: File? = null): Boolean = locate()?.let { scan(file, it) } ?: false

    private fun scan(file: File?, target: Found): Boolean {
        val url = "${target.base}/rest/db/scan".toHttpUrl().newBuilder()
            .addQueryParameter("folder", target.id)
            .apply {
                val sub = file?.let { relative(it, target.root) }
                if (!sub.isNullOrEmpty()) addQueryParameter("sub", sub)
            }
            .build()
        val ok = runCatching {
            client.newCall(
                Request.Builder().url(url).header("X-API-Key", target.key)
                    .post(ByteArray(0).toRequestBody()).build()
            ).execute().use { it.isSuccessful }
        }.getOrElse { Log.d(TAG, "scan failed: ${it.message}"); false }
        // Restarted on another port, or the folder was re-added: look again next time.
        if (!ok) found = null
        return ok
    }

    /**
     * For the Settings check: what Syncthing said, in words. Runs on the
     * calling thread.
     */
    fun describe(): String {
        if (Prefs.syncthingKey.isEmpty()) return "No API key set"
        // Asked afresh: a cached answer would hide that Syncthing has since stopped.
        val target = when (val result = lookup(Prefs.syncthingKey, Prefs.syncFolder)) {
            is Found -> result
            else -> return result as String
        }
        return if (scan(null, target)) "Connected · Syncthing shares this folder as “${target.id}”"
        else "Syncthing found the folder but refused the scan"
    }

    /** The cached answer for the current key and folder, or a fresh one. */
    private fun locate(): Found? {
        val key = Prefs.syncthingKey
        val folder = Prefs.syncFolder
        if (key.isEmpty()) return null
        found?.takeIf { it.key == key && it.folder == folder }?.let { return it }
        return lookup(key, folder) as? Found
    }

    /** Where Syncthing answers and which of its folders holds [folder]; or, as a String, why not. */
    private fun lookup(key: String, folder: String): Any {
        for (base in addresses) {
            val folders = try {
                client.newCall(
                    Request.Builder().url("$base/rest/config/folders").header("X-API-Key", key).build()
                ).execute().use { response ->
                    when (response.code) {
                        200 -> JSONArray(response.body?.string() ?: "[]")
                        401, 403 -> return "Syncthing refused the API key"
                        else -> null
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "$base: ${e.message}")
                null
            } ?: continue
            val match = matchFolder(folders, folder)
                ?: return "Syncthing does not share $folder"
            return Found(key, folder, base, match.first, match.second).also { found = it }
        }
        return "Syncthing is not running, or its API is not on 127.0.0.1:8384"
    }

    /** The id and path of the Syncthing folder that holds [folder], if it shares one. */
    internal fun matchFolder(folders: JSONArray, folder: String): Pair<String, String>? {
        val wanted = normalise(folder)
        for (i in 0 until folders.length()) {
            val entry = folders.optJSONObject(i) ?: continue
            val path = normalise(entry.optString("path"))
            if (path.isNotEmpty() && (wanted == path || wanted.startsWith("$path/"))) {
                return entry.optString("id").ifEmpty { null }?.let { it to path }
            }
        }
        return null
    }

    /** /sdcard and /storage/emulated/0 are the same place, written two ways. */
    private fun normalise(path: String): String = path.trim().trimEnd('/')
        .replace(Regex("^/sdcard(?=/|$)"), "/storage/emulated/0")
        .replace(Regex("^/storage/self/primary(?=/|$)"), "/storage/emulated/0")

    /** [file]'s path inside the Syncthing folder at [root], which is what "sub" wants. */
    private fun relative(file: File, root: String): String? {
        val base = normalise(root)
        val path = normalise(file.path)
        return if (path.startsWith("$base/")) path.removePrefix("$base/") else null
    }

    internal fun resetForTest() { found = null }
}
