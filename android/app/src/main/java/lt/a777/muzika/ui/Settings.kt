package lt.a777.muzika.ui

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import lt.a777.muzika.data.Dropbox
import lt.a777.muzika.data.LocalMusic
import lt.a777.muzika.data.Prefs
import lt.a777.muzika.data.Store
import lt.a777.muzika.data.Sync
import lt.a777.muzika.player.BatteryOptimisation
import lt.a777.muzika.sources.Discover

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(nav: Nav) {
    val scope = rememberCoroutineScope()
    val uris = LocalUriHandler.current
    val context = LocalContext.current

    // Re-read on resume: the user grants this in the system settings and comes
    // back, and a stale warning here would be worse than none.
    var unrestricted by remember { mutableStateOf(BatteryOptimisation.isExempt(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        unrestricted = BatteryOptimisation.isExempt(context)
    }
    val version = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()
    }
    var folderDialog by remember { mutableStateOf(false) }
    var tokenDialog by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var clearDialog by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    var localCount by remember { mutableIntStateOf(-1) }

    LaunchedEffect(Unit) {
        localCount = withContext(Dispatchers.IO) { Store.localCount() }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Settings") },
            navigationIcon = {
                IconButton(onClick = nav.back) { Icon(Icons.Rounded.ArrowBack, "Back") }
            }
        )
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {

            Group("Appearance")
            ListItem(
                headlineContent = { Text("Theme") },
                supportingContent = {
                    SingleChoiceSegmentedButtonRow(Modifier.padding(top = 8.dp)) {
                        listOf(
                            Prefs.THEME_SYSTEM to "System",
                            Prefs.THEME_LIGHT to "Light",
                            Prefs.THEME_DARK to "Dark",
                        ).forEachIndexed { index, (value, label) ->
                            SegmentedButton(
                                selected = Prefs.theme == value,
                                onClick = { Prefs.updateTheme(value) },
                                shape = SegmentedButtonDefaults.itemShape(index, 3),
                            ) { Text(label) }
                        }
                    }
                },
                leadingContent = { Icon(Icons.Rounded.DarkMode, null) },
            )
            if (Build.VERSION.SDK_INT >= 31) {
                Toggle(
                    title = "Use wallpaper colours",
                    subtitle = "Take the palette from the system theme",
                    icon = Icons.Rounded.Palette,
                    checked = Prefs.dynamicColour,
                ) { Prefs.updateDynamicColour(it) }
            }

            Group("Sync")
            ListItem(
                headlineContent = { Text("Where the library lives") },
                supportingContent = {
                    SingleChoiceSegmentedButtonRow(Modifier.padding(top = 8.dp)) {
                        listOf(
                            Prefs.BACKEND_FOLDER to "Folder",
                            Prefs.BACKEND_DROPBOX to "Dropbox",
                        ).forEachIndexed { index, (value, label) ->
                            SegmentedButton(
                                selected = Prefs.syncBackend == value,
                                onClick = { Prefs.updateSyncBackend(value) },
                                shape = SegmentedButtonDefaults.itemShape(index, 2),
                            ) { Text(label) }
                        }
                    }
                },
                leadingContent = { Icon(Icons.Rounded.CloudSync, null) },
            )
            if (Prefs.syncBackend == Prefs.BACKEND_FOLDER) {
                ListItem(
                    headlineContent = { Text("Sync folder") },
                    supportingContent = {
                        Text("${Prefs.syncFolder}/muzika-library.json\n" +
                            "Anything that syncs a folder works here: Syncthing, " +
                            "Nextcloud, OpenCloud.")
                    },
                    leadingContent = { Icon(Icons.Rounded.Folder, null) },
                    modifier = Modifier.clickable { folderDialog = true },
                )
            } else {
                ListItem(
                    headlineContent = { Text("Dropbox access token") },
                    supportingContent = {
                        Text(
                            if (Prefs.dropboxToken.isEmpty())
                                "Not set. Dropbox has no anonymous mode, so it needs a " +
                                    "token you generate yourself."
                            else "Set · tap to replace"
                        )
                    },
                    leadingContent = { Icon(Icons.Rounded.Key, null) },
                    modifier = Modifier.clickable { tokenDialog = true },
                )
                ListItem(
                    headlineContent = { Text("Test the connection") },
                    supportingContent = { Text("Check the token before relying on it") },
                    leadingContent = { Icon(Icons.Rounded.NetworkCheck, null) },
                    modifier = Modifier.clickable {
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                runCatching { Dropbox.accountName() }
                            }
                            nav.toast(result.fold(
                                { "Connected as $it" },
                                { it.message ?: "Dropbox refused the token" }))
                        }
                    },
                )
                ListItem(
                    headlineContent = { Text("How to get a token") },
                    supportingContent = {
                        Text("Dropbox App Console → Create app → Scoped access → " +
                            "App folder → Permissions: files.content.read and " +
                            "files.content.write → Generate access token")
                    },
                    leadingContent = { Icon(Icons.Rounded.OpenInNew, null) },
                    modifier = Modifier.clickable {
                        runCatching { uris.openUri("https://www.dropbox.com/developers/apps") }
                    },
                )
            }
            Toggle(
                title = "Sync when the app opens",
                subtitle = "Pull changes from the other devices on launch",
                icon = Icons.Rounded.Autorenew,
                checked = Prefs.autoSync,
            ) { Prefs.updateAutoSync(it) }
            ListItem(
                headlineContent = { Text("Sync now") },
                leadingContent = {
                    if (busy) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Rounded.Sync, null)
                },
                modifier = Modifier.clickable(enabled = !busy) {
                    busy = true
                    scope.launch {
                        val report = withContext(Dispatchers.IO) { Sync.syncNow() }
                        busy = false; nav.changed(); nav.toast(report.describe())
                    }
                },
            )

            Group("Music on this device")
            ListItem(
                headlineContent = { Text("Music folder") },
                supportingContent = {
                    Text("${LocalMusic.root()}\n" +
                        "The desktop app copies your audio files here " +
                        "(Settings \u2192 Music) and your sync service carries them.")
                },
                leadingContent = { Icon(Icons.Rounded.Folder, null) },
            )
            ListItem(
                headlineContent = { Text("Rescan music files") },
                supportingContent = {
                    Text(
                        if (localCount < 0) "Counting\u2026"
                        else "$localCount file${if (localCount == 1) "" else "s"} indexed"
                    )
                },
                leadingContent = {
                    if (scanning) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Rounded.Refresh, null)
                },
                modifier = Modifier.clickable(enabled = !scanning) {
                    scanning = true
                    scope.launch {
                        val found = withContext(Dispatchers.IO) { LocalMusic.refresh() }
                        localCount = found
                        scanning = false
                        nav.changed()
                        nav.toast("Indexed $found file${if (found == 1) "" else "s"}")
                    }
                },
            )

            Group("Playback")
            ListItem(
                headlineContent = { Text("Background playback") },
                supportingContent = {
                    Text(
                        if (unrestricted)
                            "Android is allowed to keep Muzika playing when you " +
                                "leave the app."
                        else
                            "Android may stop playback shortly after you leave the " +
                                "app, and it will not come back on its own. Tap to " +
                                "let Muzika keep running."
                    )
                },
                leadingContent = {
                    Icon(
                        if (unrestricted) Icons.Rounded.CheckCircle
                        else Icons.Rounded.BatteryAlert,
                        null,
                        tint = if (unrestricted) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error,
                    )
                },
                modifier = Modifier.clickable(enabled = !unrestricted) {
                    if (!BatteryOptimisation.request(context)) {
                        nav.toast("Could not open battery settings on this device")
                    }
                },
            )

            Group("Sources")
            Text(
                "YouTube Music is always on. These add their own results to a search.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            Toggle("SoundCloud", "Independent uploads and remixes",
                Icons.Rounded.Cloud, Prefs.sourceSoundCloud) { Prefs.updateSourceSoundCloud(it) }
            Toggle("Bandcamp", "Artist-published tracks", Icons.Rounded.Album,
                Prefs.sourceBandcamp) { Prefs.updateSourceBandcamp(it) }

            Group("Library")
            ListItem(
                headlineContent = { Text("Refresh suggestions") },
                supportingContent = { Text("Rebuild the home page from recent listening") },
                leadingContent = { Icon(Icons.Rounded.Refresh, null) },
                modifier = Modifier.clickable {
                    Discover.invalidate(); nav.changed(); nav.toast("Suggestions will rebuild")
                },
            )
            ListItem(
                headlineContent = { Text("Clear listening history") },
                supportingContent = { Text("Playlists and liked songs are kept") },
                leadingContent = {
                    Icon(Icons.Rounded.DeleteSweep, null, tint = MaterialTheme.colorScheme.error)
                },
                modifier = Modifier.clickable { clearDialog = true },
            )

            Group("About")
            ListItem(
                headlineContent = { Text("Muzika" + (version?.let { " $it" } ?: "")) },
                supportingContent = {
                    Text("Plays from YouTube Music, SoundCloud and Bandcamp without an " +
                        "account. Suggestions are worked out on this device from what you " +
                        "play - nothing about you is sent anywhere.")
                },
                leadingContent = { Icon(Icons.Rounded.Info, null) },
            )
            Spacer(Modifier.height(32.dp))
        }
    }

    if (folderDialog) {
        NameDialog("Sync folder", Prefs.syncFolder, "Save", { folderDialog = false }) { path ->
            Prefs.updateSyncFolder(path); nav.toast("Sync folder set")
        }
    }
    if (tokenDialog) {
        NameDialog("Dropbox access token", Prefs.dropboxToken, "Save", { tokenDialog = false }) {
            Prefs.updateDropboxToken(it)
            nav.toast(if (it.isBlank()) "Token cleared" else "Token saved")
        }
    }
    if (clearDialog) {
        AlertDialog(
            onDismissRequest = { clearDialog = false },
            icon = { Icon(Icons.Rounded.DeleteSweep, null) },
            title = { Text("Clear history?") },
            text = { Text("Your suggestions are built from this, so the home page will " +
                "start over. Playlists and liked songs are not touched.") },
            confirmButton = {
                TextButton(onClick = {
                    clearDialog = false
                    scope.launch {
                        withContext(Dispatchers.IO) { Store.clearHistory() }
                        Discover.invalidate(); nav.changed(); nav.toast("History cleared")
                    }
                }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { clearDialog = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun Group(title: String) {
    Text(
        title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, top = 22.dp, bottom = 4.dp),
    )
}

@Composable
private fun Toggle(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = { Icon(icon, null) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
        modifier = Modifier.clickable { onChange(!checked) },
    )
}
