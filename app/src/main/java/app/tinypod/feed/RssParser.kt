package app.tinypod.feed

import android.util.Xml
import java.io.InputStream
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.xmlpull.v1.XmlPullParser

data class ParsedFeed(
  val title: String,
  val author: String?,
  val description: String?,
  val artworkUrl: String?,
  val episodes: List<ParsedEpisode>,
  /** The show's website. */
  val link: String? = null,
)

data class ParsedEpisode(
  val guid: String,
  val title: String,
  val audioUrl: String,
  /** Epoch millis, or null when the feed's date couldn't be parsed. */
  val publishedAt: Long?,
  val durationMs: Long?,
  val description: String?,
  /** The episode's web page. */
  val link: String? = null,
)

class FeedParseException(message: String) : Exception(message)

/**
 * Minimal podcast RSS 2.0 parser: channel metadata plus one entry per <item> that has an audio
 * <enclosure>. Understands the handful of itunes: and content: tags podcasts actually rely on.
 *
 * [newParser] exists so JVM unit tests can supply a real parser; on-device, Android's built-in one is used.
 */
class RssParser(private val newParser: () -> XmlPullParser = { Xml.newPullParser() }) {

  fun parse(input: InputStream): ParsedFeed {
    val parser = newParser()
    parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
    parser.setInput(input, null)

    var channelTitle: String? = null
    var author: String? = null
    var description: String? = null
    var itunesImage: String? = null
    var rssImage: String? = null
    var link: String? = null
    val episodes = mutableListOf<ParsedEpisode>()
    var sawChannel = false

    while (parser.next() != XmlPullParser.END_DOCUMENT) {
      if (parser.eventType != XmlPullParser.START_TAG) continue
      // Only direct children of <channel> are channel metadata; depth is rss(1) > channel(2) > child(3).
      if (parser.depth == 2 && parser.name == "channel" && parser.namespace.isEmpty()) sawChannel = true
      if (parser.depth != 3) continue
      when (parser.key()) {
        "title" -> channelTitle = parser.text()
        "itunes:author" -> author = parser.text()
        "description" -> description = description ?: parser.text()
        "itunes:summary" -> description = description ?: parser.text()
        "itunes:image" -> itunesImage = parser.getAttributeValue(null, "href")?.trim()
        "image" -> rssImage = parser.readImageUrl()
        "link" -> link = parser.text() // atom:link (the feed's own URL) has a namespace, so it's not this
        "item" -> parser.readItem()?.let(episodes::add)
      }
    }

    if (!sawChannel) throw FeedParseException("Not an RSS podcast feed")
    return ParsedFeed(
      title = channelTitle?.takeIf { it.isNotBlank() } ?: "Untitled podcast",
      author = author?.takeIf { it.isNotBlank() },
      description = description?.takeIf { it.isNotBlank() },
      artworkUrl = (itunesImage ?: rssImage)?.takeIf { it.isNotBlank() },
      episodes = episodes,
      link = link?.takeIf { it.isNotBlank() },
    )
  }

  private fun XmlPullParser.readItem(): ParsedEpisode? {
    val itemDepth = depth
    var title: String? = null
    var guid: String? = null
    var audioUrl: String? = null
    var pubDate: String? = null
    var duration: String? = null
    var description: String? = null
    var summary: String? = null
    var contentEncoded: String? = null
    var link: String? = null

    while (!(next() == XmlPullParser.END_TAG && depth == itemDepth)) {
      if (eventType == XmlPullParser.END_DOCUMENT) break
      if (eventType != XmlPullParser.START_TAG || depth != itemDepth + 1) continue
      when (key()) {
        "title" -> title = text()
        "guid" -> guid = text()
        "enclosure" -> if (audioUrl == null) audioUrl = getAttributeValue(null, "url")?.trim()
        "pubDate" -> pubDate = text()
        "itunes:duration" -> duration = text()
        "description" -> description = text()
        "itunes:summary" -> summary = text()
        "content:encoded" -> contentEncoded = text()
        "link" -> link = text()
      }
    }

    val url = audioUrl?.takeIf { it.isNotBlank() } ?: return null
    return ParsedEpisode(
      guid = guid?.takeIf { it.isNotBlank() } ?: url,
      title = title?.takeIf { it.isNotBlank() } ?: "Untitled episode",
      audioUrl = url,
      publishedAt = pubDate?.let(::parseRssDate),
      durationMs = duration?.let(::parseDuration),
      description = listOf(contentEncoded, description, summary).firstOrNull { !it.isNullOrBlank() },
      link = link?.takeIf { it.isNotBlank() },
    )
  }

  /** Reads the <url> child of an RSS <image> element. */
  private fun XmlPullParser.readImageUrl(): String? {
    val imageDepth = depth
    var url: String? = null
    while (!(next() == XmlPullParser.END_TAG && depth == imageDepth)) {
      if (eventType == XmlPullParser.END_DOCUMENT) break
      if (eventType == XmlPullParser.START_TAG && key() == "url") url = text()
    }
    return url
  }

  /** Element name with a conventional prefix for the namespaces we care about, e.g. "itunes:image". */
  private fun XmlPullParser.key(): String =
    when (namespace) {
      ITUNES_NS -> "itunes:$name"
      CONTENT_NS -> "content:$name"
      "" -> name
      else -> "?:$name"
    }

  /** Reads the text of the current element (including CDATA) and leaves the parser on its end tag. */
  private fun XmlPullParser.text(): String {
    val startDepth = depth
    val sb = StringBuilder()
    while (!(next() == XmlPullParser.END_TAG && depth == startDepth)) {
      when (eventType) {
        XmlPullParser.TEXT -> sb.append(text)
        XmlPullParser.END_DOCUMENT -> break
      }
    }
    return sb.toString().trim()
  }

  companion object {
    const val ITUNES_NS = "http://www.itunes.com/dtds/podcast-1.0.dtd"
    const val CONTENT_NS = "http://purl.org/rss/1.0/modules/content/"

    // Real-world feeds deviate from RFC 822 a lot; try the strict parser, then common variants.
    private val fallbackPatterns =
      listOf(
        "EEE, d MMM yyyy HH:mm:ss zzz",
        "EEE, d MMM yyyy HH:mm zzz",
        "d MMM yyyy HH:mm:ss zzz",
        "EEE, d MMM yyyy HH:mm:ss Z",
        "d MMM yyyy HH:mm:ss Z",
        "EEE, d MMM yyyy",
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd",
      )

    fun parseRssDate(raw: String): Long? {
      val s = raw.trim().replace(Regex("\\s+"), " ")
      runCatching { return ZonedDateTime.parse(s, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }
      for (pattern in fallbackPatterns) {
        val fmt = SimpleDateFormat(pattern, Locale.US).apply { isLenient = false }
        val pos = ParsePosition(0)
        val date = fmt.parse(s, pos)
        if (date != null && pos.index == s.length) return date.time
      }
      return null
    }

    /** Accepts "HH:MM:SS", "MM:SS" or plain seconds (possibly fractional). */
    fun parseDuration(raw: String): Long? {
      val parts = raw.trim().split(":")
      if (parts.size > 3 || parts.any { it.isBlank() }) return null
      var seconds = 0.0
      for (part in parts) seconds = seconds * 60 + (part.toDoubleOrNull() ?: return null)
      return (seconds * 1000).toLong().takeIf { it > 0 }
    }
  }
}
