package pw.pawchive.app

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.luminance
import coil3.compose.AsyncImage
import pw.pawchive.data.*
import pw.pawchive.media.PreviewImage
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Tab clicks and direct drags share a pager; the underline follows finger progress. */
@Composable
fun PagerTabStrip(labels: List<String>, pager: PagerState, tabWidth: Dp = 88.dp, beforeChange: () -> Unit = {}, onTabBounds: (Int, androidx.compose.ui.geometry.Rect) -> Unit = {_,_->}) {
    val scope = rememberCoroutineScope()
    Box(Modifier.width(tabWidth * labels.size).height(51.dp)) {
        Row {
            labels.forEachIndexed { page, label ->
                Tab(selected = pager.currentPage == page,
                    onClick = { beforeChange(); scope.launch { pager.animateScrollToPage(page) } },
                    modifier = Modifier.width(tabWidth).height(48.dp),
                    selectedContentColor = Pink, unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    text = { Text(label,modifier=Modifier.onGloballyPositioned {onTabBounds(page,it.boundsInRoot())}) })
            }
        }
        Box(Modifier.align(Alignment.BottomStart).offset {
            IntOffset(((tabWidth.toPx() - 22.dp.toPx()) / 2 + tabWidth.toPx() * (pager.currentPage + pager.currentPageOffsetFraction)).roundToInt(), 0)
        }.width(22.dp).height(3.dp).background(Pink))
    }
}

