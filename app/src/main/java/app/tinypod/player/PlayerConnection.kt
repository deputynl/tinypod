package app.tinypod.player

import android.content.ComponentName
import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** What the UI needs to draw the mini and full players. */
data class NowPlaying(
  val episodeId: Long,
  val title: String,
  val podcastTitle: String,
  val artworkUrl: String?,
  val isPlaying: Boolean,
  val isBuffering: Boolean,
  val positionMs: Long,
  /** Null until the player knows the real length. */
  val durationMs: Long?,
  val speed: Float,
)

/**
 * The UI's handle on [PlaybackService]: a MediaController plus a [nowPlaying] state flow.
 * Created per activity (connect in onStart, release in onStop).
 */
class PlayerConnection(private val context: Context) {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private var controller: MediaController? = null
  private var ticker: Job? = null

  private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
  val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying.asStateFlow()

  fun connect() {
    if (controller != null) return
    scope.launch {
      val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
      val c = MediaController.Builder(context, token).buildAsync().await()
      controller = c
      c.addListener(
        object : Player.Listener {
          override fun onEvents(player: Player, events: Player.Events) = update()
        }
      )
      update()
    }
  }

  fun release() {
    ticker?.cancel()
    ticker = null
    controller?.release()
    controller = null
    scope.coroutineContext[Job]?.children?.forEach { it.cancel() }
  }

  fun dispose() {
    release()
    scope.cancel()
  }

  /** Plays [episodeId] from its saved position (the service resolves the id). */
  /** Plays [episodeId] from its saved position, or from [fromMs] (e.g. a timestamp in the show notes). */
  fun play(episodeId: Long, fromMs: Long? = null) {
    val c = controller ?: return
    if (_nowPlaying.value?.episodeId == episodeId) {
      if (c.playbackState == Player.STATE_IDLE) c.prepare()
      fromMs?.let(c::seekTo)
      c.play()
      return
    }
    val item = MediaItem.Builder().setMediaId(episodeId.toString()).build()
    if (fromMs != null) c.setMediaItem(item, fromMs) else c.setMediaItem(item)
    c.prepare()
    c.play()
  }

  fun togglePlayPause() {
    val c = controller ?: return
    if (c.isPlaying) c.pause()
    else {
      if (c.playbackState == Player.STATE_IDLE) c.prepare()
      c.play()
    }
  }

  fun seekTo(positionMs: Long) = controller?.seekTo(positionMs).also { update() }

  fun skipBack() = controller?.seekBack().also { update() }

  fun skipForward() = controller?.seekForward().also { update() }

  fun setSpeed(speed: Float) = controller?.setPlaybackSpeed(speed)

  private fun update() {
    val c = controller ?: return
    val item = c.currentMediaItem
    val id = item?.mediaId?.toLongOrNull()
    if (item == null || id == null) {
      _nowPlaying.value = null
      return
    }
    val meta = item.mediaMetadata
    _nowPlaying.value =
      NowPlaying(
        episodeId = id,
        title = meta.title?.toString().orEmpty(),
        podcastTitle = meta.artist?.toString().orEmpty(),
        artworkUrl = meta.artworkUri?.toString(),
        isPlaying = c.isPlaying,
        isBuffering = c.playbackState == Player.STATE_BUFFERING,
        positionMs = c.currentPosition,
        durationMs = c.duration.takeIf { it > 0 } ?: meta.durationMs,
        speed = c.playbackParameters.speed,
      )
    // While playing, tick the position so the scrubber moves.
    if (c.isPlaying && ticker?.isActive != true) {
      ticker =
        scope.launch {
          while (isActive && controller?.isPlaying == true) {
            delay(500)
            update()
          }
        }
    }
  }
}

val LocalPlayer = staticCompositionLocalOf<PlayerConnection> { error("No PlayerConnection provided") }
