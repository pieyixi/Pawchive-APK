/* Pupil, Hitomi.la viewer for Android. Copyright (C) 2026 tom5079.
 * GPL-3.0-or-later; see third_party/hitomi/COPYING.
 * Pawchive adapter around the original reader's chrome, lazy-list/snapping,
 * immediate page turns, neighbouring prefetch, page grid and options components. */
package pw.pawchive.media.reader

import android.content.res.Configuration
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.gestures.snapping.*
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.*
import androidx.compose.ui.unit.*
import androidx.core.view.*
import androidx.lifecycle.*
import androidx.lifecycle.compose.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.*
import pw.pawchive.data.*
import kotlin.math.roundToInt

private val CHROME_TAP_REGION = 1 / 3f..2 / 3f
private const val CHROME_LINGER = 5000L
private val SpreadSpacing = 8.dp
private val SettingsSaver = Saver<ReaderSettings, String>(save = { wireJson.encodeToString(it) }, restore = { wireJson.decodeFromString(it) })

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(title: String, files: List<RemoteFile>, initialPage: Int,
    initialSettings: ReaderSettings, onSettingsChange: (ReaderSettings) -> Unit,
    onProgress: (Int) -> Unit, onBack: () -> Unit, onSave: (RemoteFile, Boolean) -> Unit) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val view = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val configuration = LocalConfiguration.current
    val scope = rememberCoroutineScope()
    var options by rememberSaveable(stateSaver = SettingsSaver) { mutableStateOf(initialSettings) }
    val source = remember(files) { PreviewReaderSource(context, files) }
    DisposableEffect(source) { onDispose { source.close() } }
    val cached by source.cachedPages.collectAsStateWithLifecycle()
    var currentPage by rememberSaveable { mutableIntStateOf(initialPage.coerceIn(0, files.lastIndex) + 1) }
    var originalPage by rememberSaveable { mutableIntStateOf(-1) }
    val multipage = originalPage < 0 && when (options.readerLayout) {
        ReaderLayout.SINGLE -> false
        ReaderLayout.DOUBLE -> true
        ReaderLayout.AUTO -> configuration.orientation == Configuration.ORIENTATION_LANDSCAPE || options.multipagePortrait && configuration.screenWidthDp >= 600
    }
    val vertical = options.readerOrientation == ReaderOrientation.VERTICAL
    val reverse = options.readerReverse
    val reversed = !vertical && reverse
    val spreadRtl = when (options.multipageOrder) { MultipageOrder.LTR -> false; MultipageOrder.RTL -> true; MultipageOrder.AUTO -> reverse }
    val contentScale = if (multipage && options.multipageFit == MultipageFit.FILL) ContentScale.Crop else ContentScale.Fit
    val spreads = remember(source, cached, multipage, options.multipageCover) { buildSpreads(files.indices.map(source::info), multipage, options.multipageCover) }
    fun spreadOf(page: Int) = spreads.indexOfFirst { page - 1 in it }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = spreadOf(currentPage).coerceAtLeast(0), prefetchStrategy = remember { NeighbourPrefetchStrategy() })
    var chromeVisible by rememberSaveable { mutableStateOf(true) }
    var chromeAwaitsUse by rememberSaveable { mutableStateOf(true) }
    fun keepChrome() { chromeAwaitsUse = false }
    val insetsController = remember(activity, view) { activity?.window?.let { WindowCompat.getInsetsController(it, view).apply { systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE } } }

    LaunchedEffect(chromeVisible, chromeAwaitsUse) {
        if (!chromeVisible || !chromeAwaitsUse) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { delay(CHROME_LINGER); chromeVisible = false }
    }
    LifecycleResumeEffect(insetsController, chromeVisible) {
        insetsController?.run { if (chromeVisible) show(WindowInsetsCompat.Type.systemBars()) else hide(WindowInsetsCompat.Type.systemBars()) }
        onPauseOrDispose { }
    }
    DisposableEffect(insetsController) { onDispose { insetsController?.show(WindowInsetsCompat.Type.systemBars()) } }

    // Kept from Hitomi: a second fast turn starts from the pending target, not an old page.
    var turnTarget by remember { mutableStateOf<Int?>(null) }
    val dragging by listState.interactionSource.collectIsDraggedAsState()
    fun showSpread(spread: Int) {
        turnTarget = spread
        scope.launch {
            var arrived = false
            try {
                // A tap can stop a fling: its UP still owns UserInput priority until
                // dispatch completes. Start our Default-priority scroll after that owner.
                yield()
                snapshotFlow { dragging }.first { !it }
                if (turnTarget != spread) return@launch
                if (options.instantFlip) listState.scrollToItem(spread) else listState.animateScrollToItem(spread)
                arrived = true
            } finally {
                // A second DOWN cancels LazyList animation before its UP invokes turn().
                // Preserve the intended target across that tap; a real drag clears it above.
                if (arrived && turnTarget == spread) turnTarget = null
            }
        }
    }
    fun turn(delta: Int) { if (spreads.isNotEmpty()) showSpread(((turnTarget ?: listState.firstVisibleItemIndex) + delta).coerceIn(0, spreads.lastIndex)) }
    fun jumpToPage(page: Int) {
        val spread = spreadOf(page)
        if (spread < 0 || spread == listState.firstVisibleItemIndex) return
        turnTarget = null; scope.launch { listState.scrollToItem(spread) }
    }
    fun onTap(x: Float) {
        when {
            chromeVisible -> { turnTarget = null; chromeVisible = false }
            x in CHROME_TAP_REGION -> { turnTarget = null; chromeVisible = true; keepChrome() }
            else -> turn(if (options.tapSides && (x < .5f) != reversed) -1 else 1)
        }
    }
    val currentOnTap by rememberUpdatedState(::onTap)
    val reportProgress by rememberUpdatedState(onProgress)
    LaunchedEffect(spreads, vertical) {
        if (spreads.isEmpty()) return@LaunchedEffect
        val spread = spreadOf(currentPage)
        if (spread >= 0) listState.scrollToItem(spread)
        snapshotFlow { listState.shownSpreads() }.filterNotNull().distinctUntilChanged().collect { (first, last) ->
            if (last < spreads.size) {
                currentPage = spreads[first].first() + 1
                source.pin((first - 1..last + 1).filter { it in spreads.indices }.flatMap { spreads[it] })
                reportProgress(currentPage - 1)
            }
        }
    }
    var optionsOpen by remember { mutableStateOf(false) }
    var pageGridOpen by remember { mutableStateOf(false) }
    var targetPage by rememberSaveable { mutableIntStateOf(0) }
    val actedPage = targetPage.takeIf { it > 0 } ?: currentPage
    var timerDialogShown by remember { mutableStateOf(false) }
    var timerSeconds by rememberSaveable { mutableIntStateOf(5) }
    var timerRunning by rememberSaveable { mutableStateOf(false) }
    VolumePageTurnHandler(if (options.volumePageTurn && !optionsOpen && !pageGridOpen && !timerDialogShown) { { turn(it) } } else null)

    if (timerRunning) {
        DisposableEffect(view) { view.keepScreenOn = true; onDispose { view.keepScreenOn = false } }
        LaunchedEffect(timerSeconds) {
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    delay(timerSeconds * 1000L)
                    if (!listState.canScrollForward) { timerRunning = false; return@repeatOnLifecycle }
                    if (!optionsOpen && !pageGridOpen && !timerDialogShown && !dragging) turn(1)
                }
            }
        }
    }
    val downloadedPages = remember(cached, files.size) { List(files.size) { it in cached } }
    Scaffold(bottomBar = {
        AnimatedVisibility(chromeVisible, enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
            ReaderBottomBar(currentPage, files.size, reversed, downloadedPages,
                onPageSelected = ::jumpToPage, onInteraction = ::keepChrome,
                onPageGridClick = { pageGridOpen = true }, onOptionsClick = { targetPage = 0; optionsOpen = true })
        }
    }, containerColor = MaterialTheme.colorScheme.background, contentWindowInsets = WindowInsets.systemBars) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().testTag("reader_pages").pointerInput(listState) {
            // Observe without consuming: pans, drags and pinches abandon a pending tap turn.
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.changes.size > 1 || (event.changes.first().position - down.position).getDistance() > viewConfiguration.touchSlop) { turnTarget = null; break }
                    if (event.changes.none { it.pressed }) break
                }
            }
        }) {
            val viewport = IntSize(constraints.maxWidth, constraints.maxHeight)
            val content: LazyListScope.() -> Unit = {
                items(spreads, key = { it.first() }) { spread ->
                    val pageInfo = spread.map(source::info)
                    val width = pageInfo.sumOf { it.width }; val height = pageInfo.maxOf { it.height }
                    val ratio = if (width > 0 && height > 0) width.toFloat() / height else if (spread.size > 1) 1.4f else .7f
                    ReaderSpread(source, if (spreadRtl) spread.asReversed() else spread, originalPage,
                        spread.joinToString("-") { (it + 1).toString() }, spreadSize(viewport, ratio, options.readerPadding, vertical), contentScale,
                        onLongPress = { page -> turnTarget = null; keepChrome(); targetPage = page + 1; optionsOpen = true },
                        modifier = when {
                            options.readerPadding -> Modifier.fillParentMaxSize()
                            vertical -> Modifier.fillMaxWidth().aspectRatio(ratio)
                            else -> Modifier.widthIn(max = maxWidth).fillParentMaxHeight().aspectRatio(ratio, matchHeightConstraintsFirst = true)
                        })
                }
            }
            val fling = if (options.readerSnap) rememberPageFlingBehavior(listState) else ScrollableDefaults.flingBehavior()
            // Hitomi's immediate-tap detector observes the stationary viewport. Attaching
            // it to a moving page makes a stationary second tap look like a drag.
            val readerInput = Modifier.fillMaxSize().pointerInput(Unit) { turnOnTap { currentOnTap(it.x / size.width.coerceAtLeast(1)) } }
            if (vertical) LazyColumn(state = listState, modifier = readerInput, reverseLayout = reverse, verticalArrangement = Arrangement.spacedBy(SpreadSpacing), flingBehavior = fling, content = content)
            else LazyRow(state = listState, modifier = readerInput, reverseLayout = reverse, horizontalArrangement = Arrangement.spacedBy(SpreadSpacing), flingBehavior = fling, content = content)
            Column(Modifier.fillMaxSize().padding(bottom = padding.calculateBottomPadding())) {
                AnimatedVisibility(chromeVisible, enter = slideInVertically { -it } + fadeIn(), exit = slideOutVertically { -it } + fadeOut()) { ReaderTopBar(title, onBack) }
            }
        }
    }
    if (pageGridOpen) ReaderPageGridSheet(source, files.size, currentPage, downloadedPages, onPageSelected = { pageGridOpen = false; jumpToPage(it) }, onDismiss = { pageGridOpen = false })
    if (optionsOpen) {
        val quickActions = listOf(
            ReaderQuickAction("保存", { rememberVectorPainter(Icons.Default.Download) }) { optionsOpen = false; onSave(files[actedPage - 1], false) },
            ReaderQuickAction("保存到…", { rememberVectorPainter(Icons.Default.FolderOpen) }) { optionsOpen = false; onSave(files[actedPage - 1], true) },
            ReaderQuickAction(if (originalPage == actedPage - 1) "返回预览" else "查看原图", { rememberVectorPainter(Icons.Default.ZoomIn) }) {
                originalPage = if (originalPage == actedPage - 1) -1 else actedPage - 1; optionsOpen = false
            })
        val actions = listOf(ReaderAction(if (timerRunning) "停止自动翻页" else "定时翻页", { rememberVectorPainter(Icons.Default.Timer) }) { if (timerRunning) timerRunning = false else timerDialogShown = true })
        ReaderOptionsSheet(quickActions, actions, optionsState = options,
            onOptionsChange = { transform -> options = transform(options); onSettingsChange(options) }, onDismiss = { optionsOpen = false })
    }
    if (timerDialogShown) NumberPickerDialog("自动翻页间隔（秒）", 1..60, timerSeconds,
        onConfirm = { timerSeconds = it; timerRunning = true; timerDialogShown = false }, onDismiss = { timerDialogShown = false })
}

