package app.tinypod.player

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import app.tinypod.TinypodApp
import app.tinypod.feed.Http
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Serves podcast artwork to Android Auto, which only loads content:// (not web) images for browse
 * items. `content://app.tinypod.artwork/podcast/<id>` returns that podcast's artwork, fetched once
 * and cached. Exported so the car app can read it; it only ever serves artwork of subscribed
 * podcasts, looked up by id, so it can't be used to fetch arbitrary URLs.
 */
class ArtworkProvider : ContentProvider() {
  override fun onCreate() = true

  override fun getType(uri: Uri) = "image/*"

  override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
    if (mode != "r") throw SecurityException("Artwork is read-only")
    val podcastId = uri.pathSegments.takeIf { it.size == 2 && it[0] == "podcast" }?.get(1)?.toLongOrNull() ?: throw FileNotFoundException("$uri")
    val context = checkNotNull(context)
    val app = context.applicationContext as TinypodApp
    val url = runBlocking { app.database.podcastDao().observe(podcastId).first()?.artworkUrl } ?: throw FileNotFoundException("$uri")

    val dir = File(context.cacheDir, "artwork").apply { mkdirs() }
    val file = File(dir, "$podcastId-${version(url)}")
    if (!file.exists()) {
      dir.listFiles { f -> f.name.startsWith("$podcastId-") }?.forEach { it.delete() } // artwork changed
      val tmp = File.createTempFile("download-$podcastId-", ".tmp", dir)
      try {
        runBlocking { Http.get(url) { input -> tmp.outputStream().use { out -> copyLimited(input, out) } } }
        tmp.renameTo(file)
      } catch (e: IOException) {
        throw FileNotFoundException("Couldn't fetch artwork for podcast $podcastId: ${e.message}")
      } finally {
        tmp.delete()
      }
    }
    return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
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

    private fun version(url: String) = Integer.toHexString(url.hashCode())

    /** The artwork URI for a podcast; it changes when the artwork URL does, so the car doesn't show a stale cached image. */
    fun uriFor(podcastId: Long, artworkUrl: String?): Uri? =
      artworkUrl?.let {
        Uri.Builder().scheme("content").authority(AUTHORITY).appendPath("podcast").appendPath("$podcastId").appendQueryParameter("v", version(it)).build()
      }
  }
}
