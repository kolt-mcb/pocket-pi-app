package com.piremote.screens

import org.junit.Assert.assertEquals
import org.junit.Test

/** The encoder turns typed text + sticky modifiers into the exact bytes pi's PTY
 *  expects. Sticky modifiers are one-shot — they apply to the first char only. */
class TtyKeysTest {
    @Test fun plain_text_passes_through() {
        assertEquals("ab", TtyKeys.encode("ab", ctrl = false, alt = false))
    }

    @Test fun newline_becomes_carriage_return() {
        assertEquals("\r", TtyKeys.encode("\n", ctrl = false, alt = false))
    }

    @Test fun ctrl_maps_to_control_byte() {
        assertEquals("\u0003", TtyKeys.encode("c", ctrl = true, alt = false)) // Ctrl-C
        assertEquals("\u0001", TtyKeys.encode("a", ctrl = true, alt = false)) // Ctrl-A
        assertEquals("\u001b", TtyKeys.encode("[", ctrl = true, alt = false)) // Ctrl-[ = ESC
    }

    @Test fun alt_prefixes_escape() {
        assertEquals("\u001bx", TtyKeys.encode("x", ctrl = false, alt = true))
    }

    @Test fun ctrl_alt_combine_on_first_char() {
        assertEquals("\u001b\u0001", TtyKeys.encode("a", ctrl = true, alt = true))
    }

    @Test fun modifier_applies_to_first_char_only() {
        assertEquals("\u0001bc", TtyKeys.encode("abc", ctrl = true, alt = false))
    }

    @Test fun empty_text_is_empty() {
        assertEquals("", TtyKeys.encode("", ctrl = true, alt = true))
    }

    @Test fun shift_uppercases_the_first_char_only() {
        assertEquals("Abc", TtyKeys.encode("abc", ctrl = false, alt = false, shift = true))
    }

    @Test fun shift_with_ctrl_is_the_same_control_byte() {
        // Ctrl already folds case, so shift changes nothing for a chord.
        assertEquals("\u0003", TtyKeys.encode("c", ctrl = true, alt = false, shift = true))
    }

    @Test fun shift_with_alt_uppercases_after_the_escape() {
        assertEquals("\u001bX", TtyKeys.encode("x", ctrl = false, alt = true, shift = true))
    }

    @Test fun shifted_cursor_keys_get_the_xterm_modifier() {
        assertEquals("\u001b[1;2A", TtyKeys.shifted(TtyKeys.UP, shift = true))
        assertEquals("\u001b[1;2B", TtyKeys.shifted(TtyKeys.DOWN, shift = true))
        assertEquals("\u001b[1;2C", TtyKeys.shifted(TtyKeys.RIGHT, shift = true))
        assertEquals("\u001b[1;2D", TtyKeys.shifted(TtyKeys.LEFT, shift = true))
    }

    @Test fun unshifted_and_non_cursor_sequences_pass_through() {
        assertEquals(TtyKeys.UP, TtyKeys.shifted(TtyKeys.UP, shift = false))
        assertEquals(TtyKeys.ESC, TtyKeys.shifted(TtyKeys.ESC, shift = true))
        assertEquals(TtyKeys.BACK_TAB, TtyKeys.shifted(TtyKeys.BACK_TAB, shift = true))
    }

    @Test fun key_constants_are_the_expected_sequences() {
        assertEquals("\u001b", TtyKeys.ESC)
        assertEquals("\u001b[A", TtyKeys.UP)
        assertEquals("\u001b[B", TtyKeys.DOWN)
        assertEquals("\u001b[C", TtyKeys.RIGHT)
        assertEquals("\u001b[D", TtyKeys.LEFT)
        assertEquals("\u0003", TtyKeys.CTRL_C)
        assertEquals("\u0004", TtyKeys.CTRL_D)
        assertEquals("\u007f", TtyKeys.DEL)
        assertEquals("\u001b[Z", TtyKeys.BACK_TAB)
    }
}
