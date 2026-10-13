package app.tinypod

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tinypod.player.LocalPlayer
import app.tinypod.player.PlayerConnection
import app.tinypod.theme.TinypodTheme
import app.tinypod.ui.LocalDownloadProgress
import app.tinypod.ui.LocalQueuedEpisodes
import kotlinx.coroutines.flow.map

class MainActivity : ComponentActivity() {
  private lateinit var player: PlayerConnection

  /** Set when the notification asks for the full-screen player; cleared once it's shown. */
  private var openFullPlayer by mutableStateOf(false)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    player = PlayerConnection(this)
    // A recreated activity (rotation, restored process) or one relaunched from Recents gets the
    // original intent again: it was already handled, so only act on a fresh launch.
    if (savedInstanceState == null && intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0) handleIntent(intent)

    enableEdgeToEdge()
    setContent {
      val app = application as TinypodApp
      val downloads by app.downloads.progress.collectAsStateWithLifecycle(emptyMap())
      val queued by remember { app.database.queueDao().observeIds().map { it.toSet() } }.collectAsStateWithLifecycle(emptySet())
      CompositionLocalProvider(LocalPlayer provides player, LocalDownloadProgress provides downloads, LocalQueuedEpisodes provides queued) {
        TinypodTheme {
          Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            MainNavigation(openFullPlayer = openFullPlayer, onFullPlayerOpened = { openFullPlayer = false })
          }
        }
      }
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    handleIntent(intent)
  }

  private fun handleIntent(intent: Intent) {
    if (intent.getBooleanExtra(EXTRA_OPEN_FULL_PLAYER, false)) {
      intent.removeExtra(EXTRA_OPEN_FULL_PLAYER)
      openFullPlayer = true
    }
  }

  override fun onStart() {
    super.onStart()
    player.connect()
  }

  override fun onStop() {
    player.release()
    super.onStop()
  }

  override fun onDestroy() {
    player.dispose()
    super.onDestroy()
  }

  companion object {
    /** Intent extra (boolean) that opens the full-screen player, used by the playback notification. */
    const val EXTRA_OPEN_FULL_PLAYER = "open_full_player"
  }
}
