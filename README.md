# Pi Remote Control — Android app

Drive a [pi](https://github.com/earendil-works/pi) coding-agent session from
your phone. This is the companion app for the
[`pi-remote-control`](https://github.com/kolt-mcb/pi-remote-control) pi
extension: the extension mirrors pi's live terminal UI over a LAN WebSocket,
and this app renders it and sends your input back. No cloud, no accounts, no
telemetry — the phone talks straight to your machine over pinned TLS.

<p align="center">
  <img src="docs/screenshots/mirror-demo.gif" width="360"
       alt="Live pi session mirrored on a phone: streaming agent output, session tabs, terminal keyboard">
</p>

*A live pi session mirrored to the phone ([full-res video](docs/screenshots/mirror-demo.mp4)).*

<!-- more screenshots welcome in docs/screenshots/: inline image in the
     mirror, tablet layout, connect screen with QR scanner. -->

## Features

- **Live terminal mirror** — the phone shows pi's actual TUI, re-rendered
  server-side to fit your screen width: messages, menus, pickers, spinners,
  colors, everything. Frames arrive as row diffs (deflate-compressed), so it
  stays smooth on slow links.
- **Terminal keyboard** — a purpose-built keyboard row with Esc/Tab/arrows,
  Ctrl chords, and multiline input, on top of your normal keyboard with
  autocorrect intact. Typing `/` opens pi's own command menu in the mirror.
- **Multi-session** — see every pi running on the host, switch between them
  in tabs, spawn new sessions in a directory you pick, or resume a saved
  session from the browser.
- **Inline images** — images the agent shows (kitty graphics / OSC 1337)
  render right in the mirror, even when the host terminal can't display them.
- **File delivery** — the agent can push files to your phone
  (`send_file_to_phone` on the host side); they land in a download dialog.
- **Theme mirroring** — the app picks up pi's active theme palette so colors
  match your terminal.
- **Tablet layout** — side-by-side sessions and a folder picker on
  large screens.
- **Background notifications** — a "pi is ready" heads-up when a turn
  finishes while the app is backgrounded, plus an in-app updater that checks
  the GitHub releases feed.

## Setup

1. On the machine running pi:
   `pi install git:github.com/kolt-mcb/pi-remote-control`
2. Start `pi`. The extension prints a `wss://` URL and a QR code
   (re-show it anytime with `/remote-qr`).
3. Install this app (below), then scan the QR from the connect screen.

Scan the QR rather than typing the URL: it carries the auth token **and the
TLS certificate fingerprint** the app pins for the connection. Camera
permission is requested for the scanner, notification permission for the
foreground-service indicator and turn-finished alerts.

## Install

Side-load the APK from [Releases](../../releases). CI publishes a versioned,
release-signed APK on every push to `master`; the rolling **`latest`**
pre-release always points at the most recent build, and the in-app updater
checks it and offers newer builds.

## Build from source

```bash
git clone https://github.com/kolt-mcb/pi-remote-control-app
cd pi-remote-control-app
./gradlew :app:assembleDebug   # or :app:assembleRelease
```

Output: `app/build/outputs/apk/{debug,release}/`. A release build without
signing env vars produces an unsigned APK — fine for local use.

`versionCode` = git commit count + 100, `versionName` = short commit SHA; see
`app/build.gradle.kts`. The `+100` offset must stay in sync between
`VERSION_CODE_OFFSET` there and the matching `+ 100` in
`.github/workflows/build-apk.yml`.

### CI signing (maintainers)

The publish job needs four repo secrets: `RELEASE_KEYSTORE_BASE64` (base64 of
a `.keystore`/`.jks`), `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`,
`RELEASE_KEY_PASSWORD`. The keystore must be stable across builds — Android
only installs an update over a build signed with the same key. PR builds run
an unsigned `assembleDebug` smoke check and publish nothing.

## Protocol

The wire protocol (WS + JSON, mirror frames, escape sequences) is documented
in [docs/PROTOCOL.md](docs/PROTOCOL.md) and in the
[extension repo's README](https://github.com/kolt-mcb/pi-remote-control#protocol).

## License

MIT — see [LICENSE](LICENSE).