/** Turns at most one page per fling, the way a pager does. */
@Composable
private fun rememberPageFlingBehavior(state: LazyListState) = rememberSnapFlingBehavior(
    remember(state) {
        val provider = SnapLayoutInfoProvider(state, SnapPosition.Start)

        object : SnapLayoutInfoProvider by provider {
            override fun calculateApproachOffset(velocity: Float, decayOffset: Float) = 0f
        }
    }
)

/**
 * Keeps the spread either side of the one on screen composed, so a turn onto it
 * is already drawn rather than put together on arrival.
 */
@OptIn(ExperimentalFoundationApi::class)
private class NeighbourPrefetchStrategy : LazyListPrefetchStrategy {
    private val handles = mutableMapOf<Int, LazyLayoutPrefetchState.PrefetchHandle>()

    override fun LazyListPrefetchScope.onScroll(delta: Float, layoutInfo: LazyListLayoutInfo) {
        keepNeighbours(layoutInfo)
    }

    override fun LazyListPrefetchScope.onVisibleItemsUpdated(layoutInfo: LazyListLayoutInfo) {
        keepNeighbours(layoutInfo)
    }

    override fun NestedPrefetchScope.onNestedPrefetch(firstVisibleItemIndex: Int) {}

    private fun LazyListPrefetchScope.keepNeighbours(layoutInfo: LazyListLayoutInfo) {
        val visible = layoutInfo.visibleItemsInfo
        if (visible.isEmpty()) return

        val wanted = (visible.first().index - 1)..(visible.last().index + 1)

        handles.keys.filter { it !in wanted }.forEach { handles.remove(it)?.cancel() }

        for (index in wanted) {
            if (index < 0 || index >= layoutInfo.totalItemsCount) continue
            if (visible.any { it.index == index } || index in handles) continue

            handles[index] = schedulePrefetch(index)
        }
    }
}

/**
 * The box a spread is laid out in, which is the size its image is asked for at.
 * It mirrors the modifier the spread is given: the whole viewport when pages are
 * fitted to the screen, and otherwise the aspect the spread is drawn at.
 */
private fun spreadSize(viewport: IntSize, ratio: Float, padding: Boolean, vertical: Boolean) = when {
    padding -> viewport
    vertical -> IntSize(viewport.width, (viewport.width / ratio).roundToInt())
    else -> IntSize(
        (viewport.height * ratio).roundToInt().coerceAtMost(viewport.width),
        viewport.height,
    )
}

/** The first spread on screen and the last one fully shown. */
private fun LazyListState.shownSpreads(): Pair<Int, Int>? {
    val info: LazyListLayoutInfo = layoutInfo
    if (info.visibleItemsInfo.isEmpty()) return null

    val first = firstVisibleItemIndex
    val lastFullyShown = info.visibleItemsInfo
        .lastOrNull { it.offset + it.size <= info.viewportEndOffset }
        ?.index ?: first

    return first to maxOf(first, lastFullyShown)
}
