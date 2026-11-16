package pw.pawchive.media

import android.content.Context
import android.os.Build
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.disk.DiskCache
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import kotlinx.coroutines.launch
import me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage
import pw.pawchive.data.*
import okio.Path.Companion.toOkioPath

fun installImageLoader(context: Context) {
    SingletonImageLoader.setSafe {
        ImageLoader.Builder(context).components {
            add(OkHttpNetworkFetcherFactory(callFactory = { siteClient() }))
            if (Build.VERSION.SDK_INT >= 28) add(AnimatedImageDecoder.Factory()) else add(GifDecoder.Factory())
        }.memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.16).build() }
            .diskCache { DiskCache.Builder().directory(context.cacheDir.resolve("images").toOkioPath()).maxSizeBytes(256L * 1024 * 1024).build() }
            .build()
    }
}

@Composable
fun PreviewImage(file: RemoteFile?, modifier: Modifier = Modifier, description: String? = null, scale: ContentScale = ContentScale.Fit) {
    var failed by remember(file?.path) { mutableStateOf(false) }
    Box(modifier.background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
        if (file == null || failed) Text(if (file == null) "暂无封面" else "预览暂不可用", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        if (file != null) AsyncImage(model = file.thumbnail, contentDescription = description, modifier = Modifier.fillMaxSize(), contentScale = scale, onError = { failed = true }, onSuccess = { failed = false })
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun VideoPage(file: RemoteFile, onBack: () -> Unit) {
    var requested by remember { mutableStateOf(false) }
    var position by rememberSaveable(file.path) { mutableLongStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (!requested) {
            PreviewImage(file, Modifier.fillMaxSize())
            Button(onClick = { requested = true }, modifier = Modifier.align(Alignment.Center).size(80.dp), shape = androidx.compose.foundation.shape.CircleShape) { Text("▶", fontSize = 28.sp) }
        } else {
            val player = remember(file.path) {
                ExoPlayer.Builder(context).build().apply {
                    addListener(object : Player.Listener { override fun onPlayerError(e: PlaybackException) { error = "播放失败，请重试" } })
                    setMediaItem(MediaItem.fromUri(file.original)); seekTo(position); prepare(); playWhenReady = true
                }
            }
            AndroidView(factory = { PlayerView(it).apply { this.player = player; useController = true } }, modifier = Modifier.fillMaxSize(), onRelease = { it.player = null })
            DisposableEffect(player, lifecycle) {
                val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) { position = player.currentPosition; player.pause() } }
                lifecycle.addObserver(observer)
                onDispose { position = player.currentPosition; lifecycle.removeObserver(observer); player.release() }
            }
        }
        TextButton(onClick = onBack, modifier = Modifier.align(Alignment.TopStart).size(48.dp)) { Text("‹", color = Color.White, fontSize = 30.sp) }
        error?.let { message -> Column(Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = .8f)).padding(16.dp)) {
            Text(message, color = Color.White); TextButton(onClick = { requested = false; error = null }) { Text("重试") }
        } }
    }
}
