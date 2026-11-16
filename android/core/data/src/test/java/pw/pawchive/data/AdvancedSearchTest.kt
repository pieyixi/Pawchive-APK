package pw.pawchive.data

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class AdvancedSearchTest {
    @Test fun retiredExclusionsAreIgnoredWhenRestoringOldSavedFilters() {
        val restored = wireJson.decodeFromString<SearchFilters>("""{"exclude":"WIP"}""")
        assertEquals(SearchFilters(), restored)
        assertEquals("JK", restored.workQuery("JK"))
        assertFalse(wireJson.encodeToString(restored).contains("exclude"))
    }
    @Test fun queryUsesSiteOperatorsAndInclusiveEndDay() {
        val filters = SearchFilters(range = "title", start = "2026-11-01", end = "2026-11-16", service = "fanbox", media = "video")
        assertEquals("intitle:水着 intitle:\"blue archive\" after:2026-11-01 before:2026-11-17 service:fanbox has:video", filters.workQuery("水着 \"blue archive\""))
        assertEquals("service:fanbox -draft intitle:JK", SearchFilters(range = "title").workQuery("service:fanbox -draft JK"))
        assertEquals("intitle:\"blue archive\" intitle:JK", SearchFilters(range = "title").workQuery("intitle:\"blue archive\" JK"))
    }
    @Test fun invalidDatesAreNotSent() {
        assertFalse(SearchFilters(start = "2026-11-16", end = "2026-11-01").validDates)
        assertFalse(SearchFilters(start = "2026-02-30").validDates)
        assertThrows(IllegalArgumentException::class.java) { SearchFilters(start = "invalid").workQuery("test") }
    }
    @Test fun aiFiltersReachServerAndPaginationStaysRaw() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("[]"))
            loadWorkSearchPage(pawApi(siteClient(), server.url("/api/v1/").toString()), "JK", 50, false, SearchFilters(ai = "hide", media = "image"))
            val request = server.takeRequest().requestUrl!!
            assertEquals("JK has:image", request.queryParameter("q"))
            assertEquals("ai", request.queryParameter("hide")); assertNull(request.queryParameter("only")); assertEquals("50", request.queryParameter("o"))
        }
    }
    @Test fun lateImportedOldPostAppearsBeforeRecentPublicationsAndSurvivesCap() {
        val old = (0..499).map { Post("$it", "author-$it", "qa", published = JsonPrimitive(2000 + it)) }
        val backfill = Post("backfill", "late-author", "qa", published = JsonPrimitive(1000), added = JsonPrimitive(9999))
        val merged = mergeFeed(old, listOf(backfill))
        assertEquals(500, merged.size); assertEquals(backfill.key, merged.first().key)
        assertEquals(1_000_000, backfill.timestamp); assertEquals(9_999_000, backfill.feedTimestamp)
        assertEquals(merged, mergeFeed(merged, listOf(backfill)))
    }
}
