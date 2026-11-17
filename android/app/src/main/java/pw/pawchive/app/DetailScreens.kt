package pw.pawchive.app

import android.graphics.Typeface
import android.text.Spanned
import android.text.style.*
import androidx.core.text.HtmlCompat
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.pager.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.activity.compose.BackHandler
import kotlin.math.roundToInt
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.input.nestedscroll.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.jsoup.Jsoup
import pw.pawchive.data.*
import pw.pawchive.media.PreviewImage
import pw.pawchive.media.ReferenceVideo

@Composable
fun DetailScreen(vm: DetailViewModel, app: AppViewModel, onBack: () -> Unit, onAuthor: () -> Unit, onImage: (Int) -> Unit, onVideo: (Int) -> Unit, onFiles: () -> Unit, onHome: () -> Unit = onBack, videoUri: (RemoteFile) -> String = {it.original}) {
    val state by vm.state.collectAsStateWithLifecycle()
    val follows by app.follows.collectAsStateWithLifecycle()
    val favorites by app.favorites.collectAsStateWithLifecycle()
    var video by rememberSaveable(vm.id) { mutableIntStateOf(-1) }
    var expanded by rememberSaveable(vm.id) { mutableStateOf(false) }
    var started by rememberSaveable(vm.id) { mutableStateOf(false) }
    var capture by remember { mutableStateOf<(() -> android.graphics.Bitmap?)?>(null) }
    var closingFrame by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var origin by remember { mutableStateOf<Rect?>(null) }
    var videoTabBounds by remember { mutableStateOf(Rect.Zero) }
    var rootTop by remember { mutableFloatStateOf(0f) }
    val progress = remember { Animatable(if(expanded) 1f else 0f) }
    var selectedPoster by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    val moving by remember { derivedStateOf { video >= 0 && (progress.value < 1f || !expanded) } }
    fun collapsePreview() {
        if (!expanded) return
        closingFrame = if (started) capture?.invoke() else null
        expanded = false
    }
    BackHandler(video >= 0 && !started) { collapsePreview() }
    LaunchedEffect(expanded, video) {
        if (video >= 0) {
            // Mount the destination once before moving the already decoded poster.
            withFrameNanos { }
            progress.animateTo(if (expanded) 1f else 0f, tween(260, easing = FastOutSlowInEasing))
            if (!expanded) { video = -1; origin = null; started = false; closingFrame = null; selectedPoster = null }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().onGloballyPositioned { rootTop = it.boundsInRoot().top }) {
        val post = state.post
        if (post == null) { StatusContent(state.error == null, state.error, vm::load); return@BoxWithConstraints }
        val playerHeight = maxWidth * 9f / 16f
        val fixedHeader = post.videos.isNotEmpty()
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            if (fixedHeader) Box(Modifier.fillMaxWidth().height(playerHeight).testTag("detail_top_slot")) {
                // Geometry never changes: gallery tabs keep the same Y throughout entry and exit.
                Column(Modifier.fillMaxSize().then(if (video >= 0 && !moving) Modifier.clearAndSetSemantics {} else Modifier)
                    .graphicsLayer { alpha = if (video >= 0 && !moving) 0f else 1f }
                    .verticalScroll(rememberScrollState())) {
                    DetailToolbar(onBack)
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        DetailInfo(post, state.creator, follows.any { it.key == state.creator?.key }, onAuthor, { state.creator?.let(app::follow) })
                    }
                }
                post.videos.getOrNull(video)?.let { file -> key(file.path) {
                    ReferenceVideo(file, post.title, onBack, onHome,
                        Modifier.fillMaxSize().graphicsLayer { alpha = if (moving) 0f else 1f },
                        mediaUri = videoUri(file), onLater = { app.later(post) }, onDownload = onFiles,
                        sourceLink = "$SITE/${post.service}/user/${post.user}/post/${post.id}",
                        onPreviewBack = { collapsePreview() }, onPlaybackRequested = { started = it },
                        suspended = !expanded, onCaptureReady = { capture = it }, previewBitmap = selectedPoster)
                } }
            } else DetailToolbar(onBack)
            DetailMediaLayout(post, onImage, onVideo, Modifier.weight(1f),
                onVideoOrigin = { index, bounds, bitmap ->
                    closingFrame = null; selectedPoster = bitmap; origin = bounds
                    video = index; started = false; expanded = true
                },
                onMediaPageChanged = { page -> if (page == 0 && video >= 0) collapsePreview() },
                onVideoTabBounds = { videoTabBounds = it }) {
                if (!fixedHeader) item(key = "detail_info") {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        DetailInfo(post, state.creator, follows.any { it.key == state.creator?.key }, onAuthor, { state.creator?.let(app::follow) })
                    }
                }
                if (state.error != null) item(key = "error") { StatusContent(false, state.error, vm::load) }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val selected = favorites.any { it.key == post.key }
                Button(onClick = { app.favorite(post) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp), shape = RoundedCornerShape(5.dp), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurface)) {
                    Icon(if (selected) Icons.Outlined.Star else Icons.Outlined.StarOutline, null, Modifier.size(22.dp)); Text(if (selected) "已收藏" else "收藏")
                }
                Button(onClick = onFiles, modifier = Modifier.weight(1f).heightIn(min = 48.dp), shape = RoundedCornerShape(5.dp), colors = ButtonDefaults.buttonColors(containerColor = Pink, contentColor = Color(0xFF26121A))) { Text("下载文件") }
            }
        }
        val source = if (expanded) origin else videoTabBounds.takeIf { it != Rect.Zero }?.let { Rect(it.center, it.center) } ?: origin
        if (source != null && moving) {
            val poster = remember(selectedPoster) { selectedPoster?.asImageBitmap() }
            val frame = remember(closingFrame) { closingFrame?.asImageBitmap() }
            val background = MaterialTheme.colorScheme.background
            Canvas(Modifier.fillMaxSize().testTag("video_poster_transition")) {
                val p = progress.value.coerceIn(0f, 1f)
                val x = source.left * (1-p); val y = (source.top-rootTop) * (1-p)
                val w = source.width + (size.width-source.width)*p
                val h = source.height + (playerHeight.toPx()-source.height)*p
                drawRoundRect(androidx.compose.ui.graphics.lerp(background, Color.Black, p), Offset(x,y), androidx.compose.ui.geometry.Size(w,h), androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()*(1-p)), alpha = if(expanded) 1f else p)
                fun fitted(image: androidx.compose.ui.graphics.ImageBitmap, alpha: Float) {
                    val scale = minOf(w/image.width, h/image.height)
                    val dw = (image.width*scale).roundToInt().coerceAtLeast(1)
                    val dh = (image.height*scale).roundToInt().coerceAtLeast(1)
                    drawImage(image, dstOffset=IntOffset((x+(w-dw)/2).roundToInt(),(y+(h-dh)/2).roundToInt()), dstSize=IntSize(dw,dh), alpha=alpha, filterQuality=androidx.compose.ui.graphics.FilterQuality.Medium)
                }
                poster?.let { fitted(it, if(expanded) 1f else p) }
                frame?.let { fitted(it,p) }
            }
        }
    }
}

