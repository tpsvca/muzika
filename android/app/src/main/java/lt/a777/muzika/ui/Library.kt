package lt.a777.muzika.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import lt.a777.muzika.data.Store
import lt.a777.muzika.data.Sync
import lt.a777.muzika.data.Track
import lt.a777.muzika.player.MuzikaPlayer
import lt.a777.muzika.sources.Discover

private enum class Shelf(val label: String) {
    PLAYLISTS("Playlists"), SONGS("Songs"), ARTISTS("Artists"),
    ALBUMS("Albums"), MUSIC("On device"), LIKED("Liked"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(version: Int, nav: Nav) {
    var shelf by remember { mutableStateOf(Shelf.PLAYLISTS) }
    var playlists by remember { mutableStateOf(emptyList<Store.Playlist>()) }
    var songs by remember { mutableStateOf(emptyList<Track>()) }
    var artists by remember { mutableStateOf(emptyList<Store.ArtistEntry>()) }
    var albums by remember { mutableStateOf(emptyList<Store.AlbumEntry>()) }
    var liked by remember { mutableStateOf(emptyList<Track>()) }
    var savedAlbums by remember { mutableStateOf(emptyList<Store.Saved>()) }
    var savedArtists by remember { mutableStateOf(emptyList<Store.Saved>()) }
    var savedPlaylists by remember { mutableStateOf(emptyList<Store.Saved>()) }
    var onDevice by remember { mutableStateOf(emptyList<Track>()) }
    var newPlaylist by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(version) {
        withContext(Dispatchers.IO) {
            val p = Store.playlists()
            val s = Store.songs()
            val ar = Store.artists()
            val al = Store.albums()
            val f = Store.favourites()
            val sAl = Store.saved("album")
            val sAr = Store.saved("artist")
            val sPl = Store.saved("playlist")
            val od = Store.localTracks()
            withContext(Dispatchers.Main) {
                playlists = p; songs = s; artists = ar; albums = al; liked = f
                savedAlbums = sAl; savedArtists = sAr; savedPlaylists = sPl
                onDevice = od
            }
        }
    }

    // Saved catalogue items and items derived from your own songs, merged.
    // A saved one wins, so tapping it opens the real album or artist page.
    fun buildAlbumentries(): List<Entry> {
        val savedTitles = savedAlbums.map { it.title.lowercase() }.toSet()
        return savedAlbums.map { item ->
            Entry("s-${item.id}", item.title, item.subtitle, item.thumb, false) {
                nav.push(Dest.AlbumPage(item.id, item.title, item.thumb))
            }
        } + onDevice.filter { !it.album.isNullOrBlank() }
            .groupBy { it.album!! }
            .filterKeys { it.lowercase() !in savedTitles }
            .map { (name, tracks) ->
                Entry("d-$name", name, tracks.first().primaryArtist,
                    tracks.firstNotNullOfOrNull { it.thumb }, false) {
                    nav.push(Dest.Tracks(name, tracks.first().primaryArtist,
                        tracks.firstNotNullOfOrNull { it.thumb },
                        tracks.sortedBy { it.trackNumber }))
                }
            } + albums.filterNot { it.name.lowercase() in savedTitles }.map { album ->
            Entry("l-${album.name}", album.name, album.artist, album.thumb, false) {
                nav.push(Dest.Tracks(album.name, album.artist, album.thumb,
                    songs.filter { it.album.equals(album.name, true) }))
            }
        }
    }

    fun buildArtistentries(): List<Entry> {
        val savedNames = savedArtists.map { it.title.lowercase() }.toSet()
        return savedArtists.map { item ->
            Entry("s-${item.id}", item.title, item.subtitle, item.thumb, true) {
                nav.push(Dest.ArtistPage(item.id, item.title, item.thumb))
            }
        } + onDevice.filter { it.primaryArtist.isNotEmpty() }
            .groupBy { it.primaryArtist }
            .filterKeys { it.lowercase() !in savedNames }
            .map { (name, tracks) ->
                Entry("d-$name", name, songCount(tracks.size),
                    tracks.firstNotNullOfOrNull { it.thumb }, true) {
                    nav.push(Dest.Tracks(name, songCount(tracks.size),
                        tracks.firstNotNullOfOrNull { it.thumb }, tracks, circle = true))
                }
            } + artists.filterNot { it.name.lowercase() in savedNames }.map { artist ->
            Entry("l-${artist.name}", artist.name, songCount(artist.trackCount),
                artist.thumb, true) {
                nav.push(Dest.Tracks(artist.name,
                    "${songCount(artist.trackCount)} in your library", artist.thumb,
                    songs.filter { it.primaryArtist.equals(artist.name, true) }, circle = true))
            }
        }
    }

    fun buildPlaylistentries(): List<Entry> =
        playlists.map { playlist ->
            Entry("l-${playlist.id}", playlist.name, songCount(playlist.count),
                playlist.thumb, false) { nav.push(Dest.LocalPlaylist(playlist)) }
        } + savedPlaylists.map { item ->
            Entry("s-${item.id}", item.title, item.subtitle, item.thumb, false) {
                nav.push(Dest.OnlinePlaylist(item.id, item.title, item.thumb))
            }
        }

    // These walk the whole song list, and countFor() asks for two of them on
    // every chip. Without remember they were rebuilt on each recomposition.
    val albumList = remember(songs, savedAlbums, albums) { buildAlbumentries() }
    val artistList = remember(songs, savedArtists, artists) { buildArtistentries() }
    val playlistList = remember(playlists, savedPlaylists) { buildPlaylistentries() }

    fun countFor(entry: Shelf) = when (entry) {
        Shelf.PLAYLISTS -> playlistList.size
        Shelf.SONGS -> songs.size
        Shelf.ARTISTS -> artistList.size
        Shelf.ALBUMS -> albumList.size
        Shelf.MUSIC -> onDevice.size
        Shelf.LIKED -> liked.size
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text("Your library", style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold)
            },
            actions = {
                IconButton(onClick = { newPlaylist = true }) {
                    Icon(Icons.Rounded.Add, "New playlist", modifier = Modifier.size(28.dp))
                }
            }
        )
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Shelf.entries.forEach { entry ->
                FilterChip(
                    selected = shelf == entry,
                    onClick = { shelf = entry },
                    label = { Text("${entry.label} · ${countFor(entry)}") },
                )
            }
        }
        Spacer(Modifier.height(4.dp))

