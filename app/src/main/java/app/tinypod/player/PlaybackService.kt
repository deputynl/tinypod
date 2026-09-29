package app.tinypod.player

import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import androidx.media3.session.SessionError
import com.google.common.collect.ImmutableList
import app.tinypod.TinypodApp
import app.tinypod.data.EpisodeWithPodcast
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Owns the ExoPlayer and exposes it through a MediaLibrarySession, which gives background playback,
 * notification/lock-screen/Bluetooth controls, and Android Auto (browsing via [BrowseTree]).
 *
 * The player holds one episode at a time. Media items coming from controllers carry only the
 * episode id as mediaId; they're resolved here from the database, so the UI and Android
 * Auto never need to know about URLs or saved positions. When an episode finishes, it's marked
 * played and the next one is taken from the queue.
 */
class PlaybackService : MediaLibraryService() {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private val db by lazy { (application as TinypodApp).database }
  private val prefs by lazy { getSharedPreferences("player", Context.MODE_PRIVATE) }
  private lateinit var player: ExoPlayer
  private var session: MediaLibrarySession? = null
  private val tree by lazy { BrowseTree(db, ArtworkProvider::uriFor) }

  /** Whether the current item has played at all since it was loaded; until then there's no progress to save. */
  private var playedSinceLoad = false

  /** Whether the current item's real length has been stored yet (see [saveMeasuredDuration]). */
  private var durationSaved = false

  override fun onCreate() {
    super.onCreate()
    player =
      ExoPlayer.Builder(this)
        .setAudioAttributes(
          AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(),
          /* handleAudioFocus = */ true,
        )
        .setHandleAudioBecomingNoisy(true) // pause when headphones/Bluetooth disconnect
        .setWakeMode(C.WAKE_MODE_NETWORK)
        .setSeekBackIncrementMs(SKIP_MS)
        .setSeekForwardIncrementMs(SKIP_MS)
        .build()
    player.playbackParameters = PlaybackParameters(prefs.getFloat(KEY_SPEED, 1f))
    player.addListener(PlayerListener())
    session = MediaLibrarySession.Builder(this, player, LibraryCallback()).build()

    scope.launch { restoreLastEpisode() }
    scope.launch {
      while (isActive) {
        delay(SAVE_INTERVAL_MS)
        if (player.isPlaying) savePosition()
      }
    }
  }

  override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

  override fun onTaskRemoved(rootIntent: Intent?) {
    // Swiping the app away while paused stops the service; while playing, keep going.
    if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()
  }

  override fun onDestroy() {
    savePositionBlocking()
    scope.cancel()
    session?.release()
    session = null
    player.release()
    super.onDestroy()
  }

  /** Loads the last unfinished episode, paused at its saved position, so the mini player can show it. */
  private suspend fun restoreLastEpisode() {
    if (player.mediaItemCount > 0) return
    val row = db.episodeDao().resumable() ?: return
    if (player.mediaItemCount > 0) return // a controller got there first
    player.setMediaItem(row.toMediaItem(), row.episode.positionMs)
    player.prepare()
  }

  private fun currentEpisodeId(): Long? = player.currentMediaItem?.mediaId?.toLongOrNull()

  private suspend fun savePosition() {
    val id = currentEpisodeId() ?: return
    if (!playedSinceLoad || player.playbackState == Player.STATE_ENDED) return
    db.episodeDao().savePosition(id, player.currentPosition, System.currentTimeMillis())
  }

  private fun savePositionBlocking() {
    val id = currentEpisodeId() ?: return
    if (!playedSinceLoad || player.playbackState == Player.STATE_ENDED) return
    val position = player.currentPosition
    // The service is going away; a short blocking write is the only way to be sure it lands.
    runBlocking(Dispatchers.IO) { db.episodeDao().savePosition(id, position, System.currentTimeMillis()) }
  }

  /** Stores the length of the loaded audio, which is often longer than the feed says (e.g. inserted ads). */
  private suspend fun saveMeasuredDuration() {
    val id = currentEpisodeId() ?: return
    val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return
    durationSaved = true
    db.episodeDao().saveMeasuredDuration(id, duration)
  }

  private suspend fun onEpisodeEnded() {
    currentEpisodeId()?.let { db.episodeDao().markFinished(it, System.currentTimeMillis()) }
    val next = db.queueDao().pop()?.let { db.episodeDao().getWithPodcast(it) } ?: return
    player.setMediaItem(next.toMediaItem(), next.episode.positionMs)
    player.prepare()
    player.play()
  }

