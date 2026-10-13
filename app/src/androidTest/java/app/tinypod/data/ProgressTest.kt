package app.tinypod.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Saving progress, and finishing an episode once it's [FINISHED_FRACTION] of the way through. */
@RunWith(AndroidJUnit4::class)
class ProgressTest {
  private lateinit var db: TinypodDatabase
  private val episodes get() = db.episodeDao()

  @Before
  fun setUp() = runBlocking {
    db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), TinypodDatabase::class.java).build()
    val podcastId = db.podcastDao().insert(Podcast(feedUrl = "https://a.example/rss", title = "Show A"))
    episodes.insertNew(
      listOf(1, 2).map {
        Episode(podcastId = podcastId, guid = "ep$it", title = "Episode $it", audioUrl = "https://a.example/$it.mp3", publishedAt = 0, durationMs = 100_000)
      }
    )
    db.queueDao().append(1)
    Unit
  }

  @After
  fun tearDown() = db.close()

  @Test
  fun beforeTheEndOnlyThePositionIsSaved() = runBlocking {
    episodes.saveMeasuredDuration(1, 100_000)
    episodes.saveProgress(1, 97_000, playedAt = 1_000, advanceNew = true)

    val e = episodes.get(1)!!
    assertFalse(e.isPlayed)
    assertEquals(97_000L, e.positionMs)
    assertEquals(listOf(1L), db.queueDao().episodeIds())
  }

  @Test
  fun nearTheEndTheEpisodeIsFinished() = runBlocking {
    episodes.saveMeasuredDuration(1, 100_000)
    episodes.saveProgress(1, 98_000, playedAt = 1_000, advanceNew = true)

    val e = episodes.get(1)!!
    assertTrue(e.isPlayed)
    assertEquals(0L, e.positionMs)
    assertEquals(1_000L, e.lastPlayedAt)
    assertEquals(emptyList<Long>(), db.queueDao().episodeIds())
  }

  @Test
  fun theFeedsDurationIsNotTrusted() = runBlocking {
    // 98 % of what the feed claims; the real audio may be longer (inserted ads).
    episodes.saveProgress(2, 99_000, playedAt = 1_000, advanceNew = true)

    val e = episodes.get(2)!!
    assertFalse(e.isPlayed)
    assertEquals(99_000L, e.positionMs)
  }

  @Test
  fun aFinishedEpisodeKeepsNoPositionNearTheEnd() = runBlocking {
    episodes.saveMeasuredDuration(1, 100_000)
    episodes.saveProgress(1, 98_500, playedAt = 1_000, advanceNew = true)
    episodes.saveProgress(1, 99_500, playedAt = 2_000, advanceNew = true)

    val e = episodes.get(1)!!
    assertTrue(e.isPlayed)
    assertEquals(0L, e.positionMs)
    assertEquals(1_000L, e.lastPlayedAt)
  }
}
