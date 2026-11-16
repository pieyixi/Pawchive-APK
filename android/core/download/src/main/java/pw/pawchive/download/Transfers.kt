package pw.pawchive.download

import android.app.*
import android.app.job.*
import android.content.*
import android.content.pm.ServiceInfo
import android.net.*
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import okhttp3.Request
import pw.pawchive.data.*
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Configured by Application in every process, including system-restarted jobs. */
object TransferRuntime {
    lateinit var repository: PawRepository
    val running = ConcurrentHashMap<String, Job>()
    const val CHANNEL = "pawchive.downloads"
    fun notification(context: Context, id: String, name: String, bytes: Long = 0, total: Long = -1): Notification {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "文件下载", NotificationManager.IMPORTANCE_LOW))
        val cancel = Intent(context, CancelTransferReceiver::class.java).putExtra("id", id)
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply { data = Uri.parse("pawchive://downloads"); flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP }
        return NotificationCompat.Builder(context, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(name).setContentText(if (total > 0) "${bytes * 100 / total}%" else "正在下载")
            .setProgress(100, if (total > 0) (bytes * 100 / total).toInt() else 0, total <= 0)
            .setOnlyAlertOnce(true).setOngoing(true)
            .setContentIntent(open?.let { PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE) })
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "取消", PendingIntent.getBroadcast(context, id.hashCode(), cancel, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .build()
    }
    fun jobId(id: String) = id.hashCode() and 0x7fffffff
}

class DownloadCoordinator(private val context: Context, private val repository: PawRepository) {
    suspend fun reconcileInterruptedTasks() {
        val scheduled = if (Build.VERSION.SDK_INT >= 34) context.getSystemService(JobScheduler::class.java).allPendingJobs.mapNotNull { it.extras.getString("id") }.toSet() else emptySet()
        repository.dao.unfinishedDownloads().filter { !TransferRuntime.running.containsKey(it.id) && it.id !in scheduled }.forEach {
            repository.dao.download(it.copy(status = "paused", error = "下载已中断，可重试续传"))
        }
    }
    suspend fun enqueue(file: RemoteFile, destination: Uri): String {
        val id = UUID.randomUUID().toString()
        val row = DownloadRow(id, file.name.ifBlank { file.path.substringAfterLast('/') }, file.original, destination.toString())
        repository.dao.download(row)
        try { schedule(id) } catch (e: Exception) { repository.dao.download(row.copy(status = "failed", error = e.message)); throw e }
        return id
    }
    suspend fun schedule(id: String) {
        val previous = repository.dao.download(id) ?: error("下载记录不存在")
        repository.dao.download(previous.copy(status = "queued", error = null))
        try { if (Build.VERSION.SDK_INT >= 34) {
            val job = JobInfo.Builder(TransferRuntime.jobId(id), ComponentName(context, TransferJobService::class.java))
                .setUserInitiated(true).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setExtras(PersistableBundle().apply { putString("id", id) }).build()
            check(context.getSystemService(JobScheduler::class.java).schedule(job) == JobScheduler.RESULT_SUCCESS) { "系统暂时无法启动下载，请保持应用在前台重试" }
        } else ContextCompat.startForegroundService(context, Intent(context, TransferService::class.java).putExtra("id", id))
        } catch (error: Exception) {
            repository.dao.download(previous.copy(status = "paused", error = error.message))
            throw error
        }
    }
    suspend fun cancel(id: String) {
        TransferRuntime.running[id]?.cancelAndJoin()
        context.getSystemService(JobScheduler::class.java).cancel(TransferRuntime.jobId(id))
        repository.dao.download(id)?.takeUnless { it.status == "complete" }?.let {
            repository.dao.download(it.copy(status = "cancelled", error = null))
            File(context.cacheDir, "transfers/$id.part").delete()
            if (Build.VERSION.SDK_INT >= 29 && Uri.parse(it.destination).authority == "media") context.contentResolver.delete(Uri.parse(it.destination), null, null)
        }
    }
}

