package pw.pawchive.media

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.view.TextureView
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.*
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.*
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.delay
import pw.pawchive.data.RemoteFile

private fun Context.activity(): Activity? = when(this) { is Activity -> this; is ContextWrapper -> baseContext.activity(); else -> null }
private val VideoPink = Color(0xFFFF6699)
private val TimeFont=androidx.compose.ui.text.font.FontFamily(android.graphics.Typeface.create("sans-serif-condensed",android.graphics.Typeface.NORMAL))
private fun time(ms: Long): String { val s = ms.coerceAtLeast(0) / 1000; return if (s >= 3600) "%d:%02d:%02d".format(s/3600, s/60%60, s%60) else "%02d:%02d".format(s/60,s%60) }

/** Measured icon geometry in a 24dp viewport; touch area remains 48dp. */
@Composable fun VideoGlyph(kind: String, modifier: Modifier = Modifier, tint: Color = Color.White) {
    val traced = when(kind) { "back" -> R.drawable.video_back; "home" -> R.drawable.video_home; "pause" -> R.drawable.video_pause; "full" -> R.drawable.video_full; "more" -> R.drawable.video_more; "rate"->R.drawable.video_rate;"later"->R.drawable.video_later;"download"->R.drawable.video_download;"copy"->R.drawable.video_copy;"loop"->R.drawable.video_loop;"mirror"->R.drawable.video_mirror; else -> null }
    if(traced!=null) { Icon(androidx.compose.ui.res.painterResource(traced),null,modifier,tint=tint);return }
    Canvas(modifier) {
        val k = size.minDimension / 24f
        fun point(x: Float,y: Float)=Offset(x*k,y*k)
        fun line(x: Float,y: Float,u: Float,v: Float)=drawLine(Color.White, point(x,y),point(u,v),2f*k, StrokeCap.Round)
        when(kind) {
            "back" -> { line(15f,3f,6f,12f);line(6f,12f,15f,21f) }
            "home" -> { val p=Path().apply { moveTo(2*k,11*k);lineTo(12*k,2*k);lineTo(22*k,11*k);moveTo(5*k,9*k);lineTo(5*k,20*k);quadraticTo(5*k,22*k,8*k,22*k);lineTo(17*k,22*k);quadraticTo(20*k,22*k,20*k,19*k);lineTo(20*k,9*k) };drawPath(p,Color.White,style=Stroke(1.8f*k,cap=StrokeCap.Round,join=StrokeJoin.Round)); val smile=Path().apply { moveTo(9*k,15*k);quadraticTo(12*k,20*k,16*k,15*k) };drawPath(smile,Color.White,style=Stroke(1.7f*k,cap=StrokeCap.Round)) }
            "pause" -> { drawRoundRect(Color.White,point(4f,2f),androidx.compose.ui.geometry.Size(5*k,20*k),androidx.compose.ui.geometry.CornerRadius(2.5f*k));drawRoundRect(Color.White,point(15f,2f),androidx.compose.ui.geometry.Size(5*k,20*k),androidx.compose.ui.geometry.CornerRadius(2.5f*k)) }
            "play" -> drawPath(Path().apply { moveTo(6*k,2*k);lineTo(22*k,12*k);lineTo(6*k,22*k);close() },Color.White)
            "more" -> for(y in listOf(4f,12f,20f)) drawCircle(Color.White,1.9f*k,point(12f,y))
            "full" -> { line(3f,10f,3f,3f);line(3f,3f,10f,3f);line(14f,21f,21f,21f);line(21f,21f,21f,14f) }
            "exit" -> { line(3f,10f,10f,10f);line(10f,10f,10f,3f);line(14f,21f,14f,14f);line(14f,14f,21f,14f) }
        }
    }
}
@Composable private fun GlyphButton(kind: String,label: String, modifier: Modifier = Modifier, action: () -> Unit) {
    val ink=when(kind) { "back" -> 20.25.dp; "home" -> 20.8.dp; "pause" -> 23.dp; "full","exit" -> 16.dp; "more" -> 18.dp; else -> 22.dp }
    Box(modifier.size(48.dp).semanticsLabel(label).clickable(interactionSource=remember { androidx.compose.foundation.interaction.MutableInteractionSource() },indication=null,onClick=action),contentAlignment=Alignment.Center) { VideoGlyph(kind,Modifier.size(ink)) }
}
private fun Modifier.semanticsLabel(label: String) = this.then(Modifier.semantics { contentDescription = label })

