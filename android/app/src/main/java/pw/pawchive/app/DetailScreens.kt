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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys
import androidx.compose.foundation.text.contextmenu.modifier.filterTextContextMenuComponents
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
import androidx.activity.compose.BackHandler
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
    var origin by remember { mutableStateOf<Rect?>(null) }
    var videoTabBounds by remember { mutableStateOf(Rect.Zero) }
    var rootTop by remember { mutableFloatStateOf(0f) }
    var slotBounds by remember { mutableStateOf(Rect.Zero) }
    var closingOrigin by remember { mutableStateOf<Rect?>(null) }
    var closingProgress by remember { mutableFloatStateOf(1f) }
    val progress = remember { Animatable(if(expanded) 1f else 0f) }
    var selectedPoster by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    val moving by remember { derivedStateOf { video >= 0 && (progress.value < 1f || !expanded) } }
    fun collapsePreview() {
        if (!expanded) return
        closingProgress = progress.value.coerceAtLeast(.0001f)
        closingOrigin = interpolateRect(origin ?: slotBounds, slotBounds, progress.value)
        expanded = false
    }
    BackHandler(video >= 0) { collapsePreview() }
    LaunchedEffect(expanded, video) {
        if (video >= 0) {
            // Mount the destination once before moving the already decoded poster.
            withFrameNanos { }
            progress.animateTo(if (expanded) 1f else 0f, tween(260, easing = FastOutSlowInEasing))
            if (!expanded) { video = -1; origin = null; selectedPoster = null }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().onGloballyPositioned { rootTop = it.boundsInRoot().top }) {
        val post = state.post
        if (post == null) { StatusContent(state.error == null, state.error, vm::load); return@BoxWithConstraints }
        val playerHeight = (maxWidth * 9f / 16f).coerceAtLeast(200.dp)
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Box(Modifier.fillMaxWidth().height(playerHeight).testTag("detail_top_slot").onGloballyPositioned {slotBounds=it.boundsInRoot()}) {
                // Geometry never changes: gallery tabs keep the same Y throughout entry and exit.
                Column(Modifier.fillMaxSize().then(if (video >= 0 && !moving) Modifier.clearAndSetSemantics {} else Modifier)
                    .graphicsLayer { alpha = if (video >= 0 && !moving) 0f else 1f }
                    .verticalScroll(rememberScrollState())) {
                    DetailToolbar { if(video >= 0) collapsePreview() else onBack() }
                    Column(Modifier.padding(horizontal=12.dp,vertical=8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        DetailInfo(post, state.creator, follows.any { it.key == state.creator?.key }, onAuthor, { state.creator?.let(app::follow) })
                        if(state.error!=null) StatusContent(false,state.error,vm::load)
                    }
                }

            }
            DetailMediaLayout(post, onImage, onVideo, Modifier.weight(1f),
                onVideoOrigin = { index, bounds, bitmap ->
                    selectedPoster = bitmap; origin = bounds
                    video = index; expanded = true
                },
                onVideoTabBounds = { videoTabBounds = it })
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val selected = favorites.any { it.key == post.key }
                Button(onClick = { app.favorite(post) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp), shape = RoundedCornerShape(5.dp), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurface)) {
                    Icon(if (selected) Icons.Outlined.Star else Icons.Outlined.StarOutline, null, Modifier.size(22.dp)); Text(if (selected) "已收藏" else "收藏")
                }
                Button(onClick = onFiles, modifier = Modifier.weight(1f).heightIn(min = 48.dp), shape = RoundedCornerShape(5.dp), colors = ButtonDefaults.buttonColors(containerColor = Pink, contentColor = Color(0xFF26121A))) { Text("下载文件") }
            }
        }
        post.videos.getOrNull(video)?.let { file -> key(file.path) {
            // One live composition carries the poster AND controls from the first frame.
            // Uniform layer transforms avoid resizing the surface or handing off a snapshot.
            ReferenceVideo(file, post.title, { collapsePreview() }, onHome,
                Modifier.fillMaxWidth().height(playerHeight).graphicsLayer {
                    val p = progress.value.coerceIn(0f,1f)
                    val fraction = if(expanded) p else (p/closingProgress).coerceIn(0f,1f)
                    val destination = slotBounds.takeIf {it!=Rect.Zero} ?: Rect(0f,rootTop,size.width,rootTop+size.height)
                    val start = if(expanded) origin ?: destination else videoTabBounds.takeIf {it!=Rect.Zero}?.let {Rect(it.center,it.center)} ?: destination
                    val end = if(expanded) destination else closingOrigin ?: destination
                    val frame = interpolateRect(start,end,fraction)
                    val scale = (frame.width/size.width).coerceAtLeast(.0001f)
                    transformOrigin=androidx.compose.ui.graphics.TransformOrigin(0f,0f)
                    scaleX=scale;scaleY=scale
                    translationX=frame.left
                    translationY=frame.top-rootTop+(frame.height-size.height*scale)/2
                    alpha=if(expanded) 1f else fraction
                    shape=RoundedCornerShape(3.dp*(1-p));clip=true
                },
                mediaUri=videoUri(file),onLater={app.later(post)},onDownload=onFiles,
                sourceLink="$SITE/${post.service}/user/${post.user}/post/${post.id}",
                onPreviewBack={collapsePreview()},suspended=!expanded,previewBitmap=selectedPoster)
        } }

    }
}

private fun interpolateRect(start:Rect,end:Rect,fraction:Float)=Rect(
    start.left+(end.left-start.left)*fraction,start.top+(end.top-start.top)*fraction,
    start.right+(end.right-start.right)*fraction,start.bottom+(end.bottom-start.bottom)*fraction)

@Composable private fun DetailToolbar(onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回") }
        Text("作品详情", style = MaterialTheme.typography.titleMedium)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable private fun DetailInfo(post: Post, creator: Creator?, followed: Boolean, onAuthor: () -> Unit, onFollow: () -> Unit) {
    OneLineExpandableText(AnnotatedString(post.title.ifBlank { "无标题" }),"detail_title",19.sp,27.sp,FontWeight.SemiBold)
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
    OneLineExpandableText(annotated,"detail_content",14.sp,22.sp)

}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun OneLineExpandableText(text:AnnotatedString,tag:String,fontSize:TextUnit,lineHeight:TextUnit,weight:FontWeight?=null) {
    var overflows by remember(text) {mutableStateOf(false)}
    var expanded by rememberSaveable(text.hashCode()) {mutableStateOf(false)}
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
        Text(text,Modifier.weight(1f).testTag("${tag}_line"),fontSize=fontSize,lineHeight=lineHeight,fontWeight=weight,
            maxLines=1,overflow=TextOverflow.Ellipsis,onTextLayout={overflows=it.hasVisualOverflow})
        if(overflows) Text("展开",Modifier.padding(start=8.dp).heightIn(min=24.dp).testTag("${tag}_expand").clickable {expanded=true},
            color=Pink,fontSize=14.sp,lineHeight=24.sp)
    }
    if(expanded) ModalBottomSheet(onDismissRequest={expanded=false},sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),
        shape=RoundedCornerShape(topStart=12.dp,topEnd=12.dp)) {
        Column(Modifier.fillMaxWidth().heightIn(max=androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp*.75f)
            .verticalScroll(rememberScrollState()).padding(horizontal=16.dp).padding(bottom=32.dp)) {
            SelectionContainer(Modifier.filterTextContextMenuComponents {it.key==TextContextMenuKeys.CopyKey}) {
                Text(text,Modifier.testTag("${tag}_full"),fontSize=fontSize,lineHeight=lineHeight,fontWeight=weight)
            }
        }
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
