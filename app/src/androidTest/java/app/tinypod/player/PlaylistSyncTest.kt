package app.tinypod.player

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.tinypod.TinypodApp
import app.tinypod.data.Episode
import app.tinypod.data.Podcast
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The player's playlist, which the car's and lock screen's queue views show, mirrors the queue:
 * the playing episode first, then the queue in order. Runs against the real service and database,
 * with its own test podcast, and puts the queue back afterwards.
 */
@RunWith(AndroidJUnit4::class)
class PlaylistSyncTest {
  private val context = ApplicationProvider.getApplicationContext<Context>()
  private val db = (context.applicationContext as TinypodApp).database
  private lateinit var controller: MediaController
  private lateinit var savedQueue: List<Long>
  private var podcastId = 0L
  private val ids = mutableListOf<Long>()

  @Before
  fun setUp(): Unit = runBlocking {
    savedQueue = db.queueDao().episodeIds()
    podcastId = db.podcastDao().insert(Podcast(feedUrl = "https://playlist-test.invalid/rss", title = "Playlist test"))
    db.episodeDao().insertNew((1..4).map { Episode(podcastId = podcastId, guid = "p$it", title = "Test $it", audioUrl = "https://playlist-test.invalid/$it.mp3", publishedAt = it * 1_000L) })
    ids += db.episodeDao().observeForPodcast(podcastId).first().map { it.episode.id }.sorted()
    db.queueDao().replace(listOf(ids[1], ids[2], ids[3])) // episodes 2, 3, 4
    val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
    controller = onMain { MediaController.Builder(context, token).buildAsync() }.get(10, TimeUnit.SECONDS)
  }

  @After
  fun tearDown(): Unit = runBlocking {
    onMain {
      controller.stop()
      controller.clearMediaItems()
      controller.release()
    }
    db.podcastDao().observe(podcastId).first()?.let { db.podcastDao().delete(it) }
    db.queueDao().replace(savedQueue.filter { db.episodeDao().get(it) != null })
  }

  private fun playlist(): List<Long> = onMain { (0 until controller.mediaItemCount).map { controller.getMediaItemAt(it).mediaId.toLong() } }

  /** Waits (up to 5 s) for the playlist to become [expected]; the service syncs asynchronously. */
  private fun awaitPlaylist(expected: List<Long>) {
    val deadline = System.currentTimeMillis() + 5_000
    while (playlist() != expected && System.currentTimeMillis() < deadline) Thread.sleep(50)
    assertEquals(expected, playlist())
  }

  @Test
  fun playlistFollowsTheQueue() = runBlocking {
    val (e1, e2, e3, e4) = ids

    // Playing an unqueued episode: it comes first, then the queue.
    onMain { controller.setMediaItem(MediaItem.Builder().setMediaId("$e1").build()) }
    awaitPlaylist(listOf(e1, e2, e3, e4))

    // Reordering the queue reorders what follows.
    db.queueDao().replace(listOf(e4, e2, e3))
    awaitPlaylist(listOf(e1, e4, e2, e3))

    // Jumping to a queued episode (as from the car's queue list): it's first, the rest in queue order,
    // and the unqueued one that was playing drops out.
    onMain { controller.seekToDefaultPosition(2) }
    awaitPlaylist(listOf(e2, e4, e3))

    // Removing from the queue removes it from the playlist.
    db.queueDao().remove(e3)
    awaitPlaylist(listOf(e2, e4))
  }

  private fun <T> onMain(block: () -> T): T {
    var result: Result<T>? = null
    InstrumentationRegistry.getInstrumentation().runOnMainSync { result = runCatching(block) }
    return result!!.getOrThrow()
  }
}
