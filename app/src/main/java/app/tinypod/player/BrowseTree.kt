package app.tinypod.player

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaConstants
import app.tinypod.data.EpisodeWithPodcast
import app.tinypod.data.Folder
import app.tinypod.data.Podcast
import app.tinypod.data.TinypodDatabase
import kotlinx.coroutines.flow.first

/**
 * The browsable hierarchy Android Auto shows. Its top level becomes the car's tabs (at most four):
 *
 *     New ─ episodes          Queue ─ episodes          Downloads ─ episodes
 *     Library ─ folders ─ podcasts ─ episodes
 *             └ unfiled podcasts ─ episodes
 *
 * Episodes use their plain database id as mediaId, like the phone UI, so [PlaybackService] resolves
 * them the same way; everything else has a "kind/id" mediaId.
 */
class BrowseTree(
  db: TinypodDatabase,
  /** The car can't load web images in browse lists, so artwork goes through [ArtworkProvider]. */
  private val artworkUri: (podcastId: Long, artworkUrl: String?) -> Uri?,
) {
  private val episodes = db.episodeDao()
  private val podcasts = db.podcastDao()
  private val folders = db.folderDao()
  private val queue = db.queueDao()

  val root: MediaItem =
    browsable(ROOT, "Tinypod", extras = Bundle().apply { putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM) })

  private val tabs =
    listOf(
      browsable(NEW, "New"),
      browsable(LIBRARY, "Library"),
      browsable(QUEUE, "Queue"),
      browsable(DOWNLOADS, "Downloads"),
    )

  /** The children of [parentId], or null if there is no such node. */
  suspend fun children(parentId: String): List<MediaItem>? {
    val (kind, id) = split(parentId)
    return when (kind) {
      ROOT -> tabs
      NEW -> episodes.observeNew().first().map(::episodeItem)
      QUEUE -> queue.observe().first().map(::episodeItem)
      DOWNLOADS -> episodes.observeDownloads().first().filter { it.episode.isDownloaded }.map(::episodeItem)
      LIBRARY -> folders.observeAll().first().map(::folderItem) + podcasts.observeUnfiled().first().map(::podcastItem)
      FOLDER -> id?.takeIf { folders.observe(it).first() != null }?.let { podcasts.observeInFolder(it).first().map(::podcastItem) }
      PODCAST -> id?.takeIf { podcasts.observe(it).first() != null }?.let { episodes.observeForPodcast(it).first().take(MAX_EPISODES).map(::episodeItem) }
      else -> null
    }
  }

  /** The item with [mediaId] (a node or an episode), or null if there is none. */
  suspend fun item(mediaId: String): MediaItem? {
    mediaId.toLongOrNull()?.let { return episodes.getWithPodcast(it)?.let(::episodeItem) }
    val (kind, id) = split(mediaId)
    return when (kind) {
      ROOT -> root
      NEW, LIBRARY, QUEUE, DOWNLOADS -> tabs.first { it.mediaId == kind }
      FOLDER -> id?.let { folders.observe(it).first()?.let(::folderItem) }
      PODCAST -> id?.let { podcasts.observe(it).first()?.let(::podcastItem) }
      else -> null
    }
  }

  /**
   * What to play for a voice request ("play <query> on Tinypod"): the matching podcast's in-progress
   * or newest unplayed episode; with no (matching) query, the episode last listened to.
   */
  suspend fun forVoiceQuery(query: String?): EpisodeWithPodcast? {
    val q = query?.trim().orEmpty()
    val podcast = if (q.isEmpty()) null else podcasts.observeAll().first().firstOrNull { it.title.contains(q, ignoreCase = true) }
    if (podcast != null) {
      val list = episodes.observeForPodcast(podcast.id).first()
      list.firstOrNull { !it.episode.isPlayed && it.episode.positionMs > 0 }?.let { return it }
      list.firstOrNull { !it.episode.isPlayed }?.let { return it }
      list.firstOrNull()?.let { return it }
    }
    return episodes.resumable() ?: episodes.observeNew().first().firstOrNull()
  }

  private fun folderItem(folder: Folder) = browsable("$FOLDER/${folder.id}", folder.name)

  private fun podcastItem(podcast: Podcast) =
    browsable("$PODCAST/${podcast.id}", podcast.title, subtitle = podcast.author, artwork = artworkUri(podcast.id, podcast.artworkUrl))

  private fun episodeItem(row: EpisodeWithPodcast): MediaItem {
    val e = row.episode
    val extras = Bundle()
    // Lets the car show played / in-progress badges and a progress bar.
    when {
      e.isPlayed -> extras.putInt(MediaConstants.EXTRAS_KEY_COMPLETION_STATUS, MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_FULLY_PLAYED)
      e.positionMs > 0 -> {
        extras.putInt(MediaConstants.EXTRAS_KEY_COMPLETION_STATUS, MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_PARTIALLY_PLAYED)
        e.durationMs?.takeIf { it > 0 }?.let { extras.putDouble(MediaConstants.EXTRAS_KEY_COMPLETION_PERCENTAGE, e.positionMs.toDouble() / it) }
      }
      else -> extras.putInt(MediaConstants.EXTRAS_KEY_COMPLETION_STATUS, MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_NOT_PLAYED)
    }
    return MediaItem.Builder()
      .setMediaId(e.id.toString())
      .setMediaMetadata(
        MediaMetadata.Builder()
          .setTitle(e.title)
          .setArtist(row.podcastTitle)
          .setAlbumTitle(row.podcastTitle)
          .setArtworkUri(artworkUri(e.podcastId, row.artworkUrl))
          .setDurationMs(e.durationMs)
          .setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE)
          .setIsPlayable(true)
          .setIsBrowsable(false)
          .setExtras(extras)
          .build()
      )
      .build()
  }

  private fun browsable(mediaId: String, title: String, subtitle: String? = null, artwork: Uri? = null, extras: Bundle? = null) =
    MediaItem.Builder()
      .setMediaId(mediaId)
      .setMediaMetadata(
        MediaMetadata.Builder()
          .setTitle(title)
          .setSubtitle(subtitle)
          .setArtworkUri(artwork)
          .setMediaType(if (mediaId.startsWith(PODCAST)) MediaMetadata.MEDIA_TYPE_PODCAST else MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
          .setIsPlayable(false)
          .setIsBrowsable(true)
          .setExtras(extras)
          .build()
      )
      .build()

  private fun split(mediaId: String): Pair<String, Long?> = mediaId.substringBefore('/') to mediaId.substringAfter('/', "").toLongOrNull()

  companion object {
    const val ROOT = "root"
    const val NEW = "new"
    const val LIBRARY = "library"
    const val QUEUE = "queue"
    const val DOWNLOADS = "downloads"
    const val FOLDER = "folder"
    const val PODCAST = "podcast"

    /** The car truncates long lists anyway; keep browsing fast. */
    private const val MAX_EPISODES = 100
  }
}
