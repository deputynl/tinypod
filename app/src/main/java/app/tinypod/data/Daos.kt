package app.tinypod.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

private const val EPISODE_ROW = "SELECT e.*, p.title AS podcastTitle, p.artworkUrl AS artworkUrl FROM Episode e JOIN Podcast p ON p.id = e.podcastId"

@Dao
interface FolderDao {
  @Query("SELECT * FROM Folder ORDER BY sortOrder, name COLLATE NOCASE")
  fun observeAll(): Flow<List<Folder>>

  @Insert suspend fun insert(folder: Folder): Long

  @Update suspend fun update(folder: Folder)

  @Delete suspend fun delete(folder: Folder)
}

@Dao
interface PodcastDao {
  @Query("SELECT * FROM Podcast ORDER BY title COLLATE NOCASE")
  fun observeAll(): Flow<List<Podcast>>

  @Query("SELECT * FROM Podcast WHERE folderId = :folderId ORDER BY title COLLATE NOCASE")
  fun observeInFolder(folderId: Long): Flow<List<Podcast>>

  @Query("SELECT * FROM Podcast WHERE folderId IS NULL ORDER BY title COLLATE NOCASE")
  fun observeUnfiled(): Flow<List<Podcast>>

  @Query("SELECT * FROM Podcast")
  suspend fun getAll(): List<Podcast>

  @Query("SELECT * FROM Podcast WHERE feedUrl = :feedUrl")
  suspend fun findByFeedUrl(feedUrl: String): Podcast?

  @Insert suspend fun insert(podcast: Podcast): Long

  @Update suspend fun update(podcast: Podcast)

  @Query("UPDATE Podcast SET folderId = :folderId WHERE id = :podcastId")
  suspend fun setFolder(podcastId: Long, folderId: Long?)

  @Delete suspend fun delete(podcast: Podcast)
}

@Dao
interface EpisodeDao {
  @Query("$EPISODE_ROW WHERE e.isPlayed = 0 ORDER BY e.publishedAt DESC")
  fun observeNew(): Flow<List<EpisodeWithPodcast>>

  @Query("$EPISODE_ROW WHERE e.podcastId = :podcastId ORDER BY e.publishedAt DESC")
  fun observeForPodcast(podcastId: Long): Flow<List<EpisodeWithPodcast>>

  @Query("$EPISODE_ROW WHERE e.lastPlayedAt IS NOT NULL ORDER BY e.lastPlayedAt DESC")
  fun observeHistory(): Flow<List<EpisodeWithPodcast>>

  @Query("$EPISODE_ROW WHERE e.localFilePath IS NOT NULL ORDER BY e.publishedAt DESC")
  fun observeDownloaded(): Flow<List<EpisodeWithPodcast>>

  @Query("SELECT * FROM Episode WHERE id = :id")
  suspend fun get(id: Long): Episode?

  @Query("SELECT * FROM Episode WHERE lastPlayedAt IS NOT NULL ORDER BY lastPlayedAt DESC LIMIT 1")
  suspend fun lastPlayed(): Episode?

  /** Inserts episodes not seen before; existing ones (same podcast + guid) keep their playback state. */
  @Insert(onConflict = OnConflictStrategy.IGNORE)
  suspend fun insertNew(episodes: List<Episode>): List<Long>

  @Query("UPDATE Episode SET positionMs = :positionMs, lastPlayedAt = :playedAt WHERE id = :id")
  suspend fun savePosition(id: Long, positionMs: Long, playedAt: Long)

  @Query("UPDATE Episode SET isPlayed = :played WHERE id = :id")
  suspend fun setPlayed(id: Long, played: Boolean)

  @Query("UPDATE Episode SET localFilePath = :path WHERE id = :id")
  suspend fun setLocalFile(id: Long, path: String?)
}

@Dao
interface QueueDao {
  @Query("$EPISODE_ROW JOIN QueueItem q ON q.episodeId = e.id ORDER BY q.position")
  fun observe(): Flow<List<EpisodeWithPodcast>>

  @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM QueueItem")
  suspend fun nextPosition(): Int

  @Insert(onConflict = OnConflictStrategy.IGNORE)
  suspend fun insert(item: QueueItem)

  @Query("DELETE FROM QueueItem WHERE episodeId = :episodeId")
  suspend fun remove(episodeId: Long)

  @Query("DELETE FROM QueueItem")
  suspend fun clear()

  @Insert suspend fun insertAll(items: List<QueueItem>)

  @Transaction
  suspend fun append(episodeId: Long) = insert(QueueItem(episodeId, nextPosition()))

  /** Rewrites the whole queue in the given order. */
  @Transaction
  suspend fun replace(episodeIds: List<Long>) {
    clear()
    insertAll(episodeIds.mapIndexed { i, id -> QueueItem(id, i) })
  }
}