private class SourceChangedException : IOException("文件版本已变化，重试后将重新下载")
private suspend fun transfer(context: Context, id: String, progress: (Notification) -> Unit) = withContext(Dispatchers.IO) {
    val repo = TransferRuntime.repository
    var row = repo.dao.download(id) ?: return@withContext
    val partial = File(context.cacheDir, "transfers/$id.part").apply { parentFile!!.mkdirs() }
    try {
        row = row.copy(status = "running", error = null); repo.dao.download(row)
        var offset = if (row.etag != null && partial.exists()) partial.length() else 0L
        if (offset == 0L) partial.delete()
        val request = Request.Builder().url(row.url)
        if (offset > 0) request.header("Range", "bytes=$offset-").header("If-Range", row.etag!!)
        val opened = repo.publicClient.newCall(request.build()).await()
        if (opened.code == 416) { opened.close(); throw SourceChangedException() }
        opened.requireSuccess().use { response ->
            val etag = response.header("ETag")
            if (response.code == 206) {
                val start = response.header("Content-Range")?.substringAfter("bytes ")?.substringBefore('-')?.toLongOrNull()
                if (start != offset || (row.etag != null && etag != row.etag)) throw SourceChangedException()
            } else { offset = 0; partial.delete() }
            val body = response.body ?: throw IOException("空文件响应")
            val total = body.contentLength().takeIf { it >= 0 }?.plus(offset) ?: -1
            row = row.copy(bytes = offset, total = total, etag = etag); repo.dao.download(row)
            var last = 0L
            body.byteStream().use { input -> java.io.FileOutputStream(partial, offset > 0).use { out ->
                val buffer = ByteArray(65536)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val size = input.read(buffer); if (size < 0) break
                    out.write(buffer, 0, size); row = row.copy(bytes = row.bytes + size)
                    if (SystemClock.elapsedRealtime() - last > 750) {
                        repo.dao.download(row); progress(TransferRuntime.notification(context, id, row.name, row.bytes, total)); last = SystemClock.elapsedRealtime()
                    }
                }
                out.fd.sync()
            } }
            if (total >= 0 && partial.length() != total) throw IOException("文件尚未完整下载")
        }
        currentCoroutineContext().ensureActive()
        context.contentResolver.openOutputStream(Uri.parse(row.destination), "wt")?.use { output -> partial.inputStream().use { it.copyTo(output) } }
            ?: throw IOException("无法写入所选位置")
        if (Build.VERSION.SDK_INT >= 29 && Uri.parse(row.destination).authority == "media") {
            context.contentResolver.update(Uri.parse(row.destination), ContentValues().apply { put("is_pending", 0) }, null, null)
        }
        partial.delete(); repo.dao.download(row.copy(status = "complete", bytes = row.bytes, error = null))
    } catch (e: CancellationException) {
        withContext(NonCancellable) { repo.dao.download(row.copy(status = "paused", error = "下载已暂停")) }
        throw e
    } catch (e: Exception) {
        if (e is SourceChangedException) { partial.delete(); row = row.copy(bytes = 0, etag = null) }
        repo.dao.download(row.copy(status = "failed", error = e.message ?: "下载失败"))
    }
}

class TransferJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    override fun onStartJob(params: JobParameters): Boolean {
        val id = params.extras.getString("id") ?: return false
        if (Build.VERSION.SDK_INT >= 34) setNotification(params, TransferRuntime.jobId(id), TransferRuntime.notification(this, id, "Pawchive 下载"), JOB_END_NOTIFICATION_POLICY_REMOVE)
        val task = scope.launch {
            try { transfer(this@TransferJobService, id) { getSystemService(NotificationManager::class.java).notify(TransferRuntime.jobId(id), it) } }
            finally { TransferRuntime.running.remove(id); jobFinished(params, false) }
        }
        TransferRuntime.running[id] = task
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { params.extras.getString("id")?.let { TransferRuntime.running.remove(it)?.cancel() }; return false }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}

class TransferService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra("id") ?: return START_NOT_STICKY
        val notification = TransferRuntime.notification(this, id, "Pawchive 下载")
        if (Build.VERSION.SDK_INT >= 29) startForeground(TransferRuntime.jobId(id), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(TransferRuntime.jobId(id), notification)
        TransferRuntime.running[id] = scope.launch {
            try { transfer(this@TransferService, id) { getSystemService(NotificationManager::class.java).notify(TransferRuntime.jobId(id), it) } }
            finally { TransferRuntime.running.remove(id); if (TransferRuntime.running.isEmpty()) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() } }
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    override fun onTimeout(startId: Int, fgsType: Int) { scope.cancel(); stopSelf() }
}

class CancelTransferReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("id") ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch { try { DownloadCoordinator(context, TransferRuntime.repository).cancel(id) } finally { pending.finish() } }
    }
}
