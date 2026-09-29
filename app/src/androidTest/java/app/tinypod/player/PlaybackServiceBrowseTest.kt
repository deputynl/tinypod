package app.tinypod.player

import android.content.ComponentName
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

  private fun <T> onMain(block: () -> T): T {
    var result: Result<T>? = null
    InstrumentationRegistry.getInstrumentation().runOnMainSync { result = runCatching(block) }
    return result!!.getOrThrow()
  }
}
