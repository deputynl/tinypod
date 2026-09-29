package app.tinypod.feed

import org.junit.Assert.assertEquals
import org.junit.Test

class PodcastSearchTest {
  @Test
  fun `maps iTunes results and drops ones without a feed`() {
    val json =
      """
      {"resultCount": 3, "results": [
        {"collectionName": "Show A", "artistName": "Maker A", "feedUrl": "https://a.example/rss",
         "artworkUrl100": "https://a.example/100.jpg", "artworkUrl600": "https://a.example/600.jpg"},
        {"collectionName": "Apple-only show", "artistName": "Nobody"},
        {"trackName": "Show C", "feedUrl": "https://c.example/rss", "artworkUrl100": "https://c.example/100.jpg"}
      ]}
      """

    val results = PodcastSearch.parseResults(json)

    assertEquals(
      listOf(
        SearchResult("Show A", "Maker A", "https://a.example/rss", "https://a.example/600.jpg"),
        SearchResult("Show C", null, "https://c.example/rss", "https://c.example/100.jpg"),
      ),
      results,
    )
  }

  @Test
  fun `keeps only the first result per feed`() {
    val json =
      """
      {"resultCount": 2, "results": [
        {"collectionName": "Show A", "feedUrl": "https://a.example/rss"},
        {"collectionName": "Show A (again)", "feedUrl": "https://a.example/rss"}
      ]}
      """

    val results = PodcastSearch.parseResults(json)

    assertEquals(listOf(SearchResult("Show A", null, "https://a.example/rss", null)), results)
  }
}
