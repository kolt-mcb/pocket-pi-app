# Plan: Kitty graphics in the live mirror

## Goal

Render **real images** in the live `MirrorSurface` on the phone, transmitted via
the **kitty graphics protocol**, even though the host terminal can't display
images itself.

## Root constraint (verified, not assumed)

- The host runs in `TERM=xterm-256color` with no kitty/ghostty/wezterm/iterm2
  markers. pi-tui's `detectCapabilities()` therefore returns `images: null`.
- With `images: null`, pi-tui's `Image` component (`pi-tui/src/components/image.ts`,
  `render()`) emits a **text fallback** (`[image WxH]`), *not* an image escape.
- So the mirror buffer the app captures (`tui.render(width)` in the host's
  `renderMirrorNow`) contains **no image data** — there is nothing for an app-side
  parser to find. (This is also why images show as text on the host's own
  terminal.)
- The phone **can** render images: `TtyStreamParser` already parses kitty APC
  (`f=100` PNG, chunked `m=1`/`m=0`) and OSC 1337, with the 8 MiB cap and
  braille/text fallback (PROTOCOL.md).

**Conclusion:** the fix is host-side first — the host must *force* image output
for the phone-width mirror render, decoupled from its own image-incapable
terminal. Then the app must parse+render those images in the mirror (today
`MirrorSurface` uses text-only `parseAnsiLine`).

## Precedent to reuse

The host already emits **OSC 1337** images into the `stream` field for the chat
path (PROTOCOL.md: "Images embedded as OSC 1337"), despite `caps.images === null`.
**Step 0 of this plan is to find how it does that** (likely a scoped
`setCapabilities(...)` from `pi-tui/src/terminal-image.ts`, or a direct
`renderImage(...)` call) and reuse the exact mechanism for the mirror render —
switched to kitty (`images: "kitty"`) since that's the requested protocol.

---

## Phase 0: Investigate the host's existing image emission

- Find where the extension renders the `stream`/`ansiLines` with OSC 1337 images
  (grep `1337`, `renderImage`, `setCapabilities`, `Image(` in the host repo).
- Determine the exact lever: `setCapabilities({images: ...})` around a render, a
  direct `renderImage()` call, or a per-component option.
