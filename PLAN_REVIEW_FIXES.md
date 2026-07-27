# Plan: fix the 10 confirmed code-review findings

Two independent clusters, disjoint files → fixed in parallel.

## Cluster A — TTY keyboard regressions (`TtyKeyboard.kt` only)

1. **Backspace dead on empty buffer** (~line 266)
   Restore the sacrificial-baseline design on top of the new reconcile buffer: the
   capture field keeps a sacrificial prefix (pre-rewrite: 8 spaces) so backspace at
   logical-empty still produces an `onValueChange` → send DEL and re-pad the prefix.
   Reconcile diffs only the text after the prefix. Reset to the prefix (not `""`)
   after Enter and on every syncEpoch bump.

2. **Ctrl/Alt chord desyncs reconcile baseline** (~line 293)
   After sending chord bytes (Ctrl+W/U/A mutate pi's line unpredictably), treat it
   like the special-key row does: route through the epoch-bumping send
   (`sendExternal`) so the buffer resets and the mirror resyncs, instead of leaving
   `sent` stale.

3. **Chord dropped + modifier left armed on IME rewrite** (~line 291)
   The else/rewrite branch never calls `mods.clear()`. Restore the old contract:
   when a modifier is armed, apply the chord best-effort on any growth (as the
   pre-rewrite code did) and clear mods on **every** branch — an armed modifier must
   never survive a keystroke (a later `d` silently becoming Ctrl-D kills the shell).

4. **Multi-line IME commit truncated at first `\n`** (~line 284)
   `substringBefore('\n')` drops the rest. Reconcile the first line, send ENTER,
   then push the remaining text through `TtyKeys.encode` (which maps `\n` → CR),
   then reset.

5. **`KeyboardCapitalization.Sentences` capitalizes shell commands** (~line 313)
   Change to `KeyboardCapitalization.None`.

## Cluster B — tablet folder picker (`TabletLayout.kt` + `Screens.kt`)

Root cause for 6/8/9: `showFolderPicker` / `pickerBasePath` / `mkdirCurrentDir` are
declared twice — TabletLayout:71–73 and shadowed again in TabletSidebar:189–191 —
callbacks write one copy, the UI reads the other.

6. **Dialog never closes on select** — collapse to a single source of truth: hoist
   the picker state to TabletLayout, pass value + callbacks into TabletSidebar,
   delete the shadow trio. `onDirSelect` then closes the real dialog after
   `spawnPeer`.
7. **Dialog hosted inside a LazyColumn `item {}`** — never composed when scrolled
   off-screen. Render `FolderPickerDialog` unconditionally at the top level of the
   sidebar (outside the LazyColumn). Also removes the
   `mkdirCurrentDir = dirBasePath` side-effect-during-composition in `item {}`.
8. **mkdir error wedges the create button** — handle `!r.success`: surface
   `r.errorMessage` in the dialog and reset the `creating` flag (wire the declared
   but unused `onDirResetCreating`, or pass creating/error as hoisted state).
9. **Post-mkdir refresh targets `""`** — falls out of the state collapse: the
   collector reads the one real `mkdirCurrentDir`.
10. **Empty dir renders permanent "Loading…"** (`Screens.kt:1343`) — distinguish
    "no response yet" from "zero subdirectories" (nullable listing or loading
    flag); always render the `..` parent row once a listing has loaded; show a
    "no subfolders" placeholder for empty.

## Workflow

- **Fix**: 2 parallel agents (disjoint files, no worktree needed), pipelined so
  each cluster's verification starts as soon as its fixer finishes.
- **Verify**: one adversarial verifier per finding — reads the working tree,
  confirms the failure scenario is impossible now and no obvious new defect.
- **Build**: `./gradlew compileDebugKotlin` (reports env-blocked if no Android SDK).
- **Repair**: failed verdicts + compile errors feed a repair agent, re-verify;
  max 2 rounds.

No commits — changes land in the working tree for review.
