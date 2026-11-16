package pw.pawchive.app

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.*
import org.json.JSONObject
import pw.pawchive.data.await
import java.io.File
import java.security.MessageDigest

internal data class UpdateRelease(val version: String, val apkUrl: String, val sumsUrl: String, val apkName: String, val pageUrl: String)
internal data class UpdateState(val busy: Boolean = false, val progress: Float? = null, val message: String = "", val release: UpdateRelease? = null, val ready: File? = null)
internal fun versionParts(version: String): List<Int> = version.removePrefix("v").split('.').map { it.toIntOrNull() ?: -1 }
internal fun newerVersion(remote: String, local: String): Boolean {
    val a = versionParts(remote); val b = versionParts(local)
    if (a.any { it < 0 } || b.any { it < 0 }) return false
    for (i in 0 until maxOf(a.size, b.size)) if (a.getOrElse(i) { 0 } != b.getOrElse(i) { 0 }) return a.getOrElse(i) { 0 } > b.getOrElse(i) { 0 }
    return false
}
internal fun parseUpdateRelease(body: String): UpdateRelease {
    val json = JSONObject(body)
    require(!json.optBoolean("draft") && !json.optBoolean("prerelease")) { "没有正式更新" }
    val version = json.getString("tag_name").removePrefix("v")
    require(Regex("\\d+\\.\\d+\\.\\d+").matches(version)) { "更新版本无效" }
    val assets = json.getJSONArray("assets")
    val names = (0 until assets.length()).map { assets.getJSONObject(it) }.associateBy { it.getString("name") }
    val apk = "Pawchive-$version.apk"
    fun asset(name: String): String {
        val url = names[name]?.getString("browser_download_url") ?: error("更新缺少安装包或校验文件")
        require(url.startsWith("https://github.com/pieyixi/Pawchive-APK/releases/download/v$version/")) { "更新来源无效" }
        return url
    }
    return UpdateRelease(version, asset(apk), asset("SHA256SUMS.txt"), apk, "https://github.com/pieyixi/Pawchive-APK/releases/tag/v$version")
}

internal class AppUpdater(private val app: Application, private val scope: CoroutineScope, private val client: OkHttpClient) {
    val state = MutableStateFlow(UpdateState())
    private var job: Job? = null
    fun check() {
        if (state.value.busy) return
        state.value = UpdateState(busy = true, message = "正在检查…")
        job = scope.launch {
            try {
                val body = client.newCall(Request.Builder().url("https://api.github.com/repos/pieyixi/Pawchive-APK/releases/latest").header("Accept", "application/vnd.github+json").header("User-Agent", "Pawchive/${BuildConfig.VERSION_NAME}").build()).await().use {
                    check(it.isSuccessful) { "检查失败（${it.code}），请稍后重试" }; it.body!!.string()
                }
                val release = parseUpdateRelease(body)
                currentCoroutineContext().ensureActive()
                state.value = if (newerVersion(release.version, BuildConfig.VERSION_NAME)) UpdateState(message = "发现 ${release.version}", release = release) else UpdateState(message = "已是最新版本")
            } catch (e: Exception) { if (e is CancellationException) throw e; currentCoroutineContext().ensureActive(); state.value = UpdateState(message = e.message ?: "检查失败，请重试") }
        }
    }
    fun dismiss() { job?.cancel(); state.value = UpdateState() }
    fun download() {
        val release = state.value.release ?: return
        if (state.value.busy) return
        state.value = state.value.copy(busy = true, message = "正在下载…", progress = 0f)
        job = scope.launch {
            val directory = File(app.cacheDir, "updates").apply { mkdirs() }
            val part = File(directory, release.apkName.removeSuffix(".apk") + "-${java.util.UUID.randomUUID()}.partial.apk")
            try {
                val sums = client.newCall(Request.Builder().url(release.sumsUrl).build()).await().use {
                    check(it.isSuccessful) { "无法读取安装包校验文件" }; it.body!!.string()
                }
                val expected = sums.lineSequence().map { it.trim().split(Regex("\\s+"), limit = 2) }.firstOrNull { it.size == 2 && it[1].removePrefix("*") == release.apkName }?.first()
                require(expected != null && Regex("[a-fA-F0-9]{64}").matches(expected)) { "安装包校验信息无效" }
                val digest = MessageDigest.getInstance("SHA-256")
                client.newCall(Request.Builder().url(release.apkUrl).build()).await().use { response ->
                    check(response.isSuccessful) { "下载失败（${response.code}）" }
                    val body = response.body ?: error("下载内容为空")
                    val total = body.contentLength(); var received = 0L; var updateAt = 0L
                    require(total <= 100_000_000) { "安装包过大" }
                    body.byteStream().use { input -> part.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer); if (count < 0) break
                            received += count; require(received <= 100_000_000) { "安装包过大" }
                            output.write(buffer, 0, count); digest.update(buffer, 0, count)
                            val now = android.os.SystemClock.elapsedRealtime()
                            if (now - updateAt >= 100) { updateAt = now; state.value = state.value.copy(progress = if (total > 0) received.toFloat() / total else null) }
                        }
                    } }
                    require(received > 0 && (total < 0 || received == total)) { "下载未完成，请重试" }
                }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                require(actual.equals(expected, true)) { "安装包校验失败，请重试" }
                validatePackage(part, release.version)
                currentCoroutineContext().ensureActive()
                val file = File(directory, release.apkName)
                check(part.renameTo(file)) { "无法保存安装包，请重试" }
                directory.listFiles()?.filter { it != file }?.forEach { it.delete() }
                currentCoroutineContext().ensureActive()
                state.value = UpdateState(message = "下载完成", release = release, ready = file)
            } catch (e: Exception) {
                part.delete()
                if (e is CancellationException) throw e
                currentCoroutineContext().ensureActive()
                state.value = state.value.copy(busy = false, progress = null, message = e.message ?: "下载失败，请重试")
            }
        }
    }
    @Suppress("DEPRECATION")
    private fun validatePackage(file: File, version: String) {
        val manager = app.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val incoming = manager.getPackageArchiveInfo(file.absolutePath, flags) ?: error("安装包无效")
        val installed = manager.getPackageInfo(app.packageName, flags)
        require(incoming.packageName == app.packageName && incoming.versionName == version) { "安装包身份不匹配" }
        val newer = if (Build.VERSION.SDK_INT >= 28) incoming.longVersionCode > installed.longVersionCode else incoming.versionCode > installed.versionCode
        require(newer) { "安装包版本未更新" }
        fun signatures(info: android.content.pm.PackageInfo) = (if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures)?.map { it.toCharsString() }?.toSet().orEmpty()
        require(signatures(incoming).isNotEmpty() && signatures(incoming) == signatures(installed)) { "安装包签名与当前应用不一致" }
    }
}

