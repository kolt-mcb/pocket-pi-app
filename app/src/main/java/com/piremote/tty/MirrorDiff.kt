package com.piremote.tty

/** Upper bound on a mirror buffer's line count, so a hostile/garbled `lineCount`
 *  can't make us pad billions of empty lines (OOM). A real terminal scrollback
 *  is nowhere near this. */
const val MIRROR_MAX_LINES = 100_000

/** How many trailing rows of the host's buffer the phone actually keeps.
 *  The host stays the source of truth for the rest; nobody scrolls back
 *  thousands of rows on a phone, and holding the full scrollback of a long
 *  conversation costs tens of MB of ANSI strings — enough GC pressure to show
 *  up as dropped frames. */
const val MIRROR_KEEP_LINES = 4_000

/** Slack above [MIRROR_KEEP_LINES] before a head trim actually runs. Trimming
 *  shifts every row index, which invalidates every composed row, so it must be
 *  amortised: trim once per [MIRROR_TRIM_SLACK] appended rows, not once per row. */
const val MIRROR_TRIM_SLACK = 1_000

/**
 * Apply a row-level mirror diff to [buf] in place: resize the buffer to
 * [lineCount] (truncating removed trailing rows, padding new ones with ""),
 * then overwrite each changed row from [rows] (index → new text). This mirrors
 * the host's diffRows/keyframe protocol: rows carries exactly the indices whose
 * text changed (including new trailing rows); the removed tail is conveyed by a
 * smaller lineCount. Out-of-range indices are ignored.
 *
 * [buf] holds only the *tail* of the host's buffer — [dropped] says how many
 * head rows were trimmed away locally, so host row `i` lives at `i - dropped`.
 * Rows below the retained window are ignored. Returns the new dropped count,
 * which the caller must carry into the next diff.
 *
 * Pass `keep = MIRROR_MAX_LINES` to disable trimming (the buffer then mirrors
 * the host row-for-row and the return value stays 0).
 */
fun applyMirrorDiff(
    buf: MutableList<String>,
    lineCount: Int,
    rows: List<Pair<Int, String>>,
    dropped: Int = 0,
    keep: Int = MIRROR_MAX_LINES,
    slack: Int = MIRROR_TRIM_SLACK,
): Int {
    val total = lineCount.coerceIn(0, MIRROR_MAX_LINES)
    var drop = dropped.coerceIn(0, total)
    // Rows we should be holding now that the host is `total` rows tall.
    val want = total - drop
    while (buf.size > want) buf.removeAt(buf.size - 1)
    while (buf.size < want) buf.add("")
    // Translate host indices into local ones; anything already trimmed is gone.
    for ((i, t) in rows) {
        val k = i - drop
        if (k >= 0 && k < buf.size) buf[k] = t
    }
    val cap = keep.coerceIn(1, MIRROR_MAX_LINES)
    if (buf.size > cap + slack.coerceAtLeast(0)) {
        val excess = buf.size - cap
        // One bulk rewrite rather than `excess` removeAt(0) shifts — on a
        // SnapshotStateList each mutation is also a state write.
        val kept = ArrayList<String>(cap)
        for (i in excess until buf.size) kept.add(buf[i])
        buf.clear()
        buf.addAll(kept)
        drop += excess
    }
    return drop
}
