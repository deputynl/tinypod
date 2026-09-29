package app.tinypod.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.tinypod.TinypodApp
import app.tinypod.data.Episode
import app.tinypod.data.EpisodeWithPodcast
import app.tinypod.player.LocalPlayer
import coil3.compose.AsyncImage
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

sealed interface EpisodeEvent {
  val episodeId: Long

  data class Play(override val episodeId: Long) : EpisodeEvent

  data class PlayNext(override val episodeId: Long) : EpisodeEvent

  data class AddToQueue(override val episodeId: Long) : EpisodeEvent

  data class RemoveFromQueue(override val episodeId: Long) : EpisodeEvent

  data class SetPlayed(override val episodeId: Long, val played: Boolean) : EpisodeEvent
}

/** Handles [EpisodeEvent]s from any episode list: playback goes to the player, the rest to the database. */
@Composable
fun rememberEpisodeEventHandler(): (EpisodeEvent) -> Unit {
  val player = LocalPlayer.current
  val actions = (LocalContext.current.applicationContext as TinypodApp).episodeActions
  val scope = rememberCoroutineScope()
  return remember(player, actions) {
    { event ->
      when (event) {
        is EpisodeEvent.Play -> player.play(event.episodeId)
        is EpisodeEvent.PlayNext -> scope.launch { actions.playNext(event.episodeId) }
        is EpisodeEvent.AddToQueue -> scope.launch { actions.addToQueue(event.episodeId) }
        is EpisodeEvent.RemoveFromQueue -> scope.launch { actions.removeFromQueue(event.episodeId) }
        is EpisodeEvent.SetPlayed -> scope.launch { actions.setPlayed(event.episodeId, event.played) }
      }
    }
  }
}

/** The id of the episode loaded in the player, for highlighting it in lists. */
@Composable
fun currentEpisodeId(): Long? {
  val nowPlaying by LocalPlayer.current.nowPlaying.collectAsState()
  return nowPlaying?.episodeId
}

@Composable
fun EpisodeList(
  rows: List<EpisodeWithPodcast>,
  empty: String,
  modifier: Modifier = Modifier,
  showPodcast: Boolean = true,
  inQueue: Boolean = false,
  scrollableEmpty: Boolean = false,
  currentId: Long? = null,
  onEvent: (EpisodeEvent) -> Unit = {},
) {
  if (rows.isEmpty()) return EmptyState(empty, modifier, scrollable = scrollableEmpty)
  LazyColumn(modifier.fillMaxSize()) {
    items(rows, key = { it.episode.id }) { row ->
      EpisodeRow(row, showPodcast, inQueue, isCurrent = row.episode.id == currentId, onEvent)
      HorizontalDivider()
    }
  }
}

@Composable
private fun EpisodeRow(row: EpisodeWithPodcast, showPodcast: Boolean, inQueue: Boolean, isCurrent: Boolean, onEvent: (EpisodeEvent) -> Unit) {
  val e = row.episode
  var menuOpen by remember { mutableStateOf(false) }
  ListItem(
    modifier = Modifier.clickable { onEvent(EpisodeEvent.Play(e.id)) },
    leadingContent = if (showPodcast) ({ Artwork(row.artworkUrl, Modifier.size(48.dp)) }) else null,
    overlineContent = if (showPodcast) ({ Text(row.podcastTitle, maxLines = 1) }) else null,
    headlineContent = {
      Text(
        e.title,
        maxLines = 2,
        fontWeight = if (isCurrent) FontWeight.SemiBold else null,
        color = if (e.isPlayed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
      )
    },
    supportingContent = { Text(episodeMeta(e)) },
    trailingContent = {
      Row(verticalAlignment = Alignment.CenterVertically) {
        if (isCurrent) Icon(Icons.Filled.GraphicEq, contentDescription = "Now playing", tint = MaterialTheme.colorScheme.primary)
        Box {
          IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Episode actions") }
          DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            val pick = { event: EpisodeEvent ->
              menuOpen = false
              onEvent(event)
            }
            DropdownMenuItem(text = { Text("Play next") }, onClick = { pick(EpisodeEvent.PlayNext(e.id)) })
            if (inQueue) DropdownMenuItem(text = { Text("Remove from queue") }, onClick = { pick(EpisodeEvent.RemoveFromQueue(e.id)) })
            else DropdownMenuItem(text = { Text("Add to queue") }, onClick = { pick(EpisodeEvent.AddToQueue(e.id)) })
            if (e.isPlayed) DropdownMenuItem(text = { Text("Mark as unplayed") }, onClick = { pick(EpisodeEvent.SetPlayed(e.id, false)) })
            else DropdownMenuItem(text = { Text("Mark as played") }, onClick = { pick(EpisodeEvent.SetPlayed(e.id, true)) })
          }
        }
      }
    },
  )
}

@Composable
fun Artwork(url: String?, modifier: Modifier = Modifier) {
  val placeholder = ColorPainter(MaterialTheme.colorScheme.surfaceVariant)
  AsyncImage(
    model = url,
    contentDescription = null,
    placeholder = placeholder,
    error = placeholder,
    fallback = placeholder,
    contentScale = ContentScale.Crop,
    modifier = modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
  )
}

private fun episodeMeta(e: Episode): String {
  val date = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(e.publishedAt))
  val status =
    when {
      e.isPlayed -> "Played"
      e.positionMs > 0 && e.durationMs != null -> "${formatMinutes(e.durationMs - e.positionMs)} left"
      e.durationMs != null -> formatMinutes(e.durationMs)
      else -> null
    }
  return if (status != null) "$date · $status" else date
}

private fun formatMinutes(ms: Long): String {
  val minutes = ((ms + 30_000) / 60_000).coerceAtLeast(1)
  return if (minutes < 60) "$minutes min" else "${minutes / 60} h ${minutes % 60} min"
}