@Composable
fun Page(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(end = 16.dp).testTag("page_title"))
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        content()
    }
}
@Composable
fun StatusContent(loading: Boolean, message: String? = null, retry: (() -> Unit)? = null) {
    Box(Modifier.fillMaxWidth().padding(28.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (loading) CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
            else {
                Text(message ?: "暂无内容", color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (retry != null) TextButton(onClick = retry) { Text("重试") }
            }
        }
    }
}
@Composable
fun Avatar(creator: Creator?, modifier: Modifier = Modifier) {
    Box(modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        if (creator == null) Image(painterResource(R.drawable.paw_logo), null, Modifier.fillMaxSize().padding(6.dp))
        else AsyncImage(creator.avatar, creator.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}
@Composable
fun FollowButton(selected: Boolean, onClick: () -> Unit) {
    ConfirmedFollow(selected, onClick, ReferenceMetrics.DetailFollowWidth, ReferenceMetrics.DetailFollowHeight, ReferenceMetrics.DetailFollowRadius, 12.sp, "compact_follow")
}
@Composable
fun DetailFollowButton(selected: Boolean, onClick: () -> Unit) {
    ConfirmedFollow(selected, onClick, ReferenceMetrics.DetailFollowWidth, ReferenceMetrics.DetailFollowHeight, ReferenceMetrics.DetailFollowRadius, 12.sp, "detail_follow")
}
@Composable
fun ProfileFollowButton(selected: Boolean, onClick: () -> Unit) {
    ConfirmedFollow(selected, onClick, ReferenceMetrics.ProfileFollowWidth, ReferenceMetrics.ProfileFollowHeight, ReferenceMetrics.ProfileFollowRadius, 14.sp, "profile_follow")
}

@Composable
private fun ConfirmedFollow(selected: Boolean, onClick: () -> Unit, width: Dp, height: Dp, radius: Dp, textSize: TextUnit, tag: String) {
    var confirm by remember { mutableStateOf(false) }
    ReferenceFollow(selected, { if (selected) confirm = true else onClick() }, width, height, radius, textSize, tag)
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("取消关注？") },
        text = { Text("取消后，该作者将从我的关注中移除。") },
        confirmButton = { TextButton(onClick = { confirm = false; onClick() }) { Text("取消关注") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("保留关注") } })
}
@Composable
private fun ReferenceFollow(selected: Boolean, onClick: () -> Unit, width: Dp, height: Dp, radius: Dp, textSize: TextUnit, tag: String) {
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val background = if (selected) { if (dark) MaterialTheme.colorScheme.surfaceVariant else androidx.compose.ui.graphics.Color(0xFFE4E5E9) } else Pink
    val color = if (selected) { if (dark) MaterialTheme.colorScheme.onSurfaceVariant else androidx.compose.ui.graphics.Color(0xFF969AA0) } else androidx.compose.ui.graphics.Color.White
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Box(Modifier.width(width).height(48.dp).clickable(interactionSource = interaction, indication = null, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick).testTag("${tag}_touch"), contentAlignment = Alignment.Center) {
        Row(Modifier.width(width).height(height).clip(RoundedCornerShape(radius)).background(background).indication(interaction, ripple(bounded = true)).testTag("${tag}_visual"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            if (selected) Icon(Icons.Outlined.Menu, null, Modifier.size(12.dp), tint = color)
            else Text("+", color = color, fontSize = textSize, maxLines = 1)
            Spacer(Modifier.width(4.dp))
            Text(if (selected) "已关注" else "关注", color = color, fontSize = textSize, maxLines = 1, softWrap = false)
        }
    }
}
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun WorkCard(post: Post, app: AppViewModel, showMenu: Boolean = true, onClick: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    if (menu) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val sheetScope = rememberCoroutineScope()
        val favorites by app.favorites.collectAsState()
        val later by app.later.collectAsState()
        val blocked by app.blocked.collectAsState()
        ModalBottomSheet(onDismissRequest = { menu = false }, sheetState = sheetState, shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    (if (later.any { it.key == post.key }) "移出稍后再看" else "稍后再看") to { app.later(post) },
                    (if (blocked.any { it.key == post.authorKey }) "取消屏蔽作者" else "屏蔽作者") to { app.block(post) },
                    (if (favorites.any { it.key == post.key }) "取消收藏" else "收藏") to { app.favorite(post) }
                ).forEach { (label, action) ->
                    TextButton(onClick = { sheetScope.launch { sheetState.hide(); menu = false; action() } }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.textButtonColors(containerColor = MaterialTheme.colorScheme.surface)) { Text(label, fontSize = 16.sp) }
                }
            }
        }
    }
    var author by remember(post.authorKey) { mutableStateOf(post.user) }
    LaunchedEffect(post.authorKey) {
        runCatching { app.repo.author(post.service, post.user) }.onSuccess { author = it.name.ifBlank { post.user } }
            .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surface).clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1.37f).clip(RoundedCornerShape(5.dp))) {
            if (post.cover == null) PawCoverPlaceholder() else PreviewImage(post.cover, Modifier.fillMaxSize(), scale = ContentScale.Crop)
        }
        // Reserve exactly two title lines; no text can bleed into the author/footer area.
        Text(post.title.ifBlank { "无标题" }, fontSize = 14.sp, lineHeight = 21.sp, minLines = 2, maxLines = 2,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth().padding(start = 5.dp, end = 5.dp, top = 8.dp))
        Row(Modifier.fillMaxWidth().padding(start = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.PersonOutline, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(author, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f).padding(start = 3.dp))
            if (showMenu) Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.MoreVert, "作品菜单", Modifier.size(17.dp)) }
            } else Spacer(Modifier.height(48.dp))
        }
    }
}
@Composable
private fun PawCoverPlaceholder() {
    Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xFF303034)), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            drawCircle(androidx.compose.ui.graphics.Color(0xFFCAA09B).copy(alpha = .08f), size.minDimension * .44f, androidx.compose.ui.geometry.Offset(size.width * .8f, size.height * .85f))
            drawCircle(androidx.compose.ui.graphics.Color(0xFFCAA09B).copy(alpha = .06f), size.minDimension * .32f, androidx.compose.ui.geometry.Offset(size.width * .15f, size.height * .12f))
        }
        Image(painterResource(R.drawable.paw_logo), "爪印封面", Modifier.fillMaxHeight(.48f).aspectRatio(1f))
    }
}
private val timeFormatter = java.time.format.DateTimeFormatter.ofPattern("MM/dd HH:mm").withZone(java.time.ZoneId.systemDefault())
fun displayTime(time: Long): String = if (time <= 0) "" else timeFormatter.format(java.time.Instant.ofEpochMilli(time))
