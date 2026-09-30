package app.tinypod.player

import android.content.ComponentName
import android.os.Bundle
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Browses the real service the way Android Auto does. Read-only, so it's safe on a device with real data. */
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
      assertEquals(listOf("New", "Library", "Queue", "Downloads"), tabs.value!!.map { it.mediaMetadata.title.toString() })
      val library = onMain { browser.getChildren("library", 0, 100, null) }.get(10, TimeUnit.SECONDS)
      assertEquals(LibraryResult.RESULT_SUCCESS, library.resultCode)
    } finally {
      onMain { browser.release() }
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
