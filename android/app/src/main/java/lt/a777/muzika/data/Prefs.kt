package lt.a777.muzika.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Settings, kept as Compose state so the UI reacts the moment one changes.
 */
object Prefs {
    const val THEME_SYSTEM = 0
    const val THEME_LIGHT = 1
    const val THEME_DARK = 2

    const val BACKEND_FOLDER = "folder"
    const val BACKEND_DROPBOX = "dropbox"

    private lateinit var prefs: SharedPreferences

    var theme by mutableStateOf(THEME_SYSTEM)
        private set
    var dynamicColour by mutableStateOf(true)
        private set
    var syncBackend by mutableStateOf(BACKEND_FOLDER)
        private set
    var syncFolder by mutableStateOf("/storage/emulated/0/Muzika")
        private set
    var dropboxToken by mutableStateOf("")
        private set
    var autoSync by mutableStateOf(true)
        private set
    var sourceSoundCloud by mutableStateOf(true)
        private set
    var sourceBandcamp by mutableStateOf(true)
        private set

    fun open(context: Context) {
        prefs = context.applicationContext.getSharedPreferences("muzika", Context.MODE_PRIVATE)
        theme = prefs.getInt("theme", THEME_SYSTEM)
        dynamicColour = prefs.getBoolean("dynamic", true)
        syncBackend = prefs.getString("backend", BACKEND_FOLDER) ?: BACKEND_FOLDER
        syncFolder = prefs.getString("folder", syncFolder) ?: syncFolder
        dropboxToken = prefs.getString("dropbox_token", "") ?: ""
        autoSync = prefs.getBoolean("auto_sync", true)
        sourceSoundCloud = prefs.getBoolean("src_soundcloud", true)
        sourceBandcamp = prefs.getBoolean("src_bandcamp", true)
    }

    fun updateTheme(value: Int) { theme = value; prefs.edit().putInt("theme", value).apply() }

    fun updateDynamicColour(value: Boolean) {
        dynamicColour = value; prefs.edit().putBoolean("dynamic", value).apply()
    }

    fun updateSyncBackend(value: String) {
        syncBackend = value; prefs.edit().putString("backend", value).apply()
    }

    fun updateSyncFolder(value: String) {
        val clean = value.trim().trimEnd('/').ifEmpty { "/storage/emulated/0/Muzika" }
        syncFolder = clean; prefs.edit().putString("folder", clean).apply()
    }

    fun updateDropboxToken(value: String) {
        dropboxToken = value.trim(); prefs.edit().putString("dropbox_token", dropboxToken).apply()
    }

    fun updateAutoSync(value: Boolean) {
        autoSync = value; prefs.edit().putBoolean("auto_sync", value).apply()
    }

    fun updateSourceSoundCloud(value: Boolean) {
        sourceSoundCloud = value; prefs.edit().putBoolean("src_soundcloud", value).apply()
    }

    fun updateSourceBandcamp(value: Boolean) {
        sourceBandcamp = value; prefs.edit().putBoolean("src_bandcamp", value).apply()
    }
}
