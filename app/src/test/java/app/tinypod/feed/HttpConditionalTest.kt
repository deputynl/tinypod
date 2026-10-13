package app.tinypod.feed

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class HttpConditionalTest {
  private lateinit var server: HttpServer
  private val requests = mutableListOf<Map<String, String?>>()
  private val url get() = "http://127.0.0.1:${server.address.port}/feed"

  @Before
  fun setUp() {
    server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/feed") { exchange ->
      val etag = exchange.requestHeaders.getFirst("If-None-Match")
      val since = exchange.requestHeaders.getFirst("If-Modified-Since")
      requests += mapOf("etag" to etag, "since" to since)
      if (etag == "\"v1\"" || since == LAST_MODIFIED) {
        exchange.sendResponseHeaders(304, -1)
      } else {
        exchange.responseHeaders.add("ETag", "\"v1\"")
        exchange.responseHeaders.add("Last-Modified", LAST_MODIFIED)
        val body = "feed".toByteArray()
        exchange.sendResponseHeaders(200, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
      }
      exchange.close()
    }
    server.start()
  }

  @After
  fun tearDown() = server.stop(0)

  private fun get(etag: String?, lastModified: String?) = runBlocking {
    Http.getIfChanged(url, etag, lastModified) { it.readBytes().decodeToString() }
  }

  @Test
  fun `a first fetch returns the body and the validators`() {
    assertEquals(Conditional.Fetched("feed", "\"v1\"", LAST_MODIFIED), get(null, null))
    assertEquals(mapOf("etag" to null, "since" to null), requests.single())
  }

  @Test
  fun `an unchanged feed is not modified`() {
    assertEquals(Conditional.NotModified, get("\"v1\"", LAST_MODIFIED))
    assertEquals(mapOf("etag" to "\"v1\"", "since" to LAST_MODIFIED), requests.single())
  }

  @Test
  fun `last-modified alone is enough`() {
    assertEquals(Conditional.NotModified, get(null, LAST_MODIFIED))
  }

  @Test
  fun `a changed feed is fetched again`() {
    assertEquals(Conditional.Fetched("feed", "\"v1\"", LAST_MODIFIED), get("\"v0\"", null))
  }

  private companion object {
    const val LAST_MODIFIED = "Tue, 13 Oct 2026 09:00:00 GMT"
  }
}
