package app.tinypod.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.tinypod.TinypodApp
import app.tinypod.data.PodcastRepository
import app.tinypod.data.TinypodDatabase
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Exposes the lists behind the five top-level tabs, and feed refreshing. */
class TabsViewModel(private val db: TinypodDatabase, private val repository: PodcastRepository) : ViewModel() {
  val newEpisodes = db.episodeDao().observeNew().state()
  val folders = db.folderDao().observeAll().state()
  val podcasts = db.podcastDao().observeAll().state()

  /** New episodes per podcast id, for the library's badges. */
  val newCounts: StateFlow<Map<Long, Int>> =
    db.episodeDao().observeNewCounts().map { list -> list.associate { it.podcastId to it.count } }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())
  val queue = db.queueDao().observe().state()

  fun reorderQueue(episodeIds: List<Long>) {
    viewModelScope.launch { db.queueDao().reorder(episodeIds) }
  }

  /** One episode, kept up to date (e.g. the one playing, for the Queue tab). */
  fun episode(id: Long) = db.episodeDao().observeWithPodcast(id)
  val history = db.episodeDao().observeHistory().state()
  val downloads = db.episodeDao().observeDownloads().state()

  /** Space taken by finished downloads, in bytes. */
  val downloadsSize: StateFlow<Long> =
    downloads
      .map { rows -> rows.sumOf { row -> row.episode.localFilePath?.let { File(it).length() } ?: 0L } }
      .flowOn(Dispatchers.IO)
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

  private val _refreshing = MutableStateFlow(false)
  val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

  /** Number of feeds that failed in the last manual refresh, shown once then cleared. */
  private val _refreshFailures = MutableStateFlow(0)
  val refreshFailures: StateFlow<Int> = _refreshFailures.asStateFlow()

  init {
    // Refresh on app open, skipping feeds fetched very recently (e.g. by the background job).
    refresh(olderThanMs = TimeUnit.MINUTES.toMillis(15), reportFailures = false)
  }

  fun refresh(olderThanMs: Long = 0, reportFailures: Boolean = true) {
    if (_refreshing.value) return
    viewModelScope.launch {
      _refreshing.value = true
      try {
        val failures = repository.refreshAll(olderThanMs)
        if (reportFailures) _refreshFailures.value = failures
      } finally {
        _refreshing.value = false
      }
    }
  }

  fun refreshFailuresShown() {
    _refreshFailures.value = 0
  }

  private fun <T> Flow<List<T>>.state(): StateFlow<List<T>> =
    stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

  companion object {
    val Factory = viewModelFactory {
      initializer {
        val app = this[APPLICATION_KEY] as TinypodApp
        TabsViewModel(app.database, app.repository)
      }
    }
  }
}
