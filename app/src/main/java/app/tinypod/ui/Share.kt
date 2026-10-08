package app.tinypod.ui

import android.content.Context
import android.content.Intent
import app.tinypod.data.EpisodeWithPodcast
import app.tinypod.data.Podcast

/** The podcast's title, its website if it has one, and its feed for adding it to a podcast app. */
fun podcastShareText(podcast: Podcast): String = listOfNotNull(podcast.title, podcast.link, "RSS: ${podcast.feedUrl}").joinToString("\n")

/** "Episode — Podcast", then the best link there is: the episode's page, the podcast's site, or the audio itself. */
fun episodeShareText(row: EpisodeWithPodcast): String {
  val e = row.episode
  val link = e.link ?: row.podcastLink ?: e.audioUrl
  return "${e.title} — ${row.podcastTitle}\n$link"
}

/** Opens the system share sheet with [text]. */
fun share(context: Context, text: String) {
  val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
  context.startActivity(Intent.createChooser(send, null))
}
