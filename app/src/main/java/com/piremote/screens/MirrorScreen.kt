package com.piremote.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.piremote.MirrorFrame
import com.piremote.theme.accent
import com.piremote.theme.bg
import com.piremote.theme.textMuted
import com.piremote.tty.AnsiStyle
import com.piremote.tty.MirrorItem
import com.piremote.tty.TtyBlock
import com.piremote.tty.parseMirrorLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

private const val ESC = "\u001b"

/** A clickable link inside one rendered line: [start, end) are 0-based column
 *  (== character) indices into the line's text. */
private data class LinkSpan(val start: Int, val end: Int, val url: String)

// Bare URLs in plain text (http/https/www) — pi doesn't mark these as OSC 8
// links, so we detect them client-side. Trailing sentence punctuation is
// trimmed off the match below.
private val URL_REGEX = Regex("""(https?://|www\.)\S+""", RegexOption.IGNORE_CASE)

/** Link ranges for a line: OSC 8 hyperlinks (carried on [AnsiStyle.link]) plus
 *  bare URLs found in the text. Bare URLs are skipped where they'd overlap an
 *  OSC 8 link. Columns are character offsets into the concatenated line text. */
private fun linkSpansForLine(segments: List<Pair<String, AnsiStyle>>): List<LinkSpan> {
    val spans = mutableListOf<LinkSpan>()
    var col = 0
    for ((text, st) in segments) {
        val link = st.link
        if (!link.isNullOrBlank()) spans.add(LinkSpan(col, col + text.length, link))
        col += text.length
    }
    val full = segments.joinToString("") { it.first }
    for (m in URL_REGEX.findAll(full)) {
        val trimmed = m.value.trimEnd('.', ',', ';', ':', ')', ']', '}', '>', '"', '\'', '!', '?')
        if (trimmed.isEmpty()) continue
        val start = m.range.first
        val end = start + trimmed.length
        // Don't double-mark text that's already an OSC 8 link.
        if (spans.any { start < it.end && end > it.start }) continue
        val href = if (trimmed.startsWith("www.", ignoreCase = true)) "https://$trimmed" else trimmed
        spans.add(LinkSpan(start, end, href))
    }
    return spans
}

/** A mirror row rendered for display: the parsed item plus, for text rows, the
 *  styled string handed to [Text]. [styled] is null for image rows. */
private class RenderedLine(val item: MirrorItem, val styled: AnnotatedString?)

/**
 * LRU cache of rendered rows, keyed by the row's raw ANSI text.
 *
 * Keyed by CONTENT, not by row index: the mirror buffer scrolls, so the same
 * text lands at a different index from one frame to the next. An index-keyed
 * `remember` misses on every one of those rows and re-runs the ANSI parse, the
 * bare-URL regex and the AnnotatedString build for the whole viewport, every
 * frame. Keyed by content it hits instead, and returns the identical
 * AnnotatedString instance so [Text] itself skips too.
 *
 * Keys are the very String instances the mirror buffer holds, so a cached entry
 * costs no extra string memory until that row scrolls out of the buffer.
 *
 * Touched only from composition (main thread), so it needs no synchronization.
 */
private object LineCache {
    /** Total LRU budget, in weight units (one ordinary text row = 1). */
    private const val MAX_WEIGHT = 1024
    /** A row longer than this is an inline-image escape sequence, not text. */
    private const val BIG_ROW_CHARS = 8 * 1024
    /**
     * What one image row costs against [MAX_WEIGHT]. Weighted rather than
     * measured in characters: an image row is megabytes, so a plain byte budget
     * would let a single one evict the entire cache and then thrash — miss,
     * re-parse the multi-MB sequence, evict everything, repeat, once per frame.
     * A fixed weight caps big rows at ~8 while leaving room for the text ones.
     */
    private const val BIG_ROW_WEIGHT = 128

    private var weight = 0
    private var accentKey: Color? = null
    private val map = LinkedHashMap<String, RenderedLine>(256, 0.75f, /* accessOrder = */ true)

    private fun weigh(raw: String) = if (raw.length > BIG_ROW_CHARS) BIG_ROW_WEIGHT else 1

