package app.tinypod.data

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadFileNameTest {
  private fun name(url: String) = Downloads.fileName(Episode(id = 7, podcastId = 1, guid = "g", title = "t", audioUrl = url, publishedAt = 0))

  @Test fun `keeps the audio extension`() = assertEquals("episode-7.mp3", name("https://cdn.example/show/ep7.MP3"))

  @Test fun `ignores query strings and fragments`() = assertEquals("episode-7.m4a", name("https://cdn.example/ep7.m4a?token=abc.def#t=10"))

  @Test fun `falls back when there is no usable extension`() = assertEquals("episode-7.audio", name("https://tracker.example/redirect/ep7"))
}
