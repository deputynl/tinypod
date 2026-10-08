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
import org.json.JSONArray
import org.json.JSONObject

/** Everything a backup holds. Episodes and the queue are identified by feed URL and guid, since database ids don't survive a reinstall. */
data class BackupSnapshot(
  val exportedAt: Long,
  val settings: BackupSettings,
  val folders: List<BackupFolder>,
  val podcasts: List<BackupPodcast>,
  val queue: List<QueueEntry>,
)

data class BackupSettings(val clearOlderOnFinish: Boolean, val continueWithNext: Boolean, val rewindOnResumeSec: Int, val speed: Float)

data class BackupFolder(val name: String, val sortOrder: Int)

data class BackupPodcast(
  val feedUrl: String,
  val title: String,
  /** The name of its folder, if it's in one. */
  val folder: String?,
  val newSince: Long,
  /** Only episodes with some playback state. */
  val episodes: List<BackupEpisode>,
)

data class BackupEpisode(val guid: String, val isPlayed: Boolean, val positionMs: Long, val lastPlayedAt: Long?)

/** How an import went: podcasts in the file, how many were newly subscribed, and how many feeds couldn't be fetched. */
data class ImportResult(val podcasts: Int, val subscribed: Int, val failed: Int)

class BackupFormatException(message: String) : Exception(message)

/**
 * Exports subscriptions, folders, playback state, the queue and settings to a JSON file, and imports
 * one by merging: nothing is deleted, unknown feeds are subscribed to, and the file's episode state wins.
 * Downloads are not included.
 */
class Backup(private val db: TinypodDatabase, private val repository: PodcastRepository, private val settings: Settings) {
  private val podcastDao = db.podcastDao()
  private val episodeDao = db.episodeDao()
  private val folderDao = db.folderDao()
  private val queueDao = db.queueDao()

  suspend fun export(out: OutputStream) {
    val json = toJson(snapshot(System.currentTimeMillis()))
    withContext(Dispatchers.IO) { out.bufferedWriter().use { it.write(json) } }
  }

  suspend fun snapshot(now: Long): BackupSnapshot {
    val folders = folderDao.getAll()
    val folderNames = folders.associate { it.id to it.name }
    val episodes = episodeDao.getWithState().groupBy { it.podcastId }
    return BackupSnapshot(
      exportedAt = now,
      settings = BackupSettings(settings.clearOlderOnFinish.value, settings.continueWithNext.value, settings.rewindOnResumeSec.value, settings.speed.value),
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

  /** Reads a backup and merges it in; throws [BackupFormatException] if it isn't one. */
  suspend fun import(input: InputStream): ImportResult {
    val text = withContext(Dispatchers.IO) { input.bufferedReader().use { it.readText() } }
    return restore(parse(text))
  }

  suspend fun restore(backup: BackupSnapshot): ImportResult {
    backup.settings.let {
      settings.setClearOlderOnFinish(it.clearOlderOnFinish)
      settings.setContinueWithNext(it.continueWithNext)
      settings.setRewindOnResumeSec(it.rewindOnResumeSec)
      settings.setSpeed(it.speed)
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
      if (newlySubscribed != null) podcastDao.setNewSince(id, p.newSince) else podcastDao.raiseNewSince(id, p.newSince)
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

  companion object {
    private const val TAG = "Backup"
    private const val FORMAT = "tinypod-backup"
    private const val VERSION = 1

    fun toJson(s: BackupSnapshot): String =
      JSONObject()
        .put("format", FORMAT)
        .put("version", VERSION)
        .put("exportedAt", s.exportedAt)
        .put(
          "settings",
          JSONObject()
            .put("clearOlderOnFinish", s.settings.clearOlderOnFinish)
            .put("continueWithNext", s.settings.continueWithNext)
            .put("rewindOnResumeSec", s.settings.rewindOnResumeSec)
            .put("speed", s.settings.speed.toDouble()),
        )
        .put("folders", JSONArray(s.folders.map { JSONObject().put("name", it.name).put("sortOrder", it.sortOrder) }))
        .put(
          "podcasts",
          JSONArray(
            s.podcasts.map { p ->
              JSONObject()
                .put("feedUrl", p.feedUrl)
                .put("title", p.title)
                .put("folder", p.folder ?: JSONObject.NULL)
                .put("newSince", p.newSince)
                .put(
                  "episodes",
                  JSONArray(
                    p.episodes.map { e ->
                      JSONObject()
                        .put("guid", e.guid)
                        .put("isPlayed", e.isPlayed)
                        .put("positionMs", e.positionMs)
                        .put("lastPlayedAt", e.lastPlayedAt ?: JSONObject.NULL)
                    }
                  ),
                )
            }
          ),
        )
        .put("queue", JSONArray(s.queue.map { JSONObject().put("feedUrl", it.feedUrl).put("guid", it.guid) }))
        .toString(2)

    fun parse(json: String): BackupSnapshot {
      val root = runCatching { JSONObject(json) }.getOrElse { throw BackupFormatException("Not a Tinypod backup") }
      if (root.optString("format") != FORMAT) throw BackupFormatException("Not a Tinypod backup")
      val version = root.optInt("version")
      if (version != VERSION) throw BackupFormatException("Unsupported backup version $version")
      return try {
        val settings = root.getJSONObject("settings")
        BackupSnapshot(
          exportedAt = root.optLong("exportedAt"),
          settings =
            BackupSettings(
              clearOlderOnFinish = settings.optBoolean("clearOlderOnFinish", true),
              continueWithNext = settings.optBoolean("continueWithNext", false),
              rewindOnResumeSec = settings.optInt("rewindOnResumeSec", 10),
              speed = settings.optDouble("speed", 1.0).toFloat(),
            ),
          folders = root.getJSONArray("folders").objects().map { BackupFolder(it.getString("name"), it.optInt("sortOrder")) },
          podcasts =
            root.getJSONArray("podcasts").objects().map { p ->
              BackupPodcast(
                feedUrl = p.getString("feedUrl"),
                title = p.optString("title"),
                folder = p.optStringOrNull("folder"),
                newSince = p.optLong("newSince"),
                episodes =
                  p.getJSONArray("episodes").objects().map { e ->
                    BackupEpisode(
                      guid = e.getString("guid"),
                      isPlayed = e.optBoolean("isPlayed"),
                      positionMs = e.optLong("positionMs"),
                      lastPlayedAt = if (e.isNull("lastPlayedAt")) null else e.getLong("lastPlayedAt"),
                    )
                  },
              )
            },
          queue = root.getJSONArray("queue").objects().map { QueueEntry(it.getString("feedUrl"), it.getString("guid")) },
        )
      } catch (e: org.json.JSONException) {
        throw BackupFormatException("Damaged backup: ${e.message}")
      }
    }

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map(::getJSONObject)

    private fun JSONObject.optStringOrNull(key: String): String? = if (isNull(key)) null else getString(key)
  }
}
