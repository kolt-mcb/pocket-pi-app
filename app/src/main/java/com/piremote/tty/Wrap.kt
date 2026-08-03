package com.piremote.tty

/**
 * Viewport wrapping for parsed styled lines — the TTY equivalent of a terminal
 * wrapping at its column count. Compose renders scrollback Text with
 * softWrap=false, so this is the only thing standing between a long line and
 * the right edge of the screen.
 *
 * - Style is preserved across split points.
 * - Word-aware: prefers breaking at the last space within [WORD_LOOKBACK]
 *   cells of the limit; falls back to a hard break (code, URLs).
 * - Gutter-aware: a line starting with a "│" segment (a message-border
 *   gutter) repeats "│ " on continuation lines so message borders stay intact.
 */

private const val WORD_LOOKBACK = 24

fun wrapStyledLines(
    lines: List<List<Pair<String, AnsiStyle>>>,
    cols: Int,
): List<List<Pair<String, AnsiStyle>>> {
    if (cols <= 4) return lines
    if (lines.all { line -> line.sumOf { it.first.length } <= cols }) return lines

    val out = ArrayList<List<Pair<String, AnsiStyle>>>(lines.size + 4)
    lines.forEach { line -> wrapLine(line, cols, out) }
    return out
}

private fun wrapLine(
    line: List<Pair<String, AnsiStyle>>,
    cols: Int,
    out: MutableList<List<Pair<String, AnsiStyle>>>,
) {
    val total = line.sumOf { it.first.length }
    if (total <= cols) {
        out.add(line)
        return
    }

    // Flatten to per-cell (char, style) for simple slicing; wrapped lines are
    // rendered once and cached, so the allocation is fine.
    val flat = ArrayList<Pair<Char, AnsiStyle>>(total)
    line.forEach { (text, style) -> text.forEach { c -> flat.add(c to style) } }

    // "│ " gutter prefix repeats on continuations.
    val prefix: List<Pair<Char, AnsiStyle>> =
        if (flat.size >= 2 && flat[0].first == '│') flat.subList(0, 2).toList() else emptyList()

    var start = 0
    var first = true
    while (start < flat.size) {
        val budget = (cols - (if (first) 0 else prefix.size)).coerceAtLeast(1)
        var end = (start + budget).coerceAtMost(flat.size)
        var nextStart = end

        if (end < flat.size) {
            // Prefer a word break: last space within the lookback window.
            val limit = (end - WORD_LOOKBACK).coerceAtLeast(start + 1)
            var sp = -1
            for (k in end - 1 downTo limit) {
                if (flat[k].first == ' ') { sp = k; break }
            }
            if (sp > start) {
                end = sp
                nextStart = sp + 1 // swallow the break space
            }
        }

        // Never cut between a surrogate pair, or both halves render as the
        // replacement glyph. Push the high surrogate to the next line.
        if (end in (start + 2)..(flat.size - 1) && flat[end - 1].first.isHighSurrogate()) {
            end--
            nextStart = end
        }

        val cells = if (first) flat.subList(start, end).toList()
                    else prefix + flat.subList(start, end)
        out.add(regroup(cells))
        start = nextStart
        first = false
    }
}

/** Re-group consecutive same-styled cells into (text, style) segments. */
private fun regroup(cells: List<Pair<Char, AnsiStyle>>): List<Pair<String, AnsiStyle>> {
    if (cells.isEmpty()) return emptyList()
    val segs = ArrayList<Pair<String, AnsiStyle>>()
    var style = cells[0].second
    val buf = StringBuilder()
    cells.forEach { (c, s) ->
        if (s != style) {
            segs.add(buf.toString() to style)
            buf.setLength(0)
            style = s
        }
        buf.append(c)
    }
    segs.add(buf.toString() to style)
    return segs
}
