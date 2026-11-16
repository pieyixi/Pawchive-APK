@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package pw.pawchive.app

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.pager.*
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.activity.compose.ReportDrawnWhen
import coil3.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.filter
import pw.pawchive.data.*
import pw.pawchive.media.PreviewImage

@Composable
fun HomeScreen(app: AppViewModel, onAuthor: (Creator) -> Unit, onPost: (Post) -> Unit, onMine: () -> Unit) {
    val blocked by app.blocked.collectAsStateWithLifecycle()
    val pager = rememberPagerState(initialPage = 1, pageCount = { 2 })
    val category = pager.currentPage
    val vm: HomeViewModel = viewModel(key = "home-page-$category", factory = vmFactory { HomeViewModel(app.repo, it, category) })
    val query by key(category) { vm.query.collectAsStateWithLifecycle() }
    val follows by app.follows.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(pager) {
        snapshotFlow { pager.isScrollInProgress }.filter { it }.collect { focus.clearFocus(); keyboard?.hide() }
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(48.dp).clickable(onClick = onMine), contentAlignment = Alignment.Center) { Avatar(null) }
            BasicTextField(value = query, onValueChange = vm::query, modifier = Modifier.weight(1f).heightIn(min = 48.dp), singleLine = true,
                textStyle = TextStyle(fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface), cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { focus.clearFocus(); vm.load(false) }),
                decorationBox = { input -> Box(Modifier.fillMaxWidth().heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 36.dp).border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(24.dp)).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Search, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Box(Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 8.dp)) {
                            if (query.isEmpty()) Text(if (category == 0) "搜索作者" else "搜索作品", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            input()
                        }
                        if (query.isNotEmpty()) IconButton(onClick = { vm.query("") }, modifier = Modifier.size(36.dp)) { Icon(Icons.Outlined.Close, "清空搜索", Modifier.size(18.dp)) }
                    }
                } })
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            PagerTabStrip(listOf("作者", "作品"), pager, beforeChange = { focus.clearFocus(); keyboard?.hide() })
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        HorizontalPager(state = pager, modifier = Modifier.fillMaxWidth().weight(1f).testTag("home_pager"), key = { it }) { page ->
            val pageVm: HomeViewModel = viewModel(key = "home-page-$page", factory = vmFactory { HomeViewModel(app.repo, it, page) })
            val state by pageVm.state.collectAsStateWithLifecycle()
            ReportDrawnWhen { page != pager.currentPage || !state.loading || state.creators.isNotEmpty() || state.posts.isNotEmpty() }
            val grid = rememberLazyGridState()
            val last by remember { derivedStateOf { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 } }
            val visibleCreators = state.creators.filter { author -> blocked.none { it.key == author.key } }
            val visiblePosts = state.posts.filter { post -> blocked.none { it.key == post.authorKey } }
            val count = if (page == 0) visibleCreators.size else visiblePosts.size
            val loadedCount = if (page == 0) state.creators.size else state.posts.size
            LaunchedEffect(last, count, loadedCount, state.loading, state.more, state.error) {
                if (loadedCount > 0 && last >= count - 6 && state.more && !state.loading && state.error == null) pageVm.load(true)
            }
            LazyVerticalGrid(columns = GridCells.Fixed(2), state = grid, modifier = Modifier.fillMaxSize().background(if (page == 1) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.background).testTag("home_grid_$page"), contentPadding = PaddingValues(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (page == 0) items(visibleCreators, key = { it.key }) { author ->
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable { onAuthor(author) }.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Avatar(author, Modifier.size(76.dp))
                        Text(author.name, fontSize = 14.sp, maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
                        Text(author.service, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 4.dp))
                        FollowButton(follows.any { it.key == author.key }, { app.follow(author) })
                    }
                } else items(visiblePosts, key = { it.key }) { WorkCard(it, app) { onPost(it) } }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    when {
                        state.loading -> StatusContent(true)
                        state.error != null -> StatusContent(false, state.error) { pageVm.load(false) }
                        count == 0 -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                            StatusContent(false, "没有匹配结果")
                            if (state.more && page == 1) TextButton(onClick = { pageVm.load(true) }) { Text("继续查找") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AuthorScreen(vm: AuthorViewModel, app: AppViewModel, onBack: () -> Unit, onPost: (Post) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val follows by app.follows.collectAsStateWithLifecycle()
    val grid = rememberLazyGridState()
    val last by remember { derivedStateOf { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 } }
    LaunchedEffect(last, state.posts.size) { if (state.posts.isNotEmpty() && last >= state.posts.size - 5 && !state.loading && state.error == null) vm.load(true) }
    Page(state.creator?.name ?: "作者", onBack) {
        LazyVerticalGrid(GridCells.Fixed(2), state = grid, contentPadding = PaddingValues(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 18.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        Avatar(state.creator, Modifier.size(64.dp))
                        Spacer(Modifier.weight(1f))
                        state.creator?.let { creator -> ProfileFollowButton(follows.any { it.key == creator.key }, { app.follow(creator) }) }
                    }
                    Text(state.creator?.name ?: vm.user, fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
                    Text(vm.service, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                }
            }
            items(state.posts, key = { it.key }) { post -> WorkCard(post, app, showMenu = false) { onPost(post) } }
            item(span = { GridItemSpan(maxLineSpan) }) { if (state.loading || state.error != null || state.posts.isEmpty()) StatusContent(state.loading, state.error) { vm.load(false) } }
        }
    }
}

@Composable
fun FeedScreen(app: AppViewModel, onAuthor: (Creator) -> Unit, onPost: (Post) -> Unit) {
    val rawPosts by app.feed.collectAsStateWithLifecycle()
    val blocked by app.blocked.collectAsStateWithLifecycle()
    val posts = remember(rawPosts, blocked) { rawPosts.filter { post -> blocked.none { it.key == post.authorKey } } }
    val follows by app.follows.collectAsStateWithLifecycle()
    val status by app.repo.feedStatus.collectAsStateWithLifecycle()
    val pulse by app.feedPulse.collectAsStateWithLifecycle()
    val list = rememberLazyListState()
    LaunchedEffect(pulse) { if (pulse > 0) list.animateScrollToItem(0) }
    val refresh = rememberPullToRefreshState()
    val ordered = remember(follows, posts, blocked) {
        val times = posts.groupBy { it.authorKey }.mapValues { (_, p) -> p.maxOf { it.timestamp } }
        follows.filter { author -> blocked.none { it.key == author.key } }.sortedByDescending { times[it.key] ?: it.timestamp }
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Text("动态", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
        if (ordered.isNotEmpty()) LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(ordered, key = { it.key }) { creator -> Column(Modifier.width(64.dp).clickable { onAuthor(creator) }.padding(bottom = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Avatar(creator, Modifier.size(50.dp)); Text(creator.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
            } }
        }
        PullToRefreshBox(isRefreshing = status.running, onRefresh = { app.refreshFeed() }, state = refresh, modifier = Modifier.weight(1f), indicator = {
            if (refresh.distanceFraction > 0 || status.running) Box(Modifier.align(Alignment.TopCenter).offset { IntOffset(0, ((if (status.running) 1f else refresh.distanceFraction).coerceAtMost(1.5f) * 60 - 48).dp.roundToPx()) }.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(26.dp), color = Pink, strokeWidth = 2.5.dp)
            }
        }) {
            LazyColumn(state = list, modifier = Modifier.fillMaxSize()) {
                item(key = "heading") { Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("最近更新", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    if (status.running && status.total > 0) Text("${status.checked}/${status.total}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } }
                if (status.error != null) item(key = "error") { StatusContent(false, status.error) { app.refreshFeed() } }
                if (posts.isEmpty()) item(key = "empty") { StatusContent(status.running, if (follows.isEmpty()) "关注作者后在这里查看动态" else "暂无动态") }
                items(posts, key = { it.key }) { post ->
                    val author = follows.find { it.key == post.authorKey }
                    Column(Modifier.fillMaxWidth().clickable { onPost(post) }.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Box(Modifier.size(48.dp).clickable { if (author != null) onAuthor(author) }, contentAlignment = Alignment.Center) { Avatar(author, Modifier.size(40.dp)) }
                            Column(Modifier.padding(start = 8.dp).weight(1f)) {
                                Text(author?.name ?: post.user, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                                Text(displayTime(post.timestamp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                            }
                        }
                        Text(post.title, maxLines = 3, overflow = TextOverflow.Ellipsis, fontSize = 15.sp, modifier = Modifier.padding(vertical = 10.dp))
                        post.cover?.let { cover ->
                            var ratio by rememberSaveable(cover.path) { mutableFloatStateOf(1.6f) }
                            AsyncImage(cover.thumbnail, "作品封面", Modifier.fillMaxWidth().aspectRatio(ratio).clip(RoundedCornerShape(5.dp)), contentScale = ContentScale.Fit,
                                onSuccess = { result -> val size = result.painter.intrinsicSize; if (size.width > 0 && size.height > 0) ratio = size.width / size.height })
                        }
                    }
                    HorizontalDivider(thickness = 7.dp, color = MaterialTheme.colorScheme.surfaceVariant)
                }
            }
        }
    }
}
