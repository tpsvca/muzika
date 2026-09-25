package lt.a777.muzika.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import lt.a777.muzika.sources.Innertube
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** YouTube Music's moods and genres, which need no account to browse. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExploreScreen(nav: Nav) {
    var moods by remember { mutableStateOf(emptyList<Innertube.Mood>()) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(reload) {
        loading = true
        val found = withContext(Dispatchers.IO) { Innertube.moods() }
        moods = found; failed = found.isEmpty(); loading = false
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = {
            Text("Explore", style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold)
        })
        when {
            loading -> LoadingRow()
            failed -> EmptyState(
                Icons.Rounded.CloudOff, "Could not reach YouTube Music",
                "Check the connection and try again.", "Retry"
            ) { reload++ }
            else -> {
                val sections = moods.groupBy { it.section }
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 160.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    sections.forEach { (heading, entries) ->
                        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                            Text(
                                heading.ifEmpty { "Browse" },
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(start = 6.dp, top = 14.dp, bottom = 2.dp)
                            )
                        }
                        items(entries, key = { it.params }) { mood ->
                            MoodTile(mood.title) {
                                nav.push(Dest.MoodPage(mood.title, mood.params))
                            }
                        }
                    }
                    item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
        }
    }
}

/** Everything inside one mood or genre. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoodScreen(dest: Dest.MoodPage, nav: Nav) {
    var shelves by remember { mutableStateOf(emptyList<Innertube.Shelf>()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(dest.params) {
        loading = true
        shelves = withContext(Dispatchers.IO) { Innertube.moodShelves(dest.params) }
        loading = false
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(dest.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = {
                IconButton(onClick = nav.back) { Icon(Icons.Rounded.ArrowBack, "Back") }
            }
        )
        when {
            loading -> LoadingRow()
            shelves.isEmpty() -> EmptyState(
                Icons.Rounded.SearchOff, "Nothing here",
                "YouTube Music returned no playlists for ${dest.title}."
            )
            else -> LazyColumn(Modifier.fillMaxSize()) {
                shelves.forEachIndexed { index, shelf ->
                    item(key = "shelf-$index-${shelf.title}") {
                        if (shelf.title.isNotEmpty()) SectionHeader(shelf.title)
                        else Spacer(Modifier.height(12.dp))
                        Carousel(shelf.items, key = { it.id }) { item ->
                            CatalogueCard(item, nav)
                        }
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}
