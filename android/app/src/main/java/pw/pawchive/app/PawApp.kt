package pw.pawchive.app

import android.Manifest
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.compose.ReportDrawnWhen
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.*
import androidx.compose.ui.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.background
import androidx.navigationevent.NavigationEvent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.*
import androidx.navigation3.ui.NavDisplay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.serialization.*
import pw.pawchive.data.*
import pw.pawchive.media.*

@Serializable data object HomeKey : NavKey
@Serializable data object FeedKey : NavKey
@Serializable data object MineKey : NavKey
@Serializable data class AuthorKey(val service: String, val user: String) : NavKey
@Serializable data class PostKey(val service: String, val user: String, val post: String) : NavKey
@Serializable data class VideoKey(val service: String, val user: String, val post: String, val page: Int) : NavKey
@Serializable data class FilesKey(val service: String, val user: String, val post: String) : NavKey
@Serializable data class CollectionKey(val kind: String) : NavKey
@Serializable data object TransfersKey : NavKey
@Serializable data object LoginKey : NavKey
@Serializable data object SettingsKey : NavKey

@Composable
fun PawApp(container: AppContainer, dark: Boolean, incoming: String? = null, consumeIncoming: () -> Unit = {}) {
    val repo = container.repository
    val app: AppViewModel = viewModel(factory = vmFactory { AppViewModel(repo) })
    val homeStack = rememberNavBackStack(HomeKey)
    val feedStack = rememberNavBackStack(FeedKey)
    val mineStack = rememberNavBackStack(MineKey)
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val stack = when (tab) { 1 -> feedStack; 2 -> mineStack; else -> homeStack }
    BackHandler(enabled = stack.size == 1 && tab != 0) { tab = 0 }
    LaunchedEffect(incoming) {
        incoming?.let { raw ->
            val uri = Uri.parse(raw)
            if (uri.scheme == "pawchive" && uri.host == "downloads") {
                tab = 2; if (mineStack.lastOrNull() != TransfersKey) mineStack.add(TransfersKey)
            } else if (uri.scheme == "https" && uri.host == "pawchive.pw") {
                val parts = uri.pathSegments
                if (parts.size >= 3 && parts[1] == "user") {
                    val key: NavKey = if (parts.size >= 5 && parts[3] == "post") PostKey(parts[0], parts[2], parts[4]) else AuthorKey(parts[0], parts[2])
                    tab = 0; if (homeStack.lastOrNull() != key) homeStack.add(key)
                }
            }
            consumeIncoming()
        }
    }
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    fun open(key: NavKey) { focus.clearFocus(); if (stack.lastOrNull() != key) stack.add(key) }
    fun back() { focus.clearFocus(); if (stack.size > 1) stack.removeLastOrNull() }
    var pendingFile by rememberSaveable { mutableStateOf<String?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    fun enqueue(file: RemoteFile, uri: Uri) {
        if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        app.action("save:${file.path}") { container.downloads.enqueue(file, uri); app.messages.emit("已加入下载任务") }
    }
    val createDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val file = pendingFile?.let { wireJson.decodeFromString<RemoteFile>(it) }; pendingFile = null
        if (uri != null && file != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            enqueue(file, uri)
        }
    }
    val save: (RemoteFile, Boolean) -> Unit = { file, choose ->
        val name = file.name.ifBlank { file.path.substringAfterLast('/') }.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(180)
        if (!choose && file.isImage && Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension) ?: "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Pawchive")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            app.action {
                val uri = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) } ?: error("无法创建图片")
                enqueue(file, uri)
            }
        } else {
            pendingFile = wireJson.encodeToString(file); createDocument.launch(name)
        }
    }
    val accessibility = LocalAccessibilityManager.current
    LaunchedEffect(app, accessibility) { app.messages.collectLatest { message ->
        snackbar.currentSnackbarData?.dismiss()
        val baseDuration = if (message == "已取消屏蔽") 1600L else 4000L
        val duration = accessibility?.calculateRecommendedTimeoutMillis(baseDuration, containsIcons = false, containsText = true, containsControls = false) ?: baseDuration
        try { kotlinx.coroutines.withTimeoutOrNull(duration) { snackbar.showSnackbar(message, duration = SnackbarDuration.Indefinite) } }
        finally { snackbar.currentSnackbarData?.dismiss() }
    } }
    val holder = rememberSaveableStateHolder()
    val viewModelDecorator = rememberViewModelStoreNavEntryDecorator<NavKey>()
    val latestOpen by rememberUpdatedState<(NavKey) -> Unit>({ open(it) })
    val latestBack by rememberUpdatedState<() -> Unit>({ back() })
    val latestSave by rememberUpdatedState(save)
    val latestDark by rememberUpdatedState(dark)
    val latestHome by rememberUpdatedState<() -> Unit>({ tab=0;homeStack.clear();homeStack.add(HomeKey) })
    val selectTab: (Int) -> Unit = remember(focus, app) { { next ->
        focus.clearFocus()
        if (next == tab && next < 2) app.scrollToTop.tryEmit(next) else tab = next
    } }
    // NavEntry equality includes the content lambda identity. Keep the provider stable so
    // recomposition cannot overwrite NavDisplay's pop decision with an unchanged stack.
    val provider = remember(app, container, context) {
        entryProvider<NavKey> {
            entry<HomeKey> { RootDestination(0, selectTab, container.navigationIcons) { HomeScreen(app, { latestOpen(AuthorKey(it.service, it.id)) }, { latestOpen(PostKey(it.service, it.user, it.id)) }, { selectTab(2) }) } }
            entry<FeedKey> { RootDestination(1, selectTab, container.navigationIcons) { FeedScreen(app, { latestOpen(AuthorKey(it.service, it.id)) }, { latestOpen(PostKey(it.service, it.user, it.id)) }) } }
            entry<MineKey> { RootDestination(2, selectTab, container.navigationIcons) { MineScreen(app, latestDark, { app.action { repo.setDark(!latestDark) } }, { latestOpen(it) }) } }
            entry<AuthorKey> { key ->
                val vm: AuthorViewModel = viewModel(factory = vmFactory { AuthorViewModel(repo, key.service, key.user) })
                AuthorScreen(vm, app, { latestBack() }, { latestOpen(PostKey(it.service, it.user, it.id)) })
            }
            entry<PostKey> { key ->
                val vm: DetailViewModel = viewModel(factory = vmFactory { DetailViewModel(repo, key.service, key.user, key.post) })
                DetailScreen(vm, app, { latestBack() }, { latestOpen(AuthorKey(key.service, key.user)) },
                    { context.startActivity(ReaderActivity.intent(context, key.service, key.user, key.post, it)) },
                    { latestOpen(VideoKey(key.service, key.user, key.post, it)) }, { latestOpen(FilesKey(key.service, key.user, key.post)) }, { latestHome() })
            }
            entry<VideoKey> { key -> MediaDestination(repo, key.service, key.user, key.post) { post -> post.videos.getOrNull(key.page)?.let { file -> ReferenceVideo(file,post.title,{latestBack()},{latestHome()},Modifier.fillMaxSize(),onLater={app.later(post)},onDownload={latestOpen(FilesKey(key.service,key.user,key.post))}) } } }
            entry<FilesKey> { key -> Page("下载文件", { latestBack() }) { MediaDestination(repo, key.service, key.user, key.post) { FilesScreen(it, latestSave) } } }
            entry<CollectionKey> { key -> CollectionScreen(key.kind, app, { latestBack() }, { latestOpen(AuthorKey(it.service, it.id)) }, { latestOpen(PostKey(it.service, it.user, it.id)) }) }
            entry<TransfersKey> { TransferScreen(app, container.downloads, { latestBack() }) }
            entry<LoginKey> { LoginScreen(app) { latestBack() } }
            entry<SettingsKey> { SettingsScreen(app, latestDark, { app.action { repo.setDark(!latestDark) } }, { latestBack() }) }
        }
    }
    Surface(Modifier.fillMaxSize()) {
        Scaffold(modifier = Modifier.windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout).union(WindowInsets.waterfall)), contentWindowInsets = WindowInsets(0, 0, 0, 0),
            snackbarHost = { SnackbarHost(snackbar, modifier = Modifier.imePadding()) }) { padding ->
            holder.SaveableStateProvider(tab) {
                NavDisplay(backStack = stack, modifier = Modifier.padding(padding).then(if (stack.size > 1) Modifier.imePadding() else Modifier).clipToBounds(), onBack = { back() },
                    transitionSpec = {
                        slideInHorizontally(PawMotion.page()) { it } togetherWith
                            slideOutHorizontally(PawMotion.page()) { -it / 4 }
                    },
                    popTransitionSpec = {
                        slideInHorizontally(PawMotion.page()) { -it / 4 } togetherWith
                            slideOutHorizontally(PawMotion.page()) { it }
                    },
                    predictivePopTransitionSpec = { edge ->
                        val sign = if (edge == NavigationEvent.EDGE_RIGHT) -1 else 1
                        slideInHorizontally(PawMotion.page()) { -sign * it / 4 } togetherWith
                            slideOutHorizontally(PawMotion.page()) { sign * it }
                    },
                    entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator(), viewModelDecorator),
                    entryProvider = provider)
            }
        }
    }
}

