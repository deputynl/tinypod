package app.tinypod.ui

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tinypod.data.Episode
import app.tinypod.data.EpisodeWithPodcast
import app.tinypod.data.Podcast
import app.tinypod.theme.TinypodTheme

// Stateful entry points, wired to the ViewModel.

@Composable
fun NewEpisodesScreen(vm: TabsViewModel, onAddPodcast: () -> Unit) {
  val rows by vm.newEpisodes.collectAsStateWithLifecycle()
  val podcasts by vm.podcasts.collectAsStateWithLifecycle()
  val refreshing by vm.refreshing.collectAsStateWithLifecycle()
  val failures by vm.refreshFailures.collectAsStateWithLifecycle()
  val snackbar = remember { SnackbarHostState() }

  LaunchedEffect(failures) {
    if (failures > 0) {
      snackbar.showSnackbar(if (failures == 1) "1 feed couldn't be refreshed" else "$failures feeds couldn't be refreshed")
      vm.refreshFailuresShown()
    }
  }

  Box(Modifier.fillMaxSize()) {
    PullToRefreshBox(isRefreshing = refreshing, onRefresh = { vm.refresh() }) {
      if (podcasts.isEmpty()) {
        EmptyState("No podcasts yet.", action = "Add a podcast", onAction = onAddPodcast, scrollable = true)
      } else {
        EpisodeList(rows, empty = "You're all caught up.", scrollableEmpty = true, currentId = currentEpisodeId(), onEvent = rememberEpisodeEventHandler())
      }
    }
    SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
  }
}

@Composable
fun QueueScreen(vm: TabsViewModel) {
  val rows by vm.queue.collectAsStateWithLifecycle()
  EpisodeList(rows, empty = "Your queue is empty.", inQueue = true, currentId = currentEpisodeId(), onEvent = rememberEpisodeEventHandler())
}

@Composable
fun DownloadsScreen(vm: TabsViewModel) {
  val rows by vm.downloads.collectAsStateWithLifecycle()
  val size by vm.downloadsSize.collectAsStateWithLifecycle()
  val done = rows.count { it.episode.isDownloaded }
  Column(Modifier.fillMaxSize()) {
    if (done > 0) {
      Text(
        "${if (done == 1) "1 episode" else "$done episodes"} · ${Formatter.formatShortFileSize(LocalContext.current, size)}",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
      )
    }
    EpisodeList(
      rows,
      empty = "No downloaded episodes.\nUse the download button on any episode.",
      inDownloads = true,
      currentId = currentEpisodeId(),
      onEvent = rememberEpisodeEventHandler(),
    )
  }
}

// Stateless content, previewable without a database.

@Composable
internal fun EmptyState(
  text: String,
  modifier: Modifier = Modifier,
  action: String? = null,
  onAction: () -> Unit = {},
  scrollable: Boolean = false,
) {
  // Pull-to-refresh only reacts to scrollable content, hence the optional verticalScroll.
  val scroll = if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier
  Box(modifier.fillMaxSize().then(scroll).padding(32.dp), contentAlignment = Alignment.Center) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
      Text(text, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
      if (action != null) Button(onClick = onAction) { Text(action) }
    }
  }
}

// Previews

internal val samplePodcast = Podcast(id = 1, feedUrl = "https://example.com/feed", title = "The Daily Thing", author = "Some Network")

internal val previewEpisodeRows =
  List(4) { i ->
    EpisodeWithPodcast(
      Episode(
        id = i.toLong(),
        podcastId = 1,
        guid = "g$i",
        title = "Episode ${40 - i}: A reasonably long episode title",
        audioUrl = "",
        publishedAt = 1_790_000_000_000 - i * 86_400_000L,
        durationMs = 2_400_000L + i * 300_000L,
      ),
      podcastTitle = samplePodcast.title,
      artworkUrl = null,
    )
  }

@Preview(showBackground = true)
@Composable
private fun EpisodeListPreview() = TinypodTheme { EpisodeList(previewEpisodeRows, empty = "") }

@Preview(showBackground = true)
@Composable
private fun EmptyWithActionPreview() = TinypodTheme { EmptyState("No podcasts yet.", action = "Add a podcast") }
