package pw.pawchive.data

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class NovelAndAscendingTest {
    private val cover = RemoteFile("cover.jpg", "/cover.jpg")
    private fun story(title: String = "第十二章", content: String = "<p>「${"字".repeat(750)}」</p>".repeat(8)) = Post("qa", "qa", "qa", title = title, content = content, file = cover)
    @Test fun longSingleCoverStoryIsSuspected() { assertTrue(story().isSuspectedNovel()) }
    @Test fun shortTextIsKept() { assertFalse(story(content = "<p>第十二章 ${"字".repeat(5990)}</p>").isSuspectedNovel()) }
    @Test fun mediaAndIllustratedPostsAreKept() {
        assertFalse(story().copy(attachments = listOf(RemoteFile("second.png", "/second.png"))).isSuspectedNovel())
        assertFalse(story().copy(attachments = listOf(RemoteFile("clip.mp4", "/clip.mp4"))).isSuspectedNovel())
        assertFalse(story().copy(file = null).isSuspectedNovel())
        assertFalse(story(title = "漫画 32枚").isSuspectedNovel())
    }
    @Test fun markupAndNavigationAreNotCountedAsProse() {
        assertFalse(story(content = "<script>${"字".repeat(12000)}</script><p>正文</p>").isSuspectedNovel())
        assertFalse(story(title = "目录", content = "<p>${"目录链接".repeat(2000)}</p>").isSuspectedNovel())
        assertFalse(story(title = "公告").isSuspectedNovel())
    }
    @Test fun reversingPartialLastPageDoesNotStopPagination() = runTest {
        MockWebServer().use { server ->
            val oldest = (600..611).map { Post("$it", "qa", "qa") }
            val previous = (550..599).map { Post("$it", "qa", "qa") }
            for (batch in listOf(oldest, previous)) server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(wireJson.encodeToString(batch)))
            val api = pawApi(siteClient(), server.url("/api/v1/").toString())
            val first = loadAscendingWorkSearchPage(api, "JK", 600, false, SearchFilters(workOrder = "asc"))
            assertEquals((611 downTo 600).map { "$it" }, first.rawPosts.map { it.id }); assertTrue(first.more); assertEquals(550, first.nextOffset)
            val second = loadAscendingWorkSearchPage(api, "JK", first.nextOffset, false, SearchFilters(workOrder = "asc"))
            assertEquals((599 downTo 550).map { "$it" }, second.rawPosts.map { it.id }); assertEquals(500, second.nextOffset)
            assertEquals("600", server.takeRequest().requestUrl!!.queryParameter("o")); assertEquals("550", server.takeRequest().requestUrl!!.queryParameter("o"))
        }
    }
    @Test fun reverseFirstServerPageEndsAndKeepsAiFilter() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("[]"))
            val page = loadAscendingWorkSearchPage(pawApi(siteClient(), server.url("/api/v1/").toString()), "JK", 0, true, SearchFilters(workOrder = "asc", ai = "hide"))
            assertFalse(page.more); assertEquals(-50, page.nextOffset); assertEquals(1, server.requestCount)
            assertEquals("ai", server.takeRequest().requestUrl!!.queryParameter("hide"))
        }
    }
}
