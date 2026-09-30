package app.tinypod

import android.app.Application
import app.tinypod.data.Downloads
import app.tinypod.data.EpisodeActions
import app.tinypod.data.LibraryActions
import app.tinypod.data.PodcastRepository
import app.tinypod.data.TinypodDatabase
import app.tinypod.feed.RefreshWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Holds app-wide singletons; manual wiring instead of a DI framework. */
class TinypodApp : Application() {
  /** For work that should outlive any one screen, such as download bookkeeping. */
  val scope = CoroutineScope(SupervisorJob())
  val database: TinypodDatabase by lazy { TinypodDatabase.create(this) }
  val downloads: Downloads by lazy { Downloads(this, database) }
  val repository: PodcastRepository by lazy { PodcastRepository(database, downloads) }
  val episodeActions: EpisodeActions by lazy { EpisodeActions(database) }
  val libraryActions: LibraryActions by lazy { LibraryActions(database, repository) }

  override fun onCreate() {
    super.onCreate()
    RefreshWorker.schedule(this)
    scope.launch { downloads.reconcile() }
  }
}
