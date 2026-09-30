package app.tinypod.theme

import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.palette.graphics.Palette
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Seed colours picked from artwork, by artwork URL, for the app's lifetime. */
private object ArtworkSeeds {
  private const val NONE = 0 // cached "no usable colour" (fully transparent black is never a seed)
  private val cache = ConcurrentHashMap<String, Int>()

  /** The seed if it's already known: lets a revisited page open tinted instead of fading in. */
  fun cached(url: String?): Int? = url?.let { cache[it] }?.takeIf { it != NONE }

  suspend fun load(context: Context, url: String): Int? {
    cache[url]?.let { return it.takeIf { it != NONE } }
    // Small and software-backed: Palette needs to read the pixels, and 128 px is plenty for colours.
    val request = ImageRequest.Builder(context).data(url).size(128).allowHardware(false).build()
    val bitmap = (context.imageLoader.execute(request) as? SuccessResult)?.image?.toBitmap() ?: return null // not cached: may work later
    val seed =
      withContext(Dispatchers.Default) {
        val palette = Palette.from(bitmap).maximumColorCount(16).generate()
        listOfNotNull(palette.vibrantSwatch, palette.darkVibrantSwatch, palette.lightVibrantSwatch, palette.dominantSwatch)
          .map { it.rgb }
          .firstOrNull(ArtworkColors::isTintable)
      }
    cache[url] = seed ?: NONE
    return seed
  }
}

/**
 * Re-themes [content] in colours taken from [artworkUrl], following the app's light or dark mode.
 * Keeps the normal theme while the colours load and for artwork that is too grey; changes animate.
 */
@Composable
fun ArtworkTheme(artworkUrl: String?, content: @Composable () -> Unit) {
  val base = MaterialTheme.colorScheme
  val context = LocalContext.current
  val seed by
    produceState(ArtworkSeeds.cached(artworkUrl), artworkUrl) { value = artworkUrl?.let { ArtworkSeeds.load(context.applicationContext, it) } }
  val dark = base.background.luminance() < 0.5f
  val target = seed?.let { ArtworkColors.scheme(it, base, dark) } ?: base
  MaterialTheme(colorScheme = target.animated(), typography = MaterialTheme.typography, content = content)
}

/** The roles [ArtworkColors.scheme] changes, each animated towards [this]. */
@Composable
private fun ColorScheme.animated(): ColorScheme {
  @Composable fun anim(c: Color) = animateColorAsState(c, tween(400), label = "artwork colour").value
  return copy(
    primary = anim(primary),
    onPrimary = anim(onPrimary),
    primaryContainer = anim(primaryContainer),
    onPrimaryContainer = anim(onPrimaryContainer),
    secondaryContainer = anim(secondaryContainer),
    onSecondaryContainer = anim(onSecondaryContainer),
    background = anim(background),
    onBackground = anim(onBackground),
    surface = anim(surface),
    onSurface = anim(onSurface),
    surfaceVariant = anim(surfaceVariant),
    onSurfaceVariant = anim(onSurfaceVariant),
    surfaceContainerLowest = anim(surfaceContainerLowest),
    surfaceContainerLow = anim(surfaceContainerLow),
    surfaceContainer = anim(surfaceContainer),
    surfaceContainerHigh = anim(surfaceContainerHigh),
    surfaceContainerHighest = anim(surfaceContainerHighest),
    surfaceTint = anim(surfaceTint),
    outline = anim(outline),
    outlineVariant = anim(outlineVariant),
  )
}
