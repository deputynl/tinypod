package app.tinypod.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtworkColorsTest {
  // Radiolab red-orange, a yellow (hardest for contrast), navy, green, magenta, cyan.
  private val seeds = listOf(0xFFFF3B1F, 0xFFFFD500, 0xFF1D2A4A, 0xFF2E9E4F, 0xFFD6247F, 0xFF00B7C7).map { it.toInt() }

  private fun check(label: String, fg: Color, bg: Color, min: Float) {
    val ratio = ArtworkColors.contrast(fg.toArgb(), bg.toArgb())
    assertTrue("$label contrast $ratio < $min", ratio >= min)
  }

  @Test
  fun everyHueIsReadableInLightAndDarkMode() {
    for (seed in seeds) for (dark in listOf(false, true)) {
      val s = ArtworkColors.scheme(seed, if (dark) darkColorScheme() else lightColorScheme(), dark)
      val tag = "${Integer.toHexString(seed)} dark=$dark"
      check("$tag text", s.onBackground, s.background, 7f)
      check("$tag secondary text", s.onSurfaceVariant, s.background, 4.5f)
      check("$tag button", s.onPrimary, s.primary, 4.5f)
      check("$tag accent on page", s.primary, s.background, 4.5f)
      check("$tag chip", s.onSecondaryContainer, s.secondaryContainer, 4.5f)
      check("$tag container", s.onPrimaryContainer, s.primaryContainer, 4.5f)
    }
  }

  @Test
  fun keepsTheArtworksHue() {
    val (seedHue, _, _) = ArtworkColors.hsl(0xFF2E9E4F.toInt())
    val (hue, _, _) = ArtworkColors.hsl(ArtworkColors.scheme(0xFF2E9E4F.toInt(), lightColorScheme(), false).primary.toArgb())
    assertEquals(seedHue, hue, 3f)
  }

  @Test
  fun greyAndNearBlackOrWhiteArtworkIsNotTinted() {
    assertFalse(ArtworkColors.isTintable(0xFF808080.toInt()))
    assertFalse(ArtworkColors.isTintable(0xFF7A7F84.toInt())) // bluish grey
    assertFalse(ArtworkColors.isTintable(0xFF050608.toInt()))
    assertFalse(ArtworkColors.isTintable(0xFFFBFCFA.toInt()))
    assertTrue(ArtworkColors.isTintable(0xFFFF3B1F.toInt()))
  }

  @Test
  fun hslRoundTrips() {
    for (seed in seeds) {
      val (h, s, l) = ArtworkColors.hsl(seed)
      val back = ArtworkColors.hslToArgb(h, s, l)
      for (shift in listOf(16, 8, 0)) assertEquals(((seed shr shift) and 0xFF).toFloat(), ((back shr shift) and 0xFF).toFloat(), 1f)
    }
  }
}