- Confirm `setCapabilities` / `setCellDimensions` / `renderImage` are importable
  from `@earendil-works/pi-tui` (they're exported from `terminal-image.ts`).

Deliverable: the precise, proven way to make pi-tui emit image escapes on this
host. Everything below depends on it.

## Phase 1: Host — emit kitty graphics in the phone-width mirror render

In `extension.ts` `renderMirrorNow()`, around the `tui.render(width)` call:

1. **Gate on capability:** only force images when a viewer can render them. Add
   `mirrorImages` to `client_hello` (app side) and to `clientCaps`; only force
   kitty output if at least one self-mirroring client advertises it. (Avoids
   shipping image bytes to clients that can't show them, and keeps backward
   compatibility.)
2. **Force kitty, scoped to the mirror render only:**
   ```
   const saved = getCapabilities();
   setCapabilities({ ...saved, images: "kitty" });
   try { lines = tui.render(width); ... } finally { setCapabilities(saved); }
   ```
   Node is single-threaded, so no desktop render interleaves between the flip and
   restore — *but* see the cache caveat below.
3. **Cache caveat (the main risk).** pi-tui's `Image` component caches its
   rendered lines per width (`cachedLines`/`cachedWidth`), and `getCapabilities()`
   is a global cache. Two failure modes to handle/verify:
   - A phone-width Image entry cached earlier (with `images:null` → text) won't
     re-render just because caps changed → still text. Need `image.invalidate()`
     before the forced render, or confirm the phone width was never rendered with
     null caps.
   - If the desktop and phone share the *same* width at some moment, the forced
     kitty output could land in the cache the **host terminal** reads → kitty
     escapes dumped to xterm-256color (garbage on the host). Must verify the
     phone width ≠ desktop width path keeps them separate, or scope harder.
4. **Cell dimensions:** kitty sizing uses `getCellDimensions()` (px per cell). The
   phone should report its cell pixel size in `client_hello`/`viewport` (it
   already measures the mono advance for `clientCols`); feed it via
   `setCellDimensions` so `c=`/`r=` are sane. Otherwise rely on the app's
   `CellSpec` sizing and accept approximate `c`/`r`.
5. **imageId hygiene:** kitty allocates image ids (`allocateImageId`). Mirror
   renders must not leak/clash ids with the host's own (unused) image state;
   confirm ids are per-render and bounded.

**Validation for Phase 1 (do this before any app work):** with the forced render
active, **watch the host's own terminal** for stray `\x1b_G…` garbage during
mirroring. If the host terminal stays clean and the mirror frame's lines contain
`\x1b_G` sequences, the scoping is correct.

## Phase 2: App — parse + render images in `MirrorSurface`

This touches the **just-fixed** mirror render path, so proceed carefully and keep
the diffing/scroll/tap fixes intact.

1. **`MirrorStreamParser`** (new, `tty/`): join `frame.lines` with `\n`, run
   through `TtyStreamParser.parse()`, map the resulting `TtyBlock`s back to a
   per-row render list (Text rows + Image blocks). Reuse the parser — single
   source of truth, already tested.
2. **`MirrorSurface`**: replace the per-line `parseAnsiLine` loop with the block
   list. Render `Text` rows as today; render `Image` blocks via a new
   `MirrorImageItem` (decode through the existing `decodeBase64Image` + bitmap
   cache; size via `CellSpec`).
3. **Preserve the recent fixes — these are the regression risks:**
   - **Tap→SGR-mouse mapping** (`MirrorScreen.kt`): currently maps `y / cellHeight`
     to a row. Image blocks occupy multiple rows, so the row math must account for
     image heights, or the SGR click row will drift below an image.
   - **Diff reconstruction:** `mirrorBuf` stays line-based (a kitty sequence is one
     line; its reserved rows are empty lines), so `applyMirrorDiff` is unaffected —
     but re-parse the *reconstructed* buffer, and don't re-parse the whole frame on
     every diff (memoize by buffer content / only when image lines change).
   - **Performance:** an image line is a large base64 string; keep it out of the
     hot text path and decode lazily (bitmap cache), exactly as `TtyScrollback`
     already does.

## Phase 3: App kitty-parser correctness vs pi-tui's actual output

pi-tui's `encodeKitty` chunks large images as `…,m=1;chunk` then `\x1b_Gm=0;chunk`
— **continuation chunks omit `i=<id>`**. But the app's `TtyStreamParser`
(PROTOCOL.md) currently correlates continuations by `i=<id>`; a chunk with no `i`
falls to `id=0` and won't reassemble with the opener.

- **Verify** against real pi-tui kitty output (single-chunk small images vs
  multi-chunk large ones).
- **Fix if needed:** accumulate `a`-less, `i`-less `m=1` continuation chunks onto
  the most-recently-opened transmission (kitty's actual semantics), not a separate
  `id=0` bucket. Add tests with genuine pi-tui chunk trains.
- Confirm `f=100` (PNG), `a=T`, and `c`/`r` handling match.

## Phase 4: Sizing, transport, fallback

- **Size cap:** 8 MiB base64 per image already enforced by the parser.
- **Compression interaction:** kitty payloads are base64 PNG (already compressed);
  the Phase-4 deflate layer barely shrinks them and just burns CPU — skip deflate
  when a frame is image-dominated (or accept it; harmless but wasteful).
- **Diffing payoff:** once an image line is sent in a keyframe, it's unchanged on
  later diffs → not resent. Good. But the initial keyframe with an image is large;
  rely on backpressure/adaptive cadence.
- **Fallback:** if decode fails or the app/host lacks the capability, keep today's
  behavior (the text `[image WxH]` placeholder renders as plain text).

## Phase 5: Testing & rollout

- **Host capability-leak check** (Phase 1 validation) — non-negotiable gate.
- **End-to-end:** a session that outputs an image (e.g. a tool emitting one, or a
  user image attachment surfaced in the TUI) → image visible in the mirror tap-to-
  zoom.
- **Backward compat:** old app (no `mirrorImages`) → host keeps text fallback; old
  host → app's `MirrorStreamParser` simply sees no image escapes → text as today.
- **No regression:** diffing, scroll-pause, peer mirrors, tap-to-click still work
  with the new `MirrorSurface` pipeline.
- Do it on a branch, behind the `mirrorImages` flag.

## Alternative: OSC 1337 instead of kitty (lower app-side risk)

The user asked for kitty, but note: OSC 1337 is a **single** sequence (no chunk
correlation), so it sidesteps Phase 3 entirely, and the host *already* emits OSC
1337 for the chat stream (Phase 0 precedent applies directly). If kitty chunking
proves troublesome, forcing `images: "iterm2"` for the mirror render is a strictly
simpler path to the same result with the same app-side parser work.

## Risk summary

- **Highest risk:** Phase 1 capability/cache scoping leaking kitty escapes to the
  host's own xterm terminal. Validate in isolation first.
- **Second:** Phase 2 regressing the just-fixed `MirrorSurface` (tap mapping, diff
  re-parse, perf).
- **Third:** Phase 3 chunk-reassembly mismatch silently dropping large images.

## Recommended sequence

Phase 0 (investigate) → Phase 1 (host, validate no leak) → Phase 3 (parser
correctness, cheap + testable) → Phase 2 (app render) → Phase 4/5 (polish + e2e).
Front-load the two uncertain host pieces (0/1) before committing to the app work.
