package app.tinypod.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.tinypod.TinypodApp
import app.tinypod.data.TinypodDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/** Exposes the lists behind the five top-level tabs. */
class TabsViewModel(db: TinypodDatabase) : ViewModel() {
  val newEpisodes = db.episodeDao().observeNew().state()
  val folders = db.folderDao().observeAll().state()
  val podcasts = db.podcastDao().observeAll().state()
  val queue = db.queueDao().observe().state()
  val history = db.episodeDao().observeHistory().state()
  val downloads = db.episodeDao().observeDownloaded().state()

  private fun <T> Flow<List<T>>.state(): StateFlow<List<T>> =
    stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

  companion object {
    val Factory = viewModelFactory { initializer { TabsViewModel((this[APPLICATION_KEY] as TinypodApp).database) } }
  }
}

