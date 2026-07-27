# Plan: Display Images in Terminal & Android App

## Problem

The pi-remote-control system has two rendering paths on the Android app:

1. **Live Mirror** (`MirrorSurface`) — renders the host's full terminal screen in real-time. Currently only renders ANSI text via `parseAnsiLine()` → `buildAnsiText()`. **Images are NOT rendered.**
2. **Chat Scrollback** (`TtyScrollback`) — renders chat messages through `TtyStreamParser` → `TtyBlock` pipeline. **Images ARE rendered** (OSC 1337 inline + Kitty graphics).

When the host renders a message containing images (user upload, tool output with `imgcat`/`icat`, assistant message with image blocks), the images:
- ✅ Show correctly in the **chat scrollback** (`TtyImageItem` renders the bitmap, tap opens fullscreen viewer)
- ❌ Show as **invisible ANSI garbage** in the **live mirror** (`parseAnsiLine` only handles SGR, drops image sequences but leaves cursor-movement artifacts)

Additionally, `pi-tui`'s terminal-image module uses Kitty graphics protocol on supporting terminals (Kitty, Ghostty, WezTerm) — but the extension renders images as **OSC 1337** (iTerm2-style) for the phone stream, which the parser already handles. The live mirror receives raw terminal buffer lines which may contain Kitty APC sequences from local tools.

## Current Architecture

### Host extension (`pi-remote-control/extension.ts`)
- Renders all messages via pi's interactive components → ANSI lines → `stream` field
- Images embedded as OSC 1337: `\x1b]1337;File=inline=1;size=N;mime=...;width=auto:base64\x1b\\`
- Oversize images (>8MiB base64) sent as structured `images[]` array
- `piRemote.sendFile()` delivers files via WebSocket → phone shows `FileDownloadDialog`

### Android app — TTY pipeline
- `TtyStreamParser`: parses ANSI+OSC → `TtyBlock.Text` | `TtyBlock.Image`
- `TtyBlock.Image`: base64 + mime + CellSpec dimensions
- `ScrollbackRenderer`: ChatMessage → TtyStreamParser → `ScrollbackEntry[]` → `LazyColumn`
- `TtyImageItem`: decode base64 → bitmap → Compose `Image` + fullscreen tap-to-zoom viewer

### Android app — Live mirror
- `MirrorSurface`: raw terminal buffer lines → `parseAnsiLine()` per-line → `buildAnsiText()` → `Text`
- **No image support** — `parseAnsiLine` only handles SGR (CSI … m)
- `TerminalView.kt`: `TerminalRenderView` for full-screen TUI frames — same text-only limitation

## Goals

1. **Render inline images in the live MirrorSurface** (primary gap)
2. **Render inline images in TerminalRenderView** (secondary — full-screen TUI mode)
3. **Handle both Kitty APC and OSC 1337** in the mirror (tools may emit either)
4. **No regression** — existing chat scrollback image rendering stays unchanged

---

## Implementation Plan

### Phase 1: Unified image-capable parser for mirror mode

**Problem:** `parseAnsiLine()` only handles SGR styling. The mirror needs to parse full ANSI+OSC streams (including multi-sequence images) from the terminal buffer.

**Solution:** Create a parser that processes the mirror frame's raw lines and produces a mix of `TtyBlock.Text` and `TtyBlock.Image`, similar to how `TtyStreamParser` works but optimized for the mirror's per-frame rendering.

#### 1.1. New file: `MirrorStreamParser.kt`

```kotlin
package com.piremote.tty

/**
 * Parse a mirror frame's raw ANSI lines into render blocks (Text + Image).
 * Reuses TtyStreamParser for the heavy lifting — joins lines with \n,
 * parses, then re-splits into per-line Text blocks for the mirror's
 * per-line layout.
 */
object MirrorStreamParser {
    fun parseFrame(lines: List<String>): List<MirrorRenderBlock>
}

sealed interface MirrorRenderBlock {
    data class Text(val line: String, val segments: List<Pair<String, AnsiStyle>>) : MirrorRenderBlock
    data class Image(val block: TtyBlock.Image, val rows: Int) : MirrorRenderBlock
}
```