/** Each scene keeps its own geometry throughout push/pop; no global bottom-bar reflow. */
@Composable
private fun RootDestination(tab: Int, onSelect: (Int) -> Unit, icons: NavigationIconCache, content: @Composable () -> Unit) {
    val images by icons.images.collectAsStateWithLifecycle()
    val pixels = with(LocalDensity.current) { ReferenceMetrics.BottomIconViewport.roundToPx() }
    LaunchedEffect(pixels) { icons.request(pixels) }
    ReportDrawnWhen { images?.pixels == pixels }
    val density = LocalDensity.current
    val imeBottom = WindowInsets.ime.getBottom(density)
    val barPixels = with(density) { ReferenceMetrics.BottomBarHeight.roundToPx() } + 1
    // Resolve both insets during layout, on the same frame as the system IME animation.
    // The outer Scaffold consumes system bars only; this root exclusively owns its IME inset.
    val contentInsets = WindowInsets.ime.union(WindowInsets.navigationBars.add(WindowInsets(bottom = barPixels)))
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).testTag("root_destination_$tab")) {
        Box(Modifier.fillMaxSize().windowInsetsPadding(contentInsets)) { content() }
        if (imeBottom == 0) Box(Modifier.align(Alignment.BottomCenter)) { BottomTabs(tab, images?.takeIf { it.pixels == pixels }, onSelect) }
    }
}

