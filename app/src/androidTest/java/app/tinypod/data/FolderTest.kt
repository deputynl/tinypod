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
}
