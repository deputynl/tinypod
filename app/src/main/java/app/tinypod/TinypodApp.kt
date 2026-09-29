package app.tinypod

import android.app.Application
import app.tinypod.data.EpisodeActions
import app.tinypod.data.PodcastRepository
import app.tinypod.data.TinypodDatabase
import app.tinypod.feed.RefreshWorker

/** Holds app-wide singletons; manual wiring instead of a DI framework. */
class TinypodApp : Application() {
  val database: TinypodDatabase by lazy { TinypodDatabase.create(this) }
  val repository: PodcastRepository by lazy { PodcastRepository(database) }
  val episodeActions: EpisodeActions by lazy { EpisodeActions(database) }

  override fun onCreate() {
    super.onCreate()
    RefreshWorker.schedule(this)
  }
}
