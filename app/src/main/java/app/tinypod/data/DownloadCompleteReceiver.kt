package app.tinypod.data

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.tinypod.TinypodApp
import kotlinx.coroutines.launch

/**
 * Told by DownloadManager when a download stops, successfully or not. Exported because the
 * broadcast comes from the system's download provider; a forged one is harmless, since
 * [Downloads.finished] checks the real status with DownloadManager.
 */
class DownloadCompleteReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1).takeIf { it != -1L } ?: return
    val app = context.applicationContext as TinypodApp
    val pending = goAsync()
    app.scope.launch {
      try {
        app.downloads.finished(downloadId)
      } finally {
        pending.finish()
      }
    }
  }
}
