package app.tinypod.ui

import app.tinypod.data.Episode
import app.tinypod.data.EpisodeWithPodcast
import app.tinypod.data.Podcast
import org.junit.Assert.assertEquals
import org.junit.Test

class ShareTextTest {
  private val podcast = Podcast(feedUrl = "https://example.com/feed.xml", title = "The Show")

  private fun row(link: String?, podcastLink: String?) =
    EpisodeWithPodcast(
      Episode(podcastId = 1, guid = "g", title = "Ep 1", audioUrl = "https://cdn.example.com/1.mp3", publishedAt = 0, link = link),
      podcastTitle = "The Show",
      artworkUrl = null,
      podcastLink = podcastLink,
    )

  @Test
  fun `podcast with and without a website`() {
    assertEquals("The Show\nhttps://example.com\nRSS: https://example.com/feed.xml", podcastShareText(podcast.copy(link = "https://example.com")))
    assertEquals("The Show\nRSS: https://example.com/feed.xml", podcastShareText(podcast))
  }

  @Test
  fun `episode prefers its own page, then the podcast's, then the audio`() {
    assertEquals("Ep 1 — The Show\nhttps://example.com/1", episodeShareText(row("https://example.com/1", "https://example.com")))
    assertEquals("Ep 1 — The Show\nhttps://example.com", episodeShareText(row(null, "https://example.com")))
    assertEquals("Ep 1 — The Show\nhttps://cdn.example.com/1.mp3", episodeShareText(row(null, null)))
  }
}