@Composable
private fun BottomTabs(selected: Int, images: NavigationBitmaps?, onSelect: (Int) -> Unit) {
    Column {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = with(LocalDensity.current) { 1f.toDp() })
        Row(Modifier.fillMaxWidth().height(ReferenceMetrics.BottomBarHeight).testTag("bottom_navigation")) {
            listOf("首页", "动态", "我的").forEachIndexed { i, label ->
                val bitmap = images?.let { when(i) { 0 -> it.home; 1 -> it.feed; else -> if (selected == 2) it.selectedMine else it.mine } }
                val tint = if (selected == i) Pink else MaterialTheme.colorScheme.onSurfaceVariant
                Column(Modifier.weight(1f).fillMaxHeight().testTag("bottom_tab_$i").selectable(selected = selected == i, role = Role.Tab,
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null, onClick = { onSelect(i) }),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Top) {
                    Spacer(Modifier.height(2.5.dp))
                    Box(Modifier.size(ReferenceMetrics.BottomIconViewport).testTag("nav_icon_$i")) { bitmap?.let {
                        val painter = remember(it) { androidx.compose.ui.graphics.painter.BitmapPainter(it, filterQuality = androidx.compose.ui.graphics.FilterQuality.High) }
                        Icon(painter, null, Modifier.fillMaxSize(), tint = tint)
                    } }
                    Text(label, fontSize = 11.sp, lineHeight = 13.sp, color = tint, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

@Composable
private fun MediaDestination(repo: PawRepository, service: String, user: String, id: String, content: @Composable (Post) -> Unit) {
    var post by remember { mutableStateOf<Post?>(null) }; var error by remember { mutableStateOf<String?>(null) }; var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(service, user, id, attempt) {
        runCatching { repo.cachedPosts("post:$service:$user:$id").firstOrNull() ?: repo.post(service, user, id) }
            .onSuccess { post = it }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; error = it.message }
    }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        post?.let { content(it) } ?: StatusContent(post == null && error == null, error) { attempt++ }
    }
}
