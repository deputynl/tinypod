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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
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

  /** A mediaId without the version a podcast's has. */
  private fun node(mediaId: String) = mediaId.split('/').take(2).joinToString("/")

  private suspend fun nodes(parent: String) = ids(parent).map(::node)

  @Test
  fun rootHasTheFourTabs() = runBlocking { assertEquals(listOf("new", "library", "queue", "history"), ids("root")) }

  @Test
  fun newEpisodesSayNewUnderTheTitle() = runBlocking {
    db.podcastDao().setNewSince(daily, 2_000) // only Daily 2 is new
    val subtitles = tree.children("podcast/$daily")!!.associate { it.mediaMetadata.title.toString() to it.mediaMetadata.subtitle?.toString() }
    assertEquals(mapOf("Daily 2" to "New · The Daily Thing", "Daily 1" to null), subtitles)
  }

  @Test
  fun libraryListsFoldersThenUnfiledPodcasts() = runBlocking {
    assertEquals(listOf("folder/$news", "podcast/$comedy"), nodes("library"))
    assertEquals(listOf("podcast/$daily"), nodes("folder/$news"))
    assertTrue(tree.children("library")!!.all { it.mediaMetadata.isBrowsable == true })
  }

  @Test
  fun podcastListsPlayableEpisodesWithProgress() = runBlocking {
    db.episodeDao().savePosition(1, positionMs = 25_000, playedAt = 5_000)
    db.episodeDao().markFinished(2, playedAt = 6_000, advanceNew = true)

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
    val library = tree.children("library")!!.associateBy { node(it.mediaId) }
    assertEquals("2 new", library["folder/$news"]!!.mediaMetadata.subtitle)
    assertEquals("1 new", library["podcast/$comedy"]!!.mediaMetadata.subtitle)
    assertEquals("2 new", tree.children("folder/$news")!!.single().mediaMetadata.subtitle)

    db.episodeDao().markFinished(2, playedAt = 9_000, advanceNew = true) // nothing newer left
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
  fun historyListsWhatWasPlayedLastFirst() = runBlocking {
    assertEquals(emptyList<String>(), ids("history"))
    db.episodeDao().savePosition(1, positionMs = 25_000, playedAt = 5_000) // this morning's news, not finished
    db.episodeDao().markFinished(3, playedAt = 6_000, advanceNew = false)
    db.episodeDao().savePosition(2, positionMs = 1_000, playedAt = 7_000)
    assertEquals(listOf("2", "3", "1"), ids("history"))

    val morning = tree.children("history")!!.last()
    assertTrue(morning.mediaMetadata.isPlayable == true)
    assertEquals(0.25, morning.mediaMetadata.extras!!.getDouble(MediaConstants.EXTRAS_KEY_COMPLETION_PERCENTAGE), 0.001)

    db.episodeDao().removeFromHistory(3)
    assertEquals(listOf("2", "1"), ids("history"))
    assertEquals("History", tree.item("history")!!.mediaMetadata.title)
  }

  @Test
  fun downloadsAreATileInTheLibraryOnceThereAreAny() = runBlocking {
    assertFalse("downloads" in ids("library"))
    db.episodeDao().setLocalFile(1, "/files/episode-1.mp3")
    assertEquals(listOf("downloads", "folder/$news", "podcast/$comedy"), nodes("library"))

    val tile = tree.children("library")!!.first()
    assertEquals("1 episode", tile.mediaMetadata.subtitle)
    assertEquals(Uri.parse("content://art/$daily"), tile.mediaMetadata.artworkUri)
    assertEquals("Downloads", tree.item("downloads")!!.mediaMetadata.title)
  }

  @Test
  fun tellsTheCarWhichListsChanged() = runBlocking {
    suspend fun changedBy(change: suspend () -> Unit): Set<String> {
      val seen = mutableSetOf<String>()
      val started = CompletableDeferred<Unit>()
      val job = launch(Dispatchers.IO) { tree.changes().onStart { started.complete(Unit) }.collect { seen += node(it) } }
      started.await()
      delay(300) // the first state, which is no change
      change()
      delay(300)
      job.cancel()
      return seen
    }

    val published = Episode(podcastId = daily, guid = "d3", title = "Daily 3", audioUrl = "https://d.example/3.mp3", publishedAt = 9_000)
    assertEquals(setOf("podcast/$daily", "folder/$news", "new", "library"), changedBy { db.episodeDao().insertNew(listOf(published)) })
    assertEquals(setOf("history"), changedBy { db.episodeDao().savePosition(1, positionMs = 1_000, playedAt = 5_000) })
    assertEquals(emptySet<String>(), changedBy { db.episodeDao().savePosition(1, positionMs = 6_000, playedAt = 6_000) }) // just progress
    val redated = published.copy(publishedAt = 500)
    // The tiles leading to the podcast change too: they get its new mediaId.
    assertEquals(setOf("podcast/$daily", "folder/$news", "library"), changedBy { db.episodeDao().upsertFromFeed(listOf(redated)) })
  }

  @Test
  fun aPodcastWithNewEpisodesIsANewListToTheCar() = runBlocking {
    val before = ids("folder/$news").single()
    assertEquals(before, ids("folder/$news").single()) // stable while nothing changes
    db.episodeDao().savePosition(1, positionMs = 1_000, playedAt = 5_000)
    assertEquals(before, ids("folder/$news").single()) // progress doesn't count

    db.episodeDao().insertNew(listOf(Episode(podcastId = daily, guid = "d3", title = "Daily 3", audioUrl = "https://d.example/3.mp3", publishedAt = 9_000)))
    val after = ids("folder/$news").single()
    assertTrue(after != before && after.startsWith("podcast/$daily/"))
    assertEquals(after, tree.item("podcast/$daily")!!.mediaId)

    // The old mediaId, which the car may still be showing, lists what's there now.
    assertEquals(listOf("Daily 3", "Daily 2", "Daily 1"), tree.children(before)!!.map { it.mediaMetadata.title.toString() })
    assertEquals(after, tree.item(before)!!.mediaId)
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
