package com.piremote.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.piremote.*
import com.piremote.theme.*
import com.piremote.tty.AnsiStyle
import com.piremote.tty.parseAnsiLine

/** Build a Compose AnnotatedString from ANSI segments. [defaultColor] is used
 *  for text that carries no explicit ANSI foreground, so callers (e.g. the muted
 *  status bar) keep their own base colour while still honouring embedded colours. */
fun buildAnsiText(
    segments: List<Pair<String, AnsiStyle>>,
    defaultColor: Color = textPrimary,
): AnnotatedString {
    val builder = AnnotatedString.Builder()
    segments.forEach { (text, style) ->
        val fgColor = if (style.fgR >= 0) {
            val base = Color(style.fgR / 255f, style.fgG / 255f, style.fgB / 255f)
            if (style.dim) base.copy(alpha = 0.5f) else base
        } else if (style.dim) textMuted else defaultColor
        
        val bgColor = if (style.bgR >= 0) Color(style.bgR / 255f, style.bgG / 255f, style.bgB / 255f) else Color.Transparent

        builder.withStyle(
            style = SpanStyle(
                color = fgColor,
                background = bgColor,
                fontWeight = if (style.bold) FontWeight.Bold else FontWeight.Normal,
                fontStyle = if (style.italic) FontStyle.Italic else FontStyle.Normal,
                textDecoration = if (style.underline) TextDecoration.Underline else null
            )
        ) {
            append(text)
        }
    }
    return builder.toAnnotatedString()
}

/**
 * Full-screen ANSI Terminal view — renders raw ANSI text from ANY Pi extension TUI component.
 * Works with DOOM, text editors, selectors, settings lists, custom tools — everything pi renders.
 */
@Composable
fun TerminalRenderView(frame: RenderFrame, onInput: (String) -> Unit = {}) {
    Box(modifier = Modifier.fillMaxSize().background(bg)) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Title bar
            if (frame.title.isNotBlank()) {
                Column(modifier = Modifier.fillMaxWidth().background(bgSecondary)) {
                    Text(
                        frame.title,
                        color = accent,
                        fontFamily = piMono,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                    HorizontalDivider(color = borderMuted)
                }
            }

            // Rendered ANSI lines. Lines with a non-empty tapValues[i] entry
            // get a clickable modifier; tapping sends the entry back via
            // sendInput, so a render-frame menu (e.g. /resume's session list)
            // is touch-driven with no per-extension UI on the phone.
            // LazyColumn so only visible rows compose, and each row memoizes its
            // parse + AnnotatedString keyed on the raw line — a frame that
            // changes one row (spinner, DOOM HUD) re-lays-out one row, not all.
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth().padding(2.dp)) {
                items(frame.ansiLines.size) { i ->
                    TerminalLine(
                        line = frame.ansiLines[i],
                        tap = frame.tapValues.getOrNull(i).orEmpty(),
                        onInput = onInput,
                    )
                }
            }

            // Input area based on mode
            when (frame.inputMode) {
                "text" -> TerminalTextInput(onInput)
            }
        }
    }
}

@Composable
private fun TerminalLine(line: String, tap: String, onInput: (String) -> Unit) {
    val styled = remember(line) {
        val segments = parseAnsiLine(line)
        if (segments.all { it.first.isEmpty() }) null else buildAnsiText(segments)
    }
    if (styled == null) {
        Spacer(Modifier.height(12.dp))
        return
    }
    val text = @Composable {
        Text(
            styled,
            fontFamily = piMono,
            fontSize = 12.sp,
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        )
    }
    if (tap.isNotEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .clickable(
                    role = Role.Button,
                    onClickLabel = "select $tap",
                ) { onInput(tap) },
            contentAlignment = Alignment.CenterStart,
        ) { text() }
    } else {
        text()
    }
}

// Own composable so typing recomposes just the input row, not the line list.
@Composable
private fun TerminalTextInput(onInput: (String) -> Unit) {
    var input by remember { mutableStateOf("") }
    Column(modifier = Modifier.fillMaxWidth().background(bgSecondary)) {
        HorizontalDivider(color = accent)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
            Text(">", color = accent, fontFamily = piMono, fontSize = 12.sp)
            BasicTextField(
                value = input,
                onValueChange = { input = it },
                textStyle = TextStyle(color = textPrimary, fontFamily = piMono, fontSize = 12.sp),
                modifier = Modifier.weight(1f),
                singleLine = true,
                decorationBox = { innerTextField ->
                    if (input.isEmpty()) Text("Type here...", color = textMuted, fontFamily = piMono, fontSize = 12.sp)
                    else innerTextField()
                }
            )
            Text("⏎", color = textMuted, fontFamily = piMono, fontSize = 14.sp,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .clickable(
                        role = Role.Button,
                        onClickLabel = "send input",
                    ) { onInput(input); input = "" })
        }
    }
}
