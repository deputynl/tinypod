package app.tinypod.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** "New" means unplayed and newer than the latest episode you've finished (or the subscription). */
@RunWith(AndroidJUnit4::class)
class NewEpisodesTest {
  private fun inMemory() =
    Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, TinypodDatabase::class.java).build()

  /** A podcast subscribed when episode 2 was the newest, with episodes 1..5 published at 1000..5000. */
  private suspend fun TinypodDatabase.podcastWithFiveEpisodes(): Long {
    val id = podcastDao().insert(Podcast(feedUrl = "https://a.example/rss", title = "Show A", newSince = 2_000))
    episodeDao().insertNew((1..5).map { Episode(podcastId = id, guid = "e$it", title = "Ep $it", audioUrl = "https://a.example/$it.mp3", publishedAt = it * 1_000L) })
    return id
  }

  private suspend fun TinypodDatabase.newTitles() = episodeDao().observeNew().first().map { it.episode.title }

  @Test
  fun finishingAnEpisodeClearsTheOlderOnes() = runBlocking {
    val db = inMemory()
    db.podcastWithFiveEpisodes()
    assertEquals(listOf("Ep 5", "Ep 4", "Ep 3", "Ep 2"), db.newTitles())

    db.episodeDao().markFinished(4, playedAt = 10_000)

    assertEquals(listOf("Ep 5"), db.newTitles())
    db.close()
  }

  @Test
  fun finishingAnOlderEpisodeChangesNothingForNewerOnes() = runBlocking {
    val db = inMemory()
    db.podcastWithFiveEpisodes()
    db.episodeDao().markFinished(4, playedAt = 10_000)
    db.episodeDao().markFinished(1, playedAt = 11_000) // from the back catalogue

    assertEquals(listOf("Ep 5"), db.newTitles())
    db.close()
  }

  @Test
  fun markingAsPlayedCountsAsFinishingButStartingDoesNot() = runBlocking {
    val db = inMemory()
    db.podcastWithFiveEpisodes()
    db.episodeDao().savePosition(4, positionMs = 60_000, playedAt = 10_000) // started, not finished
    assertEquals(listOf("Ep 5", "Ep 4", "Ep 3", "Ep 2"), db.newTitles())

    db.episodeDao().setPlayed(3, played = true)

    assertEquals(listOf("Ep 5", "Ep 4"), db.newTitles())
    db.close()
  }

  @Test
  fun migrationAppliesTheRuleToEpisodesFinishedBefore() = runBlocking {
    // Version 4 has the same tables as 3, so the migration's update can run on a database holding "before" data.
    val db = inMemory()
    val a = db.podcastWithFiveEpisodes()
    val b = db.podcastDao().insert(Podcast(feedUrl = "https://b.example/rss", title = "Show B", newSince = 2_000))
    db.episodeDao().setPlayedState(3, played = true) // finished under the old rule: the marker didn't move

    TinypodDatabase.MIGRATION_3_4.migrate(db.openHelper.writableDatabase)

    assertEquals(3_001L, db.podcastDao().observe(a).first()!!.newSince) // past the finished episode 3
    assertEquals(2_000L, db.podcastDao().observe(b).first()!!.newSince) // nothing finished: unchanged
    assertEquals(listOf("Ep 5", "Ep 4"), db.newTitles())
    db.close()
  }
}
