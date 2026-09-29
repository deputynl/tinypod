package app.tinypod.data

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import android.widget.Toast
import androidx.core.net.toUri
import app.tinypod.feed.Http
import app.tinypod.feed.HttpException
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Episode downloads via the system DownloadManager, which runs them in the background (surviving the
 * app being closed), retries, resumes, and shows its own progress notification. Files go to the
 * app's own external storage, so no permissions are needed; the player prefers them over streaming.
 *
 * An episode is "downloading" while [Episode.downloadId] is set and "downloaded" once
 * [Episode.localFilePath] is. Completion arrives via [DownloadCompleteReceiver]; [reconcile] catches
 * up on anything missed while the app wasn't running.
 */
class Downloads(
  private val context: Context,
  db: TinypodDatabase,
  /** Where files go; null if external storage is unavailable, which DownloadManager needs even for app-private files. */
  private val directory: () -> File? = { context.getExternalFilesDir(Environment.DIRECTORY_PODCASTS) },
) {
  private val episodes = db.episodeDao()
  private val manager = context.getSystemService(DownloadManager::class.java)
  /** [finished] can be reached from the completion broadcast and the progress poll at the same time. */
  private val finishing = Mutex()
  private val dir: File?
    get() = directory()

  /** Progress (0..1, or null while the size is unknown) of each episode being downloaded, polled while collected. */
  @OptIn(ExperimentalCoroutinesApi::class)
  val progress: Flow<Map<Long, Float?>> =
    episodes.observeActiveDownloads().flatMapLatest { active ->
      if (active.isEmpty()) flowOf(emptyMap())
      else
        flow {
          while (true) {
            val statuses = query(active.map { it.downloadId })
            emit(active.associate { it.episodeId to statuses[it.downloadId]?.fraction })
            // In case the completion broadcast is late or lost.
            statuses.filterValues { it.isFinished }.keys.forEach { finished(it) }
            delay(1_000)
          }
        }.flowOn(Dispatchers.IO)
    }

  suspend fun start(episodeId: Long) =
    withContext(Dispatchers.IO) {
      val row = episodes.getWithPodcast(episodeId) ?: return@withContext
      val episode = row.episode
      if (episode.isDownloaded || episode.isDownloading) return@withContext
      val dir = dir ?: return@withContext toast("Can't download: storage unavailable")
      val file = File(dir.apply { mkdirs() }, fileName(episode))
      file.delete() // DownloadManager would pick another name rather than overwrite a leftover
      // DownloadManager gives up after a few redirects, fewer than many podcast URLs chain; follow them here.
      // (If the connection drops later, DownloadManager resumes from this resolved URL by itself.)
      val source =
        try {
          Http.resolveRedirects(episode.audioUrl)
        } catch (e: HttpException) {
          Log.w(TAG, "Couldn't start download of episode $episodeId", e)
          return@withContext toast("Download failed: ${episode.title}")
        } catch (e: IOException) {
          // Probably offline. Queueing the original URL would only hit the redirect limit later.
          Log.w(TAG, "Couldn't reach ${episode.audioUrl}", e)
          return@withContext toast("Can't download while offline")
        }
      val request =
        DownloadManager.Request(source.toUri())
          .setDestinationUri(Uri.fromFile(file))
          .setTitle(episode.title)
          .setDescription(row.podcastTitle)
          .addRequestHeader("User-Agent", Http.USER_AGENT)
          .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
      try {
        episodes.setDownloadId(episodeId, manager.enqueue(request))
      } catch (e: IllegalArgumentException) { // e.g. storage unavailable, or an unsupported URL scheme
        Log.w(TAG, "Couldn't start download of episode $episodeId", e)
        toast("Couldn't start download: ${episode.title}")
      }
    }

  suspend fun cancel(episodeId: Long) =
    withContext(Dispatchers.IO) {
      val downloadId = episodes.get(episodeId)?.downloadId ?: return@withContext
      manager.remove(downloadId) // also deletes the partial file
      episodes.setDownloadId(episodeId, null)
    }

  suspend fun delete(episodeId: Long) =
    withContext(Dispatchers.IO) {
      val path = episodes.get(episodeId)?.localFilePath ?: return@withContext
      File(path).delete()
      episodes.setLocalFile(episodeId, null)
    }

  /** Cancels or deletes all downloads of a podcast, before unsubscribing from it. */
  suspend fun removeForPodcast(podcastId: Long) =
    withContext(Dispatchers.IO) {
      episodes.getDownloadsForPodcast(podcastId).forEach {
        it.downloadId?.let(manager::remove)
        it.localFilePath?.let { path -> File(path).delete() }
      }
    }

  /** Records the outcome of a DownloadManager download that has stopped. Safe to call more than once. */
  suspend fun finished(downloadId: Long) =
    withContext(Dispatchers.IO) { finishing.withLock { finishedLocked(downloadId) } }

  private suspend fun finishedLocked(downloadId: Long) {
    // Not ours any more (cancelled, or the podcast was unsubscribed): leave it; [reconcile] removes stray files.
    val episode = episodes.findByDownloadId(downloadId) ?: return
    val status = query(listOf(downloadId))[downloadId]
    when {
      status == null -> episodes.setDownloadId(episode.id, null) // DownloadManager forgot it
      status.status == DownloadManager.STATUS_SUCCESSFUL && status.path != null -> episodes.setLocalFile(episode.id, status.path)
      status.isFinished -> {
        Log.w(TAG, "Download of episode ${episode.id} failed, reason ${status.reason}")
        manager.remove(downloadId)
        episodes.setDownloadId(episode.id, null)
        toast("Download failed: ${episode.title}")
      }
    }
  }

  private suspend fun toast(text: String) = withContext(Dispatchers.Main) { Toast.makeText(context, text, Toast.LENGTH_LONG).show() }

  /** Brings the database in line with DownloadManager and the files on disk; run at startup. */
  suspend fun reconcile() =
    withContext(Dispatchers.IO) {
      for (episode in episodes.getWithDownloads()) {
        val downloadId = episode.downloadId
        when {
          downloadId != null -> finished(downloadId) // no-op while still running
          episode.localFilePath?.let { File(it).exists() } == false -> episodes.setLocalFile(episode.id, null)
        }
      }
      // Remove our files that no episode refers to any more (e.g. finished after an unsubscribe).
      val keep = episodes.getWithDownloads().map { it.id }.toSet()
      dir?.listFiles()?.forEach { file ->
        val id = FILE_NAME.matchEntire(file.name)?.groupValues?.get(1)?.toLongOrNull() ?: return@forEach
        if (id !in keep) file.delete()
      }
    }

  private class Status(val status: Int, val reason: Int, val bytes: Long, val total: Long, val path: String?) {
    val isFinished
      get() = status == DownloadManager.STATUS_SUCCESSFUL || status == DownloadManager.STATUS_FAILED

    val fraction
      get() = if (total > 0) (bytes.toFloat() / total).coerceIn(0f, 1f) else null
  }

  private fun query(ids: List<Long>): Map<Long, Status> {
    if (ids.isEmpty()) return emptyMap()
    val result = mutableMapOf<Long, Status>()
    manager.query(DownloadManager.Query().setFilterById(*ids.toLongArray()))?.use { c ->
      val id = c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)
      val status = c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
      val reason = c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)
      val bytes = c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
      val total = c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
      val uri = c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI)
      while (c.moveToNext()) {
        result[c.getLong(id)] = Status(c.getInt(status), c.getInt(reason), c.getLong(bytes), c.getLong(total), c.getString(uri)?.toUri()?.path)
      }
    }
    return result
  }

  companion object {
    private const val TAG = "Downloads"
    private val FILE_NAME = Regex("""episode-(\d+)\.\w+""")

    /** "episode-<id>.<ext>", keeping the audio URL's extension when it has a plausible one. */
    internal fun fileName(episode: Episode): String {
      val ext = Regex("""\.([A-Za-z0-9]{2,4})$""").find(episode.audioUrl.substringBefore('?').substringBefore('#'))?.groupValues?.get(1)
      return "episode-${episode.id}.${ext?.lowercase() ?: "audio"}"
    }
  }
}
