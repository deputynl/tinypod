package app.tinypod.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DownloadsTest {
  private val context = ApplicationProvider.getApplicationContext<Context>()
  // A scratch directory, so reconcile() can never touch the app's real downloads.
  private val dir = File(context.cacheDir, "downloads-test").apply { mkdirs() }
  private lateinit var db: TinypodDatabase
  private val episodes get() = db.episodeDao()
  private var podcastId = 0L

  @Before
  fun setUp(): Unit = runBlocking {
    db = Room.inMemoryDatabaseBuilder(context, TinypodDatabase::class.java).build()
    podcastId = db.podcastDao().insert(Podcast(feedUrl = "https://a.example/rss", title = "Show A"))
    episodes.insertNew(
      (1..3).map { Episode(podcastId = podcastId, guid = "ep$it", title = "Episode $it", audioUrl = "https://a.example/$it.mp3", publishedAt = it * 1_000L) }
    )
    dir.listFiles()?.forEach { it.delete() }
  }

  @After
  fun tearDown() {
    db.close()
    dir.listFiles()?.forEach { it.delete() }
  }

  @Test
  fun downloadsListShowsActiveOnesFirst() = runBlocking {
    episodes.setLocalFile(3, File(dir, "episode-3.mp3").path)
    episodes.setDownloadId(1, 42)

    assertEquals(listOf(1L, 3L), episodes.observeDownloads().first().map { it.episode.id })
    assertEquals(listOf(ActiveDownload(episodeId = 1, downloadId = 42)), episodes.observeActiveDownloads().first())
  }

  @Test
  fun finishingADownloadClearsItsId() = runBlocking {
    episodes.setDownloadId(1, 42)
    episodes.setLocalFile(1, "/somewhere/episode-1.mp3")

    val episode = episodes.get(1)!!
    assertTrue(episode.isDownloaded)
    assertFalse(episode.isDownloading)
  }

  @Test
  fun reconcileForgetsMissingFilesAndRemovesStrayOnes() = runBlocking {
    val kept = File(dir, "episode-1.mp3").apply { writeText("audio") }
    val stray = File(dir, "episode-99.mp3").apply { writeText("audio") }
    val unrelated = File(dir, "notes.txt").apply { writeText("keep me") }
    episodes.setLocalFile(1, kept.path)
    episodes.setLocalFile(2, File(dir, "episode-2.mp3").path) // never written

    Downloads(context, db, directory = { dir }).reconcile()

    assertEquals(kept.path, episodes.get(1)!!.localFilePath)
    assertNull(episodes.get(2)!!.localFilePath)
    assertTrue(kept.exists())
    assertFalse(stray.exists())
    assertTrue(unrelated.exists())
  }
}
