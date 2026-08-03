# Wire protocol

What the app speaks to the [`pi-remote-control`](https://github.com/kolt-mcb/pi-remote-control)
host extension. Two layers:

1. **Transport** — JSON messages over a WebSocket (`wss://` with a pinned
   self-signed cert; token auth in the URL). The primary channel is the
   **screen mirror**: the host renders pi's whole TUI at the phone's column
   width and streams it as ANSI lines.
2. **Encoding** — the escape sequences that may appear inside those ANSI
   lines (SGR styling, hyperlinks, inline images), parsed by
   `com.piremote.tty.*`.

The host side of every message below lives in the extension repo's
`extension.ts`; its README carries the full client→host / host→client tables.
This document describes what *this app* sends and expects.

## Connection

The QR the extension prints encodes
`wss://<host>:<port>/?token=<32 hex>&fp=<64 hex sha256>`. The app pins the
TLS cert by that fingerprint and appends the token; connections with a bad
token are closed with WS code 4001.

First message after connect is the capability handshake:

```json
{"type":"client_hello","mirrorOnly":true,"diff":true,"deflate":true,"mirrorImages":true}
```

- `mirrorOnly` — this app renders the screen mirror, not a chat scrollback,
  so the host skips the (large) history replay on connect.
- `diff` — accept `mirror_diff` row diffs instead of only full keyframes.
- `deflate` — accept binary WS messages that are zlib-deflated JSON (the host
  compresses anything over 512 B; the app inflates via `tty/Inflate.kt`).
- `mirrorImages` — force image escapes (kitty) into mirror frames even when
  the host terminal reports no image support.

Then the app sends `{"type":"get_sessions"}` and
`{"type":"viewport","cols":N}` (re-sent whenever the device width changes —
the host re-renders the mirror to fit).

## Screen mirror

Subscribe with `{"type":"mirror","on":true,"agentId":<optional peer id>}`.
The host replies with a keyframe and then a stream of frames/diffs at up to
~15 fps:

```json
{"type":"mirror_frame","agentId":"…","seq":123,"lines":["…ANSI…"],
 "cursor":{"row":r,"col":c},"width":W,"height":H}
```

```json
{"type":"mirror_diff","agentId":"…","seq":124,"lineCount":N,
 "rows":[{"i":17,"t":"…ANSI…"}],"cursor":{…}}
```

- `lines` are full ANSI strings, one per buffer row, already wrapped to the
  reported viewport width.
- A diff applies to the previous frame for the **same `agentId`**: replace
  row `i` with `t`, then truncate/extend to `lineCount` rows. `seq` gaps mean
  a dropped frame — the host resends a keyframe when it must resync (e.g.
  width change, subscribe, backpressure skip).
- The app keeps one buffer per agentId (capped at 100 000 rows) and renders
  only the newest frame.

Input goes back as raw bytes:
`{"type":"mirror_input","data":"…","agentId":<optional>}` — one keystroke or
paste chunk per message; the host injects it into pi's own input path. This
is how *everything* is driven: typing, arrow keys, Enter, and `/` opening
pi's command menu inside the mirror.

## Sessions, files, dialogs

- `{"type":"session_list","sessions":[…]}` — every pi on the host (host +
  peers); the app shows them as tabs. `prompt`, `slash_command`, `mirror`,
  and `mirror_input` all accept an agent id to target a specific one.
- `{"type":"spawn_peer","sessionPath"?,"cwd"?}` — start a new pi on the host,
  optionally resuming a saved session (list via `get_saved_sessions`) in a
  directory picked via `list_host_dirs`/`host_dirs`.
- `{"type":"file","name","mimeType","data","agentId"?}` — a file pushed by
  the host's `send_file_to_phone` tool; base64 in `data`, surfaced as a
  download dialog. No hard size cap (device memory dominates).
- `{"type":"extension_ui_request",…}` / `{"type":"render",…}` — host-driven
  dialogs, notifications, and ANSI-rendered menus; answered with
  `extension_ui_response` / `input`.
- `{"type":"theme_info","theme":{…}}` — pi's active theme palette; the app
  restyles itself to match.

## SGR styling (CSI ... m)

Supported codes:

| Codes | Meaning |
|---|---|
| `0` (or empty) | reset (does not close an open OSC 8 link) |
| `1` `2` `3` `4` | bold, dim, italic, underline |
| `30–37` / `40–47` | 16-color foreground / background |
| `90–97` / `100–107` | bright foreground / background |
| `38;5;N` / `48;5;N` | xterm 256-color |
| `38;2;R;G;B` / `48;2;R;G;B` | truecolor |
| `39` / `49` | default foreground / background |

All other CSI sequences (cursor movement, erase, scroll regions) are **parsed
and silently dropped** — mirror rows arrive pre-composed, so in-band cursor
movement has no meaning here.

## OSC 8 hyperlinks

```
ESC ] 8 ; params ; URI  (BEL | ESC \)   ...link text...   ESC ] 8 ; ;  (BEL | ESC \)
```

Link text gets a sub-line tap target that opens the URI in the phone browser.
`params` (e.g. `id=`) are accepted and ignored. SGR resets inside the link do
not close it; only `8;;` does.

## OSC 1337 inline images

```
ESC ] 1337 ; File = inline=1 ; size=N ; mime=image/png ; width=W ; height=H ;
            preserveAspectRatio=0|1 : <base64>  (BEL | ESC \)
```

- `inline=1` is **required**; sequences without it are dropped.
- `width` / `height` accept `N` (terminal cells), `Npx`, `N%` (of viewport),
  or `auto` (default — natural size capped to the viewport).
- `mime` is a piremote extension (iTerm2 uses `name=`); when absent the type
  is sniffed from the payload magic bytes. PNG / JPEG / GIF / WebP.
- `preserveAspectRatio=0` stretches to the requested box; default preserves.
- The newline conventionally emitted after the sequence is swallowed (it does
  not produce a blank line).

## kitty graphics (APC)

```
ESC _ G key=val,key=val ; <base64 payload> ESC \
```

Supported subset:

- `a=T` (or `t`): transmit + display. Other actions (placement `a=p`,
  delete `a=d`, animation) are dropped.
- `f=100` (PNG) only. Raw `f=24/32` and zlib `o=z` payloads are dropped.
- Chunking: `m=1` on every chunk except the last (`m=0`), correlated by
  `i=<id>` (continuation chunks may omit `a`/`f`). An unterminated chunk
  train is discarded at end of stream.
- `c=<cols>` / `r=<rows>` set the display size in cells.

## Terminators

The app **accepts** BEL (`0x07`) and ST (`ESC \`) for OSC; kitty APC requires
ST. Emit ST — it is the spec-correct form and what `TtyEscapes` produces.

## Limits & error handling

- Decoded bitmaps are downsampled to ≤ 2048 px on the longest axis.
- Non-image OSC payloads: ≤ 4 KiB.
- Malformed, unterminated, or oversized sequences are **dropped whole**;
  surrounding text is preserved; the parser never throws.
- ESC characters inside user-typed content are stripped before rendering, so
  user input cannot inject sequences.

## Legacy chat-stream fields

Older builds rendered a chat scrollback from per-message `stream` /
`streamExpanded` / `ansiLines[]` / `content` fields on the message events,
with `history` replaying the conversation on connect. The mirror replaced
that as the primary UI (`client_hello.mirrorOnly` opts out of the history
replay), but the fields still arrive on message events and are still parsed —
they're what the notification summaries and session previews read.
