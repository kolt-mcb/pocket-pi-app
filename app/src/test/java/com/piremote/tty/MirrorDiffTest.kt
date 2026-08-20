package com.piremote.tty

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Row-level mirror diff application must reconstruct exactly what the host had,
 *  for every shape of change (same size, grow, shrink, no-op) — a desync here
 *  silently corrupts the mirrored screen. */
class MirrorDiffTest {

    private fun buf(vararg s: String) = s.toMutableList()
    private fun rows(vararg p: Pair<Int, String>) = p.toList()

    @Test fun overwrites_changed_rows_same_size() {
        val b = buf("a", "b", "c")
        applyMirrorDiff(b, 3, rows(1 to "B"))
        assertEquals(listOf("a", "B", "c"), b)
    }

    @Test fun grows_buffer_with_new_trailing_rows() {
        val b = buf("a", "b")
        applyMirrorDiff(b, 4, rows(2 to "c", 3 to "d"))
        assertEquals(listOf("a", "b", "c", "d"), b)
    }

    @Test fun shrinks_buffer_by_line_count() {
        val b = buf("a", "b", "c", "d")
        applyMirrorDiff(b, 2, rows())
        assertEquals(listOf("a", "b"), b)
    }

    @Test fun shrink_then_overwrite_remaining() {
        val b = buf("a", "b", "c", "d")
        applyMirrorDiff(b, 2, rows(0 to "A"))
        assertEquals(listOf("A", "b"), b)
    }

    @Test fun empty_diff_is_a_noop() {
        val b = buf("x", "y", "z")
        applyMirrorDiff(b, 3, rows())
        assertEquals(listOf("x", "y", "z"), b)
    }

    @Test fun out_of_range_index_ignored() {
        val b = buf("a", "b")
        applyMirrorDiff(b, 2, rows(5 to "nope", 0 to "A"))
        assertEquals(listOf("A", "b"), b)
    }

    @Test fun reconstructs_against_host_diff_semantics() {
        // Simulate the host: old vs new, rows = changed indices, lineCount = new size.
        val old = listOf("line0", "line1", "line2", "line3")
        val new = listOf("line0", "CHANGED", "line2")     // line1 edited, line3 removed
        val hostRows = new.indices.filter { old.getOrNull(it) != new[it] }.map { it to new[it] }
        val b = old.toMutableList()
        applyMirrorDiff(b, new.size, hostRows)
        assertEquals(new, b)
    }

    // ── head trimming (MIRROR_KEEP_LINES) ──────────────────────────────
    // The phone keeps only the tail of the host's buffer, so host row `i` lives
    // at `i - dropped`. Get that translation wrong and the mirrored screen
    // silently paints the right text onto the wrong rows.

    @Test fun no_trim_until_cap_plus_slack_is_exceeded() {
        val b = (0 until 12).map { "l$it" }.toMutableList()
        val dropped = applyMirrorDiff(b, 12, rows(), dropped = 0, keep = 10, slack = 5)
        assertEquals(0, dropped)
        assertEquals(12, b.size)
        assertEquals("l0", b[0])
    }

    @Test fun trims_head_down_to_cap_and_reports_dropped() {
        val b = (0 until 20).map { "l$it" }.toMutableList()
        val dropped = applyMirrorDiff(b, 20, rows(), dropped = 0, keep = 10, slack = 5)
        assertEquals(10, dropped)
        assertEquals(10, b.size)
        assertEquals("l10", b.first())
        assertEquals("l19", b.last())
    }

    @Test fun host_indices_translate_into_the_trimmed_window() {
        val b = (10 until 20).map { "l$it" }.toMutableList()   // host rows 10..19
        // Host edits row 15 and appends row 20.
        val dropped = applyMirrorDiff(b, 21, rows(15 to "EDIT", 20 to "l20"), dropped = 10, keep = 10, slack = 5)
        assertEquals(10, dropped)                               // still under cap+slack
        assertEquals("EDIT", b[5])                              // host 15 → local 5
        assertEquals("l20", b[10])
    }

    @Test fun rows_below_the_retained_window_are_ignored() {
        val b = (10 until 20).map { "l$it" }.toMutableList()
        applyMirrorDiff(b, 20, rows(3 to "scrolled off", 10 to "KEPT"), dropped = 10, keep = 10, slack = 5)
        assertEquals("KEPT", b[0])
        assertEquals(10, b.size)
        assertEquals(listOf("KEPT") + (11 until 20).map { "l$it" }, b)
    }

    @Test fun shrink_below_the_dropped_head_empties_the_window() {
        val b = (10 until 20).map { "l$it" }.toMutableList()
        // Host truncated to 4 rows — everything we held is gone.
        val dropped = applyMirrorDiff(b, 4, rows(), dropped = 10, keep = 10, slack = 5)
        assertEquals(4, dropped)
        assertEquals(0, b.size)
    }

    @Test fun tracks_the_host_tail_across_many_trimming_rounds() {
        // Simulate the real loop: host appends, we diff, we trim, repeat. The
        // local buffer must always equal the tail of the host's buffer.
        val host = mutableListOf<String>()
        val local = mutableListOf<String>()
        var dropped = 0
        for (round in 0 until 60) {
            val before = host.size
            repeat(3) { host.add("r$round-$it") }
            host[maxOf(0, before - 1)] = "touched$round"        // an in-place edit too
            val hostRows = listOf(maxOf(0, before - 1) to host[maxOf(0, before - 1)]) +
                (before until host.size).map { it to host[it] }
            dropped = applyMirrorDiff(local, host.size, hostRows, dropped, keep = 20, slack = 10)
            assertEquals("round $round: dropped accounts for the whole head",
                host.size - local.size, dropped)
            assertEquals("round $round: local mirrors the host tail",
                host.subList(dropped, host.size), local)
        }
        // Trimming is amortised — it runs only once the buffer passes cap+slack,
        // so the window settles somewhere inside [cap, cap+slack] rather than
        // being shaved on every appended row.
        assertTrue("settled size ${local.size} outside [20, 30]", local.size in 20..30)
    }

    @Test fun absurd_line_count_is_capped_not_ooming() {
        val b = buf("a")
        applyMirrorDiff(b, Int.MAX_VALUE, rows())
        assertEquals(MIRROR_MAX_LINES, b.size)
    }
}
