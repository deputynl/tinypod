package app.tinypod.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tinypod.data.Episode
import app.tinypod.data.EpisodeWithPodcast
import app.tinypod.data.Folder
import app.tinypod.data.Podcast
import app.tinypod.theme.TinypodTheme
import java.text.DateFormat
import java.util.Date

// Stateful entry points, wired to the ViewModel.

@Composable
fun NewEpisodesScreen(vm: TabsViewModel) {
  val rows by vm.newEpisodes.collectAsStateWithLifecycle()
  EpisodeList(rows, empty = "No new episodes.\nSubscribe to a podcast to get started.")
}

@Composable
fun LibraryScreen(vm: TabsViewModel) {
  val folders by vm.folders.collectAsStateWithLifecycle()
  val podcasts by vm.podcasts.collectAsStateWithLifecycle()
  LibraryContent(folders, podcasts)
}

@Composable
fun QueueScreen(vm: TabsViewModel) {
  val rows by vm.queue.collectAsStateWithLifecycle()
  EpisodeList(rows, empty = "Your queue is empty.")
}

@Composable
fun HistoryScreen(vm: TabsViewModel) {
  val rows by vm.history.collectAsStateWithLifecycle()
  EpisodeList(rows, empty = "Nothing played yet.")
}

@Composable
fun DownloadsScreen(vm: TabsViewModel) {
  val rows by vm.downloads.collectAsStateWithLifecycle()
  EpisodeList(rows, empty = "No downloaded episodes.")
}

// Stateless content, previewable without a database.

@Composable
fun EpisodeList(rows: List<EpisodeWithPodcast>, empty: String, modifier: Modifier = Modifier) {
  if (rows.isEmpty()) return EmptyState(empty, modifier)
  LazyColumn(modifier.fillMaxSize()) {
    items(rows, key = { it.episode.id }) { row ->
      ListItem(
        overlineContent = { Text(row.podcastTitle, maxLines = 1) },
        headlineContent = { Text(row.episode.title, maxLines = 2) },
        supportingContent = { Text(DateFormat.getDateInstance().format(Date(row.episode.publishedAt))) },
      )
      HorizontalDivider()
    }
  }
}

@Composable
fun LibraryContent(folders: List<Folder>, podcasts: List<Podcast>, modifier: Modifier = Modifier) {
  if (folders.isEmpty() && podcasts.isEmpty()) return EmptyState("No podcasts yet.", modifier)
  LazyColumn(modifier.fillMaxSize()) {
    items(folders, key = { "f${it.id}" }) { folder ->
      val count = podcasts.count { it.folderId == folder.id }
      ListItem(headlineContent = { Text(folder.name) }, supportingContent = { Text("$count podcasts") })
      HorizontalDivider()
    }
    items(podcasts.filter { it.folderId == null }, key = { "p${it.id}" }) { podcast ->
      ListItem(headlineContent = { Text(podcast.title) }, supportingContent = podcast.author?.let { { Text(it) } })
      HorizontalDivider()
    }
  }
}

@Composable
private fun EmptyState(text: String, modifier: Modifier = Modifier) {
  Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
    Text(text, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
  }
}

// Previews

private val samplePodcast = Podcast(id = 1, feedUrl = "https://example.com/feed", title = "The Daily Thing", author = "Some Network")

private val sampleRows =
  List(4) { i ->
    EpisodeWithPodcast(
      Episode(id = i.toLong(), podcastId = 1, guid = "g$i", title = "Episode ${40 - i}: A reasonably long episode title", audioUrl = "", publishedAt = 1_790_000_000_000 - i * 86_400_000L),
      podcastTitle = samplePodcast.title,
      artworkUrl = null,
    )
  }

@Preview(showBackground = true)
@Composable
private fun EpisodeListPreview() = TinypodTheme { EpisodeList(sampleRows, empty = "") }

@Preview(showBackground = true)
@Composable
private fun EpisodeListEmptyPreview() = TinypodTheme { EpisodeList(emptyList(), empty = "No new episodes.\nSubscribe to a podcast to get started.") }

@Preview(showBackground = true)
@Composable
private fun LibraryPreview() =
  TinypodTheme {
    LibraryContent(
      folders = listOf(Folder(id = 1, name = "News"), Folder(id = 2, name = "Comedy")),
      podcasts = listOf(samplePodcast.copy(folderId = 1), samplePodcast.copy(id = 2, title = "Unfiled Show", folderId = null)),
    )
  }
