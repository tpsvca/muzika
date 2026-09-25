package lt.a777.muzika.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import lt.a777.muzika.data.Store
import lt.a777.muzika.data.Sync
import lt.a777.muzika.data.Track
import lt.a777.muzika.player.MuzikaPlayer

@Composable
fun NameDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it },
                singleLine = true, label = { Text("Name") }
            )
        },
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank(),
                onClick = { onDismiss(); onConfirm(text.trim()) }
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackMenu(
    track: Track,
    onDismiss: () -> Unit,
    toast: (String) -> Unit,
    onChanged: () -> Unit,
) {
    var choosingPlaylist by remember { mutableStateOf(false) }
    var favourite by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(track.id) {
        favourite = withContext(Dispatchers.IO) { Store.isFavourite(track.id) }
    }

    if (choosingPlaylist) {
        PlaylistPicker(
            tracks = listOf(track),
            onDismiss = { choosingPlaylist = false; onDismiss() },
            toast = toast,
            onChanged = onChanged,
        )
        return
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            ListItem(
                headlineContent = { Text(track.title) },
                supportingContent = { Text(track.artist) },
                leadingContent = { Cover(track.thumb, 48.dp) }
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("Play next") },
                leadingContent = { Icon(Icons.Filled.QueuePlayNext, null) },
                modifier = Modifier.clickable {
                    MuzikaPlayer.playNext(track); toast("Playing next"); onDismiss()
                }
            )
            ListItem(
                headlineContent = { Text("Add to queue") },
                leadingContent = { Icon(Icons.Filled.PlaylistAdd, null) },
                modifier = Modifier.clickable {
                    MuzikaPlayer.append(listOf(track)); toast("Added to queue"); onDismiss()
                }
            )
            ListItem(
                headlineContent = { Text("Add to playlist…") },
                leadingContent = { Icon(Icons.Filled.LibraryAdd, null) },
                modifier = Modifier.clickable { choosingPlaylist = true }
            )
            ListItem(
                headlineContent = {
                    Text(if (favourite) "Remove from favourites" else "Add to favourites")
                },
                leadingContent = {
                    Icon(if (favourite) Icons.Filled.Star else Icons.Filled.StarBorder, null)
                },
                modifier = Modifier.clickable {
                    scope.launch {
                        val state = withContext(Dispatchers.IO) {
                            Store.toggleFavourite(track).also { Sync.push() }
                        }
                        toast(if (state) "Added to favourites" else "Removed from favourites")
                        onChanged(); onDismiss()
                    }
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistPicker(
    tracks: List<Track>,
    onDismiss: () -> Unit,
    toast: (String) -> Unit,
    onChanged: () -> Unit,
) {
    var playlists by remember { mutableStateOf(emptyList<Store.Playlist>()) }
    var creating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        playlists = withContext(Dispatchers.IO) { Store.playlists() }
    }

    fun addTo(id: Long, name: String) {
        scope.launch {
            val added = withContext(Dispatchers.IO) {
                Store.addManyToPlaylist(id, tracks).also { Sync.push() }
            }
            val skipped = tracks.size - added
            toast(
                when {
                    added == 0 -> "Already in “$name”"
                    skipped > 0 -> "Added $added to “$name” · $skipped already there"
                    added == 1 -> "Added to “$name”"
                    else -> "Added $added to “$name”"
                }
            )
            onChanged(); onDismiss()
        }
    }

    if (creating) {
        NameDialog("New playlist", "", "Create", onDismiss = { creating = false }) { name ->
            scope.launch {
                val id = withContext(Dispatchers.IO) { Store.createPlaylist(name) }
                addTo(id, name)
            }
        }
        return
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            ListItem(
                headlineContent = { Text("New playlist…") },
                leadingContent = { Icon(Icons.Filled.Add, null) },
                modifier = Modifier.clickable { creating = true }
            )
            HorizontalDivider()
            LazyColumn(Modifier.heightIn(max = 380.dp)) {
                items(playlists, key = { it.id }) { playlist ->
                    ListItem(
                        headlineContent = { Text(playlist.name) },
                        supportingContent = { Text(songCount(playlist.count)) },
                        leadingContent = { Cover(playlist.thumb, 40.dp) },
                        modifier = Modifier.clickable { addTo(playlist.id, playlist.name) }
                    )
                }
            }
        }
    }
}
