package app.tinypod.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.tinypod.TinypodApp
import app.tinypod.data.EpisodeWithPodcast
import app.tinypod.player.LocalPlayer
import app.tinypod.theme.ArtworkTheme
import app.tinypod.theme.TinypodTheme
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn

class EpisodeViewModel(episodeId: Long, app: TinypodApp) : ViewModel() {
  val episode = app.database.episodeDao().observeWithPodcast(episodeId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

  companion object {
    fun factory(episodeId: Long) = viewModelFactory { initializer { EpisodeViewModel(episodeId, this[APPLICATION_KEY] as TinypodApp) } }
  }
}

/** An episode's page: what it is, play / queue / download, and its show notes. */
@Composable
fun EpisodeScreen(episodeId: Long, onPodcastClick: (Long) -> Unit) {
  val vm: EpisodeViewModel = viewModel(key = "episode-$episodeId", factory = EpisodeViewModel.factory(episodeId))
  val row by vm.episode.collectAsStateWithLifecycle()
  val player = LocalPlayer.current
  val currentId = currentEpisodeId()
  val playing = playingId()
  val queued = LocalQueuedEpisodes.current
  val onEvent = rememberEpisodeEventHandler()

  ArtworkTheme(row?.artworkUrl) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
      val r = row ?: return@Surface
      EpisodeContent(
        r,
        isCurrent = currentId == episodeId,
        isPlaying = playing == episodeId,
        inQueue = episodeId in queued,
        onPodcastClick = { onPodcastClick(r.episode.podcastId) },
        onSeek = { ms -> player.play(episodeId, fromMs = ms) },
        onEvent = onEvent,
        modifier = Modifier.statusBarsPadding(),
      )
    }
  }
}

@Composable
fun EpisodeContent(
  row: EpisodeWithPodcast,
  isCurrent: Boolean,
  isPlaying: Boolean,
  inQueue: Boolean,
  onPodcastClick: () -> Unit,
  onSeek: (Long) -> Unit,
  onEvent: (EpisodeEvent) -> Unit,
  modifier: Modifier = Modifier,
) {
  BoxWithConstraints(modifier.fillMaxSize()) {
    // Wide windows (unfolded foldables, landscape): details on the left, show notes on the right.
    if (maxWidth >= 600.dp && maxWidth > maxHeight) {
      Row(Modifier.fillMaxSize().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
        Column(Modifier.width(340.dp).verticalScroll(rememberScrollState()).padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
          EpisodeHeader(row, onPodcastClick)
          EpisodeActions(row, isCurrent, isPlaying, inQueue, onEvent)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 16.dp)) {
          ShowNotesText(row.episode.description, row.episode.durationMs, onSeek)
        }
      }
    } else {
      Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        EpisodeHeader(row, onPodcastClick)
        EpisodeActions(row, isCurrent, isPlaying, inQueue, onEvent)
        HorizontalDivider()
        ShowNotesText(row.episode.description, row.episode.durationMs, onSeek)
      }
    }
  }
}

@Composable
private fun EpisodeHeader(row: EpisodeWithPodcast, onPodcastClick: () -> Unit) {
  val e = row.episode
  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Artwork(row.artworkUrl, Modifier.size(96.dp))
      Column(Modifier.padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
          "${row.podcastTitle} ›",
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.primary,
          modifier = Modifier.clickable(onClickLabel = "Open podcast", onClick = onPodcastClick),
        )
        Text(e.title, style = MaterialTheme.typography.titleLarge)
        Text(episodeMeta(e), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }
    val duration = e.durationMs
    if (e.positionMs > 0 && !e.isPlayed && duration != null && duration > 0) {
      LinearProgressIndicator(progress = { (e.positionMs.toFloat() / duration).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
    }
  }
}

@Composable
private fun EpisodeActions(row: EpisodeWithPodcast, isCurrent: Boolean, isPlaying: Boolean, inQueue: Boolean, onEvent: (EpisodeEvent) -> Unit) {
  val e = row.episode
  var menuOpen by remember { mutableStateOf(false) }
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Button(onClick = { onEvent(EpisodeEvent.PlayPause(e.id)) }, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
      Icon(if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = null, Modifier.size(ButtonDefaults.IconSize))
      Spacer(Modifier.width(ButtonDefaults.IconSpacing))
      Text(
        when {
          isPlaying -> "Pause"
          isCurrent || (e.positionMs > 0 && !e.isPlayed) -> "Resume"
          else -> "Play"
        }
      )
    }
    if (inQueue) {
      OutlinedButton(onClick = { onEvent(EpisodeEvent.RemoveFromQueue(e.id)) }, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
        Icon(Icons.Filled.Check, contentDescription = null, Modifier.size(ButtonDefaults.IconSize))
        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
        Text("In queue")
      }
    } else {
      OutlinedButton(onClick = { onEvent(EpisodeEvent.AddToQueue(e.id)) }, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
        Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = null, Modifier.size(ButtonDefaults.IconSize))
        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
        Text("Queue")
      }
    }
    DownloadButton(e, deletable = false, onEvent)
    Box {
      IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More actions") }
      DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
        val pick = { event: EpisodeEvent ->
          menuOpen = false
          onEvent(event)
        }
        if (!isCurrent) DropdownMenuItem(text = { Text("Play next") }, onClick = { pick(EpisodeEvent.PlayNext(e.id)) })
        if (e.isPlayed) DropdownMenuItem(text = { Text("Mark as unplayed") }, onClick = { pick(EpisodeEvent.SetPlayed(e.id, false)) })
        else DropdownMenuItem(text = { Text("Mark as played") }, onClick = { pick(EpisodeEvent.SetPlayed(e.id, true)) })
        if (e.isDownloaded) DropdownMenuItem(text = { Text("Delete download") }, onClick = { pick(EpisodeEvent.DeleteDownload(e.id)) })
      }
    }
  }
}

/** Show notes as rich text: links open in the browser, timestamps like 12:34 jump to that point. */
@Composable
private fun ShowNotesText(notes: String?, durationMs: Long?, onSeek: (Long) -> Unit) {
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text("Show notes", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    if (notes.isNullOrBlank()) {
      Text("This episode has no show notes.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
      return
    }
    val linkColor = MaterialTheme.colorScheme.primary
    val seek by rememberUpdatedState(onSeek)
    val text =
      remember(notes, durationMs, linkColor) {
        val styles = TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
        val html = AnnotatedString.fromHtml(ShowNotes.toHtml(notes), linkStyles = styles)
        val trimmed = html.subSequence(0, html.text.trimEnd().length) // paragraphs leave trailing blank lines
        buildAnnotatedString {
          append(trimmed)
          for (t in ShowNotes.timestamps(trimmed.text, durationMs)) {
            val end = t.range.last + 1
            if (trimmed.getLinkAnnotations(t.range.first, end).isNotEmpty()) continue // already a link
            addLink(LinkAnnotation.Clickable("seek:${t.positionMs}", styles) { seek(t.positionMs) }, t.range.first, end)
          }
        }
      }
    Text(text, style = MaterialTheme.typography.bodyLarge)
  }
}

// Previews

@Preview(showBackground = true)
@Composable
private fun EpisodePreview() =
  TinypodTheme {
    val row = previewEpisodeRows[0].let { it.copy(episode = it.episode.copy(positionMs = 600_000, description = "<p>A look at <b>grass</b>. More at <a href=\"https://example.com\">example.com</a>.</p><p>0:00 Intro<br>12:34 The experiment</p>")) }
    EpisodeContent(row, isCurrent = false, isPlaying = false, inQueue = true, onPodcastClick = {}, onSeek = {}, onEvent = {})
  }