**Design:** Join the frame lines → feed through `TtyStreamParser.parse()` → map the resulting `TtyBlock` list back into a sequence of mirror render blocks. Text blocks become one `MirrorRenderBlock.Text` per original line. Image blocks get a row count based on their CellSpec (or estimated from bitmap dimensions).

**Why reuse TtyStreamParser instead of a custom parser:**
- Single source of truth for Kitty/OSC 1337 parsing
- Already handles chunk reassembly, validation, size caps
- Already tested (15+ tests in `TtyStreamParserTest.kt`)
- ~15 lines of mapping code vs. reimplementing

#### 1.2. Modify `MirrorScreen.kt` — `MirrorSurface`

Change the per-line render loop from:
```kotlin
// Current: text only
frame.lines.forEachIndexed { idx, raw ->
    val styled = remember(raw) { buildAnsiText(parseAnsiLine(raw)) }
    Text(text = styled, ...)
}
```

To:
```kotlin
// New: unified render blocks
val blocks = remember(frame.lines.joinToString("\n")) {
    MirrorStreamParser.parseFrame(frame.lines)
}
blocks.forEachIndexed { i, block ->
    when (block) {
        is MirrorRenderBlock.Text -> {
            val styled = remember(i, block.segments) { buildAnsiText(block.segments) }
            Text(text = styled, ...)
        }
        is MirrorRenderBlock.Image -> {
            MirrorImageItem(block.block, metrics)
        }
    }
}
```

#### 1.3. New Composable: `MirrorImageItem` in `MirrorScreen.kt`

Similar to `TtyImageItem` from `TtyScrollback.kt` but adapted for the mirror's inline layout:

```kotlin
@Composable
private fun MirrorImageItem(
    image: TtyBlock.Image,
    metrics: TtyMetrics,
) {
    val bitmap = remember(image.base64) { decodeBase64Image(image.base64) }
    bitmap?.let { bm ->
        val width = resolveCellSpec(image.widthSpec, metrics, bm.width)
        val aspect = bm.height.toFloat() / bm.width.toFloat()
        Image(
            bitmap = bm.asImageBitmap(),
            contentDescription = "inline image",
            contentScale = if (image.preserveAspect) ContentScale.Fit else ContentScale.FillBounds,
            modifier = Modifier
                .fillMaxWidth()
                .width(width)
                .height(width * aspect)
        )
    }
}
```

**File changes:**
- **New:** `app/src/main/java/com/piremote/tty/MirrorStreamParser.kt`
- **Modify:** `app/src/main/java/com/piremote/screens/MirrorScreen.kt` — `MirrorSurface` + `MirrorImageItem`

---

### Phase 2: TerminalRenderView image support

`TerminalRenderView` in `TerminalView.kt` renders full-screen TUI frames (DOOM, editors, etc.). Same problem — text-only `parseAnsiLine`.

#### 2.1. Modify `TerminalRenderView` in `TerminalView.kt`

Apply the same `MirrorStreamParser` approach:
- Parse frame's `ansiLines` into `MirrorRenderBlock[]`
- Render Text and Image blocks in the column
- Pass `onInput` through for Text tap targets (unchanged)

**File changes:**
- **Modify:** `app/src/main/java/com/piremote/screens/TerminalView.kt`

---

### Phase 3: Image caching & memory management

Currently each image decode is `remember(base64)` — if the same image appears in multiple messages (e.g. history replay), it decodes multiple times. Large images can OOM the bitmap cache.

#### 3.1. Shared LRU bitmap cache

```kotlin
object BitmapCache {
    private val cache = LruCache<String, Bitmap>(MAX_CACHE_SIZE_BYTES)
    fun getOrDecode(base64: String, mimeType: String): Bitmap?
}
```

Replace `remember(block.base64) { decodeBase64Image(block.base64) }` with `BitmapCache.getOrDecode()`.

**Max dimensions:** Already capped to 2048px longest axis per PROTOCOL.md. Enforce in the cache layer.

**File changes:**
- **New:** `app/src/main/java/com/piremote/tty/BitmapCache.kt`
- **Modify:** `TtyScrollback.kt`, `MirrorScreen.kt`, `TerminalView.kt` — use `BitmapCache`

