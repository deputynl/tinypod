package app.tinypod.data

/** Folder and subscription changes the user can make from the library, folder and podcast screens. */
class LibraryActions(db: TinypodDatabase, private val repository: PodcastRepository) {
  private val folders = db.folderDao()
  private val podcasts = db.podcastDao()

  suspend fun createFolder(name: String): Long = folders.insert(Folder(name = name.trim()))

  suspend fun renameFolder(folder: Folder, name: String) = folders.update(folder.copy(name = name.trim()))

  /** Its podcasts stay subscribed and become unfiled. */
  suspend fun deleteFolder(folder: Folder) = folders.delete(folder)

  suspend fun moveToFolder(podcastId: Long, folderId: Long?) = podcasts.setFolder(podcastId, folderId)

  suspend fun moveToNewFolder(podcastId: Long, name: String) = podcasts.setFolder(podcastId, createFolder(name))

  suspend fun unsubscribe(podcast: Podcast) = repository.unsubscribe(podcast)
}
