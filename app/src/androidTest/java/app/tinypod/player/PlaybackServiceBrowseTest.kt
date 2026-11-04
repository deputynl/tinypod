package app.tinypod.player

import android.content.ComponentName
import android.os.Bundle
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import androidx.media3.session.legacy.MediaBrowserCompat as LegacyBrowser
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.tinypod.TinypodApp
import app.tinypod.data.Episode
import app.tinypod.data.Podcast
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Browses the real service the way Android Auto does. Safe on a device with real data: what it adds, it removes. */
@RunWith(AndroidJUnit4::class)
class PlaybackServiceBrowseTest {
  @Test
  fun serviceServesTheBrowseTree() {
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
    // The browser must be used on the main thread, but waiting on its futures there would deadlock.
    val browser = onMain { MediaBrowser.Builder(context, token).buildAsync() }.get(10, TimeUnit.SECONDS)
    try {
      val root = onMain { browser.getLibraryRoot(null) }.get(10, TimeUnit.SECONDS)
      assertEquals(LibraryResult.RESULT_SUCCESS, root.resultCode)
      val tabs = onMain { browser.getChildren(root.value!!.mediaId, 0, 10, null) }.get(10, TimeUnit.SECONDS)
      assertEquals(listOf("New", "Library", "Queue", "History"), tabs.value!!.map { it.mediaMetadata.title.toString() })
      val library = onMain { browser.getChildren("library", 0, 100, null) }.get(10, TimeUnit.SECONDS)
      assertEquals(LibraryResult.RESULT_SUCCESS, library.resultCode)
      // The car subscribes to what it shows, which needs each node to resolve; Downloads is no longer a tab.
      for (node in listOf("history", "downloads")) {
        assertEquals(LibraryResult.RESULT_SUCCESS, onMain { browser.subscribe(node, null) }.get(10, TimeUnit.SECONDS).resultCode)
        onMain { browser.unsubscribe(node) }.get(10, TimeUnit.SECONDS)
      }
    } finally {
      onMain { browser.release() }
    }
  }

  /**
   * Android Auto is a legacy browser and doesn't reload a list unless it's told to. It has to be
   * MediaBrowserCompat: the platform's own MediaBrowser never reaches onSubscribe, so it's not sent changes.
   */
  @Test
  fun aListTheCarIsShowingIsPushedWhenItChanges() {
    val app = ApplicationProvider.getApplicationContext<TinypodApp>()
    val ready = CountDownLatch(1)
    val loads = LinkedBlockingQueue<List<String>>()
    var browser: LegacyBrowser? = null
    val podcastId = runBlocking { app.database.podcastDao().insert(Podcast(feedUrl = "https://push.example/${System.nanoTime()}", title = "Push test")) }
    try {
      onMain {
        browser = LegacyBrowser(app, ComponentName(app, PlaybackService::class.java), object : LegacyBrowser.ConnectionCallback() {
          override fun onConnected() = ready.countDown()
        }, null).apply { connect() }
      }
      assertTrue(ready.await(10, TimeUnit.SECONDS))
      onMain {
        browser!!.subscribe("podcast/$podcastId", Bundle.EMPTY, object : LegacyBrowser.SubscriptionCallback() {
          override fun onChildrenLoaded(parentId: String?, children: MutableList<LegacyBrowser.MediaItem>?, options: Bundle?) {
            loads.add(children.orEmpty().map { it.getDescription().getTitle().toString() })
          }
        })
      }
      assertEquals(emptyList<String>(), loads.poll(10, TimeUnit.SECONDS))
      val episode = Episode(podcastId = podcastId, guid = "1", title = "Fresh", audioUrl = "https://push.example/1.mp3", publishedAt = 1)
      runBlocking { app.database.episodeDao().insertNew(listOf(episode)) }
      assertEquals(listOf("Fresh"), loads.poll(10, TimeUnit.SECONDS))
    } finally {
      onMain { browser?.disconnect() }
      runBlocking { app.database.podcastDao().delete(Podcast(id = podcastId, feedUrl = "", title = "")) }
    }
  }

  @Test
  fun offersSkipAndSpeedButtonsAndTheSpeedButtonCycles() {
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
    val browser = onMain { MediaBrowser.Builder(context, token).buildAsync() }.get(10, TimeUnit.SECONDS)
    val original = onMain { browser.playbackParameters.speed }
    try {
      val buttons = onMain { browser.mediaButtonPreferences }
      assertEquals(listOf("Back 10 seconds", "Forward 30 seconds", "Speed ${PlaybackService.formatSpeed(original)}"), buttons.map { it.displayName.toString() })

      val speed = buttons.last().sessionCommand!!
      onMain { browser.sendCustomCommand(speed, Bundle.EMPTY) }.get(10, TimeUnit.SECONDS)
      val expected = PlaybackService.SPEEDS.firstOrNull { it > original + 0.01f } ?: PlaybackService.SPEEDS.first()
      Thread.sleep(500) // let the new speed reach this controller
      assertEquals(expected, onMain { browser.playbackParameters.speed })
      assertEquals("Speed ${PlaybackService.formatSpeed(expected)}", onMain { browser.mediaButtonPreferences.last().displayName.toString() })
    } finally {
      onMain { browser.setPlaybackSpeed(original) } // leave the user's speed as it was
      Thread.sleep(300)
      onMain { browser.release() }
    }
  }

  private fun <T> onMain(block: () -> T): T {
    var result: Result<T>? = null
    InstrumentationRegistry.getInstrumentation().runOnMainSync { result = runCatching(block) }
    return result!!.getOrThrow()
  }
}
