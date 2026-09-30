package app.tinypod.ui

/** Show notes helpers: turning feed descriptions into displayable HTML, and finding timestamps in them. */
internal object ShowNotes {
  private val TAG = Regex("""<[a-zA-Z/][^>]*>""")

  // m:ss, mm:ss or h:mm:ss, not glued to other digits or colons (so not part of a date, ratio or IP).
  private val TIMESTAMP = Regex("""(?<![\d:.])(?:(\d{1,2}):)?(\d{1,2}):([0-5]\d)(?![\d:])""")

  /** A timestamp in the text, as its character range and position in the episode. */
  data class Timestamp(val range: IntRange, val positionMs: Long)

  /** Notes as HTML: feeds send either HTML or plain text, and plain text should keep its line breaks. */
  fun toHtml(notes: String): String {
    val trimmed = notes.trim()
    if (TAG.containsMatchIn(trimmed)) return trimmed
    return trimmed
      .replace("&", "&amp;")
      .replace("<", "&lt;")
      .replace(">", "&gt;")
      .replace(Regex("""\r?\n"""), "<br>")
  }

  /**
   * The timestamps in [text] that fall within the episode ([durationMs], when known): chapter times
   * like "12:34" or "1:02:03". Ones past the end are more likely clock times ("at 8:30") and skipped.
   */
  fun timestamps(text: String, durationMs: Long?): List<Timestamp> =
    TIMESTAMP.findAll(text).mapNotNull { m ->
      val (h, min, s) = m.destructured
      val hours = h.toLongOrNull() ?: 0
      val minutes = min.toLong()
      if (h.isNotEmpty() && minutes > 59) return@mapNotNull null
      val ms = ((hours * 60 + minutes) * 60 + s.toLong()) * 1000
      if (durationMs != null && durationMs > 0 && ms > durationMs) null else Timestamp(m.range, ms)
    }.toList()
}