@Composable
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
fun ReferenceVideo(file: RemoteFile, title: String, onBack: () -> Unit, onHome: () -> Unit, modifier: Modifier = Modifier, mediaUri: String = file.original, onLater: () -> Unit = {}, onDownload: () -> Unit = {}, sourceLink: String = file.original, onPreviewBack: () -> Unit = onBack, onPlaybackRequested: (Boolean) -> Unit = {}, suspended: Boolean = false, onCaptureReady: ((()->android.graphics.Bitmap?)?)->Unit = {}, previewBitmap:android.graphics.Bitmap?=null) {
    val context=LocalContext.current
    val clipboard=LocalClipboardManager.current
    val activity=context.activity()
    val audio = remember(context) { context.getSystemService(android.media.AudioManager::class.java) }
    val stream = android.media.AudioManager.STREAM_MUSIC
    var volumeStart by remember { mutableIntStateOf(0) }
    var volumeLevel by remember { mutableStateOf<Int?>(null) }
    var displayedVolume by remember { mutableIntStateOf(0) }
    var volumeDragging by remember { mutableStateOf(false) }
    val volumeMax = remember(audio) { audio.getStreamMaxVolume(stream) }
    LaunchedEffect(volumeDragging, volumeLevel != null, suspended) {
        if (suspended) { volumeDragging = false; volumeLevel = null }
        else if (!volumeDragging && volumeLevel != null) { delay(650); volumeLevel = null }
    }
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    var requested by rememberSaveable(file.path) { mutableStateOf(false) }
    var full by rememberSaveable { mutableStateOf(false) }
    fun leaveFullscreen() { full=false;activity?.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
    var position by rememberSaveable(file.path) { mutableLongStateOf(0) }
    var playing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var controls by remember { mutableStateOf(true) }
    var interaction by remember { mutableIntStateOf(0) }
    var panel by remember { mutableStateOf<String?>(null) }
    var loop by rememberSaveable { mutableStateOf(false) }

    var speed by rememberSaveable { mutableFloatStateOf(1f) }
    val activePlayer = remember(file.path, requested) {
        if (requested) ExoPlayer.Builder(context.applicationContext).build().apply {
            setAudioAttributes(AudioAttributes.DEFAULT, true)
            setHandleAudioBecomingNoisy(true)
            setPlaybackSpeed(speed)
            repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        } else null
    }
    val player by rememberUpdatedState(activePlayer)
    var texture by remember { mutableStateOf<TextureView?>(null) }
    DisposableEffect(player) {
        onCaptureReady { texture?.takeIf {it.isAvailable && it.width>0 && it.height>0}?.let {view ->
            val ratio=view.width.toFloat()/view.height
            val w=minOf(480,(320*ratio).toInt()).coerceAtLeast(1)
            runCatching {view.getBitmap(w,(w/ratio).toInt().coerceAtLeast(1))}.getOrNull()
        } }
        onDispose {onCaptureReady(null)}
    }
    var duration by remember { mutableLongStateOf(0) }
    var buffered by remember { mutableLongStateOf(0) }
    var buffering by remember { mutableStateOf(false) }
    var videoRatio by remember(file.path) { mutableFloatStateOf(16f / 9f) }
    var seekPreview by remember { mutableStateOf<Long?>(null) }
    var resumeAfterSeek by remember { mutableStateOf(false) }
    fun beginSeek(): Long? {
        if (!requested || duration <= 0 || suspended) return null
        val player = player ?: return null
        if (seekPreview == null) {
            resumeAfterSeek = player.playWhenReady
            player.pause()
            seekPreview = player.currentPosition.coerceIn(0, duration)
            controls = true; interaction++
        }
        return seekPreview
    }
    fun previewSeek(target: Long) { if (seekPreview != null) seekPreview = target.coerceIn(0, duration) }
    fun endSeek(commit: Boolean) {
        val target = seekPreview ?: return
        seekPreview = null
        val player = player ?: return
        if (commit) { player.seekTo(target); position = target }
        if (resumeAfterSeek && !suspended && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) player.play()
        resumeAfterSeek = false; controls = true; interaction++
    }
    var firstFrame by remember(file.path) { mutableStateOf(false) }
    var preparingAt by remember { mutableLongStateOf(0L) }
    DisposableEffect(activePlayer,lifecycle) {
        val player = activePlayer ?: return@DisposableEffect onDispose {}
        fun updateState() {
            position = player.currentPosition
            duration = player.duration.coerceAtLeast(0)
            buffered = player.bufferedPosition
            playing = player.isPlaying
            buffering = player.playbackState == Player.STATE_BUFFERING
        }
        val listener=object: Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) { updateState() }
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    videoRatio = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                }
            }
            override fun onRenderedFirstFrame() {
                firstFrame = true
                if (preparingAt > 0) {
                    android.util.Log.d("PawVideo", "firstFrameMs=${android.os.SystemClock.elapsedRealtime() - preparingAt}")
                    preparingAt = 0
                }
            }
            override fun onPlayerError(e: PlaybackException) {
                android.util.Log.w("PawVideo", "playbackError=${e.errorCode}")
                error="播放失败，请重试"; controls=true
            }
        }
        player.addListener(listener)
        val observer=LifecycleEventObserver { _,event -> if(event==Lifecycle.Event.ON_STOP) { resumeAfterSeek=false;seekPreview=null;position=player.currentPosition;player.pause() } }
        lifecycle.addObserver(observer)
        onDispose { position=player.currentPosition;lifecycle.removeObserver(observer);player.removeListener(listener);player.release() }
    }
    LaunchedEffect(activePlayer, mediaUri) {
        onPlaybackRequested(requested)
        val player = activePlayer ?: return@LaunchedEffect
        preparingAt = android.os.SystemClock.elapsedRealtime()
        firstFrame = false
        player.setMediaItem(MediaItem.fromUri(mediaUri), position)
        player.prepare()
        if (!suspended) player.play()
    }
    LaunchedEffect(suspended) { if(suspended) {resumeAfterSeek=false;seekPreview=null;player?.pause()} }
    LaunchedEffect(activePlayer, controls, playing, buffering, suspended) {
        val player = activePlayer ?: return@LaunchedEffect
        if (!suspended && controls) {
            position = player.currentPosition
            buffered = player.bufferedPosition
            while (playing || buffering) {
                delay(200)
                position = player.currentPosition
                buffered = player.bufferedPosition
            }
        }
    }
    LaunchedEffect(controls,playing,interaction,panel,seekPreview!=null) { if(controls && playing && panel==null && seekPreview==null) { delay(3500);controls=false } }
    DisposableEffect(playing) { if(playing) activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) } }
    DisposableEffect(full) {
        activity?.requestedOrientation=if(full) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        onDispose { if(full) activity?.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
    }
    BackHandler(full) { leaveFullscreen();controls=true }
    val content=remember { movableContentOf {
        Box(Modifier.fillMaxSize().background(Color.Black).testTag("video_surface")
            .videoScrubInput(requested && duration>0 && panel==null, onStart={beginSeek()}, duration={duration},
                onPreview={previewSeek(it)}, onFinish={endSeek(true)}, onCancel={endSeek(false)}, onTap={controls=!controls;interaction++},
                onVolumeStart={ point, width ->
                    val allowed = requested && !suspended && panel == null && point.x >= width / 2f && volumeMax > 0 && !audio.isVolumeFixed
                    if (allowed) {
                        volumeStart = audio.getStreamVolume(stream)
                        volumeDragging = true
                        volumeLevel = volumeStart
                        displayedVolume = volumeStart
                        controls = false
                        interaction++
                    }
                    allowed
                },
                onVolumeDrag={ fraction ->
                    // Portrait samples: 30–60px on a 608px surface moved 7–15% of the range.
                    val target = (volumeStart + java.lang.Math.round(fraction * volumeMax * 1.5f)).coerceIn(0, volumeMax)
                    if (target != volumeLevel) {
                        try { audio.setStreamVolume(stream, target, 0) } catch (_: SecurityException) { }
                        volumeLevel = audio.getStreamVolume(stream)
                    }
                    displayedVolume = volumeLevel ?: volumeStart
                },
                onVolumeEnd={ volumeDragging = false })) {
            val scrubbing = seekPreview != null
            val shownPosition = seekPreview ?: position
            val quiet = Modifier.graphicsLayer { alpha = if(scrubbing) 0f else 1f }
            player?.let { player -> BoxWithConstraints(Modifier.fillMaxSize()) {
                val ratio = videoRatio
                val w=minOf(maxWidth,maxHeight*ratio)
                AndroidView(factory={ TextureView(it).apply { texture=this;player.setVideoTextureView(this) } },modifier=Modifier.align(Alignment.Center).size(w,w/ratio),onRelease={if(texture===it) texture=null;player.clearVideoTextureView(it)})
            } }
            if(!firstFrame) {
                if(previewBitmap!=null) Image(previewBitmap.asImageBitmap(),null,Modifier.fillMaxSize(),contentScale=androidx.compose.ui.layout.ContentScale.Fit)
                else PreviewImage(file,Modifier.fillMaxSize(),background=Color.Black)
            }
            if(!requested) Box(Modifier.size(72.dp).align(Alignment.Center).background(Color.Black.copy(alpha=.35f),androidx.compose.foundation.shape.CircleShape).semanticsLabel("开始播放").clickable { requested=true;interaction++ },contentAlignment=Alignment.Center) { VideoGlyph("play",Modifier.size(32.dp)) }
            if(buffering && !scrubbing && volumeLevel == null) CircularProgressIndicator(Modifier.size(28.dp).align(Alignment.Center),color=VideoPink,strokeWidth=2.dp)
            if(controls) {
                Box(Modifier.fillMaxWidth().height(72.dp).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha=.65f),Color.Transparent))))
                Row(quiet.align(Alignment.TopStart).fillMaxWidth().padding(top=4.dp),verticalAlignment=Alignment.CenterVertically) {
                    GlyphButton("back","返回") { if(full) leaveFullscreen() else if(!requested) onPreviewBack() else onBack() }
                    if(full) Text(title,Modifier.weight(1f),color=Color.White,fontSize=14.sp,maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    if(!full) Spacer(Modifier.weight(1f))
                    GlyphButton("more","播放设置") { panel="more";interaction++ }
                }
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent,Color.Black.copy(alpha=.65f))))) {
                    if(full) {
                        Text("${time(shownPosition)}/${time(duration)}",quiet.padding(start=20.dp),color=Color.White,fontSize=12.sp,fontFamily=TimeFont)
                        VideoSeek(shownPosition,duration,buffered,onStart={beginSeek()},onPreview={previewSeek(it)},onFinish={endSeek(true)},onCancel={endSeek(false)})
                    }
                    Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal=4.dp),verticalAlignment=Alignment.CenterVertically) {
                        GlyphButton(if(playing) "pause" else "play",if(playing) "暂停" else "播放", quiet) {
                            val current = player
                            if (current == null) requested = true
                            else if (current.playWhenReady && current.playbackState != Player.STATE_ENDED) current.pause()
                            else { if(current.playbackState==Player.STATE_ENDED) current.seekTo(0); current.play() }
                            interaction++
                        }
                        if(!full) { VideoSeek(shownPosition,duration,buffered,Modifier.weight(1f).padding(horizontal=8.dp),onStart={beginSeek()},onPreview={previewSeek(it)},onFinish={endSeek(true)},onCancel={endSeek(false)}); Text("${time(shownPosition)}/${time(duration)}",quiet.padding(horizontal=4.dp),color=Color.White,fontSize=11.sp,maxLines=1,fontFamily=TimeFont) }
                        else { Spacer(Modifier.weight(1f));TextButton(modifier=quiet,onClick={panel="speed";interaction++}) { Text(if(speed==1f) "倍速" else "${speed}X",color=Color.White) } }
                        GlyphButton(if(full) "exit" else "full",if(full) "退出全屏" else "全屏", quiet) { if(full) leaveFullscreen() else full=true;controls=true;interaction++ }
                    }
                }
            }
            if(scrubbing) {
                Row(Modifier.align(Alignment.Center).offset(y=if(full) 80.dp else 40.dp).testTag("video_seek_preview"),verticalAlignment=Alignment.CenterVertically) {
                    Text(time(shownPosition),color=Color.White,fontSize=14.sp,fontWeight=androidx.compose.ui.text.font.FontWeight.SemiBold,fontFamily=TimeFont)
                    Text(" / ${time(duration)}",color=Color(0xFFB8B8B8),fontSize=14.sp,fontWeight=androidx.compose.ui.text.font.FontWeight.SemiBold,fontFamily=TimeFont)
                }
            }
            error?.let { message -> Column(Modifier.align(Alignment.Center).background(Color.Black.copy(alpha=.8f)).padding(12.dp)) { Text(message,color=Color.White);TextButton(onClick={
                error = null
                preparingAt = android.os.SystemClock.elapsedRealtime()
                player?.prepare()
                player?.play()
            }) {Text("重试")} } }
            androidx.compose.animation.AnimatedVisibility(
                visible = volumeLevel != null && panel == null && !suspended,
                modifier = Modifier.align(Alignment.Center),
                enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(90)),
                exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(180)),
            ) {
                Box(
                    Modifier.size(150.dp, 44.dp)
                        .background(Color.Black.copy(alpha = .3f),
                            androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                        .testTag("video_volume_overlay")
                ) {
                    Icon(
                        androidx.compose.ui.res.painterResource(
                            if (displayedVolume == 0) R.drawable.video_volume_1 else R.drawable.video_volume_2),
                        "音量",
                        Modifier.align(Alignment.CenterStart).offset(x = 10.dp).size(24.dp),
                        tint = Color.White
                    )
                    Canvas(Modifier.align(Alignment.CenterStart).offset(x = 57.dp).size(70.dp, 1.5.dp)) {
                        val fraction = displayedVolume.toFloat() / volumeMax.coerceAtLeast(1)
                        val radius = androidx.compose.ui.geometry.CornerRadius(size.height / 2)
                        drawRoundRect(Color.White.copy(alpha = .2f), cornerRadius = radius)
                        if (fraction > 0f) drawRoundRect(VideoPink,
                            size = androidx.compose.ui.geometry.Size(size.width * fraction, size.height),
                            cornerRadius = radius)
                    }
                }
            }
            if(panel!=null) Box(Modifier.fillMaxSize(),contentAlignment=Alignment.CenterEnd) {
                VideoMenus(panel,full,speed,loop,{panel=null;interaction++},{rate -> speed=rate;player?.setPlaybackSpeed(rate)},
                    {enabled -> loop=enabled;player?.repeatMode=if(enabled) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF},
                    onLater,onDownload,{clipboard.setText(androidx.compose.ui.text.AnnotatedString(sourceLink))})
            }
        }
    } }
    Box(modifier.background(Color.Black)) { if(!full) content() }
    if(full) Dialog(onDismissRequest={if(panel!=null) panel=null else leaveFullscreen()},properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
        val view=LocalView.current
        DisposableEffect(view) {
            val window=(view.parent as? DialogWindowProvider)?.window
            val controller=window?.let { WindowCompat.getInsetsController(it,view) }
            controller?.systemBarsBehavior=WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
            onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
        }
        Box(Modifier.fillMaxSize().background(Color.Black).windowInsetsPadding(WindowInsets.displayCutout)) { content() }
    }
}