---

### Phase 4: Fullscreen image viewer in mirror mode

Currently `TtyScrollback` has `ImageViewerDialog` (tap image → zoomable fullscreen). The mirror surface should offer the same.

#### 4.1. Add viewer state to `MirrorSurface`

```kotlin
var viewer by remember { mutableStateOf<TtyBlock.Image?>(null) }
// ...
Image(
    // ...
    modifier = Modifier.clickable { viewer = block.block }
)
viewer?.let { ImageViewerDialog(image = it, onDismiss = { viewer = null }) }
```

**File changes:**
- **Modify:** `app/src/main/java/com/piremote/screens/MirrorScreen.kt`

---

### Phase 5: Host extension — Kitty protocol for mirror buffer

Currently the host renders images as OSC 1337 in the `stream` field. But the **live mirror buffer** (what `MirrorSurface` sees) is the raw terminal screen capture from `pi-tui`. If the host uses `pi-tui`'s `renderImage()` which emits Kitty graphics protocol, the mirror buffer will contain Kitty APC sequences.

#### 5.1. Verify `MirrorStreamParser` handles both protocols

The `TtyStreamParser` already handles both:
- OSC 1337 (primary, what the extension uses)
- Kitty APC (what `pi-tui` emits to local terminals)

Since `MirrorStreamParser` delegates to `TtyStreamParser`, both are covered.

**No code changes needed** — verify with integration test.

#### 5.2. Extension: ensure mirror buffer includes images

When pi renders assistant messages with images, the host's `AssistantMessageComponent.render()` may include image escape sequences in the ANSI output. These flow into the mirror buffer lines.

**File changes:**
- **Verify (no change):** `pi-remote-control/extension.ts` — mirror buffer already captures full terminal output

---

### Phase 6: Tests

#### 6.1. New tests for `MirrorStreamParser`

```kotlin
// MirrorStreamParserTest.kt
@Test fun parsesOsc1337Image() { ... }
@Test fun parsesKittySingleChunk() { ... }
@Test fun parsesKittyMultiChunk() { ... }
@Test fun textLinesBecomeMirrorRenderBlockText() { ... }
@Test fun imageFollowedByText() { ... }
@Test fun mixedKittyAndOsc1337() { ... }
@Test fun emptyFrameReturnsEmpty() { ... }
@Test fun sgrStylingPreserved() { ... }
```

#### 6.2. Existing tests — no changes needed

The core `TtyStreamParserTest` tests cover the parser. The `MessageNormalizerTest` covers the chat pipeline.

**File changes:**
- **New:** `app/src/test/java/com/piremote/tty/MirrorStreamParserTest.kt`

---

## Summary of File Changes

| File | Change | Phase |
|------|--------|-------|
| `tty/MirrorStreamParser.kt` | **New** — unified parser for mirror frames | 1 |
| `tty/BitmapCache.kt` | **New** — LRU bitmap cache | 3 |
| `screens/MirrorScreen.kt` | Modified — `MirrorSurface` + `MirrorImageItem` + viewer | 1, 4 |
| `screens/TerminalView.kt` | Modified — `TerminalRenderView` image support | 2 |
| `screens/TtyScrollback.kt` | Modified — use `BitmapCache` | 3 |
| `test/tty/MirrorStreamParserTest.kt` | **New** — unit tests | 6 |

## What stays unchanged

- `TtyStreamParser.kt` — the core parser is correct and tested
- `TtyBlock.kt` — block model handles images well
- `TtyEscapes.kt` — escape builders (for tests)
- `MessageNormalizer.kt` — chat message → stream conversion
- `ScrollbackRenderer.kt` — chat scrollback rendering
- `FileDownloadDialog.kt` — file delivery from host
- `PiWebSocket.kt` — WebSocket protocol handling

## Non-goals

- **Sixel support** — not in scope (parser already drops DCS/Sixel)
- **Image upload from phone improvements** — current picker + data URI works
- **Animated GIF/WebP support** — Android Compose `Image` doesn't animate; static decode is fine
- **Progressive image loading** — images are embedded in the stream; no progressive download needed
- **Image compression** — host already caps at 8MiB; bitmap downscaling to 2048px is sufficient
