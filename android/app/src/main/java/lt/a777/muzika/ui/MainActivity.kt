package lt.a777.muzika.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Surface
import lt.a777.muzika.player.MuzikaPlayer
import lt.a777.muzika.player.PlaybackService

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        MuzikaPlayer.attach(applicationContext)
        // Start the media service from the foreground, once. Starting it from
        // MuzikaPlayer on every track meant a background start whenever a track
        // auto-advanced with the app hidden, which Android 12+ kills the app for.
        runCatching { startService(Intent(this, PlaybackService::class.java)) }

        val wanted = when {
            Build.VERSION.SDK_INT >= 33 -> listOf(Manifest.permission.POST_NOTIFICATIONS)
            Build.VERSION.SDK_INT < 30 -> listOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            )
            else -> emptyList()
        }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (wanted.isNotEmpty()) {
            registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
                .launch(wanted.toTypedArray())
        }
        ensureStorageAccess()
        Launch.request(intent?.getStringExtra("destination"))

        setContent {
            MuzikaTheme { Surface { MuzikaRoot() } }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        Launch.request(intent.getStringExtra("destination"))
    }

    /**
     * The sync folder lives in shared storage, which needs all-files access
     * from Android 11. Before that the storage permissions asked for in
     * onCreate are what it takes - and they were never asked for, so on
     * Android 8 to 10 the library and handoff files could not be read or
     * written at all ("Permission denied" on every save).
     */
    private fun ensureStorageAccess() {
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            runCatching {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            }
        }
    }
}
