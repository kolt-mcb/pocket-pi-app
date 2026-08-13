package com.piremote.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.ui.graphics.Color
import com.piremote.ChatImage
import com.piremote.theme.*
import com.piremote.tty.AnsiStyle
import java.util.concurrent.ConcurrentHashMap

/**
 * A single braille (or block) cell with its color.
 * [char] is either a Unicode braille character (U+2800–U+28FF) or a block element.
 * Color is RGB — passed through as AnsiStyle fg for truecolor rendering.
 */
data class BrailleCell(
    val char: Char,
    val r: Int = -1, val g: Int = -1, val b: Int = -1
)

/** Image cache: (dataHash, cols) → List<BrailleCell>. */
private val brailleCache = ConcurrentHashMap<Pair<Int, Int>, List<BrailleCell>>()

/** Decoded images may not exceed this on either axis; larger sources are downsampled. */
private const val MAX_DECODE_DIM = 2048

/** Bitmap cache: base64 hash → Bitmap. Byte-bounded LRU (~48 MB). */
private val bitmapCache = object : android.util.LruCache<Int, Bitmap>(48 * 1024 * 1024) {
    override fun sizeOf(key: Int, value: Bitmap): Int = value.byteCount
}

/** Cache-only lookup: the already-decoded Bitmap, or null without decoding.
 *  Lets composables show a cache hit synchronously and defer the (100ms+ for
 *  a large image) decode to a background dispatcher instead of blocking the
 *  frame inside composition. */
fun peekDecodedImage(base64: String): Bitmap? = bitmapCache.get(base64.hashCode())

/**
 * Decode a base64 image to a Bitmap, downsampled to ≤ [MAX_DECODE_DIM] px on the
 * longest axis. Cached by base64 hash. Returns null on any decode failure.
 */
fun decodeBase64Image(base64: String): Bitmap? {
    val hash = base64.hashCode()
    bitmapCache.get(hash)?.let { return it }
    return try {
        val bytes = Base64.decode(base64, Base64.DEFAULT)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
        while (maxDim / sample > MAX_DECODE_DIM) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            ?.also { bitmapCache.put(hash, it) }
    } catch (_: Exception) { null }
}

/** Decode a ChatImage to an Android Bitmap. Cached by base64 hash. */
private fun decodeChatImage(image: ChatImage): Bitmap? = decodeBase64Image(image.data)

/**
 * Convert a ChatImage to colored braille cells.
 *
 * Braille characters have 8 dots in 2 cols × 4 rows:
 *   1 4    dot bits: 01234567
 *   2 5
 *   3 6
 *     7 8
 *
 * Each braille cell maps to a 2×4 pixel region of the source image.
 * A dot bit is set if that region's luminance exceeds ~120.
 * Cell color = average RGB across all 8 sample pixels.
 *
 * @param image      The ChatImage to render.
 * @param targetCols Width in braille cells (Unicode chars). Viewport width minus gutters.
 * @param maxRows    Cap on output rows to prevent huge outputs on phone.
 * @return Flat list of BrailleCell, row-major order. Caller wraps into lines of [targetCols].
 */
fun renderImageToBraille(image: ChatImage, targetCols: Int, maxRows: Int = 30): List<BrailleCell> {
    val cacheKey = image.data.hashCode() to targetCols
    return brailleCache.getOrPut(cacheKey) {
        val bm = decodeChatImage(image) ?: return@getOrPut emptyList()
        bitmapToCharset(bm, targetCols, maxRows)
    }
}

/**
 * Core conversion: Bitmap → flat braille/block cells.
 * Small images (< 50px on either axis) use block elements for better S/N.
 */
internal fun bitmapToCharset(bm: Bitmap, targetCols: Int, maxRows: Int = 30): List<BrailleCell> {
    val srcW = bm.width
    val srcH = bm.height
    if (srcW <= 0 || srcH <= 0) return emptyList()

    val aspect = srcW / srcH.toDouble()

    // Sub-50px images look noisy as braille — use block elements instead.
    if (srcW < 50 || srcH < 50) {
        return bitmapToBlocks(bm, targetCols.coerceAtMost(20), maxRows.coerceAtMost(10))
    }

    return bitmapToBraille(bm, targetCols, maxRows, aspect)
}