@Composable private fun VideoSeek(position: Long,duration: Long,buffered: Long,modifier: Modifier=Modifier,
    onStart:()->Long?,onPreview:(Long)->Unit,onFinish:()->Unit,onCancel:()->Unit) {
    Canvas(modifier.fillMaxWidth().height(48.dp).testTag("video_seek")
        .semantics {
            contentDescription="播放进度"
            progressBarRangeInfo=ProgressBarRangeInfo(position.toFloat(),0f..duration.coerceAtLeast(1).toFloat())
            setProgress { value ->
                if(onStart()!=null) {onPreview(value.toLong());onFinish();true} else false
            }
        }
        .videoScrubInput(duration>0,track=true,onStart=onStart,duration={duration},onPreview=onPreview,onFinish=onFinish,onCancel=onCancel)) {
        val progress=if(duration>0) position.toFloat()/duration else 0f
        val y=center.y;val thickness=2.dp.toPx()
        drawLine(Color.White.copy(alpha=.3f),Offset(0f,y),Offset(size.width,y),thickness)
        drawLine(Color.White.copy(alpha=.45f),Offset(0f,y),Offset(size.width*(if(duration>0) buffered.toFloat()/duration else 0f).coerceIn(0f,1f),y),thickness)
        drawLine(VideoPink,Offset(0f,y),Offset(size.width*progress.coerceIn(0f,1f),y),thickness)
        for(sign in listOf(-1f,1f)) drawLine(Color.Black,Offset(size.width*progress+sign*3.dp.toPx(),y-5.dp.toPx()),Offset(size.width*progress+sign*6.dp.toPx(),y-8.dp.toPx()),2.dp.toPx(),StrokeCap.Round)
        drawRoundRect(Color.Black,Offset(size.width*progress-7.dp.toPx(),y-5.dp.toPx()),androidx.compose.ui.geometry.Size(14.dp.toPx(),10.dp.toPx()),androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()))
        drawRoundRect(Color.White,Offset(size.width*progress-6.dp.toPx(),y-4.dp.toPx()),androidx.compose.ui.geometry.Size(12.dp.toPx(),8.dp.toPx()),androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()))
        for(dx in listOf(-2f,2f)) drawLine(Color.Black,Offset(size.width*progress+dx.dp.toPx(),y-1.5f.dp.toPx()),Offset(size.width*progress+dx.dp.toPx(),y+1.5f.dp.toPx()),1.5f.dp.toPx(),StrokeCap.Round)
    }
}
