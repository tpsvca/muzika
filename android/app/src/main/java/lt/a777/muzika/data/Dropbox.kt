package lt.a777.muzika.data

import android.util.Log
import lt.a777.muzika.sources.NpeDownloader
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * The library file in Dropbox, over its HTTP API.
 *
 * Dropbox has no anonymous mode: it needs a token that belongs to the account.
 * The user generates one in their own app console and pastes it into settings,
 * so nothing here ever sees a password and no app secret is baked in.
 */
object Dropbox {
    private const val TAG = "Dropbox"
    private const val PATH = "/Muzika/muzika-library.json"

    class Failure(message: String) : Exception(message)

    private fun token(): String = Prefs.dropboxToken.ifEmpty {
        throw Failure("No Dropbox token - add one in Settings")
    }

    /** Null when the file is simply not there yet, which is not an error. */
    fun download(): String? {
        val request = Request.Builder()
            .url("https://content.dropboxapi.com/2/files/download")
            .addHeader("Authorization", "Bearer ${token()}")
            .addHeader("Dropbox-API-Arg", JSONObject().put("path", PATH).toString())
            .post(ByteArray(0).toRequestBody(null))
            .build()
        NpeDownloader.client.newCall(request).execute().use { response ->
            if (response.code == 409) return null          // path/not_found
            if (response.code == 401) throw Failure("Dropbox rejected the token")
            if (!response.isSuccessful) {
                throw Failure("Dropbox download failed (${response.code})")
            }
            return response.body?.string()
        }
    }

    fun upload(content: String) {
        val arg = JSONObject()
            .put("path", PATH)
            .put("mode", "overwrite")
            .put("mute", true)
        val request = Request.Builder()
            .url("https://content.dropboxapi.com/2/files/upload")
            .addHeader("Authorization", "Bearer ${token()}")
            .addHeader("Dropbox-API-Arg", arg.toString())
            .post(content.toByteArray().toRequestBody("application/octet-stream".toMediaType()))
            .build()
        NpeDownloader.client.newCall(request).execute().use { response ->
            if (response.code == 401) throw Failure("Dropbox rejected the token")
            if (!response.isSuccessful) {
                Log.w(TAG, "upload failed ${response.code}")
                throw Failure("Dropbox upload failed (${response.code})")
            }
        }
    }

    /** Used by the settings screen to tell the user the token actually works. */
    fun accountName(): String {
        val request = Request.Builder()
            .url("https://api.dropboxapi.com/2/users/get_current_account")
            .addHeader("Authorization", "Bearer ${token()}")
            .post("null".toRequestBody("application/json".toMediaType()))
            .build()
        NpeDownloader.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Failure("Dropbox rejected the token")
            val body = JSONObject(response.body?.string() ?: "{}")
            return body.optJSONObject("name")?.optString("display_name")
                ?.ifEmpty { null } ?: body.optString("email").ifEmpty { "Dropbox" }
        }
    }
}
