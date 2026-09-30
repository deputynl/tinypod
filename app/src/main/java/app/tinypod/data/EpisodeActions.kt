package app.tinypod.data

/** Queue, played-state and history changes the user can make on any episode row. */
class EpisodeActions(db: TinypodDatabase) {
  private val queue = db.queueDao()
  private val episodes = db.episodeDao()

  /** Queues the episode right after [playingId] (if that's queued), else at the top. */
  suspend fun playNext(episodeId: Long, playingId: Long?) = queue.insertNext(episodeId, playingId)

  suspend fun addToQueue(episodeId: Long) = queue.append(episodeId)

  suspend fun removeFromQueue(episodeId: Long) = queue.remove(episodeId)

  suspend fun moveInQueue(episodeId: Long, toTop: Boolean) = queue.move(episodeId, toTop)

  suspend fun setPlayed(episodeId: Long, played: Boolean) = episodes.setPlayed(episodeId, played)

  suspend fun removeFromHistory(episodeId: Long) = episodes.removeFromHistory(episodeId)
}
