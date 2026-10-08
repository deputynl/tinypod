package app.tinypod.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalContext
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
import app.tinypod.data.Podcast
import app.tinypod.theme.ArtworkTheme
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
  private val actions = app.libraryActions
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
    viewModelScope.launch { actions.moveToFolder(podcastId, folderId) }
  }

  fun moveToNewFolder(name: String) {
    viewModelScope.launch { actions.moveToNewFolder(podcastId, name) }
  }

  suspend fun unsubscribe() {
    podcast.value?.let { actions.unsubscribe(it) }
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

  // The page takes its colours from the podcast's artwork, dialogs included.
  ArtworkTheme(podcast?.artworkUrl) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
      PodcastContent(
        podcast,
        results,
        totalEpisodes = episodes.size,
        query = query,
        onQueryChange = { vm.query.value = it },
        folderName = folders.firstOrNull { it.id == podcast?.folderId }?.name,
        onFolderClick = { pickFolder = true },
        onUnsubscribe = { confirmUnsubscribe = true },
        modifier = Modifier.statusBarsPadding(),
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
  val searching = query.isNotBlank()
  val searchIndex = if (podcast != null) 1 else 0
  // A changed query shows its results right under the search bar (unless the header is still in
  // view). Only on an actual change, so coming back to this screen keeps its scroll position.
  val listState = rememberLazyListState()
  var scrolledFor by rememberSaveable { mutableStateOf(query) }
  LaunchedEffect(query) {
    if (query != scrolledFor) {
      scrolledFor = query
      if (listState.firstVisibleItemIndex >= searchIndex) listState.scrollToItem(searchIndex)
    }
  }
  // The header scrolls away with the episodes; the search bar then sticks to the top.
  EpisodeList(
    episodes,
    empty = if (searching) "No episodes match “${query.trim()}”." else "This feed has no episodes.",
    modifier = modifier,
    showPodcast = false,
    currentId = currentId,
    listState = listState,
    header = {
      if (podcast != null) item(key = "header") { PodcastHeader(podcast, folderName, onFolderClick, onUnsubscribe) }
      if (totalEpisodes > 0) {
        stickyHeader(key = "search") { EpisodeSearchBar(query, onQueryChange, if (searching) "${episodes.size} of $totalEpisodes episodes" else null) }
      }
    },
    onEvent = onEvent,
  )
}

@Composable
private fun PodcastHeader(podcast: Podcast, folderName: String?, onFolderClick: () -> Unit, onUnsubscribe: () -> Unit) {
  val context = LocalContext.current
  Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
    ViewableArtwork(podcast.artworkUrl, Modifier.size(96.dp))
    Column(Modifier.padding(start = 16.dp)) {
      Text(podcast.title, style = MaterialTheme.typography.headlineSmall)
      podcast.author?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
      Row {
        TextButton(onClick = onFolderClick, contentPadding = PaddingValues(0.dp)) {
          Icon(Icons.Filled.Folder, contentDescription = null, Modifier.size(18.dp))
          Text(folderName ?: "Add to folder", Modifier.padding(start = 6.dp))
        }
        TextButton(onClick = onUnsubscribe, contentPadding = PaddingValues(horizontal = 16.dp)) { Text("Unsubscribe") }
        IconButton(onClick = { share(context, podcastShareText(podcast)) }) { Icon(Icons.Filled.Share, contentDescription = "Share podcast", tint = MaterialTheme.colorScheme.primary) }
      }
    }
  }
}

@Composable
private fun EpisodeSearchBar(query: String, onQueryChange: (String) -> Unit, resultCount: String?) {
  val keyboard = LocalSoftwareKeyboardController.current
  // Opaque, since the episodes scroll underneath it.
  Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(horizontal = 16.dp).padding(top = 4.dp, bottom = 8.dp)) {
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
      modifier = Modifier.fillMaxWidth(),
    )
    if (resultCount != null) {
      Text(
        resultCount,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp),
      )
    }
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
