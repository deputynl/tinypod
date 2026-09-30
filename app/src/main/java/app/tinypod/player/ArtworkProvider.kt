package app.tinypod.player

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.net.Uri
import android.os.ParcelFileDescriptor
import app.tinypod.TinypodApp
import app.tinypod.data.Podcast
import app.tinypod.feed.Http
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Serves artwork to Android Auto, which only loads content:// (not web) images for browse items:
 * `content://app.tinypod.artwork/podcast/<id>` is a podcast's artwork (fetched once and cached) and
 * `…/folder/<id>` a 2×2 mosaic of a folder's podcasts. Exported so the car app can read it; it only
 * ever serves artwork of subscribed podcasts, looked up by id, so it can't fetch arbitrary URLs.
 */
class ArtworkProvider : ContentProvider() {
  override fun onCreate() = true

  override fun getType(uri: Uri) = "image/*"

  override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
    if (mode != "r") throw SecurityException("Artwork is read-only")
    val (kind, id) = uri.pathSegments.takeIf { it.size == 2 }?.let { it[0] to it[1].toLongOrNull() } ?: throw FileNotFoundException("$uri")
    val db = (checkNotNull(context).applicationContext as TinypodApp).database
    val file =
      runBlocking {
        when {
          id == null -> null
          kind == "podcast" -> db.podcastDao().observe(id).first()?.let { podcastArtwork(it) }
          kind == "folder" -> folderMosaic(id, db.podcastDao().observeInFolder(id).first().take(4))
          else -> null
        }
      } ?: throw FileNotFoundException("$uri")
    return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
  }

  private val dir
    get() = File(checkNotNull(context).cacheDir, "artwork").apply { mkdirs() }

  /** The podcast's artwork, fetched once and cached (until its URL changes). */
  private suspend fun podcastArtwork(podcast: Podcast): File? {
    val url = podcast.artworkUrl ?: return null
    val file = File(dir, "${podcast.id}-${version(url)}")
    if (!file.exists()) {
      dir.listFiles { f -> f.name.startsWith("${podcast.id}-") }?.forEach { it.delete() } // artwork changed
      val tmp = File.createTempFile("download-${podcast.id}-", ".tmp", dir)
      try {
        Http.get(url) { input -> tmp.outputStream().use { out -> copyLimited(input, out) } }
        tmp.renameTo(file)
      } catch (e: IOException) {
        throw FileNotFoundException("Couldn't fetch artwork for podcast ${podcast.id}: ${e.message}")
      } finally {
        tmp.delete()
      }
    }
    return file
  }

  /** A 2×2 mosaic of the artwork of a folder's first podcasts, like the folder tiles on the phone. */
  private suspend fun folderMosaic(folderId: Long, podcasts: List<Podcast>): File? {
    val urls = podcasts.mapNotNull { it.artworkUrl }
    if (urls.isEmpty()) return null
    val file = File(dir, "folder-$folderId-${version(urls.joinToString("\n"))}.png")
    if (file.exists()) return file
    dir.listFiles { f -> f.name.startsWith("folder-$folderId-") }?.forEach { it.delete() } // contents changed
    val cell = (MOSAIC_SIZE - 3 * MOSAIC_GAP) / 2
    val mosaic = Bitmap.createBitmap(MOSAIC_SIZE, MOSAIC_SIZE, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(mosaic)
    canvas.drawColor(MOSAIC_BACKGROUND)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    podcasts.filter { it.artworkUrl != null }.forEachIndexed { i, podcast ->
      val art = runCatching { podcastArtwork(podcast) }.getOrNull()?.let { decodeAtLeast(it, cell) } ?: return@forEachIndexed
      val left = MOSAIC_GAP + (i % 2) * (cell + MOSAIC_GAP)
      val top = MOSAIC_GAP + (i / 2) * (cell + MOSAIC_GAP)
      val rect = RectF(left.toFloat(), top.toFloat(), (left + cell).toFloat(), (top + cell).toFloat())
      canvas.save()
      canvas.clipPath(Path().apply { addRoundRect(rect, MOSAIC_CORNER, MOSAIC_CORNER, Path.Direction.CW) })
      canvas.drawBitmap(art, null, rect, paint)
      canvas.restore()
      art.recycle()
    }
    val tmp = File.createTempFile("mosaic-$folderId-", ".tmp", dir)
    try {
      tmp.outputStream().use { mosaic.compress(Bitmap.CompressFormat.PNG, 100, it) }
      tmp.renameTo(file)
    } finally {
      tmp.delete()
      mosaic.recycle()
    }
    return file
  }

  /** Decodes [file] downsampled, but no smaller than [size] pixels across: artwork can be 3000 px. */
  private fun decodeAtLeast(file: File, size: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= size && bounds.outHeight / (sample * 2) >= size) sample *= 2
    return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
  }

  private fun copyLimited(input: InputStream, out: OutputStream) {
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
      val n = input.read(buffer)
      if (n < 0) return
      total += n
      if (total > MAX_BYTES) throw IOException("Artwork larger than $MAX_BYTES bytes")
      out.write(buffer, 0, n)
    }
  }

  override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null

  override fun insert(uri: Uri, values: ContentValues?): Uri? = null

  override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

  override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0

  companion object {
    private const val AUTHORITY = "app.tinypod.artwork"
    private const val MAX_BYTES = 10L * 1024 * 1024
    private const val MOSAIC_SIZE = 512
    private const val MOSAIC_GAP = 24
    private const val MOSAIC_CORNER = 20f
    private const val MOSAIC_BACKGROUND = 0xFF2B312E.toInt() // a dark neutral, matching the car's dark UI

    private fun version(url: String) = Integer.toHexString(url.hashCode())

    /** The artwork URI for a podcast; it changes when the artwork URL does, so the car doesn't show a stale cached image. */
    /** A folder's mosaic; its URI changes with the artwork inside, so the car doesn't show a stale one. */
    fun folderUriFor(folderId: Long, artworkUrls: List<String>): Uri? =
      artworkUrls.takeIf { it.isNotEmpty() }?.let {
        Uri.Builder().scheme("content").authority(AUTHORITY).appendPath("folder").appendPath("$folderId").appendQueryParameter("v", version(it.joinToString("\n"))).build()
      }

    fun uriFor(podcastId: Long, artworkUrl: String?): Uri? =
      artworkUrl?.let {
        Uri.Builder().scheme("content").authority(AUTHORITY).appendPath("podcast").appendPath("$podcastId").appendQueryParameter("v", version(it)).build()
      }
  }
}
