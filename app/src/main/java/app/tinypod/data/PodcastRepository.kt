package app.tinypod.data

import android.util.Log
import app.tinypod.feed.Http
import app.tinypod.feed.ParsedEpisode
import app.tinypod.feed.ParsedFeed
import app.tinypod.feed.RssParser
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Subscribing to feeds and keeping their episodes up to date. */
class PodcastRepository(private val db: TinypodDatabase, private val downloads: Downloads, private val parser: RssParser = RssParser()) {
  private val podcasts = db.podcastDao()
  private val episodes = db.episodeDao()

  /** Fetches [feedUrl] and subscribes to it, or returns the existing podcast if already subscribed. */
  suspend fun subscribe(feedUrl: String): Podcast {
    val url = normalizeFeedUrl(feedUrl)
    podcasts.findByFeedUrl(url)?.let { return it }

    val now = System.currentTimeMillis()
    val feed = fetch(url)
    val parsed = feed.episodes.map { it.toEpisode(podcastId = 0, fallbackDate = now) }
    val podcast =
      Podcast(
        feedUrl = url,
        title = feed.title,
        author = feed.author,
        description = feed.description,
        artworkUrl = feed.artworkUrl,
        link = feed.link,
        lastFetchedAt = now,
        newSince = parsed.maxOfOrNull { it.publishedAt } ?: now,
      )
    val id = podcasts.insert(podcast)
    episodes.upsertFromFeed(parsed.map { it.copy(podcastId = id) })
    return podcast.copy(id = id)
  }

  suspend fun unsubscribe(podcast: Podcast) {
    downloads.removeForPodcast(podcast.id)
    podcasts.delete(podcast)
  }

  suspend fun refresh(podcast: Podcast) {
    val now = System.currentTimeMillis()
    val feed = fetch(podcast.feedUrl)
    episodes.upsertFromFeed(feed.episodes.map { it.toEpisode(podcast.id, fallbackDate = now) })
    podcasts.updateFeedInfo(podcast.id, feed.title, feed.author, feed.description, feed.artworkUrl, feed.link, now)
  }

  /**
   * Refreshes every podcast not fetched within [olderThanMs], a few at a time. One broken feed
   * doesn't stop the rest; returns how many failed.
   */
  suspend fun refreshAll(olderThanMs: Long = 0): Int {
    val cutoff = System.currentTimeMillis() - olderThanMs
    val due = podcasts.getAll().filter { (it.lastFetchedAt ?: 0) <= cutoff }
    val gate = Semaphore(4)
    val failures = AtomicInteger()
    coroutineScope {
      due.forEach { podcast ->
        launch {
          gate.withPermit {
            try {
              refresh(podcast)
            } catch (e: CancellationException) {
              throw e
            } catch (e: Exception) {
              Log.w(TAG, "Refresh failed for ${podcast.feedUrl}", e)
              failures.incrementAndGet()
            }
          }
        }
      }
    }
    return failures.get()
  }

  private suspend fun fetch(url: String): ParsedFeed = Http.get(url) { parser.parse(it) }

  private fun ParsedEpisode.toEpisode(podcastId: Long, fallbackDate: Long) =
    Episode(
      podcastId = podcastId,
      guid = guid,
      title = title,
      audioUrl = audioUrl,
      publishedAt = publishedAt ?: fallbackDate,
      durationMs = durationMs,
      description = description,
      link = link,
    )

  companion object {
    private const val TAG = "PodcastRepository"

    /** Accepts what people paste: bare hosts, feed:// and itpc:// links. */
    fun normalizeFeedUrl(input: String): String {
      val s = input.trim()
      return when {
        s.startsWith("feed://") -> "https://" + s.removePrefix("feed://")
        s.startsWith("itpc://") -> "https://" + s.removePrefix("itpc://")
        s.startsWith("http://") || s.startsWith("https://") -> s
        else -> "https://$s"
      }
    }

    fun looksLikeUrl(input: String): Boolean {
      val s = input.trim()
      return !s.contains(' ') && (s.contains("://") || Regex("^[\\w-]+(\\.[\\w-]+)+(/.*)?$").matches(s))
    }
  }
}