@Composable internal fun UpdateSetting() {
    val context = LocalContext.current
    val updater = (context.applicationContext as PawApplication).container.updater
    val state by updater.state.collectAsStateWithLifecycle()
    var launchedFile by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    var localError by remember { mutableStateOf<String?>(null) }
    fun install(file: File) {
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
            context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (_: Exception) { localError = "无法打开系统安装界面，请重试" }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        state.ready?.let { file -> if (context.packageManager.canRequestPackageInstalls()) install(file) else localError = "请允许此应用安装更新后重试" }
    }
    fun requestInstall(file: File) {
        if (context.packageManager.canRequestPackageInstalls()) install(file)
        else try { permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))) }
        catch (_: Exception) { localError = "请在系统设置中允许此应用安装更新" }
    }
    LaunchedEffect(state.ready) {
        val file = state.ready
        if (file != null && launchedFile != file.absolutePath) { launchedFile = file.absolutePath; requestInstall(file) }
    }
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).quietClick(!state.busy) { localError = null; updater.check() }.padding(horizontal = 20.dp).testTag("settings_check_update"), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text("检查更新", Modifier.weight(1f))
        Text(state.message.ifEmpty { "GitHub" }, Modifier.widthIn(max = 190.dp), fontSize = 12.sp, maxLines = 2,
            textAlign = androidx.compose.ui.text.style.TextAlign.End, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (state.release != null) AlertDialog(onDismissRequest = { updater.dismiss() }, title = { Text("发现新版本 ${state.release!!.version}") },
        text = { Column {
            Text(localError ?: state.message)
            if (state.busy) { Spacer(Modifier.height(12.dp)); state.progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) } ?: LinearProgressIndicator(Modifier.fillMaxWidth()) }
        } },
        confirmButton = { TextButton(enabled = !state.busy, onClick = { localError = null; state.ready?.let(::requestInstall) ?: updater.download() }) { Text(if (state.ready != null) "安装更新" else "下载并安装") } },
        dismissButton = { TextButton(onClick = { updater.dismiss() }) { Text(if (state.busy) "取消下载" else "稍后") } })
}
