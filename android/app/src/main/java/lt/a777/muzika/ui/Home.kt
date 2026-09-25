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
import lt.a777.muzika.sources.Innertube
import java.time.LocalTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(version: Int, nav: Nav) {
    var playlists by remember { mutableStateOf(emptyList<Store.Playlist>()) }
    var recent by remember { mutableStateOf(emptyList<Track>()) }
    var favourites by remember { mutableStateOf(emptyList<Track>()) }
    var feed by remember { mutableStateOf(Discover.Feed()) }
    var loadingFeed by remember { mutableStateOf(true) }
    var syncing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(version) {
        withContext(Dispatchers.IO) {
            val p = Store.playlists(); val r = Store.history(20); val f = Store.favourites()
            withContext(Dispatchers.Main) { playlists = p; recent = r; favourites = f }
        }
    }
    // The suggestions are cached, so coming back to Home does not refetch them.
    LaunchedEffect(version) {
        loadingFeed = true
        feed = Discover.feed()
        loadingFeed = false
    }

    fun openMix(mix: Discover.Mix) = nav.push(Dest.Mix(mix))

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text(greeting(), style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold)
                    if (feed.seededFrom.isNotEmpty()) {
                        Text(
                            "Tuned to ${feed.seededFrom.take(2).joinToString(" and ")}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            actions = {
                IconButton(
                    enabled = !syncing,
                    onClick = {
                        syncing = true
                        scope.launch {
                            val report = withContext(Dispatchers.IO) { Sync.syncNow() }
                            syncing = false
                            nav.changed(); nav.toast(report.describe())
                        }
                    }
                ) {
                    if (syncing) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Rounded.Sync, "Sync library")
                }
                IconButton(onClick = { nav.push(Dest.Settings) }) {
                    Icon(Icons.Rounded.Settings, "Settings")
                }
            }
        )

        LazyColumn(Modifier.fillMaxSize()) {
            // Straight back into the things already listened to.
            val shortcuts = buildList {
                if (favourites.isNotEmpty()) add(Shortcut("Liked songs", null,
                    Icons.Rounded.Favorite) {
                    nav.push(Dest.Tracks("Liked songs",
                        songCount(favourites.size), favourites.firstOrNull()?.thumb, favourites))
                })
                if (recent.isNotEmpty()) add(Shortcut("Recently played", null,
                    Icons.Rounded.History) {
                    nav.push(Dest.Tracks("Recently played",
                        songCount(recent.size), recent.firstOrNull()?.thumb, recent))
                })
                playlists.take(4).forEach { playlist ->
                    add(Shortcut(playlist.name, playlist.thumb, null) {
                        nav.push(Dest.LocalPlaylist(playlist))
                    })
                }
            }
            if (shortcuts.isNotEmpty()) {
                item {
                    Column(
                        Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        shortcuts.chunked(2).forEach { pair ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                pair.forEach { shortcut ->
                                    ShortcutTile(
                                        title = shortcut.title, thumb = shortcut.thumb,
                                        icon = shortcut.icon, modifier = Modifier.weight(1f),
                                        onClick = shortcut.onClick,
                                    )
                                }
                                if (pair.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }

            if (feed.mixes.isNotEmpty()) {
                item {
                    SectionHeader("Made for you", "Mixes built from what you play")
                    Carousel(feed.mixes, key = { it.id }) { mix ->
                        MediaCard(mix.title, mix.subtitle, mix.thumb,
                            badge = Icons.Rounded.PlayArrow) { openMix(mix) }
                    }
                }
            }

            if (feed.newAlbums.isNotEmpty()) {
                item {
                    SectionHeader("Albums from your artists")
                    Carousel(feed.newAlbums, key = { it.id }) { album ->
                        MediaCard(album.title, album.subtitle, album.thumb) {
                            nav.push(Dest.AlbumPage(album.id, album.title, album.thumb))
                        }
                    }
                }
            }

            if (feed.fresh.isNotEmpty()) {
                item {
                    SectionHeader("Fresh playlists for you")
                    Carousel(feed.fresh, key = { it.id }) { playlist ->
                        MediaCard(playlist.title, playlist.subtitle, playlist.thumb) {
                            nav.push(Dest.OnlinePlaylist(playlist.id, playlist.title, playlist.thumb))
                        }
                    }
                }
            }

            if (feed.similarArtists.isNotEmpty()) {
                item {
                    SectionHeader("Artists you might like")
                    Carousel(feed.similarArtists, key = { it.id }) { artist ->
                        MediaCard(artist.title, null, artist.thumb, circle = true, size = 132.dp) {
                            nav.push(Dest.ArtistPage(artist.id, artist.title, artist.thumb))
                        }
                    }
                }
            }

            feed.charts.forEach { shelf ->
                item(key = "shelf-${shelf.title}") {
                    SectionHeader(shelf.title)
                    Carousel(shelf.items, key = { it.id }) { item -> CatalogueCard(item, nav) }
                }
            }

            if (recent.isNotEmpty()) {
                item {
                    SectionHeader("Jump back in", actionLabel = "See all", onAction = {
                        nav.push(Dest.Tracks("Recently played", songCount(recent.size),
                            recent.firstOrNull()?.thumb, recent))
                    })
                }
                itemsIndexed(recent.take(6), key = { i, t -> "recent-$i-${t.id}" }) { _, track ->
                    SongRow(track,
                        onPlay = { MuzikaPlayer.setQueue(recent, recent.indexOf(track), false,
                            "history") },
                        onMenu = { nav.openMenu(track) })
                }
            }

            if (loadingFeed && feed.isEmpty) item { LoadingRow() }

            if (!loadingFeed && feed.isEmpty && playlists.isEmpty() && recent.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.Rounded.Headphones,
                        title = "Let's find you something",
                        message = "Play a few songs and this page fills up with mixes built " +
                            "around them. Nothing leaves the phone - the suggestions come " +
                            "from what you listen to here.",
                        actionLabel = "Browse moods",
                    ) { nav.goTo(Tab.EXPLORE) }
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

private class Shortcut(
    val title: String,
    val thumb: String?,
    val icon: androidx.compose.ui.graphics.vector.ImageVector?,
    val onClick: () -> Unit,
)

/** One card for anything the catalogue returns, routed by what it is. */
@Composable
fun CatalogueCard(item: Innertube.Item, nav: Nav, size: androidx.compose.ui.unit.Dp = 156.dp) {
    when (item.kind) {
        Innertube.ARTIST -> MediaCard(item.title, null, item.thumb, circle = true, size = size) {
            nav.push(Dest.ArtistPage(item.id, item.title, item.thumb))
        }
        Innertube.ALBUM -> MediaCard(item.title, item.subtitle, item.thumb, size = size) {
            nav.push(Dest.AlbumPage(item.id, item.title, item.thumb))
        }
        Innertube.PLAYLIST -> MediaCard(item.title, item.subtitle, item.thumb, size = size) {
            nav.push(Dest.OnlinePlaylist(item.id, item.title, item.thumb))
        }
        else -> MediaCard(item.title, item.subtitle, item.thumb, size = size,
            badge = Icons.Rounded.PlayArrow) {
            MuzikaPlayer.setQueue(listOf(item.toTrack()), 0, false)
        }
    }
}

private fun greeting(): String = when (LocalTime.now().hour) {
    in 5..11 -> "Good morning"
    in 12..17 -> "Good afternoon"
    in 18..22 -> "Good evening"
    else -> "Up late"
}