    fun get(raw: String, accent: Color): RenderedLine {
        // Link styling bakes the accent colour in, so a palette switch (remote
        // theme_info) invalidates everything.
        if (accentKey != accent) { map.clear(); weight = 0; accentKey = accent }
        map[raw]?.let { return it }
        val rendered = render(raw, accent)
        map[raw] = rendered
        weight += weigh(raw)
        if (weight > MAX_WEIGHT) {
            // accessOrder = true, so the iterator starts at the least recently
            // used. Never evict the entry we just inserted (it sorts last).
            val it = map.entries.iterator()
            while (weight > MAX_WEIGHT && map.size > 1 && it.hasNext()) {
                weight -= weigh(it.next().key)
                it.remove()
            }
        }
        return rendered
    }

    private fun render(raw: String, accent: Color): RenderedLine {
        val item = parseMirrorLine(raw)
        if (item !is MirrorItem.Line) return RenderedLine(item, null)
        val segs = item.segments
        // Base ANSI styling, then overlay accent + underline on any link runs
        // (OSC 8 or detected URLs) so they look tappable.
        val base = buildAnsiText(segs)
        val links = linkSpansForLine(segs)
        val styled = if (links.isEmpty()) base else buildAnnotatedString {
            append(base)
            for (s in links) addStyle(
                SpanStyle(color = accent, textDecoration = TextDecoration.Underline),
                s.start, s.end.coerceAtMost(base.length),
            )
        }
        return RenderedLine(item, styled)
    }
}

/**
 * Two-finger pinch → terminal text size, without stealing one-finger scrolling
 * or taps. Events are watched on the Initial pass so a pinch claims them before
 * the LazyColumn reads the two fingers as a scroll; a single-pointer gesture is
 * never consumed, so scroll, tap-to-click and long-press selection are untouched.
 */
private fun Modifier.pinchTextSize(
    onZoom: (Float) -> Unit,
    onZoomEnd: () -> Unit,
) = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var zooming = false
        var slack = 1f
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.count { it.pressed } >= 2) {
                val zoom = event.calculateZoom()
                if (!zooming && zoom != 1f) {
                    // Touch slop, in the same terms Compose's own transform
                    // detector uses: ignore the incidental spread of a two-finger
                    // scroll until the pinch is clearly a pinch.
                    slack *= zoom
                    val span = event.calculateCentroidSize(useCurrent = false)
                    if (abs(slack - 1f) * span > viewConfiguration.touchSlop) zooming = true
                }
                if (zooming) {
                    if (zoom != 1f) onZoom(zoom)
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                }
            }
        } while (event.changes.any { it.pressed })
        if (zooming) onZoomEnd()
    }
}

/**
 * Live terminal render of a pi session (tty mirror). Drop-in content for the
 * session view: shows the host TUI's composed frames verbatim — widgets,
 * overlays, autocomplete, everything — and maps taps to SGR mouse events at
 * the tapped cell, so anything clickable on the desktop is tappable here.
 */
