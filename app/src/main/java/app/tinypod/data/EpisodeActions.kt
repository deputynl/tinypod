package app.tinypod.data

/** Queue, played-state and history changes the user can make on any episode row. */
class EpisodeActions(db: TinypodDatabase) {
  private val queue = db.queueDao()
  private val episodes = db.episodeDao()

  suspend fun playNext(episodeId: Long) = queue.prepend(episodeId)

  suspend fun addToQueue(episodeId: Long) = queue.append(episodeId)

  suspend fun removeFromQueue(episodeId: Long) = queue.remove(episodeId)

  suspend fun setPlayed(episodeId: Long, played: Boolean) = episodes.setPlayed(episodeId, played)

  suspend fun removeFromHistory(episodeId: Long) = episodes.removeFromHistory(episodeId)
}
