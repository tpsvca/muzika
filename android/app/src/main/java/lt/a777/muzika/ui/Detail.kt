package lt.a777.muzika.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import lt.a777.muzika.data.Store
import lt.a777.muzika.data.Sync
import lt.a777.muzika.data.Track
import lt.a777.muzika.player.MuzikaPlayer
import lt.a777.muzika.sources.Innertube

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailBar(title: String, nav: Nav, actions: @Composable RowScope.() -> Unit = {}) {
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            IconButton(onClick = nav.back) { Icon(Icons.Rounded.ArrowBack, "Back") }
        },
        actions = actions,
    )
}

/** Any fixed list of tracks with a cover: a mix, an artist, favourites. */
@Composable
fun TrackListScreen(
    title: String,
    subtitle: String,
    thumb: String?,
    tracks: List<Track>,
    sourceId: String,
    nav: Nav,
    circle: Boolean = false,
    loading: Boolean = false,
    /** Set for catalogue pages, which can be kept in the library. */
    saveTarget: Store.Saved? = null,
    header: (@Composable () -> Unit)? = null,
) {
    val playingHere = MuzikaPlayer.source == sourceId
    var picker by remember { mutableStateOf(false) }
    var saved by remember(saveTarget?.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(saveTarget?.id) {
        val target = saveTarget ?: return@LaunchedEffect
        saved = withContext(Dispatchers.IO) { Store.isSaved(target.kind, target.id) }
    }

    Column(Modifier.fillMaxSize()) {
        DetailBar(title, nav) {
            if (saveTarget != null) {
                IconButton(onClick = {
                    val target = saveTarget.copy(title = title, subtitle = subtitle,
                        thumb = thumb)
                    scope.launch {
                        val now = withContext(Dispatchers.IO) {
                            Store.toggleSaved(target).also { Sync.push() }
                        }
                        saved = now
                        nav.changed()
                        nav.toast(if (now) "Saved to your library" else "Removed from your library")
                    }
                }) {
                    Icon(
                        if (saved) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
                        if (saved) "Remove from library" else "Save to library",
                    )
                }
            }
            if (tracks.isNotEmpty()) {
                IconButton(onClick = { picker = true }) {
                    Icon(Icons.Rounded.LibraryAdd, "Add these songs to a playlist")
                }
            }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                DetailHeader(
                    title = title, subtitle = subtitle, thumb = thumb, circle = circle,
                    playing = playingHere && MuzikaPlayer.isPlaying,
                    onPlay = {
                        if (playingHere) MuzikaPlayer.toggle()
                        else MuzikaPlayer.setQueue(tracks, 0, false, sourceId)
                    },
                    onShuffle = { MuzikaPlayer.setQueue(tracks, null, true, sourceId) },
                )
            }
            header?.let { item { it() } }
            if (loading) item { LoadingRow() }
            itemsIndexed(tracks, key = { index, track -> "$index-${track.id}" }) { index, track ->
                SongRow(track, index = index + 1, showArtwork = false,
                    onPlay = { MuzikaPlayer.setQueue(tracks, index, false, sourceId) },
                    onMenu = { nav.openMenu(track) })
            }
            if (!loading && tracks.isEmpty()) {
                item {
                    EmptyState(Icons.Rounded.MusicOff, "No songs here",
                        "This one came back empty.")
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (picker) {
        PlaylistPicker(tracks, onDismiss = { picker = false }, toast = nav.toast,
            onChanged = nav.changed)
    }
}

/** An album from YouTube Music. */
@Composable
fun AlbumScreen(dest: Dest.AlbumPage, nav: Nav) {
    var page by remember { mutableStateOf<Innertube.Page?>(null) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(dest.id) {
        loading = true
        page = withContext(Dispatchers.IO) { Innertube.album(dest.id) }
        loading = false
    }
    TrackListScreen(
        title = page?.title ?: dest.title,
        subtitle = page?.subtitle ?: "Album",
        thumb = page?.thumb ?: dest.thumb,
        tracks = page?.tracks.orEmpty(),
        sourceId = "album:${dest.id}",
        nav = nav,
        loading = loading,
        saveTarget = Store.Saved("album", dest.id, dest.title, "", dest.thumb),
    )
}

/** A playlist from YouTube Music. */
@Composable
fun OnlinePlaylistScreen(dest: Dest.OnlinePlaylist, nav: Nav) {
    var page by remember { mutableStateOf<Innertube.Page?>(null) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(dest.id) {
        loading = true
        page = withContext(Dispatchers.IO) { Innertube.playlist(dest.id) }
        loading = false
    }
    val tracks = page?.tracks.orEmpty()
    TrackListScreen(
        title = page?.title ?: dest.title,
        subtitle = page?.subtitle?.ifEmpty { songCount(tracks.size) } ?: "Playlist",
        thumb = page?.thumb ?: dest.thumb,
        tracks = tracks,
        sourceId = "ytm:${dest.id}",
        nav = nav,
        loading = loading,
        saveTarget = Store.Saved("playlist", dest.id, dest.title, "", dest.thumb),
    )
}

/** An artist page: top songs first, then their albums and related shelves. */
@Composable
fun ArtistScreen(dest: Dest.ArtistPage, nav: Nav) {
    var page by remember { mutableStateOf<Innertube.Page?>(null) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(dest.id) {
        loading = true
        page = withContext(Dispatchers.IO) { Innertube.artist(dest.id) }
        loading = false
    }
    val shelves = page?.shelves.orEmpty()
    TrackListScreen(
        title = page?.title?.ifEmpty { dest.title } ?: dest.title,
        subtitle = page?.subtitle.orEmpty(),
        thumb = page?.thumb ?: dest.thumb,
        tracks = page?.tracks.orEmpty(),
        sourceId = "artist:${dest.id}",
        nav = nav,
        circle = true,
        loading = loading,
        saveTarget = Store.Saved("artist", dest.id, dest.title, "", dest.thumb),
        header = if (shelves.isEmpty()) null else ({
            Column {
                shelves.forEach { shelf ->
                    if (shelf.title.isNotEmpty()) SectionHeader(shelf.title)
                    Carousel(shelf.items, key = { it.id }) { item ->
                        CatalogueCard(item, nav,
                            size = if (item.kind == Innertube.ARTIST) 132.dp else 156.dp)
                    }
                }
                SectionHeader("Top songs")
            }
        }),
    )
}

/** One of your own playlists: reorderable, renameable, deletable. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistScreen(playlist: Store.Playlist, version: Int, nav: Nav) {
    var tracks by remember { mutableStateOf(emptyList<Track>()) }
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf(playlist.name) }
    val scope = rememberCoroutineScope()
    val sourceId = "local:${playlist.id}"
    val playingHere = MuzikaPlayer.source == sourceId

    fun reload() {
        scope.launch {
            tracks = withContext(Dispatchers.IO) { Store.playlistTracks(playlist.id) }
        }
    }
    LaunchedEffect(playlist.id, version) { reload() }

    Column(Modifier.fillMaxSize()) {
        DetailBar(name, nav) {
            // Reordering is a mode, so the rows are not permanently cluttered.
            IconButton(onClick = { editing = !editing }) {
                Icon(if (editing) Icons.Rounded.Done else Icons.Rounded.Reorder,
                    if (editing) "Done" else "Reorder")
            }
            IconButton(onClick = { renaming = true }) { Icon(Icons.Rounded.Edit, "Rename") }
            IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Rounded.Delete, "Delete") }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                DetailHeader(
                    title = name,
                    subtitle = songCount(tracks.size),
                    thumb = tracks.firstOrNull()?.thumb ?: playlist.thumb,
                    playing = playingHere && MuzikaPlayer.isPlaying,
                    onPlay = {
                        if (playingHere) MuzikaPlayer.toggle()
                        else MuzikaPlayer.setQueue(tracks, 0, false, sourceId)
                    },
                    onShuffle = { MuzikaPlayer.setQueue(tracks, null, true, sourceId) },
                )
            }
            itemsIndexed(tracks, key = { index, track -> "$index-${track.id}" }) { index, track ->
                if (editing) {
                    ReorderRow(
                        track = track, index = index, last = index == tracks.lastIndex,
                        onMove = { delta ->
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    Store.moveTrack(playlist.id, track.id, delta); Sync.push()
                                }
                                reload()
                            }
                        },
                        onRemove = {
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    Store.removeFromPlaylist(playlist.id, track.id); Sync.push()
                                }
                                reload(); nav.changed(); nav.toast("Removed “${track.title}”")
                            }
                        },
                    )
                } else {
                    SongRow(track, index = index + 1, showArtwork = false,
                        onPlay = { MuzikaPlayer.setQueue(tracks, index, false, sourceId) },
                        onMenu = { nav.openMenu(track) })
                }
            }
            if (tracks.isEmpty()) {
                item {
                    EmptyState(Icons.Rounded.PlaylistAdd, "This playlist is empty",
                        "Find a song, tap … and add it here.",
                        "Search") { nav.goTo(Tab.SEARCH) }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (renaming) {
        NameDialog("Rename playlist", name, "Rename", { renaming = false }) { chosen ->
            name = chosen
            scope.launch {
                withContext(Dispatchers.IO) { Store.renamePlaylist(playlist.id, chosen); Sync.push() }
                nav.changed()
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            icon = { Icon(Icons.Rounded.Delete, null) },
            title = { Text("Delete playlist?") },
            text = { Text("“$name” and its ${songCount(tracks.size)} will be removed from this device.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        withContext(Dispatchers.IO) { Store.deletePlaylist(playlist.id); Sync.push() }
                        nav.changed(); nav.back(); nav.toast("Playlist deleted")
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun ReorderRow(
    track: Track,
    index: Int,
    last: Boolean,
    onMove: (Int) -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        Cover(track.thumb, 48.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge)
            Text(track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(enabled = index > 0, onClick = { onMove(-1) }) {
            Icon(Icons.Rounded.KeyboardArrowUp, "Move up")
        }
        IconButton(enabled = !last, onClick = { onMove(1) }) {
            Icon(Icons.Rounded.KeyboardArrowDown, "Move down")
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Rounded.RemoveCircleOutline, "Remove",
                tint = MaterialTheme.colorScheme.error)
        }
    }
}
