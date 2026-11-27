/* Adapted from Pupil/Hitomi spread.kt, Copyright (C) 2026 tom5079.
 * GPL-3.0-or-later; see third_party/hitomi/COPYING.
 * Pawchive adaptation: thumbnail-only acquisition, explicit originals, java.io.File cache. */
package pw.pawchive.media.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.Uri
import coil3.SingletonImageLoader
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.Request
import pw.pawchive.data.*
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class ReaderPageInfo(val width: Int = 0, val height: Int = 0) {
    val ratio get() = if (width > 0 && height > 0) width.toFloat() / height else .7f
    val fitsSpread get() = width == 0 || (height >= width && height <= width * 3)
}

class PreviewReaderSource(private val context: Context, val files: List<RemoteFile>, private val transport: okhttp3.OkHttpClient? = null, directory: File? = null) {
    val cachedPages = MutableStateFlow<Set<Int>>(emptySet())
    private val client = transport ?: siteClient()
    val cacheFolder = (directory ?: File(context.cacheDir, "reader-pages")).apply { mkdirs() }
    private val locks = Array(64) { Mutex() }
    private val gate = Semaphore(3)
    private val dimensions = ConcurrentHashMap<Int, ReaderPageInfo>()
    @Volatile private var pinned = emptySet<String>()
    fun info(index: Int) = dimensions[index] ?: ReaderPageInfo()
    fun close() { CoroutineScope(Dispatchers.IO).launch { runCatching { client.dispatcher.cancelAll(); client.connectionPool.evictAll() } } }
    fun pin(pages: List<Int>) { pinned = pages.flatMap { i -> listOf(key(files[i].thumbnail), key(files[i].original)) }.toSet() }
    private fun key(url: String) = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }

    suspend fun pageFile(index: Int, original: Boolean): File = withContext(Dispatchers.IO) {
        val file = files[index]
        val url = if (original) file.original else file.thumbnail
        val name = key(url)
        locks[(name.hashCode() and Int.MAX_VALUE) % locks.size].withLock {
            val target = File(cacheFolder, name)
            var committed = false
            if (!target.isFile) {
                val temp = File(cacheFolder, "$name.${UUID.randomUUID()}.tmp")
                try {
                    // Reuse the exact thumbnail already fetched on the detail page when possible.
                    val snapshot = SingletonImageLoader.get(context).diskCache?.openSnapshot(url)
                    if (snapshot != null) snapshot.use { it.data.toFile().inputStream().use { input -> temp.outputStream().use { out -> input.copyTo(out) } } }
                    else gate.withPermit {
                        client.newCall(Request.Builder().url(url).build()).await().requireSuccess().use { response ->
                            response.body!!.byteStream().use { input -> temp.outputStream().use { output ->
                                val buffer = ByteArray(65536)
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val count = input.read(buffer); if (count < 0) break
                                    output.write(buffer, 0, count)
                                }
                            } }
                        }
                    }
                    require(temp.length() > 0) { "图片响应为空" }
                    if (!temp.renameTo(target)) throw IOException("无法提交图片缓存")
                    committed = true
                } finally { temp.delete() }
            }
            target.setLastModified(System.currentTimeMillis())
            if (original || committed || dimensions[index] == null) {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(target.path, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) { target.delete(); throw IOException(if (original) "无法识别原图" else "预览不可用，可重试预览或明确选择原图") }
                if (!original) dimensions[index] = ReaderPageInfo(bounds.outWidth, bounds.outHeight)
            }
            if (!original) cachedPages.update { if (index in it) it else it + index }
            if (committed) prune(name)
            target
        }
    }

    private fun prune(keep: String) {
        val files = cacheFolder.listFiles()?.filter { it.isFile && !it.name.endsWith(".tmp") } ?: return
        var bytes = files.sumOf { it.length() }
        for (file in files.sortedBy { it.lastModified() }) {
            if (bytes <= 128L * 1024 * 1024) break
            if (file.name == keep || file.name in pinned) continue
            val length = file.length(); if (file.delete()) bytes -= length
        }
    }

    /** Same bounded bitmap composition and atomic commit as Hitomi's spreadImage. */
    suspend fun spreadImage(pages: List<Int>, images: List<File>): Uri = withContext(Dispatchers.IO) {
        if (images.size == 1) return@withContext Uri.fromFile(images.single())
        val target = File(cacheFolder, "spread-" + images.joinToString("-") { it.name.take(16) } + ".webp")
        if (!target.exists()) {
            val bounds = images.map { file -> BitmapFactory.Options().apply { inJustDecodeBounds = true; BitmapFactory.decodeFile(file.path, this) } }
            var sample = 1
            while (bounds.sumOf { it.outWidth.toLong() / sample } * bounds.maxOf { it.outHeight / sample } > 12_000_000) sample *= 2
            val width = bounds.sumOf { it.outWidth / sample }; val height = bounds.maxOf { it.outHeight / sample }
            require(width > 0 && height > 0)
            val config = if (width.toLong() * height > 6_000_000) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
            val merged = Bitmap.createBitmap(width, height, config)
            val temp = File(cacheFolder, target.name + ".${UUID.randomUUID()}.tmp")
            try {
                val canvas = Canvas(merged); var left = 0
                for (file in images) {
                    val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = config }) ?: throw IOException("图片解码失败")
                    try { canvas.drawBitmap(bitmap, left.toFloat(), ((height - bitmap.height) / 2).toFloat(), null); left += bitmap.width } finally { bitmap.recycle() }
                }
                temp.outputStream().use { merged.compress(Bitmap.CompressFormat.WEBP, 90, it) }
                if (!temp.renameTo(target)) throw IOException("无法提交双页预览")
                prune(target.name)
            } finally { merged.recycle(); temp.delete() }
        }
        Uri.fromFile(target)
    }
}

/** Ported from Hitomi buildSpreads; Pawchive's preview headers supply missing dimensions. */
fun buildSpreads(files: List<ReaderPageInfo>, multipage: Boolean, coverAlone: Boolean): List<List<Int>> {
    if (!multipage) return files.indices.map { listOf(it) }
    return buildList {
        var page = if (coverAlone && files.isNotEmpty()) { add(listOf(0)); 1 } else 0
        while (page < files.size) {
            if (page + 1 < files.size && files[page].fitsSpread && files[page + 1].fitsSpread) { add(listOf(page, page + 1)); page += 2 }
            else { add(listOf(page)); page++ }
        }
    }
}
