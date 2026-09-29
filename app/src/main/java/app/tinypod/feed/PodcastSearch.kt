package app.tinypod.feed

import java.net.URLEncoder
import org.json.JSONObject

data class SearchResult(
  val title: String,
  val author: String?,
  val feedUrl: String,
  val artworkUrl: String?,
)

/** Podcast search via Apple's iTunes Search API (no key needed). Results are not persisted. */
object PodcastSearch {
  suspend fun search(term: String): List<SearchResult> {
    val q = URLEncoder.encode(term.trim(), "UTF-8")
    val url = "https://itunes.apple.com/search?media=podcast&entity=podcast&limit=30&term=$q"
    return Http.get(url) { parseResults(it.bufferedReader().readText()) }
  }

  fun parseResults(json: String): List<SearchResult> {
    val results = JSONObject(json).optJSONArray("results") ?: return emptyList()
    return (0 until results.length()).mapNotNull { i ->
      val r = results.getJSONObject(i)
      val feedUrl = r.optString("feedUrl").takeIf { it.isNotBlank() } ?: return@mapNotNull null
      SearchResult(
        title = r.optString("collectionName").ifBlank { r.optString("trackName") },
        author = r.optString("artistName").takeIf { it.isNotBlank() },
        feedUrl = feedUrl,
        artworkUrl = listOf("artworkUrl600", "artworkUrl100").map(r::optString).firstOrNull { it.isNotBlank() },
      )
    }.distinctBy { it.feedUrl } // iTunes can list the same feed twice; the UI keys rows by feedUrl
  }
}
