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

@RunWith(AndroidJUnit4::class)
class EpisodeDurationTest {
  private lateinit var db: TinypodDatabase
  private val episodes get() = db.episodeDao()
  private var podcastId = 0L

  @Before
  fun setUp() = runBlocking {
    db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), TinypodDatabase::class.java).build()
    podcastId = db.podcastDao().insert(Podcast(feedUrl = "https://a.example/rss", title = "Show A"))
  }

  @After
  fun tearDown() = db.close()

  private fun feedEpisode(audioUrl: String = "https://a.example/1.mp3", durationMs: Long? = 1_320_000) =
    Episode(podcastId = podcastId, guid = "ep1", title = "Episode 1", audioUrl = audioUrl, publishedAt = 0, durationMs = durationMs)

  private suspend fun stored() = episodes.getWithPodcast(1)!!.episode

  @Test
  fun feedRefreshUpdatesUnmeasuredDuration() = runBlocking {
    episodes.upsertFromFeed(listOf(feedEpisode()))
    episodes.upsertFromFeed(listOf(feedEpisode(durationMs = 1_400_000)))

    assertEquals(1_400_000L, stored().durationMs)
    assertFalse(stored().durationMeasured)
  }

  @Test
  fun measuredDurationSurvivesFeedRefresh() = runBlocking {
    episodes.upsertFromFeed(listOf(feedEpisode()))
    episodes.saveMeasuredDuration(1, 1_608_000)
    episodes.upsertFromFeed(listOf(feedEpisode()))

    assertEquals(1_608_000L, stored().durationMs)
    assertTrue(stored().durationMeasured)
  }

  @Test
  fun newAudioDropsMeasuredDuration() = runBlocking {
    episodes.upsertFromFeed(listOf(feedEpisode()))
    episodes.saveMeasuredDuration(1, 1_608_000)
    episodes.upsertFromFeed(listOf(feedEpisode(audioUrl = "https://a.example/1-fixed.mp3")))

    assertEquals(1_320_000L, stored().durationMs)
    assertFalse(stored().durationMeasured)
  }
}
