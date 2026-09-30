package app.tinypod

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    player = PlayerConnection(this)

    enableEdgeToEdge()
    setContent {
      val app = application as TinypodApp
      val downloads by app.downloads.progress.collectAsStateWithLifecycle(emptyMap())
      val queued by remember { app.database.queueDao().observeIds().map { it.toSet() } }.collectAsStateWithLifecycle(emptySet())
      CompositionLocalProvider(LocalPlayer provides player, LocalDownloadProgress provides downloads, LocalQueuedEpisodes provides queued) {
        TinypodTheme { Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { MainNavigation() } }
      }
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
}
