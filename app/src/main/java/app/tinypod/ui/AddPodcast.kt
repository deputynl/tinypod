package app.tinypod.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.tinypod.TinypodApp
import app.tinypod.data.PodcastRepository
import app.tinypod.feed.PodcastSearch
import app.tinypod.feed.SearchResult
import app.tinypod.theme.TinypodTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

data class AddPodcastState(
  val query: String = "",
  val results: List<SearchResult> = emptyList(),
  val searching: Boolean = false,
  /** Feed URL currently being subscribed to. */
  val subscribing: String? = null,
  val error: String? = null,
  /** Set once a subscription succeeded; the screen navigates to it. */
  val subscribedId: Long? = null,
)

class AddPodcastViewModel(private val repository: PodcastRepository) : ViewModel() {
  var state by mutableStateOf(AddPodcastState())
    private set

  private var searchJob: Job? = null

  fun onQueryChange(query: String) {
    state = state.copy(query = query, error = null)
  }

  fun search() {
    val term = state.query.trim()
    if (term.isEmpty() || PodcastRepository.looksLikeUrl(term)) return
    searchJob?.cancel()
    searchJob =
      viewModelScope.launch {
        state = state.copy(searching = true, error = null)
        state =
          try {
            val results = PodcastSearch.search(term)
            state.copy(results = results, searching = false, error = if (results.isEmpty()) "No podcasts found for \"$term\"" else null)
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            state.copy(searching = false, error = "Search failed: ${e.message}")
          }
      }
  }

  fun subscribe(feedUrl: String) {
    if (state.subscribing != null) return
    viewModelScope.launch {
      state = state.copy(subscribing = feedUrl, error = null)
      state =
        try {
          state.copy(subscribing = null, subscribedId = repository.subscribe(feedUrl).id)
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          state.copy(subscribing = null, error = "Couldn't subscribe: ${e.message}")
        }
    }
  }

  companion object {
    val Factory = viewModelFactory { initializer { AddPodcastViewModel((this[APPLICATION_KEY] as TinypodApp).repository) } }
  }
}

@Composable
fun AddPodcastScreen(onSubscribed: (Long) -> Unit, vm: AddPodcastViewModel = viewModel(factory = AddPodcastViewModel.Factory)) {
  val state = vm.state
  LaunchedEffect(state.subscribedId) { state.subscribedId?.let(onSubscribed) }
  AddPodcastContent(state, vm::onQueryChange, vm::search, vm::subscribe)
}

@Composable
fun AddPodcastContent(
  state: AddPodcastState,
  onQueryChange: (String) -> Unit,
  onSearch: () -> Unit,
  onSubscribe: (feedUrl: String) -> Unit,
) {
  val isUrl = PodcastRepository.looksLikeUrl(state.query)
  Column(Modifier.fillMaxSize()) {
    OutlinedTextField(
      value = state.query,
      onValueChange = onQueryChange,
      modifier = Modifier.fillMaxWidth().padding(16.dp),
      label = { Text("Search podcasts or paste a feed URL") },
      leadingIcon = { Icon(if (isUrl) Icons.Filled.RssFeed else Icons.Filled.Search, contentDescription = null) },
      singleLine = true,
      keyboardOptions = KeyboardOptions(imeAction = if (isUrl) ImeAction.Go else ImeAction.Search),
      keyboardActions = KeyboardActions(onSearch = { onSearch() }, onGo = { onSubscribe(state.query) }),
    )
    if (state.searching) LinearProgressIndicator(Modifier.fillMaxWidth())
    state.error?.let {
      Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
    }
    LazyColumn(Modifier.fillMaxSize()) {
      if (isUrl) {
        item {
          SubscribeRow(title = "Subscribe to this feed", subtitle = state.query.trim(), busy = state.subscribing != null) { onSubscribe(state.query) }
          HorizontalDivider()
        }
      }
      items(state.results, key = { it.feedUrl }) { result ->
        SubscribeRow(result.title, result.author, busy = state.subscribing == result.feedUrl) { onSubscribe(result.feedUrl) }
        HorizontalDivider()
      }
    }
  }
}

@Composable
private fun SubscribeRow(title: String, subtitle: String?, busy: Boolean, onClick: () -> Unit) {
  ListItem(
    modifier = Modifier.clickable(enabled = !busy, onClick = onClick),
    headlineContent = { Text(title, maxLines = 2) },
    supportingContent = subtitle?.let { { Text(it, maxLines = 1) } },
    trailingContent = {
      Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
        if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        else Icon(Icons.Filled.Add, contentDescription = "Subscribe")
      }
    },
  )
}

@Preview(showBackground = true)
@Composable
private fun AddPodcastPreview() =
  TinypodTheme {
    AddPodcastContent(
      state =
        AddPodcastState(
          query = "history",
          results =
            listOf(
              SearchResult("The Rest Is History", "Goalhanger", "https://a.example/feed", null),
              SearchResult("Hardcore History", "Dan Carlin", "https://b.example/feed", null),
            ),
          subscribing = "https://b.example/feed",
        ),
      onQueryChange = {},
      onSearch = {},
      onSubscribe = {},
    )
  }
