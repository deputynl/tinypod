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
class FolderTest {
  private lateinit var db: TinypodDatabase

  @Before
  fun setUp() {
    db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), TinypodDatabase::class.java).build()
  }

  @After
  fun tearDown() = db.close()

  @Test
  fun deletingAFolderUnfilesItsPodcasts() = runBlocking {
    val folderId = db.folderDao().insert(Folder(name = "News"))
    val podcastId = db.podcastDao().insert(Podcast(feedUrl = "https://a.example/rss", title = "Show A"))
    db.podcastDao().setFolder(podcastId, folderId)
    assertEquals(listOf(podcastId), db.podcastDao().observeInFolder(folderId).first().map { it.id })

    db.folderDao().delete(Folder(id = folderId, name = "News"))

    assertEquals(listOf(podcastId), db.podcastDao().observeUnfiled().first().map { it.id })
  }

  @Test
  fun newCountsMatchTheNewTab() = runBlocking {
    val a = db.podcastDao().insert(Podcast(feedUrl = "https://a.example/rss", title = "Show A", newSince = 2_000))
    val b = db.podcastDao().insert(Podcast(feedUrl = "https://b.example/rss", title = "Show B"))
    fun ep(podcast: Long, n: Int, published: Long) =
      Episode(podcastId = podcast, guid = "$podcast-$n", title = "Ep $n", audioUrl = "https://x.example/$podcast/$n.mp3", publishedAt = published)
    // Show A: one from before subscribing (not new), two new, of which one gets played.
    db.episodeDao().insertNew(listOf(ep(a, 1, 1_000), ep(a, 2, 2_000), ep(a, 3, 3_000), ep(b, 1, 1_000)))
    db.episodeDao().markFinished(3, playedAt = 4_000)

    val counts = db.episodeDao().observeNewCounts().first().associate { it.podcastId to it.count }
    assertEquals(mapOf(a to 1, b to 1), counts)
    assertEquals(db.episodeDao().observeNew().first().groupingBy { it.episode.podcastId }.eachCount(), counts)
  }
}
