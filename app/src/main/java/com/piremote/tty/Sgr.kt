package com.piremote.tty

/**
 * SGR (Select Graphic Rendition) core: the ANSI style model, color palettes,
 * and the per-line SGR parser. Compose-free so it runs in plain JVM unit tests.
 *
 * Moved from screens/TerminalView.kt; [AnsiStyle.link] added for OSC 8 hyperlinks.
 */

/** The ESC control character (0x1B). Defined numerically to keep raw escape bytes out of source. */
val ESC: Char = 27.toChar()
val BEL: Char = 7.toChar()

/** Parsed ANSI SGR color/style. [link] carries an OSC 8 hyperlink target, if any. */
data class AnsiStyle(
    val fgR: Int = -1, val fgG: Int = -1, val fgB: Int = -1,
    val bgR: Int = -1, val bgG: Int = -1, val bgB: Int = -1,
    val bold: Boolean = false,
    val dim: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val link: String? = null
)

internal val ansi8Colors = arrayOf(
    intArrayOf(0, 0, 0),       // 30 black
    intArrayOf(197, 15, 31),   // 31 red
    intArrayOf(6, 96, 17),     // 32 green
    intArrayOf(193, 156, 0),   // 33 yellow
    intArrayOf(21, 44, 227),   // 34 blue
    intArrayOf(203, 0, 117),   // 35 magenta
    intArrayOf(0, 129, 129),   // 36 cyan
    intArrayOf(113, 113, 113)  // 37 white
)

internal val ansi8BrightColors = arrayOf(
    intArrayOf(127, 127, 127), // 90 bright black
    intArrayOf(204, 0, 0),     // 91 bright red
    intArrayOf(72, 209, 6),    // 92 bright green
    intArrayOf(255, 255, 85),  // 93 bright yellow
    intArrayOf(6, 122, 255),   // 94 bright blue
    intArrayOf(187, 0, 255),   // 95 bright magenta
    intArrayOf(0, 211, 255),   // 96 bright cyan
    intArrayOf(255, 255, 255)  // 97 bright white
)

/** xterm 256-color palette lookup (0-15 base, 16-231 6x6x6 cube, 232-255 grayscale) */
internal fun xtermColor(idx: Int): IntArray? {
    if (idx < 0 || idx > 255) return null
    return when {
        idx < 8 -> ansi8Colors[idx]
        idx < 16 -> ansi8BrightColors[idx - 8]
        idx < 232 -> {
            val n = idx - 16
            val r = n / 36
            val g = (n / 6) % 6
            val b = n % 6
            fun lvl(v: Int) = if (v == 0) 0 else 55 + v * 40
            intArrayOf(lvl(r), lvl(g), lvl(b))
        }
        else -> {
            val v = 8 + (idx - 232) * 10
            intArrayOf(v, v, v)
        }
    }
}

/** Parse a single ANSI line into styled text segments */
fun parseAnsiLine(line: String): List<Pair<String, AnsiStyle>> {
    val segments = ArrayList<Pair<String, AnsiStyle>>()
    var style = AnsiStyle()
    var textBuf = StringBuilder()
    var i = 0

    while (i < line.length) {
        if (line[i] == ESC && i + 1 < line.length && line[i + 1] == '[') {
            // CSI = ESC [ , parameter bytes (0x30-0x3F), intermediates (0x20-0x2F),
            // one final byte (0x40-0x7E). Scan to the final byte instead of hunting
            // for a literal 'm' — otherwise a non-SGR CSI like ESC[K or ESC[2J eats
            // all following text up to the next 'm' in the visible content.
            var j = i + 2
            while (j < line.length && line[j].code in 0x30..0x3F) j++
            while (j < line.length && line[j].code in 0x20..0x2F) j++
            if (j < line.length && line[j].code in 0x40..0x7E) {
                if (line[j] == 'm') {
                    val codes = line.substring(i + 2, j).split(";")
                    if (textBuf.isNotEmpty()) {
                        segments.add(Pair(textBuf.toString(), style.copy()))
                        textBuf = StringBuilder()
                    }
                    style = parseSgrCodes(codes, style)
                }
                // Any other final byte: a non-SGR CSI — parsed and silently dropped.
                i = j + 1
            } else {
                // Malformed / unterminated CSI: drop the introducer, keep scanning.
                i += 2
            }
        } else if (line[i] == ESC && i + 1 < line.length && line[i + 1] == ']') {
            // OSC sequence (e.g. OSC 8 hyperlinks: ESC]8;;URL ST text ESC]8;; ST).
            // Skip the marker to its terminator — BEL or ST (ESC \) — so the
            // bare "]8;;" never renders as literal text. The link TEXT lives
            // between the open and close markers and renders normally.
            var j = i + 2
            while (j < line.length && line[j] != BEL && line[j] != ESC) j++
            i = if (j < line.length && line[j] == ESC && j + 1 < line.length && line[j + 1] == '\\') {
                j + 2 // ST terminator (ESC \)
            } else if (j < line.length && line[j] == BEL) {
                j + 1 // BEL terminator
            } else {
                j // ran off the end or hit a lone ESC (next loop handles it)
            }
        } else {
            textBuf.append(line[i])
            i++
        }
    }
    if (textBuf.isNotEmpty()) segments.add(Pair(textBuf.toString(), style.copy()))
    return segments
}

