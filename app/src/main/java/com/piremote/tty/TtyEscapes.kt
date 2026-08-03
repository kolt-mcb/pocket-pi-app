package com.piremote.tty

/**
 * ANSI/OSC escape sequence builders — the reference encoder for the escape
 * shapes the app consumes (see PROTOCOL.md). The host extension emits these;
 * TtyStreamParser decodes them.
 *
 * Terminators: we EMIT ST (ESC \) everywhere — kitty mandates it and it is the
 * spec-correct OSC terminator. The parser ACCEPTS both BEL and ST.
 */
object TtyEscapes {
    val ESC: String = com.piremote.tty.ESC.toString()
    val BEL: String = 7.toChar().toString()

    /** String Terminator (ESC \). */
    val ST: String = ESC + "\\"

    val RESET: String = "$ESC[0m"
    val BOLD: String = "$ESC[1m"
    val DIM: String = "$ESC[2m"
    val ITALIC: String = "$ESC[3m"
    val UNDERLINE: String = "$ESC[4m"

    /** Truecolor foreground escape. */
    fun fg(r: Int, g: Int, b: Int): String =
        "$ESC[38;2;${r.coerceIn(0, 255)};${g.coerceIn(0, 255)};${b.coerceIn(0, 255)}m"

    /** Truecolor background escape. */
    fun bg(r: Int, g: Int, b: Int): String =
        "$ESC[48;2;${r.coerceIn(0, 255)};${g.coerceIn(0, 255)};${b.coerceIn(0, 255)}m"

    /** Open an OSC 8 hyperlink. Close with [osc8Close]. */
    fun osc8(url: String): String = "$ESC]8;;$url$ST"

    /** Close the current OSC 8 hyperlink. */
    fun osc8Close(): String = "$ESC]8;;$ST"

    /** Render a CellSpec as an OSC 1337 dimension value. */
    private fun dim(spec: CellSpec): String = when (spec) {
        is CellSpec.Auto -> "auto"
        is CellSpec.Cells -> "${spec.n}"
        is CellSpec.Pixels -> "${spec.n}px"
        is CellSpec.Percent -> "${spec.n}%"
    }

    /**
     * iTerm2-style inline image (OSC 1337 File=...). This is the primary image
     * channel: the normalizer desugars ChatMessage.images into this shape.
     */
    fun osc1337Image(
        base64: String,
        mime: String = "image/png",
        width: CellSpec = CellSpec.Auto,
        height: CellSpec = CellSpec.Auto,
        preserveAspect: Boolean = true,
    ): String {
        val args = buildString {
            append("inline=1")
            append(";size=${base64.length}")
            append(";mime=$mime")
            if (width != CellSpec.Auto) append(";width=${dim(width)}")
            if (height != CellSpec.Auto) append(";height=${dim(height)}")
            if (!preserveAspect) append(";preserveAspectRatio=0")
        }
        return "$ESC]1337;File=$args:$base64$ST"
    }

    /**
     * kitty graphics protocol transmit+display, chunked. Returns the APC
     * sequences in order; concatenate into the stream.
     */
    fun kittyChunks(base64: String, id: Int, cols: Int? = null, chunkSize: Int = 4096): List<String> {
        val chunks = base64.chunked(chunkSize.coerceAtLeast(1))
        val display = if (cols != null) ",c=$cols" else ""
        if (chunks.size <= 1) {
            return listOf("${ESC}_Ga=T,f=100,i=$id$display;${chunks.firstOrNull().orEmpty()}$ST")
        }
        return chunks.mapIndexed { idx, chunk ->
            when (idx) {
                0 -> "${ESC}_Ga=T,f=100,i=$id$display,m=1;$chunk$ST"
                chunks.lastIndex -> "${ESC}_Gi=$id,m=0;$chunk$ST"
                else -> "${ESC}_Gi=$id,m=1;$chunk$ST"
            }
        }
    }
}
