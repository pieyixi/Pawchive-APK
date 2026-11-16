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

@Composable
fun DetailScreen(vm: DetailViewModel, app: AppViewModel, onBack: () -> Unit, onAuthor: () -> Unit, onImage: (Int) -> Unit, onVideo: (Int) -> Unit, onFiles: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val follows by app.follows.collectAsStateWithLifecycle()
    val favorites by app.favorites.collectAsStateWithLifecycle()
    Page("作品详情", onBack) {
        val post = state.post
        if (post == null) { StatusContent(state.error == null, state.error, vm::load); return@Page }
        DetailMediaLayout(post, onImage, onVideo, Modifier.weight(1f)) {
            item(key = "title") { Text(post.title.ifBlank { "无标题" }, fontSize = 19.sp, lineHeight = 27.sp, fontWeight = FontWeight.SemiBold) }
            item(key = "author") { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.weight(1f).clickable(onClick = onAuthor), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(state.creator, Modifier.size(42.dp))
                    Column(Modifier.padding(start = 10.dp)) {
                        Text(state.creator?.name ?: post.user, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("${post.service} · ${displayTime(post.timestamp)}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                state.creator?.let { author -> DetailFollowButton(follows.any { it.key == author.key }, { app.follow(author) }) }
            } }
            if (post.content.isNotBlank()) item(key = "content") { FoldedContent(post.content) }
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
}

/** Shared collapsing header, sticky media tabs and independently saved lazy grids. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DetailMediaLayout(post: Post, onImage: (Int) -> Unit, onVideo: (Int) -> Unit,
    modifier: Modifier = Modifier, header: LazyListScope.() -> Unit = {}) {
    val pager = rememberPagerState(initialPage = if (post.images.isEmpty() && post.videos.isNotEmpty()) 1 else 0, pageCount = { 2 })
    val outer = rememberLazyListState()
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
                        PagerTabStrip(listOf("图片", "视频"), pager, tabWidth = 64.dp)
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
                                Column(Modifier.fillMaxWidth().clickable { if (page == 0) onImage(index) else onVideo(index) }, horizontalAlignment = Alignment.CenterHorizontally) {
                                    Box(Modifier.fillMaxWidth().aspectRatio(2f / 3).clip(RoundedCornerShape(3.dp))) {
                                        PreviewImage(file, Modifier.fillMaxSize(), "${if (page == 0) "图片" else "视频"} ${index + 1}")
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
