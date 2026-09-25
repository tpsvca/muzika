package lt.a777.muzika.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import lt.a777.muzika.data.LocalMusic
import lt.a777.muzika.data.Prefs
import lt.a777.muzika.data.Store
import lt.a777.muzika.data.Sync
import lt.a777.muzika.data.Track
import lt.a777.muzika.player.MuzikaPlayer
import lt.a777.muzika.sources.Discover

/** The four places the bottom bar goes. */
enum class Tab(val label: String, val icon: ImageVector, val selectedIcon: ImageVector) {
    HOME("Home", Icons.Outlined.Home, Icons.Rounded.Home),
    EXPLORE("Explore", Icons.Outlined.Explore, Icons.Rounded.Explore),
    SEARCH("Search", Icons.Outlined.Search, Icons.Rounded.Search),
    LIBRARY("Library", Icons.Outlined.LibraryMusic, Icons.Rounded.LibraryMusic),
}

/** Anything that can sit on top of a tab. */
sealed interface Dest {
    data class LocalPlaylist(val playlist: Store.Playlist) : Dest
    data class OnlinePlaylist(val id: String, val title: String, val thumb: String?) : Dest
    data class AlbumPage(val id: String, val title: String, val thumb: String?) : Dest
    data class ArtistPage(val id: String, val title: String, val thumb: String?) : Dest
    data class MoodPage(val title: String, val params: String) : Dest
    data class Mix(val mix: Discover.Mix) : Dest
    /** Any fixed list: favourites, history, an artist or album we already hold. */
    data class Tracks(val title: String, val subtitle: String, val thumb: String?,
                      val tracks: List<Track>, val circle: Boolean = false) : Dest
    data object Settings : Dest
}

/** Passed down so any screen can navigate, play and report without prop drilling. */
class Nav(
    val push: (Dest) -> Unit,
    val back: () -> Unit,
    val toast: (String) -> Unit,
    val changed: () -> Unit,
    val openMenu: (Track) -> Unit,
    val goTo: (Tab) -> Unit,
)

@Composable
fun MuzikaRoot() {
    var tab by remember { mutableStateOf(Tab.HOME) }
    val stack = remember { mutableStateListOf<Dest>() }
    var nowPlayingOpen by remember { mutableStateOf(false) }
    var menuTrack by remember { mutableStateOf<Track?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var libraryVersion by remember { mutableIntStateOf(0) }

    fun toast(message: String) {
        scope.launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(message) }
    }

    val nav = remember {
        Nav(
            push = { stack.add(it) },
            back = { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) },
            toast = ::toast,
            changed = { libraryVersion++ },
            openMenu = { menuTrack = it },
            goTo = { stack.clear(); tab = it },
        )
    }

    // Whatever the sync service dropped into the music folder, index it. Cheap
    // when nothing changed, and it is the only way new files become playable.
    LaunchedEffect(Unit) {
        val before = withContext(Dispatchers.IO) { Store.localCount() }
        val after = withContext(Dispatchers.IO) { LocalMusic.refresh() }
        if (after != before) {
            libraryVersion++
            if (after > before) toast("$after music file${if (after == 1) "" else "s"} on device")
        }
    }

    // Pull whatever the sync backend already has, then write ours back.
    LaunchedEffect(Unit) {
        if (!Prefs.autoSync) return@LaunchedEffect
        val report = withContext(Dispatchers.IO) { Sync.syncNow() }
        if (report.ok && report.describe() != "Already up to date") {
            libraryVersion++
            toast("Synced · ${report.describe()}")
        }
    }

    // Launcher shortcuts: act once, then forget, so rotating does not replay it.
    LaunchedEffect(Launch.version) {
        when (Launch.consume()) {
            Launch.LIKED -> {
                val liked = withContext(Dispatchers.IO) { Store.favourites() }
                if (liked.isEmpty()) toast("Nothing liked yet")
                else stack.add(Dest.Tracks("Liked songs", "${liked.size} songs",
                    liked.firstOrNull()?.thumb, liked))
            }
            Launch.SHUFFLE -> {
                val everything = withContext(Dispatchers.IO) { Store.songs() }
                if (everything.isEmpty()) toast("Your library is empty")
                else MuzikaPlayer.setQueue(everything, null, true, "shuffle-all")
            }
            Launch.SEARCH -> { stack.clear(); tab = Tab.SEARCH }
            else -> Unit
        }
    }

    MuzikaPlayer.errorText?.let { message ->
        LaunchedEffect(message) { toast(message); MuzikaPlayer.errorText = null }
    }

    BackHandler(enabled = stack.isNotEmpty() || tab != Tab.HOME) {
        if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) else tab = Tab.HOME
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Column {
                MiniPlayer(onOpen = { nowPlayingOpen = true })
                NavigationBar {
                    Tab.entries.forEach { entry ->
                        val selected = tab == entry && stack.isEmpty()
                        NavigationBarItem(
                            selected = selected,
                            onClick = { stack.clear(); tab = entry },
                            icon = {
                                Icon(
                                    if (selected) entry.selectedIcon else entry.icon,
                                    null, modifier = Modifier.size(26.dp)
                                )
                            },
                            label = { Text(entry.label) },
                        )
                    }
                }
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (val top = stack.lastOrNull()) {
                null -> when (tab) {
                    Tab.HOME -> HomeScreen(libraryVersion, nav)
                    Tab.EXPLORE -> ExploreScreen(nav)
                    Tab.SEARCH -> SearchScreen(nav)
                    Tab.LIBRARY -> LibraryScreen(libraryVersion, nav)
                }
                is Dest.LocalPlaylist -> PlaylistScreen(top.playlist, libraryVersion, nav)
                is Dest.OnlinePlaylist -> OnlinePlaylistScreen(top, nav)
                is Dest.AlbumPage -> AlbumScreen(top, nav)
                is Dest.ArtistPage -> ArtistScreen(top, nav)
                is Dest.MoodPage -> MoodScreen(top, nav)
                is Dest.Mix -> TrackListScreen(
                    title = top.mix.title, subtitle = top.mix.subtitle,
                    thumb = top.mix.thumb, tracks = top.mix.tracks,
                    sourceId = top.mix.id, nav = nav,
                )
                is Dest.Tracks -> TrackListScreen(
                    title = top.title, subtitle = top.subtitle, thumb = top.thumb,
                    tracks = top.tracks, sourceId = "list:${top.title}",
                    circle = top.circle, nav = nav,
                )
                Dest.Settings -> SettingsScreen(nav)
            }
        }
    }

    if (nowPlayingOpen) {
        NowPlayingSheet(
            onDismiss = { nowPlayingOpen = false },
            toast = ::toast,
            onChanged = { libraryVersion++ },
        )
    }

    menuTrack?.let { track ->
        TrackMenu(track, onDismiss = { menuTrack = null }, toast = ::toast,
            onChanged = { libraryVersion++ })
    }
}

