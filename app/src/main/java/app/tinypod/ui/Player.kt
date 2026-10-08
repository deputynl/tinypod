package app.tinypod.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import app.tinypod.player.LocalPlayer
import app.tinypod.player.NowPlaying
import app.tinypod.player.PlaybackService.Companion.SPEEDS
import app.tinypod.player.PlaybackService.Companion.formatSpeed
import app.tinypod.theme.ArtworkTheme
import app.tinypod.theme.TinypodTheme


/** Callbacks from the player UI; the stateful wrappers route them to the PlayerConnection. */
data class PlayerControls(
  val onPlayPause: () -> Unit = {},
  val onSkipBack: () -> Unit = {},
  val onSkipForward: () -> Unit = {},
  val onSeek: (Long) -> Unit = {},
  val onSpeed: (Float) -> Unit = {},
)

@Composable
private fun rememberPlayerControls(): PlayerControls {
  val player = LocalPlayer.current
  return remember(player) {
    PlayerControls(player::togglePlayPause, { player.skipBack() }, { player.skipForward() }, { player.seekTo(it) }, player::setSpeed)
  }
}

@Composable
fun MiniPlayer(onOpen: () -> Unit) {
  val nowPlaying by LocalPlayer.current.nowPlaying.collectAsState()
  val np = nowPlaying ?: return
  ArtworkTheme(np.artworkUrl) { MiniPlayerContent(np, rememberPlayerControls(), onOpen) }
}

@Composable
fun MiniPlayerContent(np: NowPlaying, controls: PlayerControls, onOpen: () -> Unit) {
  Surface(tonalElevation = 3.dp) {
    Column {
      LinearProgressIndicator(progress = { np.progress }, modifier = Modifier.fillMaxWidth().height(2.dp), drawStopIndicator = {})
      Row(Modifier.clickable(onClick = onOpen).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Artwork(np.artworkUrl, Modifier.size(44.dp))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
          Text(np.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
          Text(np.podcastTitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        IconButton(onClick = controls.onPlayPause) {
          Icon(if (np.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = if (np.isPlaying) "Pause" else "Play")
        }
      }
    }
  }
}

@Composable
fun FullPlayerScreen() {
  val nowPlaying by LocalPlayer.current.nowPlaying.collectAsState()
  val np = nowPlaying ?: return EmptyState("Nothing playing.")
  ArtworkTheme(np.artworkUrl) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
      Box(Modifier.statusBarsPadding()) { FullPlayerContent(np, rememberPlayerControls()) }
    }
  }
}

/**
 * Fits any window without scrolling: the controls take their natural height and the artwork gets
 * whatever is left. Wide windows (landscape, unfolded foldables) put the artwork beside the controls.
 */
@Composable
fun FullPlayerContent(np: NowPlaying, controls: PlayerControls) {
  BoxWithConstraints(Modifier.fillMaxSize()) {
    val wide = maxWidth > maxHeight
    val short = maxHeight < 480.dp
    val pad = if (short) 16.dp else 24.dp
    val gap = if (short) 8.dp else 16.dp
    when {
      wide -> {
        val artSize = min(maxHeight - pad * 2, maxWidth * 0.45f)
        Row(Modifier.fillMaxSize().padding(pad), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(pad)) {
          ViewableArtwork(np.artworkUrl, Modifier.size(artSize))
          // Scrolls only as a last resort, in windows too short for the controls.
          Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(gap),
          ) {
            PlayerInfo(np, maxTitleLines = 2)
            PlayerControlsSection(np, controls)
          }
        }
      }
      maxHeight < 400.dp -> {
        // Too short for artwork as well (e.g. split screen): controls only.
        Column(
          Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(pad),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(gap),
        ) {
          PlayerInfo(np, maxTitleLines = 2)
          PlayerControlsSection(np, controls)
        }
      }
      else -> {
        Column(Modifier.fillMaxSize().padding(pad), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(gap)) {
          // The largest square that fits in the space the controls leave.
          Box(Modifier.weight(1f).fillMaxWidth(0.85f), contentAlignment = Alignment.Center) {
            ViewableArtwork(np.artworkUrl, Modifier.aspectRatio(1f))
          }
          PlayerInfo(np, maxTitleLines = if (short) 2 else 3)
          PlayerControlsSection(np, controls)
        }
      }
    }
  }
}

@Composable
private fun PlayerInfo(np: NowPlaying, maxTitleLines: Int) {
  Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Text(np.title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, maxLines = maxTitleLines, overflow = TextOverflow.Ellipsis)
    Text(np.podcastTitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
  }
}

@Composable
private fun PlayerControlsSection(np: NowPlaying, controls: PlayerControls) {
  // While dragging, show the thumb where the finger is rather than fighting position updates.
  var dragPosition by remember { mutableStateOf<Float?>(null) }
  val duration = np.durationMs ?: 0L
  val shownPosition = dragPosition?.let { (it * duration).toLong() } ?: np.positionMs

  Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Column {
      Slider(
        value = dragPosition ?: np.progress,
        onValueChange = { dragPosition = it },
        onValueChangeFinished = {
          dragPosition?.let { controls.onSeek((it * duration).toLong()) }
          dragPosition = null
        },
        enabled = duration > 0,
      )
      Row(Modifier.fillMaxWidth()) {
        Text(formatClock(shownPosition), style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.weight(1f))
        Text(if (duration > 0) "-" + formatClock(duration - shownPosition) else "--:--", style = MaterialTheme.typography.labelMedium)
      }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
      IconButton(onClick = controls.onSkipBack, modifier = Modifier.size(56.dp)) {
        Icon(Icons.Filled.Replay10, contentDescription = "Back 10 seconds", modifier = Modifier.size(36.dp))
      }
      FilledIconButton(onClick = controls.onPlayPause, modifier = Modifier.size(72.dp)) {
        Icon(
          if (np.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
          contentDescription = if (np.isPlaying) "Pause" else "Play",
          modifier = Modifier.size(40.dp),
        )
      }
      IconButton(onClick = controls.onSkipForward, modifier = Modifier.size(56.dp)) {
        Icon(Icons.Filled.Forward30, contentDescription = "Forward 30 seconds", modifier = Modifier.size(36.dp))
      }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      SPEEDS.forEach { speed ->
        FilterChip(selected = np.speed == speed, onClick = { controls.onSpeed(speed) }, label = { Text(formatSpeed(speed)) })
      }
    }
    if (np.isBuffering) Text("Buffering…", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

private val NowPlaying.progress: Float
  get() = durationMs?.takeIf { it > 0 }?.let { (positionMs.toFloat() / it).coerceIn(0f, 1f) } ?: 0f

private fun formatClock(ms: Long): String {
  val total = (ms.coerceAtLeast(0) / 1000)
  val h = total / 3600
  val m = total % 3600 / 60
  val s = total % 60
  return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}


private val previewNowPlaying =
  NowPlaying(
    episodeId = 1,
    title = "Episode 40: A reasonably long episode title that wraps",
    podcastTitle = "The Daily Thing",
    artworkUrl = null,
    isPlaying = true,
    isBuffering = false,
    positionMs = 754_000,
    durationMs = 2_710_000,
    speed = 1.25f,
  )

@Preview(showBackground = true)
@Composable
private fun MiniPlayerPreview() = TinypodTheme { MiniPlayerContent(previewNowPlaying, PlayerControls(), onOpen = {}) }

@Preview(showBackground = true, heightDp = 800)
@Composable
private fun FullPlayerPreview() = TinypodTheme { FullPlayerContent(previewNowPlaying, PlayerControls()) }
