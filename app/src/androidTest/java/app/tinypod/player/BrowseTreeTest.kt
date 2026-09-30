package app.tinypod.player

import android.net.Uri
import androidx.media3.session.MediaConstants
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.tinypod.data.Episode
import app.tinypod.data.Folder
import app.tinypod.data.Podcast
import app.tinypod.data.TinypodDatabase
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
class BrowseTreeTest {
  private lateinit var db: TinypodDatabase
  private lateinit var tree: BrowseTree
  private var news = 0L
  private var daily = 0L
  private var comedy = 0L

  @Before
  fun setUp(): Unit = runBlocking {
    db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), TinypodDatabase::class.java).build()
    tree = BrowseTree(db, artworkUri = { id, url -> url?.let { Uri.parse("content://art/$id") } })
    news = db.folderDao().insert(Folder(name = "News"))
    daily = db.podcastDao().insert(Podcast(feedUrl = "https://d.example/rss", title = "The Daily Thing", artworkUrl = "https://d.example/a.jpg", folderId = news))
    comedy = db.podcastDao().insert(Podcast(feedUrl = "https://c.example/rss", title = "Comedy Hour"))
    db.episodeDao().insertNew(
      listOf(
        Episode(podcastId = daily, guid = "d1", title = "Daily 1", audioUrl = "https://d.example/1.mp3", publishedAt = 1_000, durationMs = 100_000),
        Episode(podcastId = daily, guid = "d2", title = "Daily 2", audioUrl = "https://d.example/2.mp3", publishedAt = 2_000, durationMs = 100_000),
        Episode(podcastId = comedy, guid = "c1", title = "Comedy 1", audioUrl = "https://c.example/1.mp3", publishedAt = 3_000),
      )
    )
  }

  @After
  fun tearDown() = db.close()

  private suspend fun ids(parent: String) = tree.children(parent)!!.map { it.mediaId }

  @Test
  fun rootHasTheFourTabs() = runBlocking { assertEquals(listOf("new", "library", "queue", "downloads"), ids("root")) }

  @Test
  fun libraryListsFoldersThenUnfiledPodcasts() = runBlocking {
    assertEquals(listOf("folder/$news", "podcast/$comedy"), ids("library"))
    assertEquals(listOf("podcast/$daily"), ids("folder/$news"))
    assertTrue(tree.children("library")!!.all { it.mediaMetadata.isBrowsable == true })
  }

  @Test
  fun podcastListsPlayableEpisodesWithProgress() = runBlocking {
    db.episodeDao().savePosition(1, positionMs = 25_000, playedAt = 5_000)
    db.episodeDao().markFinished(2, playedAt = 6_000)

    val episodes = tree.children("podcast/$daily")!!
    assertEquals(listOf("2", "1"), episodes.map { it.mediaId })
    assertTrue(episodes.all { it.mediaMetadata.isPlayable == true })
    val extras = episodes.associate { it.mediaId to it.mediaMetadata.extras!! }
    assertEquals(MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_FULLY_PLAYED, extras["2"]!!.getInt(MediaConstants.EXTRAS_KEY_COMPLETION_STATUS))
    assertEquals(MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_PARTIALLY_PLAYED, extras["1"]!!.getInt(MediaConstants.EXTRAS_KEY_COMPLETION_STATUS))
    assertEquals(0.25, extras["1"]!!.getDouble(MediaConstants.EXTRAS_KEY_COMPLETION_PERCENTAGE), 0.001)
    assertEquals(Uri.parse("content://art/$daily"), episodes[0].mediaMetadata.artworkUri)
  }

  @Test
  fun libraryAndFoldersAreTilesWithNewCounts() = runBlocking {
    val grid = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM
    fun style(item: androidx.media3.common.MediaItem) = item.mediaMetadata.extras?.getInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE)
    assertEquals(grid, style(tree.item("library")!!))
    assertEquals(grid, style(tree.item("folder/$news")!!))

    // The Daily Thing has two new episodes (1 and 2), Comedy Hour one.
    val library = tree.children("library")!!.associateBy { it.mediaId }
    assertEquals("2 new", library["folder/$news"]!!.mediaMetadata.subtitle)
    assertEquals("1 new", library["podcast/$comedy"]!!.mediaMetadata.subtitle)
    assertEquals("2 new", tree.children("folder/$news")!!.single().mediaMetadata.subtitle)

    db.episodeDao().markFinished(2, playedAt = 9_000) // nothing newer left
    assertEquals("1 podcast", tree.item("folder/$news")!!.mediaMetadata.subtitle)
  }

  @Test
  fun downloadedEpisodesGetTheCarsDownloadedBadge() = runBlocking {
    db.episodeDao().setLocalFile(1, "/files/episode-1.mp3")
    val extras = tree.children("podcast/$daily")!!.associate { it.mediaId to it.mediaMetadata.extras!! }
    assertEquals(BrowseTree.STATUS_DOWNLOADED, extras["1"]!!.getLong(BrowseTree.EXTRA_DOWNLOAD_STATUS))
    assertFalse(extras["2"]!!.containsKey(BrowseTree.EXTRA_DOWNLOAD_STATUS))
  }

  @Test
  fun downloadsOnlyListsFinishedDownloads() = runBlocking {
    db.episodeDao().setLocalFile(1, "/files/episode-1.mp3")
    db.episodeDao().setDownloadId(3, 42)
    assertEquals(listOf("1"), ids("downloads"))
  }

  @Test
  fun unknownNodesAreNull() = runBlocking {
    assertNull(tree.children("folder/999"))
    assertNull(tree.children("nonsense"))
    assertNull(tree.item("podcast/999"))
    assertEquals("Daily 1", tree.item("1")!!.mediaMetadata.title)
  }

  @Test
  fun voiceQueryPicksTheMatchingPodcastsNextEpisode() = runBlocking {
    assertEquals("Daily 2", tree.forVoiceQuery("daily thing")!!.episode.title) // newest unplayed
    db.episodeDao().savePosition(1, positionMs = 25_000, playedAt = 5_000)
    assertEquals("Daily 1", tree.forVoiceQuery("DAILY")!!.episode.title) // in progress wins
    assertEquals("Daily 1", tree.forVoiceQuery(null)!!.episode.title) // no query: resume the last one
    assertEquals("Daily 1", tree.forVoiceQuery("no such show")!!.episode.title)
  }
}
