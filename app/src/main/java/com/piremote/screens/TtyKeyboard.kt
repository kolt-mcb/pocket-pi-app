package com.piremote.screens

import android.net.Uri
import android.view.HapticFeedbackConstants
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.piremote.ChatViewModel
import com.piremote.theme.accent
import com.piremote.theme.bg
import com.piremote.theme.bgSecondary
import com.piremote.theme.textPrimary

/**
 * Raw key-sequence encoding for the tty keyboard — pure, no Compose, so it can be
 * unit-tested. The app is a "dumb TTY": these bytes are forwarded verbatim into
 * pi's real PTY via `mirror_input`, exactly like a terminal emulator would send
 * them, and pi echoes them back into the mirror.
 */
object TtyKeys {
    const val ESC = "\u001b"
    const val TAB = "\t"
    const val ENTER = "\r"
    const val DEL = "\u007f"
    const val UP = "\u001b[A"
    const val DOWN = "\u001b[B"
    const val RIGHT = "\u001b[C"
    const val LEFT = "\u001b[D"
    const val CTRL_C = "\u0003"
    const val CTRL_D = "\u0004"
    // Shift-Tab is its own sequence (CSI Z), not a modified Tab — TUIs read it
    // to cycle backwards through completions and fields.
    const val BACK_TAB = "\u001b[Z"

    // Bracketed paste: pi's editor treats text between these markers as a literal
    // paste (multi-line safe — embedded newlines don't submit, large content
    // collapses), matching pi's own pasteToEditor. We wrap clipboard content in
    // them so a phone paste lands in pi's prompt exactly like a desktop paste.
    const val PASTE_START = "\u001b[200~"
    const val PASTE_END = "\u001b[201~"

    /**
     * Shift-modified cursor key: ESC[A becomes ESC[1;2A — the xterm convention of
     * a "1;<mods>" parameter, where 2 is shift. Anything that isn't a bare cursor
     * sequence, and every unshifted press, passes through unchanged.
     */
    fun shifted(seq: String, shift: Boolean): String =
        if (shift && seq.length == 3 && seq.startsWith("$ESC[") && seq[2] in "ABCD")
            "$ESC[1;2${seq[2]}" else seq

    /** Wrap clipboard [text] for a bracketed paste into pi's prompt. Empty -> "". */
    fun bracketedPaste(text: String): String =
        if (text.isEmpty()) "" else PASTE_START + text.replace("\r\n", "\n") + PASTE_END

    /**
     * Encode typed [text] into raw terminal bytes. Sticky [ctrl]/[alt]/[shift]
     * are one-shot: they apply to the FIRST character only (the caller clears
     * them after). Newlines become carriage returns (what a TTY expects for
     * Enter).
     *
     * Ctrl maps a key to its control byte (Ctrl-A=0x01 … Ctrl-_=0x1f); Alt
     * prefixes ESC ("meta sends escape", the xterm convention); Shift uppercases
     * — a terminal has no shift byte, the shifted character IS the key.
     */
    fun encode(text: String, ctrl: Boolean, alt: Boolean, shift: Boolean = false): String {
        if (text.isEmpty()) return ""
        val sb = StringBuilder()
        text.forEachIndexed { i, c ->
            var ch = if (c == '\n') '\r' else c
            if (i == 0 && shift) ch = ch.uppercaseChar()
            if (i == 0 && (ctrl || alt)) {
                if (alt) sb.append(ESC)
                sb.append(if (ctrl) ctrlByte(ch) else ch)
            } else {
                sb.append(ch)
            }
        }
        return sb.toString()
    }

    /** Control-key byte for a character: e.g. 'C' -> 0x03, '[' -> 0x1b (ESC). */
    private fun ctrlByte(c: Char): Char = (c.uppercaseChar().code and 0x1f).toChar()
}

/** Sticky (one-shot) modifier state shared between the key row and the capture
 *  field. Tap a modifier to arm it; it clears after the next character. */
@Stable
class StickyMods {
    var ctrl by mutableStateOf(false)
    var alt by mutableStateOf(false)
    var shift by mutableStateOf(false)
    fun clear() { ctrl = false; alt = false; shift = false }
}

/**
 * The bottom input strip for the mirror: a row of special keys (Esc, Tab, Ctrl,
 * Alt, arrows, ^C, ^D) above the soft keyboard, plus a hidden capture field that
 * turns typed characters into raw PTY bytes. There is no separate chat box — you
 * type straight into pi's own prompt, which echoes inside the mirror.
 *
 * Every emitted byte string goes to [ChatViewModel.sendMirrorInput] with the
 * same [agentId] the mirror taps use, so typing and tapping target one session.
 */