/** Parse SGR codes and return updated style.
 *  Flattened control flow -- one branch per code, explicit skip count. */
internal fun parseSgrCodes(codes: List<String>, style: AnsiStyle): AnsiStyle {
    // Empty SGR (CSI m) is equivalent to reset (CSI 0 m)
    if (codes.size == 1 && codes[0].isEmpty()) return AnsiStyle(link = style.link)
    var s = style
    var ci = 0
    while (ci < codes.size) {
        val code = codes[ci].toIntOrNull()
        if (code == null) { ci++; continue }
        val skip = when (code) {
            0 -> { s = AnsiStyle(link = s.link); 1 }
            1 -> { s = s.copy(bold = true); 1 }
            2 -> { s = s.copy(dim = true); 1 }
            3 -> { s = s.copy(italic = true); 1 }
            4 -> { s = s.copy(underline = true); 1 }
            39 -> { s = s.copy(fgR = -1, fgG = -1, fgB = -1); 1 }
            49 -> { s = s.copy(bgR = -1, bgG = -1, bgB = -1); 1 }
            in 30..37 -> {
                val c = ansi8Colors[code - 30]
                s = s.copy(fgR = c[0], fgG = c[1], fgB = c[2]); 1
            }
            in 40..47 -> {
                val c = ansi8Colors[code - 40]
                s = s.copy(bgR = c[0], bgG = c[1], bgB = c[2]); 1
            }
            in 90..97 -> {
                val c = ansi8BrightColors[code - 90]
                s = s.copy(fgR = c[0], fgG = c[1], fgB = c[2]); 1
            }
            in 100..107 -> {
                val c = ansi8BrightColors[code - 100]
                s = s.copy(bgR = c[0], bgG = c[1], bgB = c[2]); 1
            }
            38 -> {
                // Foreground extended color: 38;2;R;G;B or 38;5;N
                val mode = codes.getOrNull(ci + 1)?.toIntOrNull()
                when (mode) {
                    2 -> if (ci + 4 < codes.size) {
                        // Clamp each component to 0..255; if any fails to parse,
                        // leave the whole channel at default rather than a mixed
                        // sentinel — Compose Color(r/255f,...) throws on out-of-range.
                        val r = codes[ci + 2].toIntOrNull()?.coerceIn(0, 255)
                        val g = codes[ci + 3].toIntOrNull()?.coerceIn(0, 255)
                        val b = codes[ci + 4].toIntOrNull()?.coerceIn(0, 255)
                        s = if (r != null && g != null && b != null)
                            s.copy(fgR = r, fgG = g, fgB = b)
                        else s.copy(fgR = -1, fgG = -1, fgB = -1)
                        5
                    } else 1
                    5 -> if (ci + 2 < codes.size) {
                        codes[ci + 2].toIntOrNull()?.let { idx ->
                            xtermColor(idx)?.let { rgb ->
                                s = s.copy(fgR = rgb[0], fgG = rgb[1], fgB = rgb[2])
                            }
                        }
                        3
                    } else 1
                    else -> 1
                }
            }
            48 -> {
                // Background extended color: 48;2;R;G;B or 48;5;N
                val mode = codes.getOrNull(ci + 1)?.toIntOrNull()
                when (mode) {
                    2 -> if (ci + 4 < codes.size) {
                        val r = codes[ci + 2].toIntOrNull()?.coerceIn(0, 255)
                        val g = codes[ci + 3].toIntOrNull()?.coerceIn(0, 255)
                        val b = codes[ci + 4].toIntOrNull()?.coerceIn(0, 255)
                        s = if (r != null && g != null && b != null)
                            s.copy(bgR = r, bgG = g, bgB = b)
                        else s.copy(bgR = -1, bgG = -1, bgB = -1)
                        5
                    } else 1
                    5 -> if (ci + 2 < codes.size) {
                        codes[ci + 2].toIntOrNull()?.let { idx ->
                            xtermColor(idx)?.let { rgb ->
                                s = s.copy(bgR = rgb[0], bgG = rgb[1], bgB = rgb[2])
                            }
                        }
                        3
                    } else 1
                    else -> 1
                }
            }
            else -> 1
        }
        ci += skip
    }
    return s
}
