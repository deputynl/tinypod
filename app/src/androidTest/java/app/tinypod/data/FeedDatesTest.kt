package app.tinypod.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FeedDatesTest {
  private lateinit var db: TinypodDatabase
  private val episodes get() = db.episodeDao()
  private var podcast = 0L

  @Before
  fun setUp(): Unit = runBlocking {
    db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), TinypodDatabase::class.java).build()
    podcast = db.podcastDao().insert(Podcast(feedUrl = "https://p.example/rss", title = "Show"))
  }

  @After
  fun tearDown() = db.close()

  private fun fromFeed(guid: String, publishedAt: Long) = Episode(podcastId = podcast, guid = guid, title = guid, audioUrl = "https://p.example/$guid.mp3", publishedAt = publishedAt)

  private suspend fun titles() = episodes.observeForPodcast(podcast).first().map { it.episode.title }

  @Test
  fun aDateCorrectedInTheFeedMovesTheEpisode() = runBlocking {
    episodes.upsertFromFeed(listOf(fromFeed("old", 9_000), fromFeed("newest", 2_000)))
    assertEquals(listOf("old", "newest"), titles())
    episodes.upsertFromFeed(listOf(fromFeed("old", 1_000), fromFeed("newest", 2_000)))
    assertEquals(listOf("newest", "old"), titles())
  }

  @Test
  fun aFeedWithoutAUsableDateKeepsTheOneWeHave() = runBlocking {
    episodes.upsertFromFeed(listOf(fromFeed("a", 1_000), fromFeed("b", 2_000)))
    episodes.upsertFromFeed(listOf(fromFeed("a", 5_000), fromFeed("b", 2_000)), undated = setOf("a")) // 5_000: the refresh time
    assertEquals(listOf("b", "a"), titles())
  }
}
