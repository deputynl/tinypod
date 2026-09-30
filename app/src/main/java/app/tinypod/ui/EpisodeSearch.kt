package app.tinypod.ui

import app.tinypod.data.EpisodeWithPodcast
import java.text.Normalizer

/**
 * Searches one podcast's episodes by title and show notes, ignoring case, accents, punctuation and
 * HTML. Every word of the query must start a word in the episode (in any order), so "apes" finds
 * "Apes" but not "Mixtapes". Episodes matching on the title alone come first,
 * then ones that also need the show notes; each group keeps the given order.
 *
 * Built once per episode list, so typing only runs cheap substring checks.
 */
internal class EpisodeSearch(rows: List<EpisodeWithPodcast>) {
  private class Entry(val row: EpisodeWithPodcast, val title: String, val notes: String)

  private val entries = rows.map { Entry(it, " ${normalize(it.episode.title)} ", " ${normalize(stripHtml(it.episode.description.orEmpty()))} ") }

  fun search(query: String): List<EpisodeWithPodcast> {
    // Texts are stored as " word word ", so " apes" only matches at the start of a word.
    val words = normalize(query).split(' ').filter { it.isNotEmpty() }.map { " $it" }
    if (words.isEmpty()) return entries.map { it.row }
    val (byTitle, byNotes) =
      entries.filter { e -> words.all { it in e.title || it in e.notes } }.partition { e -> words.all { it in e.title } }
    return (byTitle + byNotes).map { it.row }
  }

  companion object {
    private val MARKS = Regex("""\p{M}+""")
    private val NON_WORD = Regex("""[^\p{L}\p{N}]+""")
    private val TAG = Regex("""<[^>]*>""")
    private val ENTITY = Regex("""&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);""")
    private val NAMED = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ")

    /** Lower case, accents removed ("Café" -> "cafe"), anything but letters and digits turned into single spaces. */
    internal fun normalize(text: String): String =
      Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(MARKS, "").replace(NON_WORD, " ").trim()

    /** Show notes are often HTML: drop the tags and decode entities, so only the visible text matches. */
    internal fun stripHtml(html: String): String =
      html.replace(TAG, " ").replace(ENTITY) { m ->
        val e = m.groupValues[1]
        when {
          e.startsWith("#x") -> e.drop(2).toIntOrNull(16)?.let(Character::toString)
          e.startsWith("#") -> e.drop(1).toIntOrNull()?.let(Character::toString)
          else -> NAMED[e.lowercase()]
        } ?: m.value
      }
  }
}
