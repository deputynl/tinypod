package app.tinypod.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Turns one colour picked from podcast artwork (the "seed") into a colour scheme for the player and
 * podcast pages. Every role keeps the seed's hue and (toned-down) saturation, but its lightness is
 * solved for a fixed luminance, so contrast between roles is the same for a yellow cover as for a
 * navy one. Roles not listed here keep the app's normal colours.
 */
internal object ArtworkColors {
  /** Too grey to tint with: the page would just look dull, so the normal theme is kept. */
  private const val MIN_SATURATION = 0.15f

  fun isTintable(seed: Int): Boolean {
    val (_, s, l) = hsl(seed)
    return s >= MIN_SATURATION && l in 0.08f..0.95f
  }

  fun scheme(seed: Int, base: ColorScheme, dark: Boolean): ColorScheme {
    val (h, seedS, _) = hsl(seed)
    val s = seedS.coerceIn(0.3f, 0.85f)
    // Tone(saturation factor, luminance) -> a colour of the seed's hue.
    fun tone(sat: Float, luminance: Float) = Color(colorWithLuminance(h, s * sat, luminance))
    return if (!dark) {
      base.copy(
        primary = tone(1f, 0.09f),
        onPrimary = Color.White,
        primaryContainer = tone(0.7f, 0.62f),
        onPrimaryContainer = tone(1f, 0.03f),
        secondaryContainer = tone(0.5f, 0.70f),
        onSecondaryContainer = tone(0.6f, 0.035f),
        background = tone(0.4f, 0.86f),
        onBackground = tone(0.25f, 0.015f),
        surface = tone(0.4f, 0.86f),
        onSurface = tone(0.25f, 0.015f),
        surfaceVariant = tone(0.35f, 0.74f),
        onSurfaceVariant = tone(0.25f, 0.12f),
        surfaceContainerLowest = tone(0.3f, 0.93f),
        surfaceContainerLow = tone(0.35f, 0.82f),
        surfaceContainer = tone(0.35f, 0.79f),
        surfaceContainerHigh = tone(0.35f, 0.76f),
        surfaceContainerHighest = tone(0.35f, 0.72f),
        surfaceTint = tone(1f, 0.09f),
        outline = tone(0.2f, 0.22f),
        outlineVariant = tone(0.25f, 0.55f),
      )
    } else {
      base.copy(
        primary = tone(1f, 0.45f),
        onPrimary = tone(1f, 0.025f),
        primaryContainer = tone(0.6f, 0.06f),
        onPrimaryContainer = tone(0.8f, 0.70f),
        secondaryContainer = tone(0.4f, 0.05f),
        onSecondaryContainer = tone(0.5f, 0.72f),
        background = tone(0.45f, 0.008f),
        onBackground = tone(0.15f, 0.80f),
        surface = tone(0.45f, 0.008f),
        onSurface = tone(0.15f, 0.80f),
        surfaceVariant = tone(0.35f, 0.035f),
        onSurfaceVariant = tone(0.2f, 0.50f),
        surfaceContainerLowest = tone(0.45f, 0.004f),
        surfaceContainerLow = tone(0.45f, 0.012f),
        surfaceContainer = tone(0.45f, 0.018f),
        surfaceContainerHigh = tone(0.45f, 0.028f),
        surfaceContainerHighest = tone(0.45f, 0.04f),
        surfaceTint = tone(1f, 0.45f),
        outline = tone(0.15f, 0.25f),
        outlineVariant = tone(0.2f, 0.06f),
      )
    }
  }

  /** The colour of hue [h] and saturation [s] whose relative luminance (WCAG) is [target]. */
  fun colorWithLuminance(h: Float, s: Float, target: Float): Int {
    // Luminance rises monotonically with HSL lightness for a fixed hue and saturation: bisect.
    var lo = 0f
    var hi = 1f
    repeat(24) {
      val mid = (lo + hi) / 2
      if (luminance(hslToArgb(h, s, mid)) < target) lo = mid else hi = mid
    }
    return hslToArgb(h, s, (lo + hi) / 2)
  }

  /** WCAG relative luminance of an ARGB colour. */
  fun luminance(argb: Int): Float {
    fun channel(shift: Int): Double {
      val c = ((argb shr shift) and 0xFF) / 255.0
      return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }
    return (0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)).toFloat()
  }

  /** WCAG contrast ratio between two colours (1 to 21). */
  fun contrast(a: Int, b: Int): Float {
    val la = luminance(a)
    val lb = luminance(b)
    return (max(la, lb) + 0.05f) / (min(la, lb) + 0.05f)
  }

  /** Hue (0..360), saturation and lightness (0..1) of an ARGB colour. */
  fun hsl(argb: Int): Triple<Float, Float, Float> {
    val r = ((argb shr 16) and 0xFF) / 255f
    val g = ((argb shr 8) and 0xFF) / 255f
    val b = (argb and 0xFF) / 255f
    val mx = max(r, max(g, b))
    val mn = min(r, min(g, b))
    val l = (mx + mn) / 2
    val d = mx - mn
    if (d == 0f) return Triple(0f, 0f, l)
    val s = d / (1 - abs(2 * l - 1))
    val h =
      when (mx) {
        r -> 60 * (((g - b) / d).mod(6f))
        g -> 60 * ((b - r) / d + 2)
        else -> 60 * ((r - g) / d + 4)
      }
    return Triple(h, s, l)
  }

  fun hslToArgb(h: Float, s: Float, l: Float): Int {
    val c = (1 - abs(2 * l - 1)) * s
    val x = c * (1 - abs((h / 60).mod(2f) - 1))
    val m = l - c / 2
    val (r, g, b) =
      when ((h.mod(360f) / 60).toInt()) {
        0 -> Triple(c, x, 0f)
        1 -> Triple(x, c, 0f)
        2 -> Triple(0f, c, x)
        3 -> Triple(0f, x, c)
        4 -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
      }
    fun byte(v: Float) = ((v + m) * 255).toInt().coerceIn(0, 255)
    return (0xFF shl 24) or (byte(r) shl 16) or (byte(g) shl 8) or byte(b)
  }
}
