package lt.a777.muzika.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import lt.a777.muzika.data.Track
import lt.a777.muzika.player.MuzikaPlayer
import lt.a777.muzika.sources.Innertube
import lt.a777.muzika.sources.Sources

private enum class Scope(val label: String, val filter: String?) {
    ALL("All", null),
    SONGS("Songs", Innertube.F_SONGS),
    ALBUMS("Albums", Innertube.F_ALBUMS),
    ARTISTS("Artists", Innertube.F_ARTISTS),
    PLAYLISTS("Playlists", Innertube.F_PLAYLISTS),
}

private class Results(
    val songs: Map<String, List<Track>> = emptyMap(),
    val cards: List<Innertube.Item> = emptyList(),
) {
    val isEmpty get() = songs.isEmpty() && cards.isEmpty()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(nav: Nav) {
    var query by remember { mutableStateOf("") }
    var scope by remember { mutableStateOf(Scope.ALL) }
    var submitted by remember { mutableStateOf("") }
    var results by remember { mutableStateOf(Results()) }
    var searching by remember { mutableStateOf(false) }
    val coroutines = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }

    fun run(term: String, within: Scope) {
        val clean = term.trim()
        if (clean.isEmpty()) return
        submitted = clean
        searching = true
        keyboard?.hide()
        coroutines.launch {
            val found = withContext(Dispatchers.IO) {
                when (within) {
                    // "All" shows every kind at once, so every kind is fetched
                    // at once - six requests in sequence meant six latencies.
                    Scope.ALL -> coroutineScope {
                        val songs = async { Sources.searchAll(clean) }
                        val artists = async {
                            Innertube.search(clean, Innertube.F_ARTISTS).take(6)
                        }
                        val albums = async {
                            Innertube.search(clean, Innertube.F_ALBUMS).take(6)
                        }
                        val playlists = async {
                            Innertube.search(clean, Innertube.F_PLAYLISTS).take(6)
                        }
                        Results(
                            songs = songs.await(),
                            cards = artists.await() + albums.await() + playlists.await(),
                        )
                    }
                    Scope.SONGS -> Results(songs = Sources.searchAll(clean))
                    else -> Results(cards = Sources.searchCatalogue(clean, within.filter!!))
                }
            }
            results = found
            searching = false
            if (found.isEmpty) nav.toast("Nothing found for “$clean”")
        }
    }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Songs, albums, artists, playlists") },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = ""; results = Results(); submitted = "" }) {
                        Icon(Icons.Rounded.Close, "Clear")
                    }
                }
            },
            singleLine = true,
            shape = MaterialTheme.shapes.extraLarge,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { run(query, scope) }),
            modifier = Modifier.fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .focusRequester(focus),
        )

        // Filters stay visible so the same words can be re-run over another kind.
        Row(
            Modifier.fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Scope.entries.forEach { entry ->
                FilterChip(
                    selected = scope == entry,
                    onClick = {
                        scope = entry
                        if (submitted.isNotEmpty()) run(submitted, entry)
                    },
                    label = { Text(entry.label) },
                )
            }
        }

        if (searching) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))

        when {
            submitted.isEmpty() -> EmptyState(
                Icons.Rounded.Search, "Search everything",
                "YouTube Music, SoundCloud and Bandcamp at once. No account needed."
            )
            scope == Scope.ALL || scope == Scope.SONGS ->
                SongResults(results, scope == Scope.ALL, nav)
            else -> CardResults(results.cards, nav)
        }
    }

    LaunchedEffect(Unit) { if (submitted.isEmpty()) runCatching { focus.requestFocus() } }
}

@Composable
private fun SongResults(results: Results, withCards: Boolean, nav: Nav) {
    LazyColumn(Modifier.fillMaxSize()) {
        if (withCards && results.cards.isNotEmpty()) {
            val byKind = results.cards.groupBy { it.kind }
            listOf(
                Innertube.ARTIST to "Artists",
                Innertube.ALBUM to "Albums",
                Innertube.PLAYLIST to "Playlists",
            ).forEach { (kind, label) ->
                val items = byKind[kind].orEmpty()
                if (items.isNotEmpty()) {
                    item(key = "cards-$kind") {
                        SectionHeader(label)
                        Carousel(items, key = { it.id }) { item ->
                            CatalogueCard(item, nav, size = if (kind == Innertube.ARTIST)
                                132.dp else 156.dp)
                        }
                    }
                }
            }
        }
        results.songs.forEach { (source, tracks) ->
            item(key = "head-$source") {
                SectionHeader(
                    if (results.songs.size == 1) "Songs"
                    else "Songs on ${Sources.LABELS[source] ?: source}",
                    actionLabel = "Shuffle",
                    onAction = { MuzikaPlayer.setQueue(tracks, null, true, "search:$source") },
                )
            }
            itemsIndexed(tracks, key = { index, t -> "$source-$index-${t.id}" }) { _, track ->
                SongRow(track,
                    onPlay = {
                        MuzikaPlayer.setQueue(tracks, tracks.indexOf(track), false, "search:$source")
                    },
                    onMenu = { nav.openMenu(track) })
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun CardResults(items: List<Innertube.Item>, nav: Nav) {
    if (items.isEmpty()) {
        EmptyState(Icons.Rounded.SearchOff, "Nothing found", "Try different words.")
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 150.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        itemsIndexed(items, key = { index, item -> "$index-${item.id}" }) { _, item ->
            CatalogueCard(item, nav)
        }
        item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(24.dp)) }
    }
}
