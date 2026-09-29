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
class HistoryTest {
  private lateinit var db: TinypodDatabase
  private val episodes get() = db.episodeDao()

  @Before
  fun setUp() = runBlocking {
    db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), TinypodDatabase::class.java).build()
    val podcastId = db.podcastDao().insert(Podcast(feedUrl = "https://a.example/rss", title = "Show A"))
    episodes.insertNew(
      listOf(1, 2).map { Episode(podcastId = podcastId, guid = "ep$it", title = "Episode $it", audioUrl = "https://a.example/$it.mp3", publishedAt = 0) }
    )
    Unit
  }

  @After
  fun tearDown() = db.close()

  @Test
  fun historyIsNewestListenFirst() = runBlocking {
    episodes.savePosition(1, 60_000, playedAt = 1_000)
    episodes.savePosition(2, 30_000, playedAt = 2_000)

    assertEquals(listOf(2L, 1L), episodes.observeHistory().first().map { it.episode.id })
  }

  @Test
  fun removingFromHistoryKeepsThePosition() = runBlocking {
    episodes.savePosition(1, 60_000, playedAt = 1_000)
    episodes.removeFromHistory(1)

    assertEquals(emptyList<Long>(), episodes.observeHistory().first().map { it.episode.id })
    assertEquals(60_000L, episodes.getWithPodcast(1)!!.episode.positionMs)
  }
}
