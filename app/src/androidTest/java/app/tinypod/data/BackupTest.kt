package app.tinypod.data

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Export, then import into a fresh database. Both hold the same podcasts beforehand, so nothing
 * needs the network. The test runs in the app's process, so it puts the real settings back afterwards.
 */
@RunWith(AndroidJUnit4::class)
class BackupTest {
  private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
  private val settings = Settings(context)
  private lateinit var saved: BackupSettings
  private val databases = mutableListOf<TinypodDatabase>()

  @Before
  fun saveSettings() {
    saved = BackupSettings(settings.clearOlderOnFinish.value, settings.continueWithNext.value, settings.rewindOnResumeSec.value, settings.speed.value)
  }

  @After
  fun restoreSettings() {
    settings.setClearOlderOnFinish(saved.clearOlderOnFinish)
    settings.setContinueWithNext(saved.continueWithNext)
    settings.setRewindOnResumeSec(saved.rewindOnResumeSec)
    settings.setSpeed(saved.speed)
    databases.forEach { it.close() }
  }

  private fun backupFor(db: TinypodDatabase) = Backup(db, PodcastRepository(db, Downloads(context, db)), settings)

  /** Two podcasts with three episodes each (published at 1000..3000), as a fresh subscription would have them. */
  private suspend fun freshLibrary(): TinypodDatabase {
    val db = Room.inMemoryDatabaseBuilder(context, TinypodDatabase::class.java).build().also(databases::add)
    for (name in listOf("a", "b")) {
      val id = db.podcastDao().insert(Podcast(feedUrl = "https://$name.example/rss", title = "Show $name", newSince = 3_000))
      db.episodeDao().insertNew((1..3).map { Episode(podcastId = id, guid = "$name$it", title = "$name $it", audioUrl = "https://$name.example/$it.mp3", publishedAt = it * 1_000L) })
    }
    return db
  }

  @Test
  fun exportThenImportRestoresEverything() = runBlocking {
    val source = freshLibrary()
    val news = source.folderDao().insert(Folder(name = "News"))
    source.podcastDao().setFolder(1, news)
    source.podcastDao().setNewSince(2, 1_000)
    source.episodeDao().markFinished(1, playedAt = 5_000, advanceNew = false) // a1
    source.episodeDao().savePosition(2, positionMs = 60_000, playedAt = 6_000) // a2
    source.queueDao().append(5) // b2
    source.queueDao().append(2) // a2
    settings.setRewindOnResumeSec(30)
    settings.setContinueWithNext(true)
    val out = ByteArrayOutputStream()
    backupFor(source).export(out)

    settings.setRewindOnResumeSec(0)
    settings.setContinueWithNext(false)
    val target = freshLibrary()
    target.episodeDao().savePosition(6, positionMs = 1_000, playedAt = 7_000) // b3: kept, not in the file
    val result = backupFor(target).import(ByteArrayInputStream(out.toByteArray()))

    assertEquals(ImportResult(podcasts = 2, subscribed = 0, failed = 0), result)
    assertEquals(30, settings.rewindOnResumeSec.value)
    assertEquals(true, settings.continueWithNext.value)
    val folder = target.folderDao().getAll().single()
    assertEquals("News", folder.name)
    val podcasts = target.podcastDao().getAll().associateBy { it.feedUrl }
    assertEquals(folder.id, podcasts.getValue("https://a.example/rss").folderId)
    assertEquals(null, podcasts.getValue("https://b.example/rss").folderId)
    assertEquals(3_000L, podcasts.getValue("https://b.example/rss").newSince) // merged: never moved back
    val a1 = target.episodeDao().get(1)!!
    assertEquals(true, a1.isPlayed)
    assertEquals(5_000L, a1.lastPlayedAt)
    assertEquals(60_000L, target.episodeDao().get(2)!!.positionMs)
    assertEquals(1_000L, target.episodeDao().get(6)!!.positionMs)
    assertEquals(listOf(5L, 2L), target.queueDao().episodeIds())
    assertEquals(listOf("a 3", "b 3"), target.episodeDao().observeNew().first().map { it.episode.title }.sorted())
  }

  @Test
  fun importingTwiceChangesNothing() = runBlocking {
    val source = freshLibrary()
    source.queueDao().append(3)
    val out = ByteArrayOutputStream()
    backupFor(source).export(out)

    val target = freshLibrary()
    repeat(2) { backupFor(target).import(ByteArrayInputStream(out.toByteArray())) }

    assertEquals(listOf(3L), target.queueDao().episodeIds())
  }
}
