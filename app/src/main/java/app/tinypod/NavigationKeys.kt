package app.tinypod

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable data object NewEpisodes : NavKey

@Serializable data object Library : NavKey

@Serializable data object Queue : NavKey

@Serializable data object History : NavKey

@Serializable data object Downloads : NavKey

@Serializable data object AddPodcast : NavKey

@Serializable data class PodcastDetail(val podcastId: Long) : NavKey

enum class Tab(val key: NavKey, val label: String, val icon: ImageVector) {
  New(NewEpisodes, "New", Icons.Filled.NewReleases),
  Library(app.tinypod.Library, "Library", Icons.Filled.VideoLibrary),
  Queue(app.tinypod.Queue, "Queue", Icons.AutoMirrored.Filled.QueueMusic),
  History(app.tinypod.History, "History", Icons.Filled.History),
  Downloads(app.tinypod.Downloads, "Downloads", Icons.Filled.DownloadForOffline),
}