@Composable
fun TtyKeyboardBar(vm: ChatViewModel, agentId: String, focusRequester: FocusRequester) {
    val mods = remember { StickyMods() }
    var syncEpoch by remember { mutableStateOf(0) }
    val send: (String) -> Unit = { if (it.isNotEmpty()) vm.sendMirrorInput(it, agentId) }
    // Special keys / paste change pi's prompt outside the typing buffer; bump the
    // epoch so the capture resyncs (then typing appends from pi's cursor).
    val sendExternal: (String) -> Unit = { if (it.isNotEmpty()) { syncEpoch++; vm.sendMirrorInput(it, agentId) } }

    // Image picker — images can't be typed as bytes, so they stay on the
    // semantic prompt path: pick one and it's sent to the selected session.
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        uri?.let {
            try {
                val bytes = context.contentResolver.openInputStream(it)?.use { s -> s.readBytes() }
                if (bytes != null) {
                    val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                    val mime = context.contentResolver.getType(it)
                        ?.takeIf { m -> m.startsWith("image/") } ?: "image/jpeg"
                    vm.addPendingImage("data:$mime;base64,$base64")
                    vm.sendPromptWithImages()
                }
            } catch (_: Exception) {}
        }
    }

    // Match the old input's root (fillMaxWidth + bg + imePadding) so the bar rides
    // just above the soft keyboard.
    Column(modifier = Modifier.fillMaxWidth().background(bg).imePadding()) {
        ModifierKeyRow(
            mods = mods,
            onBytes = sendExternal,
            onPickImage = {
                imagePicker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            },
            onPaste = {
                val pasted = clipboard.getText()?.text
                if (!pasted.isNullOrEmpty()) sendExternal(TtyKeys.bracketedPaste(pasted))
            },
        )
        TtyInputCapture(
            mods = mods,
            focusRequester = focusRequester,
            onBytes = send,
            onChordBytes = sendExternal,
            syncEpoch = syncEpoch,
        )
    }
}

/** Row of special keys: a scrollable region of escape-sequence keys, with
 *  `paste` and the image picker pinned at the right edge (outside the scroll)
 *  so they stay visible on narrow phones. Ctrl/Alt arm sticky modifiers
 *  (highlighted while armed); the rest emit their sequences immediately. */
@Composable
private fun ModifierKeyRow(
    mods: StickyMods,
    onBytes: (String) -> Unit,
    onPickImage: () -> Unit,
    onPaste: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Emitting a key consumes an armed shift (one-shot, like ctrl/alt on a
            // typed character) but leaves ctrl/alt for the character still to come.
            val emit: (String) -> Unit = { seq -> onBytes(seq); mods.shift = false }
            KeyCap("esc") { emit(TtyKeys.ESC) }
            KeyCap("tab") { emit(if (mods.shift) TtyKeys.BACK_TAB else TtyKeys.TAB) }
            KeyCap("ctrl", active = mods.ctrl) { mods.ctrl = !mods.ctrl }
            KeyCap("alt", active = mods.alt) { mods.alt = !mods.alt }
            KeyCap("shift", active = mods.shift) { mods.shift = !mods.shift }
            KeyCap("↑") { emit(TtyKeys.shifted(TtyKeys.UP, mods.shift)) }
            KeyCap("↓") { emit(TtyKeys.shifted(TtyKeys.DOWN, mods.shift)) }
            KeyCap("←") { emit(TtyKeys.shifted(TtyKeys.LEFT, mods.shift)) }
            KeyCap("→") { emit(TtyKeys.shifted(TtyKeys.RIGHT, mods.shift)) }
            KeyCap("^C") { onBytes(TtyKeys.CTRL_C); mods.clear() }
            KeyCap("^D") { onBytes(TtyKeys.CTRL_D); mods.clear() }
        }
        KeyCap("paste") { onPaste() }
        KeyCap("📎", description = "attach image") { onPickImage() }
    }
}

/** One keycap. The drawn cap fills a ≥ 44dp-tall hit area (heightIn before
 *  clickable, so the whole cap is tappable); 4dp rounding is intentional
 *  keyboard chrome. [description] doubles as contentDescription + click label
 *  for caps whose glyph isn't self-describing (e.g. 📎).
 *
 *  Every cap ticks: KEYBOARD_TAP is the same constant the soft keyboard uses, so
 *  these keys feel like its keys — and it honours the system touch-feedback
 *  setting, staying silent for users who turned haptics off. */
