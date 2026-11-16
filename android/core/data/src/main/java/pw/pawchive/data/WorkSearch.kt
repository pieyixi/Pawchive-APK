package pw.pawchive.data

val Post.hasVisualMedia: Boolean get() = files.any { it.isImage || it.isVideo }
fun List<Post>.visibleWorks(mediaOnly: Boolean): List<Post> = if (mediaOnly) filter { it.hasVisualMedia } else this

data class WorkSearchPage(val rawPosts: List<Post>, val nextOffset: Int, val more: Boolean)

/** Filtering never changes the server offset. Bound sparse searches to three requests per load. */
suspend fun loadWorkSearchPage(api: PawApi, query: String, offset: Int, mediaOnly: Boolean, filters: SearchFilters = SearchFilters()): WorkSearchPage {
    val raw = mutableListOf<Post>()
    var nextOffset = offset
    var more = true
    repeat(if (mediaOnly) 3 else 1) {
        val batch = api.posts(filters.workQuery(query), nextOffset, hide = "ai".takeIf { filters.ai == "hide" }, only = "ai".takeIf { filters.ai == "only" })
        nextOffset += batch.size
        raw.addAll(batch)
        more = batch.size >= 50
        if (!more || !mediaOnly || raw.any { it.hasVisualMedia }) return WorkSearchPage(raw, nextOffset, more)
    }
    return WorkSearchPage(raw, nextOffset, more)
}

@kotlinx.serialization.Serializable
data class SearchPageCursor(val nextOffset: Int, val more: Boolean, val checkedAt: Long, val rawCount: Int)
data class CachedWorkPage(val page: WorkSearchPage, val checkedAt: Long)
