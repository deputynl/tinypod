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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow

/**
 * The browsable hierarchy Android Auto shows. Its top level becomes the car's tabs (at most four):
 *
 *     New ─ episodes          Queue ─ episodes          History ─ episodes
 *     Library ─ Downloads ─ episodes
 *             ├ folders ─ podcasts ─ episodes
 *             └ unfiled podcasts ─ episodes
 *
 * Episodes use their plain database id as mediaId, like the phone UI, so [PlaybackService] resolves
 * them the same way; everything else has a "kind/id" mediaId.
 */
class BrowseTree(
  db: TinypodDatabase,
  /** The car can't load web images in browse lists, so artwork goes through [ArtworkProvider]. */
  private val artworkUri: (podcastId: Long, artworkUrl: String?) -> Uri?,
  /** A folder's mosaic of the artwork of (up to four of) its podcasts, given their artwork URLs. */
  private val folderArtworkUri: (folderId: Long, artworkUrls: List<String>) -> Uri? = { _, _ -> null },
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
      browsable(LIBRARY, "Library", extras = gridOfChildren()),
      browsable(QUEUE, "Queue"),
      browsable(HISTORY, "History"),
    )

  /** The children of [parentId], or null if there is no such node. */
  suspend fun children(parentId: String): List<MediaItem>? {
    val (kind, id) = split(parentId)
    return when (kind) {
      ROOT -> tabs
      NEW -> episodes.observeNew().first().map(::episodeItem)
      QUEUE -> queue.observe().first().map(::episodeItem)
      HISTORY -> episodes.observeHistory().first().take(MAX_EPISODES).map(::episodeItem)
      DOWNLOADS -> downloaded().map(::episodeItem)
      LIBRARY -> {
        val all = podcasts.observeAll().first()
        val counts = newCounts()
        val downloads = downloaded().takeIf { it.isNotEmpty() }?.let(::downloadsItem)
        listOfNotNull(downloads) + folders.observeAll().first().map { folderItem(it, all, counts) } + all.filter { it.folderId == null }.map { podcastItem(it, counts) }
      }
      FOLDER -> id?.takeIf { folders.observe(it).first() != null }?.let { folderId ->
        val counts = newCounts()
        podcasts.observeInFolder(folderId).first().map { podcastItem(it, counts) }
      }
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
      NEW, LIBRARY, QUEUE, HISTORY -> tabs.first { it.mediaId == kind }
      DOWNLOADS -> downloadsItem(downloaded())
      FOLDER -> id?.let { folders.observe(it).first()?.let { folder -> folderItem(folder, podcasts.observeAll().first(), newCounts()) } }
      PODCAST -> id?.let { podcasts.observe(it).first()?.let { podcast -> podcastItem(podcast, newCounts()) } }
      else -> null
    }
  }

  /**
   * The lists that changed, one mediaId at a time, for telling the car: it keeps showing what it has
   * loaded otherwise. Progress doesn't count, as it's saved every few seconds and reloading for that
   * would be wasteful.
   */
  fun changes(): Flow<String> = flow {
    var before: Map<String, Any>? = null
    listSignatures().collect { now ->
      before?.let { old -> (old.keys + now.keys).filter { old[it] != now[it] }.forEach { emit(it) } }
      before = now
    }
  }

  /** For each list in the tree, a value that's different when the list needs reloading. */
  private fun listSignatures(): Flow<Map<String, Any>> {
    val ids = combine(episodes.observeHistoryIds(MAX_EPISODES), queue.observeIds(), episodes.observeDownloadedIds(), ::Triple)
    return combine(ids, episodes.observeEpisodeCounts(), episodes.observeNewCounts(), podcasts.observeAll()) { (history, queued, downloaded), totals, new, all ->
      val newCounts = new.associate { it.podcastId to it.count }
      buildMap {
        put(HISTORY, history)
        put(QUEUE, queued)
        put(DOWNLOADS, downloaded)
        put(NEW, newCounts)
        put(LIBRARY, listOf(downloaded.size, newCounts, all.map { listOf(it.id, it.title, it.folderId) }))
        totals.forEach { put("$PODCAST/${it.podcastId}", it.count) }
        all.filter { it.folderId != null }.groupBy { it.folderId }.forEach { (folderId, inside) ->
          put("$FOLDER/$folderId", inside.map { listOf(it.id, it.title, newCounts[it.id]) })
        }
      }
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

  /** New episodes per podcast, as on the New tab. */
  private suspend fun newCounts() = episodes.observeNewCounts().first().associate { it.podcastId to it.count }

  private suspend fun downloaded() = episodes.observeDownloads().first().filter { it.episode.isDownloaded }

  /** History took Downloads' place among the four tabs, so it's a tile in the Library, with the latest download's artwork. */
  private fun downloadsItem(rows: List<EpisodeWithPodcast>) =
    browsable(
      DOWNLOADS,
      "Downloads",
      subtitle = if (rows.size == 1) "1 episode" else "${rows.size} episodes",
      artwork = rows.firstOrNull()?.let { artworkUri(it.episode.podcastId, it.artworkUrl) },
    )

  /** The car has no count badges, so a count of new episodes goes in the subtitle under the tile. */
  private fun folderItem(folder: Folder, all: List<Podcast>, counts: Map<Long, Int>): MediaItem {
    val inside = all.filter { it.folderId == folder.id }
    val new = inside.sumOf { counts[it.id] ?: 0 }
    return browsable(
      "$FOLDER/${folder.id}",
      folder.name,
      subtitle = if (new > 0) "$new new" else if (inside.size == 1) "1 podcast" else "${inside.size} podcasts",
      artwork = folderArtworkUri(folder.id, inside.take(4).mapNotNull { it.artworkUrl }),
      extras = gridOfChildren(),
    )
  }

  private fun podcastItem(podcast: Podcast, counts: Map<Long, Int>): MediaItem {
    val new = counts[podcast.id] ?: 0
    return browsable("$PODCAST/${podcast.id}", podcast.title, subtitle = if (new > 0) "$new new" else podcast.author, artwork = artworkUri(podcast.id, podcast.artworkUrl))
  }

  /** Asks the car to show this node's browsable children (folders, podcasts) as artwork tiles. */
  private fun gridOfChildren() =
    Bundle().apply { putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM) }

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
    // Shows the car's "downloaded" badge, so you can tell what plays without signal.
    if (e.isDownloaded) extras.putLong(EXTRA_DOWNLOAD_STATUS, STATUS_DOWNLOADED)
    return MediaItem.Builder()
      .setMediaId(e.id.toString())
      .setMediaMetadata(
        MediaMetadata.Builder()
          .setTitle(e.title)
          .setArtist(row.podcastTitle)
          .setAlbumTitle(row.podcastTitle)
          // The car has no "new" badge; say so in the line under the title.
          .setSubtitle(if (row.isNew) "New · ${row.podcastTitle}" else null)
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
    const val HISTORY = "history"
    const val DOWNLOADS = "downloads"
    const val FOLDER = "folder"
    const val PODCAST = "podcast"

    // MediaDescriptionCompat's download-status extra, which Android Auto reads; Media3 has no constant for it.
    internal const val EXTRA_DOWNLOAD_STATUS = "android.media.extra.DOWNLOAD_STATUS"
    internal const val STATUS_DOWNLOADED = 2L

    /** The car truncates long lists anyway; keep browsing fast. */
    private const val MAX_EPISODES = 100
  }
}
