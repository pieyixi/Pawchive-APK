package pw.pawchive.data

val Post.hasVisualMedia: Boolean get() = files.any { it.isImage || it.isVideo }
fun List<Post>.visibleWorks(mediaOnly: Boolean): List<Post> = if (mediaOnly) filter { it.hasVisualMedia } else this

data class WorkSearchPage(val rawPosts: List<Post>, val nextOffset: Int, val more: Boolean)

/** Filtering never changes the server offset. Bound sparse searches to three requests per load. */
suspend fun loadWorkSearchPage(api: PawApi, query: String, offset: Int, mediaOnly: Boolean): WorkSearchPage {
    val raw = mutableListOf<Post>()
    var nextOffset = offset
    var more = true
    repeat(if (mediaOnly) 3 else 1) {
        val batch = api.posts(query, nextOffset)
        nextOffset += batch.size
        raw.addAll(batch)
        more = batch.size >= 50
        if (!more || !mediaOnly || raw.count { it.hasVisualMedia } >= 24) return WorkSearchPage(raw, nextOffset, more)
    }
    return WorkSearchPage(raw, nextOffset, more)
}