/**
 * The bar above the tabs. Larger artwork and a 48dp play target than before,
 * and the whole strip opens the full player - the small controls are only for
 * skipping without leaving the page.
 */
@Composable
fun MiniPlayer(onOpen: () -> Unit) {
    val track = MuzikaPlayer.current
    AnimatedVisibility(
        visible = track != null,
        enter = slideInVertically { it }, exit = slideOutVertically { it },
    ) {
        if (track == null) return@AnimatedVisibility
        val progress = if (MuzikaPlayer.durationMs > 0)
            MuzikaPlayer.positionMs.toFloat() / MuzikaPlayer.durationMs else 0f
        Surface(
            tonalElevation = 3.dp,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                .clip(RoundedCornerShape(14.dp))
        ) {
            Column(Modifier.clickable(onClick = onOpen)) {
                Row(
                    Modifier.fillMaxWidth().padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Cover(track.thumb, 52.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium)
                        Text(track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (MuzikaPlayer.loading) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(28.dp).padding(end = 4.dp)
                        )
                    }
                    FilledIconButton(
                        onClick = { MuzikaPlayer.toggle() },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            if (MuzikaPlayer.isPlaying) Icons.Rounded.Pause
                            else Icons.Rounded.PlayArrow,
                            if (MuzikaPlayer.isPlaying) "Pause" else "Play",
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    IconButton(onClick = { MuzikaPlayer.next() },
                        modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Rounded.SkipNext, "Next", modifier = Modifier.size(26.dp))
                    }
                }
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    drawStopIndicator = {},
                )
            }
        }
    }
}

/** Kept for the now-playing sheet and the pickers, which want a plain square. */
@Composable
fun Artwork(url: String?, size: androidx.compose.ui.unit.Dp) = Cover(url, size)

fun formatDuration(seconds: Int): String {
    if (seconds <= 0) return ""
    val minutes = seconds / 60
    val rest = seconds % 60
    return if (minutes >= 60) "%d:%02d:%02d".format(minutes / 60, minutes % 60, rest)
    else "%d:%02d".format(minutes, rest)
}

/** The old row, still used by the queue pane. */
@Composable
fun TrackRow(track: Track, index: Int? = null, onPlay: () -> Unit, onMenu: () -> Unit) =
    SongRow(track, index, showArtwork = index == null, onPlay = onPlay, onMenu = onMenu)