@Composable private fun DetailToolbar(onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回") }
        Text("作品详情", style = MaterialTheme.typography.titleMedium)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable private fun DetailInfo(post: Post, creator: Creator?, followed: Boolean, onAuthor: () -> Unit, onFollow: () -> Unit) {
    Text(post.title.ifBlank { "无标题" }, fontSize = 19.sp, lineHeight = 27.sp, fontWeight = FontWeight.SemiBold)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.weight(1f).clickable(onClick = onAuthor), verticalAlignment = Alignment.CenterVertically) {
            Avatar(creator, Modifier.size(42.dp))
            Column(Modifier.padding(start = 10.dp)) {
                Text(creator?.name ?: post.user, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${post.service} · ${displayTime(post.timestamp)}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (creator != null) DetailFollowButton(followed, onFollow)
    }
    if (post.content.isNotBlank()) FoldedContent(post.content)
}

/** Shared collapsing header, sticky media tabs and independently saved lazy grids. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DetailMediaLayout(post: Post, onImage: (Int) -> Unit, onVideo: (Int) -> Unit,
    modifier: Modifier = Modifier, onVideoOrigin: ((Int, Rect, android.graphics.Bitmap?) -> Unit)? = null, onMediaPageChanged: (Int) -> Unit = {}, outerState: LazyListState? = null,onVideoTabBounds:(Rect)->Unit={}, header: LazyListScope.() -> Unit = {}) {
    val pager = rememberPagerState(initialPage = if (post.images.isEmpty() && post.videos.isNotEmpty()) 1 else 0, pageCount = { 2 })
    LaunchedEffect(pager.currentPage) { onMediaPageChanged(pager.currentPage) }
    val outer = outerState ?: rememberLazyListState()
    val collapseHeader = remember(outer) { object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
            if (available.y < 0) Offset(0f, -outer.dispatchRawDelta(-available.y)) else Offset.Zero
    } }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        // 51dp tabs + 24dp content padding + 14dp item gap; the pager has finite bounds.
        val pagerHeight = (maxHeight - 89.dp).coerceAtLeast(100.dp)
        LazyColumn(state = outer, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            header()
            run {
                stickyHeader(key = "media_tabs") {
                    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background), verticalAlignment = Alignment.CenterVertically) {
                        PagerTabStrip(listOf("图片", "视频"), pager, tabWidth = 64.dp,onTabBounds={index,bounds -> if(index==1) onVideoTabBounds(bounds)})
                        Spacer(Modifier.weight(1f))
                        Text(if (pager.currentPage == 0) "${post.images.size} 张" else "${post.videos.size} 个", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                item(key = "media_pages") {
                    HorizontalPager(state = pager, modifier = Modifier.fillMaxWidth().height(pagerHeight).nestedScroll(collapseHeader).testTag("detail_media_pager"), key = { it }) { page ->
                        val files = if (page == 0) post.images else post.videos
                        LazyVerticalGrid(GridCells.Fixed(3), state = rememberLazyGridState(), modifier = Modifier.fillMaxSize().testTag("detail_media_grid_$page"),
                            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            itemsIndexed(files, key = { _, file -> file.path }) { index, file ->
                                var bounds by remember { mutableStateOf(Rect.Zero) }
                                var bitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
                                Column(Modifier.fillMaxWidth().clickable(interactionSource=remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication=null) { if (page == 0) onImage(index) else if(onVideoOrigin!=null) onVideoOrigin(index,bounds,bitmap) else onVideo(index) }, horizontalAlignment = Alignment.CenterHorizontally) {
                                    Box(Modifier.fillMaxWidth().aspectRatio(2f / 3).onGloballyPositioned { bounds=it.boundsInRoot() }.clip(RoundedCornerShape(3.dp))) {
                                        PreviewImage(file, Modifier.fillMaxSize(), "${if (page == 0) "图片" else "视频"} ${index + 1}", onBitmap={bitmap=it})
                                        if (page == 1) Icon(Icons.Outlined.PlayCircle, null, Modifier.size(28.dp).align(Alignment.Center), tint = Color.White)
                                    }
                                    Text("${index + 1}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FoldedContent(html: String) {
    var expanded by rememberSaveable(html.hashCode()) { mutableStateOf(false) }
    var overflows by remember(html) { mutableStateOf(false) }
    val linkColor = MaterialTheme.colorScheme.primary
    val annotated = remember(html, linkColor) {
        val document = Jsoup.parseBodyFragment(html)
        document.select("script,style,iframe,object,embed,img,video,audio").remove()
        val spanned = HtmlCompat.fromHtml(document.body().html(), HtmlCompat.FROM_HTML_MODE_COMPACT) as Spanned
        buildAnnotatedString {
            append(spanned.toString().trimEnd())
            spanned.getSpans(0, spanned.length, URLSpan::class.java).forEach { span ->
                val start = spanned.getSpanStart(span).coerceIn(0, length); val end = spanned.getSpanEnd(span).coerceIn(start, length)
                if (span.url.startsWith("https://") || span.url.startsWith("http://")) addLink(LinkAnnotation.Url(span.url, TextLinkStyles(SpanStyle(color = linkColor, textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline))), start, end)
            }
            spanned.getSpans(0, spanned.length, StyleSpan::class.java).forEach { span -> if (span.style == Typeface.BOLD) {
                val start = spanned.getSpanStart(span).coerceIn(0, length); val end = spanned.getSpanEnd(span).coerceIn(start, length)
                addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, end)
            } }
        }
    }
    Column {
        Text(annotated, fontSize = 14.sp, lineHeight = 22.sp, maxLines = if (expanded) Int.MAX_VALUE else 3,
            overflow = TextOverflow.Ellipsis, onTextLayout = { if (!expanded) overflows = it.hasVisualOverflow })
        if (expanded || overflows) TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) { Text(if (expanded) "收起" else "展开全文") }
    }
}

@Composable
fun FilesScreen(post: Post, save: (RemoteFile, Boolean) -> Unit) {
    val files = post.files.filterNot { it.isImage }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp)) {
        if (files.isEmpty()) item { StatusContent(false, "没有可下载的附件") }
        items(files, key = { it.path }) { file ->
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (file.isVideo) Icons.Outlined.VideoFile else Icons.Outlined.FolderZip, null, Modifier.padding(end = 12.dp))
                Text(file.name.ifBlank { file.path.substringAfterLast('/') }, fontSize = 14.sp, modifier = Modifier.weight(1f), overflow = TextOverflow.Ellipsis, maxLines = 3)
                TextButton(onClick = { save(file, true) }) { Text("下载") }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}
