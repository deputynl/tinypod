package app.tinypod.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
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
import app.tinypod.data.Folder
import app.tinypod.data.Podcast
import app.tinypod.data.PodcastRepository
import app.tinypod.theme.TinypodTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PodcastViewModel(private val podcastId: Long, app: TinypodApp) : ViewModel() {
  private val repository: PodcastRepository = app.repository
  private val podcastDao = app.database.podcastDao()
  private val folderDao = app.database.folderDao()
  val podcast = podcastDao.observe(podcastId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
  val folders = folderDao.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
  val episodes =
    app.database.episodeDao().observeForPodcast(podcastId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

  /** The episode search text; blank shows every episode. */
  val query = MutableStateFlow("")
  val results =
    combine(episodes.map(::EpisodeSearch), query) { search, q -> search.search(q) }
      .flowOn(Dispatchers.Default)
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

  fun moveToFolder(folderId: Long?) {
    viewModelScope.launch { podcastDao.setFolder(podcastId, folderId) }
  }

  fun moveToNewFolder(name: String) {
    viewModelScope.launch { podcastDao.setFolder(podcastId, folderDao.insert(Folder(name = name.trim()))) }
  }

  suspend fun unsubscribe() {
    podcast.value?.let { repository.unsubscribe(it) }
  }

  companion object {
    fun factory(podcastId: Long) = viewModelFactory { initializer { PodcastViewModel(podcastId, this[APPLICATION_KEY] as TinypodApp) } }
  }
}

@Composable
fun PodcastScreen(podcastId: Long, onUnsubscribed: () -> Unit) {
  val vm: PodcastViewModel = viewModel(key = "podcast-$podcastId", factory = PodcastViewModel.factory(podcastId))
  val podcast by vm.podcast.collectAsStateWithLifecycle()
  val episodes by vm.episodes.collectAsStateWithLifecycle()
  val results by vm.results.collectAsStateWithLifecycle()
  val query by vm.query.collectAsStateWithLifecycle()
  val folders by vm.folders.collectAsStateWithLifecycle()
  val scope = rememberCoroutineScope()
  var confirmUnsubscribe by remember { mutableStateOf(false) }
  var pickFolder by remember { mutableStateOf(false) }
  var newFolder by remember { mutableStateOf(false) }

  PodcastContent(
    podcast,
    results,
    totalEpisodes = episodes.size,
    query = query,
    onQueryChange = { vm.query.value = it },
    folderName = folders.firstOrNull { it.id == podcast?.folderId }?.name,
    onFolderClick = { pickFolder = true },
    onUnsubscribe = { confirmUnsubscribe = true },
    currentId = currentEpisodeId(),
    onEvent = rememberEpisodeEventHandler(),
  )

  if (pickFolder) {
    MoveToFolderDialog(
      folders = folders,
      currentFolderId = podcast?.folderId,
      onPick = { vm.moveToFolder(it); pickFolder = false },
      onNewFolder = { pickFolder = false; newFolder = true },
      onDismiss = { pickFolder = false },
    )
  }
  if (newFolder) {
    FolderNameDialog(
      title = "New folder",
      confirm = "Create",
      folders = folders,
      onConfirm = { vm.moveToNewFolder(it); newFolder = false },
      onDismiss = { newFolder = false },
    )
  }

  if (confirmUnsubscribe) {
    AlertDialog(
      onDismissRequest = { confirmUnsubscribe = false },
      title = { Text("Unsubscribe?") },
      text = { Text("This removes the podcast and all its episodes, including playback progress.") },
      confirmButton = {
        TextButton(
          onClick = {
            confirmUnsubscribe = false
            scope.launch {
              vm.unsubscribe()
              onUnsubscribed()
            }
          }
        ) {
          Text("Unsubscribe")
        }
      },
      dismissButton = { TextButton(onClick = { confirmUnsubscribe = false }) { Text("Cancel") } },
    )
  }
}

@Composable
fun PodcastContent(
  podcast: Podcast?,
  episodes: List<EpisodeWithPodcast>,
  totalEpisodes: Int,
  query: String,
  onQueryChange: (String) -> Unit,
  folderName: String?,
  onFolderClick: () -> Unit,
  onUnsubscribe: () -> Unit,
  modifier: Modifier = Modifier,
  currentId: Long? = null,
  onEvent: (EpisodeEvent) -> Unit = {},
) {
  Column(modifier.fillMaxSize()) {
    if (podcast != null) {
      Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Artwork(podcast.artworkUrl, Modifier.size(96.dp))
        Column(Modifier.padding(start = 16.dp)) {
        Text(podcast.title, style = MaterialTheme.typography.headlineSmall)
        podcast.author?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Row {
          TextButton(onClick = onFolderClick, contentPadding = PaddingValues(0.dp)) {
            Icon(Icons.Filled.Folder, contentDescription = null, Modifier.size(18.dp))
            Text(folderName ?: "Add to folder", Modifier.padding(start = 6.dp))
          }
          TextButton(onClick = onUnsubscribe, contentPadding = PaddingValues(horizontal = 16.dp)) { Text("Unsubscribe") }
        }
        }
      }
    }
    val searching = query.isNotBlank()
    if (totalEpisodes > 0) {
      val keyboard = LocalSoftwareKeyboardController.current
      OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text("Search episodes") },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon =
          if (query.isNotEmpty()) ({ IconButton(onClick = { onQueryChange("") }) { Icon(Icons.Filled.Close, contentDescription = "Clear search") } })
          else null,
        singleLine = true,
        shape = RoundedCornerShape(28.dp),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 8.dp),
      )
      if (searching) {
        Text(
          "${episodes.size} of $totalEpisodes episodes",
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
      }
    }
    // New results start at the top, not wherever the previous list was scrolled to. Only on an actual
    // change of query, so coming back to this screen keeps its scroll position.
    val listState = rememberLazyListState()
    var scrolledFor by rememberSaveable { mutableStateOf(query) }
    LaunchedEffect(query) {
      if (query != scrolledFor) {
        scrolledFor = query
        listState.scrollToItem(0)
      }
    }
    EpisodeList(
      episodes,
      empty = if (searching) "No episodes match “${query.trim()}”." else "This feed has no episodes.",
      showPodcast = false,
      currentId = currentId,
      listState = listState,
      onEvent = onEvent,
    )
  }
}

@Preview(showBackground = true)
@Composable
private fun PodcastPreview() =
  TinypodTheme {
    PodcastContent(
      Podcast(id = 1, feedUrl = "", title = "The Daily Thing", author = "Some Network"),
      previewEpisodeRows,
      totalEpisodes = previewEpisodeRows.size,
      query = "",
      onQueryChange = {},
      folderName = "News",
      onFolderClick = {},
      onUnsubscribe = {},
    )
  }
