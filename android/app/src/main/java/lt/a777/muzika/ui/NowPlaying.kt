package lt.a777.muzika.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import lt.a777.muzika.data.Store
import lt.a777.muzika.data.Sync
import lt.a777.muzika.data.Track
import lt.a777.muzika.lyrics.Lyrics
import lt.a777.muzika.lyrics.LyricsResult
import lt.a777.muzika.player.MuzikaPlayer
import lt.a777.muzika.player.REPEAT_NONE
import lt.a777.muzika.player.REPEAT_ONE

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingSheet(onDismiss: () -> Unit, toast: (String) -> Unit, onChanged: () -> Unit) {
    var page by remember { mutableIntStateOf(0) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(Modifier.fillMaxSize()) {
            TabRow(selectedTabIndex = page) {
                listOf("Song", "Lyrics", "Queue").forEachIndexed { index, label ->
                    Tab(selected = page == index, onClick = { page = index },
                        text = { Text(label) })
                }
            }
            when (page) {
                0 -> SongPane(toast, onChanged)
                1 -> LyricsPane()
                else -> QueuePane()
            }
        }
    }
}

@Composable
private fun SongPane(toast: (String) -> Unit, onChanged: () -> Unit) {
    val track = MuzikaPlayer.current
    var favourite by remember(track?.id) { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(track?.id) {
        track?.let { favourite = withContext(Dispatchers.IO) { Store.isFavourite(it.id) } }
    }

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(8.dp))
        Cover(track?.thumb, 260.dp)
        Spacer(Modifier.height(24.dp))
        Text(track?.title ?: "Nothing playing",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(4.dp))
        Text(track?.artist.orEmpty(), style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center, maxLines = 1)

        Spacer(Modifier.height(20.dp))
        var dragging by remember { mutableStateOf(false) }
        var dragValue by remember { mutableFloatStateOf(0f) }
        val duration = MuzikaPlayer.durationMs.coerceAtLeast(1L)
        val fraction = if (dragging) dragValue
        else (MuzikaPlayer.positionMs.toFloat() / duration).coerceIn(0f, 1f)

        Slider(
            value = fraction,
            onValueChange = { dragging = true; dragValue = it },
            onValueChangeFinished = {
                MuzikaPlayer.seekTo((dragValue * duration).toLong()); dragging = false
            }
        )
        Row(Modifier.fillMaxWidth()) {
            Text(formatDuration((fraction * duration / 1000).toInt()),
                style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.weight(1f))
            Text(formatDuration((duration / 1000).toInt()),
                style = MaterialTheme.typography.labelMedium)
        }

        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { MuzikaPlayer.applyShuffle(!MuzikaPlayer.shuffle) }) {
                Icon(Icons.Filled.Shuffle, "Shuffle",
                    tint = if (MuzikaPlayer.shuffle) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { MuzikaPlayer.previous() }) {
                Icon(Icons.Filled.SkipPrevious, "Previous")
            }
            FilledIconButton(
                onClick = { MuzikaPlayer.toggle() },
                modifier = Modifier.size(64.dp)
            ) {
                Icon(
                    if (MuzikaPlayer.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    null, modifier = Modifier.size(32.dp)
                )
            }
            IconButton(onClick = { MuzikaPlayer.next() }) {
                Icon(Icons.Filled.SkipNext, "Next")
            }
            IconButton(onClick = { MuzikaPlayer.cycleRepeat() }) {
                Icon(
                    if (MuzikaPlayer.repeat == REPEAT_ONE) Icons.Filled.RepeatOne
                    else Icons.Filled.Repeat,
                    "Repeat",
                    tint = if (MuzikaPlayer.repeat == REPEAT_NONE)
                        MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.primary
                )
            }
        }

        // Saving what is playing should not mean finding the same song again
        // in some list to reach its menu.
        track?.let { playing ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = {
                    scope.launch {
                        val state = withContext(Dispatchers.IO) {
                            Store.toggleFavourite(playing).also { _ -> Sync.push() }
                        }
                        favourite = state
                        toast(if (state) "Added to favourites" else "Removed from favourites")
                        onChanged()
                    }
                }) {
                    Icon(if (favourite) Icons.Filled.Star else Icons.Filled.StarBorder, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (favourite) "In favourites" else "Add to favourites")
                }

                TextButton(onClick = { picking = true }) {
                    Icon(Icons.Filled.PlaylistAdd, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Add to playlist")
                }
            }
        }
    }

    if (picking) {
        track?.let { playing ->
            PlaylistPicker(
                tracks = listOf(playing),
                onDismiss = { picking = false },
                toast = toast,
                onChanged = onChanged,
            )
        }
    }
}

@Composable
private fun LyricsPane() {
    val track = MuzikaPlayer.current
    var result by remember(track?.id) { mutableStateOf<LyricsResult?>(null) }
    var loading by remember(track?.id) { mutableStateOf(true) }
    val listState = rememberLazyListState()

    LaunchedEffect(track?.id) {
        result = null; loading = true
        if (track != null) {
            result = withContext(Dispatchers.IO) { Lyrics.fetch(track) }
        }
        loading = false
    }

    val lines = result?.lines
    val activeIndex = remember(lines, MuzikaPlayer.positionMs) {
        if (lines.isNullOrEmpty()) -1
        else lines.indexOfLast { it.atMs <= MuzikaPlayer.positionMs }
    }

    // Keep the current line in view as the song moves through it.
    LaunchedEffect(activeIndex) {
        if (activeIndex >= 0) {
            runCatching { listState.animateScrollToItem(maxOf(0, activeIndex - 3)) }
        }
    }

    when {
        loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        result == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No lyrics found", style = MaterialTheme.typography.titleMedium)
        }
        else -> LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val texts = lines?.map { it.text } ?: result!!.text.lines()
            items(texts.size) { index ->
                val active = index == activeIndex
                Text(
                    texts[index].ifEmpty { " " },
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                    color = when {
                        lines == null -> MaterialTheme.colorScheme.onSurface
                        active -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
                )
            }
            item {
                Text(
                    (if (lines != null) "Synced lyrics from " else "Lyrics from ") +
                        (result?.provider ?: ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp)
                )
            }
        }
    }
}

@Composable
private fun QueuePane() {
    val queue = MuzikaPlayer.queueView
    if (queue.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Queue is empty", style = MaterialTheme.typography.titleMedium)
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(queue.size) { index ->
            val track: Track = queue[index]
            ListItem(
                headlineContent = {
                    Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (index == MuzikaPlayer.queueIndex)
                            MaterialTheme.colorScheme.primary
                        else androidx.compose.ui.graphics.Color.Unspecified)
                },
                supportingContent = { Text(track.artist, maxLines = 1) },
                leadingContent = { Text("${index + 1}") },
                trailingContent = {
                    IconButton(onClick = { MuzikaPlayer.removeAt(index) }) {
                        Icon(Icons.Filled.Remove, "Remove")
                    }
                },
                modifier = Modifier.clickable { MuzikaPlayer.jumpTo(index) }
            )
        }
    }
}