private fun bitmapToBraille(bm: Bitmap, targetCols: Int, maxRows: Int, aspect: Double): List<BrailleCell> {
    val srcW = bm.width
    val srcH = bm.height

    // Braille cell = 2 dots wide × 4 dots tall.
    // targetCols cells → 2*targetCols pixel columns, 4*targetRows pixel rows.
    val targetRows = ((targetCols * 2.0 / aspect) / 4.0).toInt().coerceIn(1, maxRows)
    val cellW = srcW.toDouble() / targetCols
    val cellH = srcH.toDouble() / targetRows

    // Dot bit → (dotCol, dotRow) within the 2×4 cell.
    //   1 4   2 5   3 6   7 8
    val dotPositions = arrayOf(
        0 to 0, 0 to 1, 0 to 2,  // bits 0,1,2 — left column
        1 to 0, 1 to 1, 1 to 2,  // bits 3,4,5 — right column
        0 to 3, 1 to 3           // bit 6 = left-bottom, bit 7 = right-bottom
    )

    val cells = ArrayList<BrailleCell>(targetCols * targetRows)

    for (row in 0 until targetRows) {
        for (col in 0 until targetCols) {
            var dotBits = 0
            var sumR = 0
            var sumG = 0
            var sumB = 0

            for (bit in 0 until 8) {
                val (dc, dr) = dotPositions[bit]
                val px = ((col * 2 + dc) * cellW / 2).toInt().coerceIn(0, srcW - 1)
                val py = ((row * 4 + dr) * cellH / 4).toInt().coerceIn(0, srcH - 1)
                val pixel = bm.getPixel(px, py)
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                sumR += r; sumG += g; sumB += b

                val lum = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
                if (lum > 120) dotBits = dotBits or (1 shl bit)
            }

            cells.add(BrailleCell(
                char = ('\u2800' + dotBits).toChar(),
                r = (sumR / 8).coerceIn(0, 255),
                g = (sumG / 8).coerceIn(0, 255),
                b = (sumB / 8).coerceIn(0, 255)
            ))
        }
    }
    return cells
}

/**
 * Block-element renderer for very small images.
 * Uses ░▒▓█ with ANSI truecolor — better S/N than braille on tiny images.
 */
private fun bitmapToBlocks(bm: Bitmap, targetCols: Int, maxRows: Int): List<BrailleCell> {
    val srcW = bm.width
    val srcH = bm.height
    val aspect = srcW / srcH.toDouble()
    val targetRows = ((targetCols / aspect) * 2).toInt().coerceIn(1, maxRows)
    val cellW = srcW.toDouble() / targetCols
    val cellH = srcH.toDouble() / targetRows

    val cells = ArrayList<BrailleCell>(targetCols * targetRows)

    for (row in 0 until targetRows) {
        for (col in 0 until targetCols) {
            val px = ((col + 0.5) * cellW).toInt().coerceIn(0, srcW - 1)
            val py = ((row + 0.5) * cellH).toInt().coerceIn(0, srcH - 1)
            val pixel = bm.getPixel(px, py)
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            val lum = (0.299 * r + 0.587 * g + 0.114 * b).toInt()

            val blockChar = when {
                lum > 200 -> '█'
                lum > 128 -> '▓'
                lum > 64  -> '▒'
                else      -> '░'
            }
            cells.add(BrailleCell(char = blockChar, r = r, g = g, b = b))
        }
    }
    return cells
}

/**
 * Wrap a flat list of BrailleCell into per-line ANSI segments.
 * Each output line is a list of (String, AnsiStyle) pairs ready for buildAnsiText.
 */
fun brailleToAnsiLines(
    cells: List<BrailleCell>,
    cols: Int,
    defaultColor: Color = textPrimary
): List<List<Pair<String, AnsiStyle>>> {
    if (cells.isEmpty()) return emptyList()
    val lines = ArrayList<ArrayList<Pair<String, AnsiStyle>>>()

    for (i in cells.indices step cols) {
        val end = (i + cols).coerceAtMost(cells.size)
        val segments = ArrayList<Pair<String, AnsiStyle>>(end - i)

        for (j in i until end) {
            val cell = cells[j]
            segments.add(Pair(
                cell.char.toString(),
                AnsiStyle(fgR = cell.r, fgG = cell.g, fgB = cell.b)
            ))
        }
        lines.add(segments)
    }
    return lines
}

/** Clear image caches. Called from MainActivity.onTrimMemory. */
fun clearImageCaches() {
    brailleCache.clear()
    bitmapCache.evictAll()
}
