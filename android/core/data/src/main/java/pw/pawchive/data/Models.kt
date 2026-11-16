package pw.pawchive.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import okhttp3.HttpUrl.Companion.toHttpUrl

const val SITE = "https://pawchive.pw"
val wireJson = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false }

@Serializable
data class Creator(
    val id: String, val service: String, val name: String = "",
    val updated: JsonElement? = null, val indexed: JsonElement? = null,
    val favorited: JsonElement? = null
) {
    val key get() = "$service:$id"
    val avatar get() = "$SITE/icons/${segment(service)}/${segment(id)}"
    val timestamp get() = timeValue(updated)
}

@Serializable
data class RemoteFile(val name: String = "", val path: String = "", val server: String = "") {
    val extension get() = (name.ifBlank { path }).substringAfterLast('.', "").lowercase()
    val isImage get() = extension in setOf("jpg", "jpeg", "png", "webp", "gif", "avif", "bmp")
    val isVideo get() = extension in setOf("mp4", "webm", "mov", "m4v", "mkv")
    val original: String get() {
        require(path.startsWith('/') && !path.contains("..")) { "文件路径无效" }
        val host = server.ifBlank { SITE }.trimEnd('/').toHttpUrl()
        require(host.isHttps && (host.host == "pawchive.pw" || host.host.endsWith(".pawchive.pw"))) { "文件来源无效" }
        return host.resolve("/data$path")!!.toString()
    }
    val thumbnail: String get() {
        val base = path.substringAfterLast('/')
        val hash = Regex("^[a-fA-F0-9]{32,}\\.[a-zA-Z0-9]+$").matches(base)
        val tail = if (hash) "/${base.take(2)}/${base.substring(2, 4)}/$base" else "/${path.trimStart('/')}"
        return "https://img.pawchive.pw/thumbnail/data$tail"
    }
}

@Serializable
data class Post(
    val id: String, val user: String, val service: String,
    val title: String = "", val content: String = "",
    val published: JsonElement? = null, val added: JsonElement? = null,
    val edited: JsonElement? = null,
    val file: RemoteFile? = null, val attachments: List<RemoteFile> = emptyList()
) {
    val key get() = "$service:$user:$id"
    val authorKey get() = "$service:$user"
    val timestamp get() = timeValue(published).takeIf { it > 0 } ?: timeValue(added)
    val files get() = (listOfNotNull(file) + attachments).filter { it.path.isNotBlank() }.distinctBy { it.path }
    val images get() = files.filter { it.isImage }
    val videos get() = files.filter { it.isVideo }
    val cover get() = images.firstOrNull() ?: videos.firstOrNull()
    fun compact() = copy(content = "")
}

fun segment(value: String): String = SITE.toHttpUrl().newBuilder().addPathSegment(value).build().encodedPath.removePrefix("/")
fun timeValue(value: JsonElement?): Long {
    val str = (value as? JsonPrimitive)?.contentOrNull ?: return 0
    str.toDoubleOrNull()?.let { return if (it > 1e12) it.toLong() else (it * 1000).toLong() }
    return runCatching { Instant.parse(str).toEpochMilli() }.getOrElse {
        runCatching { LocalDateTime.parse(str.removeSuffix("Z")).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrDefault(0)
    }
}

/** Bounded merge also preserves older entries when a single author request fails. */
fun mergeFeed(old: List<Post>, incoming: List<Post>): List<Post> {
    val seen = HashMap<String, Int>()
    return (incoming + old).distinctBy { it.key }.sortedByDescending { it.timestamp }
        .filter { seen.merge(it.authorKey, 1, Int::plus)!! <= 60 }.take(500)
}
