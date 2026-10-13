package app.tinypod.data

import android.util.Xml
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import org.xmlpull.v1.XmlSerializer

/**
 * Reads and writes [BackupSnapshot]s as OPML 2.0. Podcasts are `<outline xmlUrl="…">` entries, inside
 * an outline per folder; everything else Tinypod keeps rides along in the [NS] namespace:
 *
 * ```
 * <opml version="2.0" xmlns:tinypod="…">
 *   <head>
 *     <tinypod:backup version="1" exportedAt="…"/>
 *     <tinypod:settings speed="1.25" …/>
 *     <tinypod:queue><tinypod:item feedUrl="…" guid="…"/></tinypod:queue>
 *   </head>
 *   <body>
 *     <outline text="News" tinypod:sortOrder="0">
 *       <outline type="rss" text="Show" xmlUrl="…" tinypod:newSince="…">
 *         <tinypod:episode guid="…" played="true" position="0" lastPlayed="…"/>
 *       </outline>
 *     </outline>
 *   </body>
 * </opml>
 * ```
 *
 * [newParser] and [newSerializer] exist so JVM unit tests can supply real ones; on-device, Android's are used.
 */
class Opml(
  private val newParser: () -> XmlPullParser = { Xml.newPullParser() },
  private val newSerializer: () -> XmlSerializer = { Xml.newSerializer() },
) {
  fun write(s: BackupSnapshot, out: OutputStream) {
    val x = newSerializer()
    x.setOutput(out, "UTF-8")
    // Readable for anyone who opens it; a serializer without the option just writes one long line.
    runCatching { x.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true) }
    x.startDocument("UTF-8", null)
    x.setPrefix("tinypod", NS)
    x.start("opml").attr("version", "2.0")

    x.start("head")
    x.start("title").text("Tinypod subscriptions").end("title")
    s.exportedAt?.let { x.start("dateCreated").text(RFC_822.format(Instant.ofEpochMilli(it))).end("dateCreated") }
    x.startNs("backup").attr("version", VERSION.toString())
    s.exportedAt?.let { x.attr("exportedAt", it.toString()) }
    x.endNs("backup")
    s.settings?.let {
      x.startNs("settings")
        .attr("clearOlderOnFinish", it.clearOlderOnFinish.toString())
        .attr("continueWithNext", it.continueWithNext.toString())
        .attr("rewindOnResumeSec", it.rewindOnResumeSec.toString())
        .attr("speed", it.speed.toString())
        .attr("hidePlayed", it.hidePlayed.toString())
        .endNs("settings")
    }
    if (s.queue.isNotEmpty()) {
      x.startNs("queue")
      s.queue.forEach { x.startNs("item").attr("feedUrl", it.feedUrl).attr("guid", it.guid).endNs("item") }
      x.endNs("queue")
    }
    x.end("head")

    x.start("body")
    for (folder in s.folders) {
      x.start("outline").attr("text", folder.name).attr("title", folder.name).attrNs("sortOrder", folder.sortOrder.toString())
      s.podcasts.filter { it.folder == folder.name }.forEach { x.podcast(it) }
      x.end("outline")
    }
    s.podcasts.filter { p -> p.folder == null || s.folders.none { it.name == p.folder } }.forEach { x.podcast(it) }
    x.end("body")

    x.end("opml")
    x.endDocument()
    x.flush()
  }

  private fun XmlSerializer.podcast(p: BackupPodcast) {
    start("outline").attr("type", "rss").attr("text", p.title).attr("title", p.title).attr("xmlUrl", p.feedUrl)
    p.newSince?.let { attrNs("newSince", it.toString()) }
    for (e in p.episodes) {
      startNs("episode").attr("guid", e.guid).attr("played", e.isPlayed.toString()).attr("position", e.positionMs.toString())
      e.lastPlayedAt?.let { attr("lastPlayed", it.toString()) }
      endNs("episode")
    }
    end("outline")
  }

  /** Reads a Tinypod backup or any app's OPML file; throws [BackupFormatException] for anything else. */
  fun read(input: InputStream): BackupSnapshot =
    try {
      parse(input)
    } catch (e: XmlPullParserException) {
      throw BackupFormatException("Not an OPML file")
    } catch (e: NumberFormatException) {
      throw BackupFormatException("Damaged backup")
    }

  private fun parse(input: InputStream): BackupSnapshot {
    val x = newParser()
    x.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
    x.setInput(input, null)
    if (x.nextTag() != XmlPullParser.START_TAG || x.name != "opml" || x.namespace.isNotEmpty()) throw BackupFormatException("Not an OPML file")

    var exportedAt: Long? = null
    var settings: BackupSettings? = null
    val queue = mutableListOf<QueueEntry>()
    val folders = linkedMapOf<String, BackupFolder>()
    val podcasts = mutableListOf<BackupPodcast>()
    // The outlines we're inside: a folder's name, or null for anything else (a podcast, or an unnamed group).
    val outlines = ArrayDeque<String?>()
    var podcast: BackupPodcast? = null

    while (x.next() != XmlPullParser.END_DOCUMENT) {
      when (x.eventType) {
        XmlPullParser.START_TAG ->
          when {
            x.namespace == NS ->
              when (x.name) {
                "backup" -> {
                  val version = x.attr("version")?.toIntOrNull()
                  if (version != VERSION) throw BackupFormatException("Unsupported backup version ${x.attr("version")}")
                  exportedAt = x.attr("exportedAt")?.toLong()
                }
                "settings" ->
                  settings =
                    BackupSettings(
                      clearOlderOnFinish = x.attr("clearOlderOnFinish")?.toBoolean() ?: true,
                      continueWithNext = x.attr("continueWithNext").toBoolean(),
                      rewindOnResumeSec = x.attr("rewindOnResumeSec")?.toInt() ?: 10,
                      speed = x.attr("speed")?.toFloat() ?: 1f,
                      hidePlayed = x.attr("hidePlayed").toBoolean(),
                    )
                "item" -> queue += QueueEntry(x.attr("feedUrl") ?: continue, x.attr("guid") ?: continue)
                "episode" -> {
                  val p = podcast ?: continue
                  val episode =
                    BackupEpisode(
                      guid = x.attr("guid") ?: continue,
                      isPlayed = x.attr("played").toBoolean(),
                      positionMs = x.attr("position")?.toLong() ?: 0,
                      lastPlayedAt = x.attr("lastPlayed")?.toLong(),
                    )
                  podcast = p.copy(episodes = p.episodes + episode)
                }
              }
            x.name == "outline" && x.namespace.isEmpty() -> {
              val feedUrl = x.attr("xmlUrl")?.trim()?.takeIf { it.isNotEmpty() }
              val name = (x.attr("text") ?: x.attr("title"))?.trim()?.takeIf { it.isNotEmpty() }
              if (feedUrl != null) {
                // Tinypod has one level of folders: a podcast goes in the closest one around it.
                val folder = outlines.lastOrNull { it != null }
                folder?.let { folders.getOrPut(it) { BackupFolder(it, folders.size) } }
                podcast = BackupPodcast(PodcastRepository.normalizeFeedUrl(feedUrl), name ?: feedUrl, folder, x.attrNs("newSince")?.toLong())
                outlines.addLast(null)
              } else {
                // Only Tinypod's own folders are kept when empty; elsewhere a group may be anything.
                val sortOrder = x.attrNs("sortOrder")?.toInt()
                if (name != null && sortOrder != null) folders[name] = BackupFolder(name, sortOrder)
                outlines.addLast(name)
              }
            }
          }
        XmlPullParser.END_TAG ->
          if (x.name == "outline" && x.namespace.isEmpty()) {
            outlines.removeLastOrNull()
            podcast?.let { podcasts += it }
            podcast = null
          }
      }
    }
    return BackupSnapshot(exportedAt, settings, folders.values.toList(), podcasts.distinctBy { it.feedUrl }, queue)
  }

  private fun XmlPullParser.attr(name: String): String? = getAttributeValue(null, name)

  private fun XmlPullParser.attrNs(name: String): String? = getAttributeValue(NS, name)

  private fun XmlSerializer.start(name: String) = startTag(null, name)

  private fun XmlSerializer.end(name: String) = endTag(null, name)

  private fun XmlSerializer.startNs(name: String) = startTag(NS, name)

  private fun XmlSerializer.endNs(name: String) = endTag(NS, name)

  private fun XmlSerializer.attr(name: String, value: String) = attribute(null, name, value)

  private fun XmlSerializer.attrNs(name: String, value: String) = attribute(NS, name, value)

  companion object {
    /** The namespace for Tinypod's own data. */
    const val NS = "https://github.com/deputynl/tinypod/backup"
    private const val VERSION = 1
    private val RFC_822 = DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC)
  }
}
