package app.tinypod.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The queue holds what you mean to finish: episodes leave it only when finished, marked played or removed. */
@RunWith(AndroidJUnit4::class)
class QueueTest {
  private lateinit var db: TinypodDatabase
  private val queue get() = db.queueDao()
  private val episodes get() = db.episodeDao()

  @Before
  fun setUp(): Unit = runBlocking {
    db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, TinypodDatabase::class.java).build()
    val podcast = db.podcastDao().insert(Podcast(feedUrl = "https://a.example/rss", title = "Show A"))
    episodes.insertNew((1..5).map { Episode(podcastId = podcast, guid = "e$it", title = "Ep $it", audioUrl = "https://a.example/$it.mp3", publishedAt = it * 1_000L) })
    listOf(1L, 2L, 3L).forEach { queue.append(it) }
  }

  @After
  fun tearDown() = db.close()

  @Test
  fun playNextGoesRightAfterThePlayingEpisode() = runBlocking {
    queue.insertNext(5, afterId = 2)
    assertEquals(listOf(1L, 2L, 5L, 3L), queue.episodeIds())
  }

  @Test
  fun playNextGoesToTheTopWhenWhatsPlayingIsntQueued() = runBlocking {
    queue.insertNext(5, afterId = 4)
    assertEquals(listOf(5L, 1L, 2L, 3L), queue.episodeIds())
    queue.insertNext(3, afterId = null) // moves an already queued episode
    assertEquals(listOf(3L, 5L, 1L, 2L), queue.episodeIds())
  }

  @Test
  fun playNextOnThePlayingEpisodeChangesNothing() = runBlocking {
    queue.insertNext(2, afterId = 2)
    assertEquals(listOf(1L, 2L, 3L), queue.episodeIds())
  }

  @Test
  fun startingAnEpisodeKeepsItQueuedButFinishingRemovesIt() = runBlocking {
    episodes.savePosition(2, positionMs = 60_000, playedAt = 10_000)
    assertEquals(listOf(1L, 2L, 3L), queue.episodeIds())

    episodes.markFinished(2, playedAt = 11_000)
    assertEquals(listOf(1L, 3L), queue.episodeIds())
    assertEquals(1L, queue.first()) // the top plays next
  }

  @Test
  fun movingToTopAndBottom() = runBlocking {
    queue.move(3, toTop = true)
    assertEquals(listOf(3L, 1L, 2L), queue.episodeIds())
    queue.move(3, toTop = false)
    assertEquals(listOf(1L, 2L, 3L), queue.episodeIds())
    queue.move(5, toTop = true) // not queued: nothing happens
    assertEquals(listOf(1L, 2L, 3L), queue.episodeIds())
  }

  @Test
  fun aDraggedOrderIsReconciledWithChangesDuringTheDrag() = runBlocking {
    // Dragged 3 to the top, while meanwhile 2 was finished and 4 queued.
    episodes.markFinished(2, playedAt = 1)
    queue.append(4)
    queue.reorder(listOf(3L, 1L, 2L))
    assertEquals(listOf(3L, 1L, 4L), queue.episodeIds())
  }

  @Test
  fun markingPlayedRemovesItButUnplayedDoesNotQueueIt() = runBlocking {
    episodes.setPlayed(1, played = true)
    assertEquals(listOf(2L, 3L), queue.episodeIds())
    episodes.setPlayed(1, played = false)
    assertEquals(listOf(2L, 3L), queue.episodeIds())
  }
}
