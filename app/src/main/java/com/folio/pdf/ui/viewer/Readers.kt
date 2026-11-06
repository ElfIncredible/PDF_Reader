package com.folio.pdf.ui.viewer

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

const val MIN_ZOOM = 1f
const val MAX_ZOOM = 5f
private const val DOUBLE_TAP_ZOOM = 2.5f

/** Lets the screen jump around without knowing which reading mode is active. */
interface PageNavigator {
    /** @param yFraction where on the page to bring into view, 0 = top. */
    suspend fun goTo(page: Int, yFraction: Float = 0f)
}

/** Zoom level for continuous mode, shared with the zoom buttons in the bottom bar. */
@Stable
class ContinuousZoom {
    var value by mutableFloatStateOf(1f)
}

// ---- Continuous vertical scroll -------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VerticalReader(
    env: PageEnv,
    zoom: ContinuousZoom,
    initialPage: Int,
    onNavigator: (PageNavigator?) -> Unit,
    onPageChanged: (Int) -> Unit,
) {
    val density = LocalDensity.current
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialPage)
    val hScroll = rememberScrollState()

    // `zoom.value` follows the fingers; `renderZoom` trails it so sharp bitmaps are only
    // re-rendered once the pinch settles.
    var renderZoom by remember { mutableFloatStateOf(zoom.value) }
    LaunchedEffect(zoom.value) {
        delay(220)
        renderZoom = zoom.value
    }

    val navigator = remember(listState) {
        object : PageNavigator {
            override suspend fun goTo(page: Int, yFraction: Float) {
                listState.scrollToItem(page)
                if (yFraction > 0f) {
                    val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == page } ?: return
                    val viewport = listState.layoutInfo.viewportSize.height
                    listState.scrollBy(item.size * yFraction - viewport / 3f)
                }
            }
        }
    }
    DisposableEffect(navigator) {
        onNavigator(navigator)
        onDispose { onNavigator(null) }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.mostVisibleIndex() }.collect { onPageChanged(it) }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                // Two-finger pinch. Watching the Initial pass grabs the gesture before the
                // list turns it into a scroll; one-finger drags pass straight through.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.count { it.pressed } >= 2) {
                            val old = zoom.value
                            val new = (old * event.calculateZoom()).coerceIn(MIN_ZOOM, MAX_ZOOM)
                            if (new != old) {
                                val c = event.calculateCentroid(useCurrent = true)
                                val ratio = new / old
                                zoom.value = new
                                // Keep the point under the fingers roughly in place.
                                hScroll.dispatchRawDelta((hScroll.value + c.x) * (ratio - 1f))
                                listState.dispatchRawDelta(
                                    (listState.firstVisibleItemScrollOffset + c.y) * (ratio - 1f),
                                )
                            }
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
    ) {
        val viewportWidth = maxWidth
        val pagePadding = 8.dp
        val renderWidthPx = with(density) {
            ((viewportWidth - pagePadding * 2) * renderZoom).toPx().roundToInt()
        }
        // Ignore visibility so hiding the system bars doesn't make the pages jump.
        val bars = WindowInsets.systemBarsIgnoringVisibility.asPaddingValues()

        Box(
            Modifier
                .fillMaxSize()
                .horizontalScroll(hScroll, enabled = zoom.value > 1.01f),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.width(viewportWidth * zoom.value).fillMaxHeight(),
                contentPadding = PaddingValues(
                    top = bars.calculateTopPadding() + 64.dp,
                    bottom = bars.calculateBottomPadding() + 88.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp * zoom.value),
            ) {
                items(count = env.doc.pageCount, key = { it }) { index ->
                    PageView(
                        env = env,
                        index = index,
                        renderWidthPx = renderWidthPx,
                        onDoubleTap = {
                            zoom.value = if (zoom.value > 1.05f) 1f else DOUBLE_TAP_ZOOM
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = pagePadding * zoom.value),
                    )
                }
            }
        }
    }
}

/** The page whose area covers the vertical centre of the viewport. */
fun LazyListState.mostVisibleIndex(): Int {
    val info = layoutInfo
    val centre = (info.viewportStartOffset + info.viewportEndOffset) / 2
    return info.visibleItemsInfo.firstOrNull { centre in it.offset..(it.offset + it.size) }?.index
        ?: firstVisibleItemIndex
}

// ---- Page by page ---------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun PagedReader(
    env: PageEnv,
    initialPage: Int,
    onNavigator: (PageNavigator?) -> Unit,
    onPageChanged: (Int) -> Unit,
) {
    val density = LocalDensity.current
    val pagerState = rememberPagerState(initialPage = initialPage) { env.doc.pageCount }

    val navigator = remember(pagerState) {
        object : PageNavigator {
            override suspend fun goTo(page: Int, yFraction: Float) = pagerState.scrollToPage(page)
        }
    }
    DisposableEffect(navigator) {
        onNavigator(navigator)
        onDispose { onNavigator(null) }
    }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { onPageChanged(it) }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val bars = WindowInsets.systemBarsIgnoringVisibility.asPaddingValues()
        // Render at 2× screen width so moderate zoom stays crisp.
        val renderWidthPx = with(density) { (maxWidth * 2).toPx() }.roundToInt().coerceAtMost(3200)

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            beyondViewportPageCount = 1,
            key = { it },
        ) { index ->
            val zoom = remember { PageZoom() }
            // Zoom resets when you swipe away from a page.
            LaunchedEffect(pagerState.settledPage) {
                if (pagerState.settledPage != index) zoom.reset()
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(
                        top = bars.calculateTopPadding() + 8.dp,
                        bottom = bars.calculateBottomPadding() + 8.dp,
                        start = 8.dp,
                        end = 8.dp,
                    )
                    .zoomable(zoom),
                contentAlignment = Alignment.Center,
            ) {
                PageView(
                    env = env,
                    index = index,
                    renderWidthPx = renderWidthPx,
                    onDoubleTap = { zoom.toggle() },
                    fitWithin = true,
                )
            }
        }
    }
}
