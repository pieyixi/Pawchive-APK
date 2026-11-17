package pw.pawchive.media

import android.content.Context
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.os.Build
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.Request
import pw.pawchive.data.*
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Seekable, bounded HTTP reads; never downloads an entire video to make a thumbnail. */
private val frameHttpClient by lazy { siteClient().newBuilder().callTimeout(4, TimeUnit.SECONDS).build() }
private class FrameSource(private val url: String) : MediaDataSource() {
    private val client = frameHttpClient
    private val chunks = LinkedHashMap<Long, ByteArray>()
    private val deadline = android.os.SystemClock.elapsedRealtime() + 8_000
    private var read = 0L
    private var length = -1L
    @Synchronized override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (size == 0) return 0
        if (android.os.SystemClock.elapsedRealtime() > deadline) throw IOException("Frame budget exceeded")
        if (length >= 0 && position >= length) return -1
        val start = position / 65536 * 65536
        val within = (position - start).toInt()
        chunks[start]?.let { bytes ->
            if(within>=bytes.size)return -1
            val count=minOf(size,bytes.size-within);bytes.copyInto(buffer,offset,within,within+count);return count
        }
        if (read >= 2_097_152) throw IOException("Frame budget exceeded")
        val count = minOf(65536, (2_097_152 - read).toInt())
        client.newCall(Request.Builder().url(url).header("Range", "bytes=$start-${start + count - 1}").build()).execute().use { response ->
            if (response.code == 416) return -1
            if (!response.isSuccessful || (start > 0 && response.code != 206)) throw IOException("Video frame HTTP ${response.code}")
            response.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()?.let { length = it }
            if (response.code == 200) length = response.body?.contentLength() ?: -1
            val stream = response.body?.byteStream() ?: return -1
            val bytes=ByteArray(count)
            var n = 0
            while (n < count) { val step = stream.read(bytes, n, count - n); if (step < 0) break; n += step }
            read += n
            val cached=bytes.copyOf(n);chunks[start]=cached
            if(chunks.size>8)chunks.remove(chunks.keys.first())
            if(within>=n)return -1
            val copied=minOf(size,n-within);cached.copyInto(buffer,offset,within,within+copied);return copied
        }
    }
    override fun getSize(): Long {
        if (length < 0) readAt(0, ByteArray(1), 0, 1)
        return length
    }
    override fun close() { chunks.clear() }
}

private val frameGate = Semaphore(2)
internal suspend fun firstVideoFrame(context: Context, file: RemoteFile): File? = withContext(Dispatchers.IO) {
    val directory = File(context.cacheDir, "video-frames").apply { mkdirs() }
    val key = MessageDigest.getInstance("SHA-256").digest(file.original.toByteArray()).joinToString("") { "%02x".format(it) }
    val target = File(directory, "$key.jpg")
    if (target.isFile && target.length() > 0) return@withContext target
    frameGate.withPermit {
        if (target.isFile && target.length() > 0) return@withPermit target
        if (Build.VERSION.SDK_INT < 27) return@withPermit null
        val retriever = MediaMetadataRetriever()
        val source = FrameSource(file.original)
        val pending = File(directory,"$key-${java.util.UUID.randomUUID()}.tmp")
        try {
            retriever.setDataSource(source)
            val frame = retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 480, 320) ?: return@withPermit null
            try { pending.outputStream().use { frame.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, it) } } finally { frame.recycle() }
            if(!pending.renameTo(target)) throw IOException("Cannot cache frame")
            directory.listFiles()?.filter {it.extension=="jpg"}?.sortedByDescending { it.lastModified() }?.drop(256)?.forEach { it.delete() }
            target
        } catch (e: Exception) { if (e is CancellationException) throw e; null }
        finally { pending.delete();retriever.release(); source.close() }
    }
}