        when (shelf) {
            Shelf.PLAYLISTS -> EntryGrid(
                playlistList,
                Icons.Rounded.QueueMusic, "No playlists yet",
                "Make one here, or sync the ones from your desktop.",
                "New playlist", { newPlaylist = true },
            )

            Shelf.ARTISTS -> EntryGrid(
                artistList,
                Icons.Rounded.Person, "No artists yet",
                "Artists appear once you have songs saved, and any you save from " +
                    "an artist page show up here too.",
            )

            Shelf.ALBUMS -> EntryGrid(
                albumList,
                Icons.Rounded.Album, "No albums yet",
                "Save an album from its page, or from the desktop player, and it " +
                    "appears here.",
            )

            Shelf.SONGS -> TrackList(songs, "song", nav) {
                EmptyState(Icons.Rounded.MusicNote, "No songs yet",
                    "Search for something and add it, or sync your desktop library.",
                    "Search") { nav.goTo(Tab.SEARCH) }
            }

            Shelf.MUSIC -> TrackList(onDevice, "device", nav) {
                EmptyState(Icons.Rounded.Folder, "No music files yet",
                    "Copy music into the sync folder from the desktop app " +
                        "(Settings \u2192 Music) and it appears here.")
            }

            Shelf.LIKED -> TrackList(liked, "liked", nav) {
                EmptyState(Icons.Rounded.FavoriteBorder, "Nothing liked yet",
                    "Tap the … on any song and add it to favourites.")
            }
        }
    }

    if (newPlaylist) {
        NameDialog("New playlist", "", "Create", onDismiss = { newPlaylist = false }) { name ->
            scope.launch {
                withContext(Dispatchers.IO) { Store.createPlaylist(name); Sync.push() }
                nav.changed(); nav.toast("Created “$name”")
            }
        }
    }
}

/** One tile in the library, whether it came from the catalogue or your songs. */
private class Entry(
    val key: String,
    val title: String,
    val subtitle: String,
    val thumb: String?,
    val circle: Boolean,
    val onClick: () -> Unit,
)

@Composable
private fun EntryGrid(
    entries: List<Entry>,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    emptyTitle: String,
    emptyMessage: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    if (entries.isEmpty()) {
        EmptyState(icon, emptyTitle, emptyMessage, actionLabel, onAction)
        return
    }
    Grid {
        items(entries, key = { it.key }) { entry ->
            MediaCard(entry.title, entry.subtitle, entry.thumb, circle = entry.circle,
                onClick = entry.onClick)
        }
    }
}

@Composable
private fun Grid(content: androidx.compose.foundation.lazy.grid.LazyGridScope.() -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 150.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        content()
        item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun TrackList(
    tracks: List<Track>,
    sourceId: String,
    nav: Nav,
    empty: @Composable () -> Unit,
) {
    if (tracks.isEmpty()) { empty(); return }
    val scope = rememberCoroutineScope()
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = { MuzikaPlayer.setQueue(tracks, 0, false, sourceId) },
                    modifier = Modifier.weight(1f).height(48.dp)
                ) {
                    Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(8.dp)); Text("Play")
                }
                OutlinedButton(
                    onClick = { MuzikaPlayer.setQueue(tracks, null, true, sourceId) },
                    modifier = Modifier.weight(1f).height(48.dp)
                ) {
                    Icon(Icons.Rounded.Shuffle, null); Spacer(Modifier.width(8.dp)); Text("Shuffle")
                }
            }
            // A radio off this list is the fastest way to hear something new.
            TextButton(
                onClick = {
                    scope.launch {
                        val mix = Discover.quickMix()
                        if (mix.isEmpty()) nav.toast("Could not build a mix")
                        else MuzikaPlayer.setQueue(mix, 0, false, "radio")
                    }
                },
                modifier = Modifier.padding(start = 12.dp)
            ) {
                Icon(Icons.Rounded.Radio, null); Spacer(Modifier.width(8.dp))
                Text("Start a radio from these")
            }
        }
        itemsIndexed(tracks, key = { index, track -> "$index-${track.id}" }) { _, track ->
            SongRow(track,
                onPlay = { MuzikaPlayer.setQueue(tracks, tracks.indexOf(track), false, sourceId) },
                onMenu = { nav.openMenu(track) })
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}
