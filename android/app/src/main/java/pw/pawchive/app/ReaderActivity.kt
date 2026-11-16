package pw.pawchive.app

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.view.KeyEvent
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.*
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.map
import kotlinx.serialization.*
import pw.pawchive.data.*
import pw.pawchive.media.reader.*

/** Hitomi-style full-screen reader window; parent navigation/insets remain intact. */
class ReaderActivity : ComponentActivity() {
    private val volume = VolumePageTurnDispatcher()
    private val handledKeys = mutableSetOf<Int>()

    companion object {
        fun intent(context: Context, service: String, user: String, post: String, page: Int) = Intent()
            .setComponent(ComponentName(BuildConfig.APPLICATION_ID, ReaderActivity::class.java.name))
            .putExtra("service", service).putExtra("user", user).putExtra("post", post).putExtra("page", page)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        val container = (application as PawApplication).container
        val repository = container.repository
        val service = intent.getStringExtra("service") ?: return finish()
        val user = intent.getStringExtra("user") ?: return finish()
        val postId = intent.getStringExtra("post") ?: return finish()
        val initialPage = intent.getIntExtra("page", 0)
        setContent {
            val appearance by remember(repository) { repository.dark.map { ReaderAppearance(it) } }.collectAsStateWithLifecycle<ReaderAppearance?>(null)
            val settings by repository.readerSettings.collectAsStateWithLifecycle<ReaderSettings?>(null)
            var post by remember { mutableStateOf<Post?>(null) }
            var error by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(service, user, postId) {
                try { post = repository.cachedPosts("post:$service:$user:$postId").firstOrNull() ?: repository.post(service, user, postId) }
                catch (e: Exception) { if (e is CancellationException) throw e; error = e.message }
            }
            val loaded = appearance
            if (loaded == null) { Box(Modifier.fillMaxSize().background(Color(0xFF19191D))); return@setContent }
            val dark = loaded.dark ?: isSystemInDarkTheme()
            SideEffect {
                setTheme(if (dark) R.style.Theme_Pawchive_Dark else R.style.Theme_Pawchive)
                enableEdgeToEdge(statusBarStyle = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT) else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT), navigationBarStyle = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT) else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT))
            }
            PawTheme(dark) {
                val save = rememberSaveAction(container)
                val ready = post
                if (ready != null && settings != null && ready.images.isNotEmpty()) {
                    val initialSettings = remember { settings!! }
                    CompositionLocalProvider(LocalVolumePageTurn provides volume) {
                        ReaderScreen(ready.title, remember(ready) { ready.images }, initialPage, initialSettings,
                            onSettingsChange = { value -> lifecycleScope.launch { repository.setReaderSettings(value) } },
                            onProgress = { page -> lifecycleScope.launch(Dispatchers.IO) { repository.dao.metadata(Metadata("reader:$service:$user:$postId", page.toString())) } },
                            onBack = { finish() }, onSave = save)
                    }
                } else if (error != null) Page("图片阅读", { finish() }) { StatusContent(false, error) }
                else if (ready != null && ready.images.isEmpty()) Page("图片阅读", { finish() }) { StatusContent(false, "没有图片") }
                else Box(Modifier.fillMaxSize().background(androidx.compose.material3.MaterialTheme.colorScheme.background).safeDrawingPadding()) { StatusContent(true) }
            }
        }
    }

    @Composable
    private fun rememberSaveAction(container: AppContainer): (RemoteFile, Boolean) -> Unit {
        var pending by rememberSaveable { mutableStateOf<String?>(null) }
        val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
        fun enqueue(file: RemoteFile, uri: Uri) {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            lifecycleScope.launch {
                try { container.downloads.enqueue(file, uri); Toast.makeText(this@ReaderActivity, "已加入下载任务", Toast.LENGTH_SHORT).show() }
                catch (e: Exception) { if (e is CancellationException) throw e; Toast.makeText(this@ReaderActivity, e.message ?: "保存失败", Toast.LENGTH_LONG).show() }
            }
        }
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/*")) { uri ->
            val file = pending?.let { wireJson.decodeFromString<RemoteFile>(it) }; pending = null
            if (uri != null && file != null) {
                runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
                enqueue(file, uri)
            }
        }
        return { file, choose ->
            val name = file.name.ifBlank { file.path.substringAfterLast('/') }.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(180)
            if (choose || Build.VERSION.SDK_INT < 29) { pending = wireJson.encodeToString(file); picker.launch(name) }
            else lifecycleScope.launch {
                try {
                    val uri = withContext(Dispatchers.IO) {
                        contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                            put(MediaStore.Images.Media.DISPLAY_NAME, name)
                            put(MediaStore.Images.Media.MIME_TYPE, MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension) ?: "image/jpeg")
                            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Pawchive")
                            put(MediaStore.Images.Media.IS_PENDING, 1)
                        })
                    } ?: error("无法创建图片")
                    enqueue(file, uri)
                } catch (e: Exception) { if (e is CancellationException) throw e; Toast.makeText(this@ReaderActivity, e.message ?: "保存失败", Toast.LENGTH_LONG).show() }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val delta = when (keyCode) { KeyEvent.KEYCODE_VOLUME_UP -> -1; KeyEvent.KEYCODE_VOLUME_DOWN -> 1; else -> 0 }
        if (delta != 0 && volume.turn(delta)) { handledKeys.add(keyCode); return true }
        return super.onKeyDown(keyCode, event)
    }
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (handledKeys.remove(keyCode)) return true
        return super.onKeyUp(keyCode, event)
    }
}
private data class ReaderAppearance(val dark: Boolean?)