@Composable
fun MirrorSurface(
    frame: MirrorFrame,
    modifier: Modifier,
    onInput: (String) -> Unit,
    onRequestKeyboard: () -> Unit = {},
    fontSize: TextUnit = 12.sp,
    onZoom: (Float) -> Unit = {},
    onZoomEnd: () -> Unit = {},
) {
    val density = LocalDensity.current
    val uriHandler = LocalUriHandler.current
    // Measure the real glyph ADVANCE (width) so taps map to the host's columns.
    val measurer = rememberTextMeasurer()
    val cellWidth = remember(fontSize, density) {
        val r = measurer.measure(
            AnnotatedString("0".repeat(100)),
            TextStyle(fontFamily = piMono, fontSize = fontSize),
        )
        r.size.width / 100f
    }

    // One TextStyle for every terminal row: font padding OFF + centered line
    // height, so each single-line row measures to the SAME whole-pixel height.
    // With default font padding, rows are a fractional pixel tall and the list
    // can't tile them to the device pixel grid — leaving 1px seams between
    // background-filled lines (the faint horizontal lines). Uniform integer rows
    // remove that.
    val lineStyle = remember(fontSize) {
        TextStyle(
            fontFamily = piMono,
            fontSize = fontSize,
            lineHeight = fontSize,
            lineHeightStyle = LineHeightStyle(
                alignment = LineHeightStyle.Alignment.Center,
                trim = LineHeightStyle.Trim.None,
            ),
            platformStyle = PlatformTextStyle(includeFontPadding = false),
        )
    }
    // Whole-pixel row height measured with that exact style, pinned on every row
    // so adjacent rows abut precisely (no sub-pixel drift, no seams).
    val cellHeight = remember(lineStyle, density) {
        val r = measurer.measure(AnnotatedString("0"), lineStyle)
        with(density) { r.size.height.toDp() }
    }

    val listState = rememberLazyListState()
    // Start pinned to the latest content and follow the live bottom — but never
    // fight the user's finger. Only an ACTIVE user scroll turns follow off; on
    // initial layout (list at the top, last line not yet visible) we must NOT turn
    // it off, or the connect-time scroll-to-bottom gets cancelled and the view
    // opens at the top of the scrollback.
    var followBottom by remember { mutableStateOf(true) }
    // Tapping an inline image opens the fullscreen viewer (zoom + save), instead
    // of forwarding the tap as an SGR click to the host.
    var viewerImage by remember { mutableStateOf<TtyBlock.Image?>(null) }
    // The running gesture/scroll coroutines below outlive any single frame; go
    // through this state so they see the frame of the moment they act, not the
    // one captured when their effect launched.
    val currentFrame by rememberUpdatedState(frame)
    // snapshotFlow rather than keying an effect on isScrollInProgress: a key
    // read here in composition would recompose the whole surface on every
    // scroll start/stop.
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (scrolling) {
                followBottom = false
            } else {
                // Settled: resume follow only if we're at the bottom; otherwise leave
                // followBottom as-is (don't clobber the initial true).
                val info = listState.layoutInfo
                val last = info.visibleItemsInfo.lastOrNull()
                if (last == null || last.index >= info.totalItemsCount - 1) followBottom = true
            }
        }
    }
    // Stick to the bottom as new frames arrive (and on the first frame after
    // connect): scrollToItem(last) clamps to the end, leaving the latest line at
    // the viewport bottom. Reacts to frame.seq (not line content): a frame that
    // arrives mid-fling bails on isScrollInProgress, and seq keeps ticking so
    // the very next frame retries — a content key would drop the scroll until
    // the buffer happened to change again. One long-lived coroutine instead of
    // a LaunchedEffect relaunched per delta.
    LaunchedEffect(listState) {
        snapshotFlow { currentFrame.seq }.collect {
            val f = currentFrame
            if (followBottom && f.lines.isNotEmpty() && !listState.isScrollInProgress) {
                // Skip the scroll when the tail is already fully on screen —
                // scrollToItem forces an extra synchronous measure/layout pass,
                // and most frames only repaint rows that are already visible.
                // Compare against the frame's own row count, not layoutInfo's:
                // after new rows arrive totalItemsCount is a measure behind.
                val info = listState.layoutInfo
                val last = info.visibleItemsInfo.lastOrNull()
                val atBottom = last != null &&
                    last.index >= f.lines.size - 1 &&
                    last.offset + last.size <= info.viewportEndOffset
                if (!atBottom) listState.scrollToItem(f.lines.size - 1)
            }
        }
    }

    // Virtualized: only on-screen rows are composed, so scrolling stays fast no
    // matter how long the conversation/scrollback grows (the host sends the full
    // composed buffer, which can be thousands of lines).
    // Gestures: a quick tap clicks the terminal (SGR mouse); a held press falls
    // through (non-consuming) to SelectionContainer for text selection.
    SelectionContainer {
        LazyColumn(
            state = listState,
            modifier = modifier
                .fillMaxSize()
                .background(bg)
                // Declared before the tap handler so it sits outside it and sees
                // the Initial pass first.
                .pinchTextSize(onZoom, onZoomEnd)
                .pointerInput(frame.width, frame.height) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val up = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        waitForUpOrCancellation()
                    }
                    if (up != null) {
                        // Quick tap → SGR click. Find the visible row under the tap
                        // (robust to variable-height image rows), then map to the
                        // host's 1-based, viewport-relative coordinates.
                        // Hit-test against the frame as of the tap: this handler
                        // is keyed only on width/height, so the lambda-captured
                        // `frame` goes stale while the stream repaints.
                        val f = currentFrame
                        val hit = listState.layoutInfo.visibleItemsInfo.firstOrNull {
                            down.position.y >= it.offset && down.position.y < it.offset + it.size
                        }
                        val lineIdx = hit?.index ?: listState.firstVisibleItemIndex
                        // Through the cache: this runs on the main thread, and a
                        // tap on an inline-image row would otherwise re-parse a
                        // multi-megabyte escape sequence.
                        val tapped = f.lines.getOrNull(lineIdx)?.let { LineCache.get(it, accent).item }
                        if (tapped is MirrorItem.Img) {
                            // Tap on an inline image → open the viewer (zoom + save),
                            // don't forward it as a terminal click.
                            viewerImage = tapped.image
                        } else {
                            val col0 = (down.position.x / cellWidth).toInt() // 0-based
                            // A tap on a link opens it (OSC 8 or a bare URL) and
                            // does NOT send an SGR click or raise the keyboard.
                            val link = (tapped as? MirrorItem.Line)?.let { linkSpansForLine(it.segments) }
                                ?.firstOrNull { col0 >= it.start && col0 < it.end }
                            if (link != null) {
                                try { uriHandler.openUri(link.url) } catch (_: Exception) {}
                            } else {
                                val viewportTop = maxOf(0, f.lines.size - f.height)
                                val row = lineIdx - viewportTop + 1
                                val col = col0 + 1
                                if (row in 1..f.height && col in 1..f.width) {
                                    onInput("$ESC[<0;$col;${row}M") // press
                                    onInput("$ESC[<0;$col;${row}m") // release
                                }
                                // Tapping the terminal also raises the keyboard so you
                                // can type straight into pi's prompt (the capture field
                                // is invisible — the terminal itself is the input).
                                onRequestKeyboard()
                            }
                        }
                    }
                    // up == null → held past long-press: leave it for
                    // SelectionContainer to start a selection.
                }
            },
        ) {
            // Rendering is memoized by row CONTENT in LineCache, so a row that
            // merely scrolled to a new index is a cache hit; a row carrying an
            // inline image (kitty/OSC 1337) renders as an image, the rest is text.
            // The LazyColumn key stays the index, not the content: the buffer is
            // padded with blank rows (MirrorDiff), so content keys are not unique
            // and LazyColumn throws on duplicates. Row identity is positional here.
            // frame.lines is a snapshot list mutated in place, so reading it here
            // invalidates only the rows the last diff actually touched.
            items(frame.lines.size, key = { it }) { idx ->
                val raw = frame.lines[idx]
                val rendered = LineCache.get(raw, accent)
                when (val item = rendered.item) {
                    is MirrorItem.Line -> {
                        val segs = item.segments
                        Text(
                            text = rendered.styled ?: AnnotatedString(""),
                            style = lineStyle,
                            softWrap = false,
                            maxLines = 1,
                            overflow = TextOverflow.Clip,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(cellHeight)
                                // Paint each run's background ourselves, full row
                                // height and edge-to-edge, so background-filled rows
                                // tile with no gap. SpanStyle backgrounds only cover
                                // the tight text box, which left 1px seams between
                                // rows (the faint horizontal lines).
                                .drawBehind {
                                    var x = 0f
                                    for ((text, st) in segs) {
                                        val w = text.length * cellWidth
                                        if (st.bgR >= 0) {
                                            drawRect(
                                                color = Color(st.bgR / 255f, st.bgG / 255f, st.bgB / 255f),
                                                topLeft = Offset(x, 0f),
                                                size = Size(w, size.height),
                                            )
                                        }
                                        x += w
                                    }
                                },
                        )
                    }
                    is MirrorItem.Img -> MirrorImageItem(item.image)
                }
            }
        }
    }

    viewerImage?.let { img ->
        ImageViewerDialog(img) { viewerImage = null }
    }
}

/** Render an inline image from the mirror (kitty graphics / OSC 1337), scaled to
 *  the mirror width preserving aspect ratio. Falls back to "[image]" if decode
 *  fails (e.g. an oversized or corrupt payload). */
@Composable
private fun MirrorImageItem(image: TtyBlock.Image) {
    // Cache hit renders synchronously (no flicker on scroll-back); a miss
    // decodes off-main so a multi-MB payload doesn't stall composition.
    val bitmapState by produceState(initialValue = peekDecodedImage(image.base64), image.base64) {
        if (value == null) value = withContext(Dispatchers.Default) { decodeBase64Image(image.base64) }
    }
    val bitmap = bitmapState
    if (bitmap == null) {
        Text("[image]", fontFamily = piMono, fontSize = 12.sp, color = textMuted)
        return
    }
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = "inline image",
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(bitmap.width.toFloat() / bitmap.height.toFloat()),
    )
}
