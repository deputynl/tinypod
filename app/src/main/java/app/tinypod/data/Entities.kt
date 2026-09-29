package app.tinypod.data

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity
data class Folder(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val name: String,
  val sortOrder: Int = 0,
)

/** A subscribed feed. A podcast lives in at most one folder; deleting the folder unfiles it. */
@Entity(
  indices = [Index(value = ["feedUrl"], unique = true), Index("folderId")],
  foreignKeys = [
    ForeignKey(entity = Folder::class, parentColumns = ["id"], childColumns = ["folderId"], onDelete = ForeignKey.SET_NULL),
  ],
)
data class Podcast(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val feedUrl: String,
  val title: String,
  val author: String? = null,
  val description: String? = null,
  val artworkUrl: String? = null,
  val folderId: Long? = null,
  val lastFetchedAt: Long? = null,
  /**
   * Only episodes published at or after this instant count as "new". Set on subscribe to the newest
   * episode's date, so a show's back catalogue doesn't flood the New Episodes list.
   */
  val newSince: Long = 0,
)

/**
 * One feed item plus its per-episode playback state. History is derived from [lastPlayedAt] rather
 * than kept in a separate log table, and "downloaded" is simply [localFilePath] being non-null.
 */
@Entity(
  indices = [Index(value = ["podcastId", "guid"], unique = true), Index("publishedAt"), Index("lastPlayedAt")],
  foreignKeys = [
    ForeignKey(entity = Podcast::class, parentColumns = ["id"], childColumns = ["podcastId"], onDelete = ForeignKey.CASCADE),
  ],
)
data class Episode(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val podcastId: Long,
  /** The RSS <guid>, falling back to the audio URL; used to recognise episodes on re-fetch. */
  val guid: String,
  val title: String,
  val audioUrl: String,
  val publishedAt: Long,
  val durationMs: Long? = null,
  val description: String? = null,
  val positionMs: Long = 0,
  val isPlayed: Boolean = false,
  val lastPlayedAt: Long? = null,
  val localFilePath: String? = null,
) {
  val isDownloaded: Boolean
    get() = localFilePath != null
}

/** An entry in the "play next" list, ordered by [position]. */
@Entity(
  foreignKeys = [
    ForeignKey(entity = Episode::class, parentColumns = ["id"], childColumns = ["episodeId"], onDelete = ForeignKey.CASCADE),
  ],
)
data class QueueItem(
  @PrimaryKey val episodeId: Long,
  val position: Int,
)

/** An episode together with the bits of its podcast that list rows need. */
data class EpisodeWithPodcast(
  @Embedded val episode: Episode,
  val podcastTitle: String,
  val artworkUrl: String?,
)
