package pw.pawchive.data

import kotlinx.serialization.Serializable
import java.time.LocalDate

/** Separate instances live in each home page's SavedStateHandle. */
@Serializable
data class SearchFilters(
    val range: String = "all", val period: String = "all",
    val start: String = "", val end: String = "", val service: String = "all",
    val media: String = "all", val ai: String = "all",
    val sort: String = "name", val order: String = "asc",
    val workOrder: String = "default"
) {
    val isDefault get() = this == SearchFilters()
    val validDates get() = runCatching {
        val first = start.takeIf { it.isNotEmpty() }?.let(LocalDate::parse)
        val last = end.takeIf { it.isNotEmpty() }?.let(LocalDate::parse)
        (first == null || last == null || first <= last) && (last == null || last <= LocalDate.now())
    }.getOrDefault(false)
    fun workQuery(query: String): String {
        require(validDates) { "日期范围无效" }
        val terms = searchTerms(query).map { token ->
            if (range == "title" && ':' !in token && !token.startsWith('-')) "intitle:$token" else token
        }.toMutableList()
        if (start.isNotEmpty()) terms += "after:$start"
        // Site before: is an exclusive midnight bound. The selected end day is inclusive.
        if (end.isNotEmpty()) terms += "before:${LocalDate.parse(end).plusDays(1)}"
        if (service in setOf("patreon", "fanbox")) terms += "service:$service"
        if (media in setOf("image", "video", "archive", "audio")) terms += "has:$media"
        return terms.joinToString(" ")
    }
    fun period(value: String, today: LocalDate = LocalDate.now()): SearchFilters {
        val days = when (value) { "day" -> 1L; "week" -> 7L; "half" -> 183L; else -> 0L }
        return copy(period = value, start = if (days == 0L) "" else today.minusDays(days).toString(), end = if (days == 0L) "" else today.toString())
    }
}

private val searchTokens = Regex("""(?:[^\s"]+|"[^"]*")+""")
fun searchTerms(text: String): List<String> = searchTokens.findAll(text.trim()).map { it.value }.toList()
