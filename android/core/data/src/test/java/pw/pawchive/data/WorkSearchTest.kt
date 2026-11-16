package pw.pawchive.data

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class WorkSearchTest {
    private fun post(id: Int, file: RemoteFile? = null) = Post("$id", "qa", "qa", file = file)
    private fun MockWebServer.page(posts: List<Post>) = enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(wireJson.encodeToString(posts)))

    @Test fun filterKeepsImageAndVideoButRejectsTextArchiveAndBlankPaths() {
        val text = post(1)
        val zip = post(2, RemoteFile("a.zip", "/a.zip"))
        val image = post(3, RemoteFile("A.PNG", "/a.png"))
        val video = post(4).copy(attachments = listOf(RemoteFile("a.MP4", "/a.mp4")))
        val invalid = post(5, RemoteFile("b.jpg", ""))
        val all = listOf(text, zip, image, video, invalid)
        assertEquals(listOf(image, video), all.visibleWorks(true))
        assertEquals(all, all.visibleWorks(false))
    }

    @Test fun skipsEmptyMediaPageWithoutSkippingRawOffsets() = runTest {
        MockWebServer().use { server ->
            server.page((1..50).map { post(it) })
            val video = post(51, RemoteFile("a.mp4", "/a.mp4"))
            server.page(listOf(video, post(52, RemoteFile("a.zip", "/a.zip"))))
            val result = loadWorkSearchPage(pawApi(siteClient(), server.url("/api/v1/").toString()), "a & b", 0, true)
            assertEquals(listOf(video), result.rawPosts.visibleWorks(true))
            assertEquals(52, result.nextOffset); assertFalse(result.more)
            assertEquals("0", server.takeRequest().requestUrl!!.queryParameter("o"))
            val second = server.takeRequest().requestUrl!!
            assertEquals("50", second.queryParameter("o")); assertEquals("a & b", second.queryParameter("q"))
        }
    }

    @Test fun sparseResultsHaveBoundedRequestsAndRemainPageable() = runTest {
        MockWebServer().use { server ->
            repeat(3) { page -> server.page((1..50).map { post(page * 50 + it) }) }
            val result = loadWorkSearchPage(pawApi(siteClient(), server.url("/api/v1/").toString()), "", 0, true)
            assertEquals(3, server.requestCount); assertEquals(150, result.nextOffset)
            assertTrue(result.more); assertTrue(result.rawPosts.visibleWorks(true).isEmpty())
        }
    }

    @Test fun disabledFilterDoesNotScanExtraPages() = runTest {
        MockWebServer().use { server ->
            server.page((1..50).map { post(it) })
            val result = loadWorkSearchPage(pawApi(siteClient(), server.url("/api/v1/").toString()), "", 0, false)
            assertEquals(1, server.requestCount); assertEquals(50, result.rawPosts.visibleWorks(false).size)
            assertEquals(50, result.nextOffset); assertTrue(result.more)
        }
    }
}