  private inner class PlayerListener : Player.Listener {
    override fun onIsPlayingChanged(isPlaying: Boolean) {
      if (isPlaying) playedSinceLoad = true
      // Save on pause too, not just periodically, so stopping is always precise.
      else scope.launch { savePosition() }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
      playedSinceLoad = false
      durationSaved = false
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
      if (playbackState == Player.STATE_READY && !durationSaved) scope.launch { saveMeasuredDuration() }
      if (playbackState == Player.STATE_ENDED) scope.launch { onEpisodeEnded() }
    }

    override fun onPlayerError(error: PlaybackException) {
      // A download deleted while its episode was loaded: carry on from the stream at the same spot.
      val item = player.currentMediaItem ?: return
      if (item.localConfiguration?.uri?.scheme != "file") return
      val position = player.currentPosition
      val resume = player.playWhenReady
      scope.launch {
        val row = currentEpisodeId()?.let { db.episodeDao().getWithPodcast(it) } ?: return@launch
        if (row.episode.localFilePath?.let { File(it).exists() } == true) return@launch // a real playback error
        player.setMediaItem(row.toMediaItem(), position)
        player.prepare()
        player.playWhenReady = resume
      }
    }

    override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
      prefs.edit { putFloat(KEY_SPEED, playbackParameters.speed) }
    }

    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
      if (reason == Player.DISCONTINUITY_REASON_SEEK) scope.launch { savePosition() }
    }
  }

  private inner class LibraryCallback : MediaLibrarySession.Callback {
    override fun onGetLibraryRoot(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?) =
      scope.future { LibraryResult.ofItem(tree.root, params) }

    override fun onGetChildren(
      session: MediaLibrarySession,
      browser: MediaSession.ControllerInfo,
      parentId: String,
      page: Int,
      pageSize: Int,
      params: LibraryParams?,
    ) =
      scope.future {
        val children = tree.children(parentId) ?: return@future LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
        val from = (page.toLong() * pageSize).coerceAtMost(children.size.toLong()).toInt()
        LibraryResult.ofItemList(ImmutableList.copyOf(children.subList(from, (from + pageSize).coerceAtMost(children.size))), params)
      }

    override fun onGetItem(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String) =
      scope.future { tree.item(mediaId)?.let { LibraryResult.ofItem(it, null) } ?: LibraryResult.ofError(SessionError.ERROR_BAD_VALUE) }

    /**
     * Controllers send bare episode ids; turn them into playable items starting at the saved position.
     * A request without one (a voice search, "play Tinypod") picks an episode via [BrowseTree.forVoiceQuery].
     */
    override fun onSetMediaItems(
      mediaSession: MediaSession,
      controller: MediaSession.ControllerInfo,
      mediaItems: List<MediaItem>,
      startIndex: Int,
      startPositionMs: Long,
    ) =
      scope.future {
        savePosition() // keep the progress of whatever was playing before
        val rows =
          mediaItems.mapNotNull { it.mediaId.toLongOrNull()?.let { id -> db.episodeDao().getWithPodcast(id) } }.ifEmpty {
            listOfNotNull(tree.forVoiceQuery(mediaItems.firstOrNull()?.requestMetadata?.searchQuery))
          }
        rows.forEach { db.queueDao().remove(it.episode.id) } // playing something takes it out of "up next"
        val index = startIndex.coerceIn(0, (rows.size - 1).coerceAtLeast(0))
        val position = if (startPositionMs == C.TIME_UNSET) rows.getOrNull(index)?.episode?.positionMs ?: 0 else startPositionMs
        MediaItemsWithStartPosition(rows.map { it.toMediaItem() }, index, position)
      }

    override fun onAddMediaItems(mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: List<MediaItem>) =
      scope.future { mediaItems.mapNotNull { it.mediaId.toLongOrNull()?.let { id -> db.episodeDao().getWithPodcast(id)?.toMediaItem() } } }

    /** Bluetooth/system "play" with nothing loaded (e.g. after a reboot) resumes the last episode. */
    override fun onPlaybackResumption(mediaSession: MediaSession, controller: MediaSession.ControllerInfo, isForPlayback: Boolean) =
      scope.future {
        val row = db.episodeDao().resumable() ?: throw UnsupportedOperationException("Nothing to resume")
        MediaItemsWithStartPosition(listOf(row.toMediaItem()), 0, row.episode.positionMs)
      }
  }

  companion object {
    const val SKIP_MS = 30_000L
    private const val SAVE_INTERVAL_MS = 5_000L
    private const val KEY_SPEED = "speed"

    /** A playable item for an episode: the downloaded file if present, otherwise the stream URL. */
    fun EpisodeWithPodcast.toMediaItem(): MediaItem {
      val local = episode.localFilePath?.let(::File)?.takeIf { it.exists() }
      return MediaItem.Builder()
        .setMediaId(episode.id.toString())
        .setUri(local?.toUri() ?: episode.audioUrl.toUri())
        .setMediaMetadata(
          MediaMetadata.Builder()
            .setTitle(episode.title)
            .setArtist(podcastTitle)
            .setAlbumTitle(podcastTitle)
            .setArtworkUri(artworkUrl?.toUri())
            .setDurationMs(episode.durationMs)
            .setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE)
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .build()
        )
        .build()
    }
  }
}
