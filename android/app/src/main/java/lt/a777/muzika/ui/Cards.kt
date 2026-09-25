package lt.a777.muzika.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import lt.a777.muzika.data.Track
import lt.a777.muzika.player.MuzikaPlayer

/** Artwork everywhere: square by default, a circle for artists. */
@Composable
fun Cover(
    url: String?,
    size: Dp,
    circle: Boolean = false,
    fallback: ImageVector = Icons.Rounded.MusicNote,
) {
    val shape = if (circle) CircleShape else RoundedCornerShape(size / 10)
    val base = Modifier.size(size).clip(shape)
        .background(MaterialTheme.colorScheme.surfaceVariant)
    if (url.isNullOrEmpty()) {
        Box(base, contentAlignment = Alignment.Center) {
            Icon(fallback, null, modifier = Modifier.size(size / 2.4f),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else {
        coil.compose.AsyncImage(
            model = url, contentDescription = null, modifier = base,
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
        )
    }
}

/** A tile in a carousel or a grid: big artwork, two lines under it. */
@Composable
fun MediaCard(
    title: String,
    subtitle: String? = null,
    thumb: String? = null,
    circle: Boolean = false,
    size: Dp = 156.dp,
    badge: ImageVector? = null,
    onClick: () -> Unit,
) {
    Column(
        Modifier.width(size).clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick)
            .padding(6.dp),
        horizontalAlignment = if (circle) Alignment.CenterHorizontally else Alignment.Start
    ) {
        Box {
            Cover(thumb, size - 12.dp, circle,
                fallback = if (circle) Icons.Rounded.Person else Icons.Rounded.MusicNote)
            if (badge != null) {
                Surface(
                    shape = CircleShape, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp)
                ) {
                    Icon(badge, null, tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(6.dp).size(18.dp))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            title, maxLines = 2, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.titleSmall,
            textAlign = if (circle) TextAlign.Center else TextAlign.Start,
            modifier = Modifier.fillMaxWidth(),
        )
        if (!subtitle.isNullOrEmpty()) {
            Text(
                subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = if (circle) TextAlign.Center else TextAlign.Start,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** A flat, wide tile - the fastest way back into something you already know. */
@Composable
fun ShortcutTile(
    title: String,
    thumb: String? = null,
    icon: ImageVector? = null,
    tint: Color? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        modifier = modifier.height(64.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Box(
                    Modifier.size(64.dp).background(tint ?: MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(28.dp)) }
            } else {
                Cover(thumb, 64.dp)
            }
            Text(
                title, maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 12.dp).weight(1f)
            )
        }
    }
}

/** A mood or genre: no artwork exists for these, so the colour is the artwork. */
@Composable
fun MoodTile(title: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val tint = tintFor(title)
    Surface(
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.height(96.dp).clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick)
    ) {
        Box(
            Modifier.background(
                Brush.linearGradient(listOf(tint, tint.copy(alpha = 0.65f)))
            ).padding(14.dp)
        ) {
            Text(
                title, style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold, color = Color.White,
                maxLines = 3, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomStart)
            )
        }
    }
}

@Composable
fun SectionHeader(
    title: String,
    subtitle: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 22.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold)
            if (!subtitle.isNullOrEmpty()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

/**
 * A row of tiles that scrolls sideways. Keys pair the caller's key with the
 * position, because a shelf can legitimately list the same album twice and a
 * repeated key crashes a lazy list.
 */
@Composable
fun <T> Carousel(items: List<T>, key: (T) -> Any, card: @Composable (T) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        itemsIndexed(items, key = { index, item -> "$index-${key(item)}" }) { _, item ->
            card(item)
        }
    }
}

/**
 * A song in a list. Bigger artwork and a full-height touch target than the
 * stock list item, and the one that is playing says so with a colour and a dot.
 */
@Composable
fun SongRow(
    track: Track,
    index: Int? = null,
    showArtwork: Boolean = true,
    onPlay: () -> Unit,
    onMenu: () -> Unit,
) {
    val playing = MuzikaPlayer.current?.id == track.id
    val accent = MaterialTheme.colorScheme.primary
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onPlay)
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (index != null) {
            Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) {
                if (playing) {
                    Icon(Icons.Rounded.GraphicEq, null, tint = accent,
                        modifier = Modifier.size(18.dp))
                } else {
                    Text("$index", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.width(8.dp))
        }
        if (showArtwork) {
            Cover(track.thumb, 56.dp)
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                track.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (playing) FontWeight.SemiBold else FontWeight.Normal,
                color = if (playing) accent else Color.Unspecified,
            )
            val detail = listOfNotNull(
                track.artist.ifEmpty { null },
                track.album?.ifEmpty { null },
                formatDuration(track.duration).ifEmpty { null },
            ).joinToString(" • ")
            if (detail.isNotEmpty()) {
                Text(detail, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        IconButton(onClick = onMenu) {
            Icon(Icons.Rounded.MoreVert, "More options for ${track.title}")
        }
    }
}

/** The head of a detail page: cover, name, and the two buttons that matter. */
@Composable
fun DetailHeader(
    title: String,
    subtitle: String,
    thumb: String?,
    circle: Boolean = false,
    playing: Boolean = false,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Cover(thumb, 200.dp, circle)
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (subtitle.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(subtitle, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center, maxLines = 2,
                overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onPlay, modifier = Modifier.weight(1f).height(52.dp)) {
                Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null)
                Spacer(Modifier.width(8.dp))
                Text(if (playing) "Pause" else "Play")
            }
            OutlinedButton(onClick = onShuffle, modifier = Modifier.weight(1f).height(52.dp)) {
                Icon(Icons.Rounded.Shuffle, null)
                Spacer(Modifier.width(8.dp))
                Text("Shuffle")
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

/** Nothing to show, and something to do about it. */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    message: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        Modifier.fillMaxWidth().padding(40.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, null, modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(20.dp))
            Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}

/** "1 song", not "1 songs". */
fun songCount(count: Int): String = if (count == 1) "1 song" else "$count songs"

@Composable
fun LoadingRow() {
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}
