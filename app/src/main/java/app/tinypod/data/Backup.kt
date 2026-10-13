package app.tinypod.data

import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Everything a backup holds. Episodes and the queue are identified by feed URL and guid, since
 * database ids don't survive a reinstall. Read from another app's OPML file, only the podcasts and
 * folders are there: [settings] is null and the rest is empty.
 */
data class BackupSnapshot(
  val exportedAt: Long?,
  val settings: BackupSettings?,
  val folders: List<BackupFolder>,
  val podcasts: List<BackupPodcast>,
  val queue: List<QueueEntry>,
)

data class BackupSettings(
  val clearOlderOnFinish: Boolean,
  val continueWithNext: Boolean,
  val rewindOnResumeSec: Int,
  val speed: Float,
  val hidePlayed: Boolean = false,
)

data class BackupFolder(val name: String, val sortOrder: Int)

data class BackupPodcast(
  val feedUrl: String,
  val title: String,
  /** The name of its folder, if it's in one. */
  val folder: String?,
  /** See [Podcast.newSince]; null when the file doesn't say (another app's OPML). */
  val newSince: Long?,
  /** Only episodes with some playback state. */
  val episodes: List<BackupEpisode> = emptyList(),
)

data class BackupEpisode(val guid: String, val isPlayed: Boolean, val positionMs: Long, val lastPlayedAt: Long?)

/** How an import went: podcasts in the file, how many were newly subscribed, and how many feeds couldn't be fetched. */
data class ImportResult(val podcasts: Int, val subscribed: Int, val failed: Int)

class BackupFormatException(message: String) : Exception(message)

/**
 * Exports subscriptions, folders, playback state, the queue and settings to an OPML file, and imports
 * one by merging: nothing is deleted, unknown feeds are subscribed to, and the file's episode state wins.
 * Downloads are not included.
 *
 * The file is ordinary OPML, so other podcast apps can import its subscriptions (and folders, if they
 * understand nested outlines); Tinypod's own data sits alongside in the [Opml.NS] namespace, which they
 * ignore. In the same way, any app's OPML file can be imported here, bringing just its subscriptions.
 */
class Backup(
  private val db: TinypodDatabase,
  private val repository: PodcastRepository,
  private val settings: Settings,
  private val opml: Opml = Opml(),
) {
  private val podcastDao = db.podcastDao()
  private val episodeDao = db.episodeDao()
  private val folderDao = db.folderDao()
  private val queueDao = db.queueDao()

  suspend fun export(out: OutputStream) {
    val snapshot = snapshot(System.currentTimeMillis())
    withContext(Dispatchers.IO) { out.use { opml.write(snapshot, it) } }
  }

  suspend fun snapshot(now: Long): BackupSnapshot {
    val folders = folderDao.getAll()
    val folderNames = folders.associate { it.id to it.name }
    val episodes = episodeDao.getWithState().groupBy { it.podcastId }
    return BackupSnapshot(
      exportedAt = now,
      settings = BackupSettings(settings.clearOlderOnFinish.value, settings.continueWithNext.value, settings.rewindOnResumeSec.value, settings.speed.value, settings.hidePlayed.value),
      folders = folders.map { BackupFolder(it.name, it.sortOrder) },
      podcasts =
        podcastDao.getAll().map { p ->
          BackupPodcast(
            feedUrl = p.feedUrl,
            title = p.title,
            folder = p.folderId?.let(folderNames::get),
            newSince = p.newSince,
            episodes = episodes[p.id].orEmpty().map { BackupEpisode(it.guid, it.isPlayed, it.positionMs, it.lastPlayedAt) },
          )
        },
      queue = queueDao.entries(),
    )
  }

  /** Reads a backup (or any OPML file) and merges it in; throws [BackupFormatException] if it isn't one. */
  suspend fun import(input: InputStream): ImportResult = restore(withContext(Dispatchers.IO) { input.use(opml::read) })

  suspend fun restore(backup: BackupSnapshot): ImportResult {
    backup.settings?.let {
      settings.setClearOlderOnFinish(it.clearOlderOnFinish)
      settings.setContinueWithNext(it.continueWithNext)
      settings.setRewindOnResumeSec(it.rewindOnResumeSec)
      settings.setSpeed(it.speed)
      settings.setHidePlayed(it.hidePlayed)
    }

    val folderIds = folderDao.getAll().associate { it.name to it.id }.toMutableMap()
    for (folder in backup.folders) {
      if (folder.name !in folderIds) folderIds[folder.name] = folderDao.insert(Folder(name = folder.name, sortOrder = folder.sortOrder))
    }

    // Subscribe to the feeds not known yet, a few at a time; ones that can't be fetched are skipped.
    val known = podcastDao.getAll().associate { it.feedUrl to it.id }
    val subscribed = mutableMapOf<String, Long>()
    val failures = AtomicInteger()
    val gate = Semaphore(4)
    coroutineScope {
      backup.podcasts.filter { it.feedUrl !in known }.forEach { p ->
        launch {
          gate.withPermit {
            try {
              val podcast = repository.subscribe(p.feedUrl)
              synchronized(subscribed) { subscribed[p.feedUrl] = podcast.id }
            } catch (e: CancellationException) {
              throw e
            } catch (e: Exception) {
              Log.w(TAG, "Couldn't subscribe to ${p.feedUrl}", e)
              failures.incrementAndGet()
            }
          }
        }
      }
    }

    for (p in backup.podcasts) {
      val newlySubscribed = subscribed[p.feedUrl]
      val id = known[p.feedUrl] ?: newlySubscribed ?: continue
      p.folder?.let { name -> folderIds[name] }?.let { podcastDao.setFolder(id, it) }
      // A fresh subscription only treats its newest episode as new; take the backup's view instead.
      p.newSince?.let { if (newlySubscribed != null) podcastDao.setNewSince(id, it) else podcastDao.raiseNewSince(id, it) }
      for (e in p.episodes) episodeDao.restoreState(id, e.guid, e.isPlayed, e.positionMs, e.lastPlayedAt)
    }

    val ids = known + subscribed
    val queued = queueDao.episodeIds().toSet()
    for (entry in backup.queue) {
      val episodeId = ids[entry.feedUrl]?.let { episodeDao.findId(it, entry.guid) } ?: continue
      if (episodeId !in queued) queueDao.append(episodeId)
    }
    return ImportResult(podcasts = backup.podcasts.size, subscribed = subscribed.size, failed = failures.get())
  }

  private companion object {
    const val TAG = "Backup"
  }
}