@Composable
private fun KeyCap(
    label: String,
    active: Boolean = false,
    description: String? = null,
    onClick: () -> Unit,
) {
    val view = LocalView.current
    Box(
        modifier = Modifier
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(if (active) accent else bgSecondary)
            .clickable(role = Role.Button, onClickLabel = description) {
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onClick()
            }
            .then(
                if (description != null) {
                    Modifier.semantics { contentDescription = description }
                } else Modifier
            )
            .padding(horizontal = 11.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (active) bg else textPrimary,
            fontFamily = piMono,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * Send the byte ops that turn pi's current line [old] into [new]: backspace the
 * characters that changed (from the first difference to the end), then type the
 * corrected tail. Because we always delete from the end and retype, the result
 * matches [new] regardless of where the change was — as long as pi's cursor is at
 * the line end (true for normal typing).
 */
private fun reconcile(old: String, new: String, onBytes: (String) -> Unit) {
    var p = 0
    val n = minOf(old.length, new.length)
    while (p < n && old[p] == new[p]) p++
    val dels = old.length - p
    if (dels > 0) onBytes(TtyKeys.DEL.repeat(dels))
    if (p < new.length) onBytes(new.substring(p))
}

/** Sacrificial prefix kept at the start of the capture field: backspacing at a
 *  logically-empty line still shrinks the field (eating into the prefix), so the
 *  deletion is observable and can be forwarded as DEL bytes — pasted or
 *  pre-existing prompt text stays deletable. 8 spaces — room for a few rapid
 *  backspaces per event; re-padded after each one. */
private const val BASE = "        "

private fun baseField() = TextFieldValue(BASE, TextRange(BASE.length))

/** The user-typed text in the capture field: everything after the sacrificial
 *  prefix ("" when backspaces have eaten into the prefix itself). */
private fun logicalText(fieldText: String): String =
    if (fieldText.length >= BASE.length) fieldText.substring(BASE.length) else ""

/**
 * Invisible keystroke capture with a reconciling buffer. The [BasicTextField]
 * holds [BASE] plus a real, editable copy of the line you're typing — so the IME
 * keeps a live composing word and **autocorrect / suggestions work**. On every
 * change we [reconcile] the text after the prefix against [sent] (what pi's
 * prompt already shows): we send backspaces for the changed tail, then the
 * corrected text. So when autocorrect rewrites "teh " → "the ", pi gets DEL×3 +
 * "he ". The field is invisible; pi echoes the result into its own prompt in the
 * mirror.
 *
 * [syncEpoch] bumps whenever a special key / paste / arrow is sent (those change
 * pi's line outside this buffer), so we resync to just the prefix and append
 * from pi's cursor. Sticky Ctrl/Alt chords go out via [onChordBytes] — the
 * epoch-bumping send — because a chord (^W, ^U, ^A…) changes pi's line in ways
 * the buffer cannot model, so it must trigger the same resync.
 */
@Composable
private fun TtyInputCapture(
    mods: StickyMods,
    focusRequester: FocusRequester,
    onBytes: (String) -> Unit,
    onChordBytes: (String) -> Unit,
    syncEpoch: Int,
) {
    var field by remember { mutableStateOf(baseField()) }
    var sent by remember { mutableStateOf("") }

    // A special key / paste / chord / history nav changed pi's prompt out from
    // under the buffer — drop our copy and append fresh from pi's cursor.
    // (epoch 0 is the initial state; don't clear on first composition.)
    LaunchedEffect(syncEpoch) {
        if (syncEpoch > 0) { sent = ""; field = baseField() }
    }

    BasicTextField(
        value = field,
        onValueChange = { nv ->
            val prev = field.text
            val cur = nv.text
            when {
                // Enter: flush the first line, send CR, then push any remaining
                // committed lines (encode maps '\n' → CR) and reset. Multi-line
                // text arrives via commitText from clipboard chips, dictation,
                // and autofill — none of which go through the Paste button.
                cur.contains('\n') -> {
                    reconcile(sent, logicalText(cur.substringBefore('\n')), onBytes)
                    onBytes(TtyKeys.ENTER)
                    val rest = cur.substringAfter('\n')
                    if (rest.isNotEmpty()) onBytes(TtyKeys.encode(rest, ctrl = false, alt = false))
                    sent = ""
                    field = baseField()
                }
                // Sticky Ctrl/Alt/Shift armed + buffer growth → chord the appended
                // character(s) best-effort and keep them OUT of the buffer. The
                // chord goes out on the epoch-bumping path so the buffer resets
                // and the mirror resyncs (pi's line just changed under us).
                (mods.ctrl || mods.alt || mods.shift) && cur.length > prev.length -> {
                    onChordBytes(TtyKeys.encode(cur.takeLast(cur.length - prev.length), mods.ctrl, mods.alt, mods.shift))
                    sent = ""
                    field = baseField()
                }
                // Normal typing / autocorrect: reconcile the post-prefix text →
                // pi; deletions that ate into the sacrificial prefix are
                // backspaces past logical-empty, forwarded as DELs, then the
                // prefix is re-padded.
                else -> {
                    val logicalCur = logicalText(cur)
                    reconcile(sent, logicalCur, onBytes)
                    val eaten = BASE.length - cur.length
                    if (eaten > 0) onBytes(TtyKeys.DEL.repeat(eaten))
                    sent = logicalCur
                    field = if (cur.startsWith(BASE)) nv
                    else TextFieldValue(BASE + logicalCur, TextRange(BASE.length + logicalCur.length))
                }
            }
            // An armed modifier never survives a keystroke, whatever the IME did.
            mods.clear()
        },
        modifier = Modifier
            .size(1.dp)
            .alpha(0f)
            .focusRequester(focusRequester),
        textStyle = TextStyle(color = Color.Transparent, fontSize = 1.sp),
        cursorBrush = SolidColor(Color.Transparent),
        // Autocorrect + suggestions ON: the buffer holds a real line so the IME
        // can compose, and reconcile() pushes any rewrite to pi. Auto-caps OFF:
        // a shell prompt is not a sentence — `ls` must not become `Ls`.
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = true,
            keyboardType = KeyboardType.Text,
            imeAction = ImeAction.None,
        ),
        singleLine = false,
        maxLines = 1,
    )
}
