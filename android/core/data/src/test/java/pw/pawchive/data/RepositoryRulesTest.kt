package pw.pawchive.data

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test

class RepositoryRulesTest {
    @Test fun thumbnailUsesHashInsteadOfStorageShard() {
        val hash = "578f" + "a".repeat(60) + ".png"
        val file = RemoteFile(hash, "/257/8f/$hash")
        assertEquals("https://img.pawchive.pw/thumbnail/data/57/8f/$hash", file.thumbnail)
        assertEquals("https://pawchive.pw/data/257/8f/$hash", file.original)
        assertFalse(file.thumbnail.contains("/257/"))
    }
    @Test fun noOriginalFallbackAndHostValidation() {
        val odd = RemoteFile("test.png", "/folder/image.png")
        assertEquals("https://img.pawchive.pw/thumbnail/data/folder/image.png", odd.thumbnail)
        for (server in listOf("http://pawchive.pw", "https://pawchive.pw.evil.test", "https://evil.test")) {
            assertThrows(IllegalArgumentException::class.java) { RemoteFile("a.png", "/a.png", server).original }
        }
        assertThrows(IllegalArgumentException::class.java) { RemoteFile("a.png", "/../a.png").original }
    }
    @Test fun fileTypesAndDeduplication() {
        val image = RemoteFile("PAGE.PNG", "/a.png")
        val video = RemoteFile("film.MP4", "/b.mp4")
        val archive = RemoteFile("pages.zip", "/c.zip")
        val post = Post("1", "2", "test", file = image, attachments = listOf(image, video, archive))
        assertEquals(3, post.files.size); assertEquals(listOf(image), post.images); assertEquals(listOf(video), post.videos)
    }
    @Test fun numericAndIsoTimestampsAreComparable() {
        assertEquals(timeValue(JsonPrimitive(1700000000)), timeValue(JsonPrimitive("2023-11-14T22:13:20Z")))
        assertEquals(timeValue(JsonPrimitive(1700000000000)), timeValue(JsonPrimitive(1700000000)))
        assertEquals(0, timeValue(JsonNull))
    }
    @Test fun incrementalMergeRetainsFailuresAndCapsMemory() {
        fun p(id: Int, author: String, time: Long) = Post(id.toString(), author, "test", published = JsonPrimitive(time))
        val old = listOf(p(1, "failed-author", 100), p(2, "changed-author", 200))
        val result = mergeFeed(old, listOf(p(2, "changed-author", 200).copy(title = "new"), p(3, "changed-author", 300)))
        assertEquals(listOf("3", "2", "1"), result.map { it.id }); assertEquals("new", result[1].title)
        val bounded = mergeFeed(emptyList(), (0..999).map { p(it, (it / 100).toString(), it.toLong()) })
        assertEquals(500, bounded.size); assertTrue(bounded.groupBy { it.authorKey }.values.all { it.size <= 60 })
    }
    @Test fun accountCookiesNeverGoToImageServersOrPlainHttp() {
        val jar = SiteCookieJar()
        val origin = SITE.toHttpUrl()
        jar.saveFromResponse(origin, listOf(Cookie.parse(origin, "session=test-only; Domain=.pawchive.pw; Path=/; Secure; HttpOnly")!!))
        assertEquals(1, jar.loadForRequest(origin).size)
        assertTrue(jar.loadForRequest("https://img.pawchive.pw/a".toHttpUrl()).isEmpty())
        assertTrue(jar.loadForRequest("http://pawchive.pw/a".toHttpUrl()).isEmpty())
        assertTrue(jar.loadForRequest("https://unrelated.test".toHttpUrl()).isEmpty())
    }
    @Test fun retrofitAcceptsUnknownFieldsAndCorrectlyEncodesSearchAndOffsets() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""[{"id":"1","user":"2","service":"test","title":"标题","future_field":true,"file":null,"attachments":[]}]"""))
            val api = pawApi(siteClient(), server.url("/api/v1/").toString())
            assertEquals("标题", api.posts("a & b", 50).single().title)
            val request = server.takeRequest()
            assertEquals("a & b", request.requestUrl!!.queryParameter("q")); assertEquals("50", request.requestUrl!!.queryParameter("o"))
        }
    }
}
