package com.piremote.screens

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.piremote.tty.TtyBlock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Measured monospace grid metrics for the scrollback viewport. */
data class TtyMetrics(
    val cols: Int,
    val cellWidth: Dp,
    val cellHeight: Dp,
    val fontSize: TextUnit,
)

/**
 * Measure the real advance of the mono font instead of guessing 0.6em — the
 * same column count feeds vm.reportViewport() and local wrapping, so host
 * rendering and phone rendering agree on width.
 */
@Composable
fun rememberTtyMetrics(maxWidth: Dp, fontSize: TextUnit = 12.sp): TtyMetrics {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(maxWidth, fontSize, density) {
        val result = measurer.measure(
            AnnotatedString("0".repeat(100)),
            TextStyle(fontFamily = piMono, fontSize = fontSize)
        )
        with(density) {
            val cw = (result.size.width / 100f).toDp()
            val ch = result.size.height.toDp()
            // 12.dp = the LazyColumn's 6.dp horizontal contentPadding x2.
            val cols = if (cw > 0.dp) ((maxWidth - 12.dp) / cw).toInt().coerceIn(20, 200) else 60
            TtyMetrics(cols, cw, ch, fontSize)
        }
    }
}

// ── fullscreen viewer ──────────────────────────────────────────────────

@Composable
fun ImageViewerDialog(image: TtyBlock.Image, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        var scale by remember { mutableStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        val transform = rememberTransformableState { zoom, pan, _ ->
            scale = (scale * zoom).coerceIn(1f, 8f)
            offset += pan
        }
        // Usually a synchronous cache hit (the inline thumbnail decoded it);
        // a cold miss decodes off-main instead of stalling the dialog frame.
        val bitmap by produceState(initialValue = peekDecodedImage(image.base64), image.base64) {
            if (value == null) value = withContext(Dispatchers.Default) { decodeBase64Image(image.base64) }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.92f))
                .clickable(
                    role = Role.Button,
                    onClickLabel = "close image viewer",
                ) { onDismiss() }
                .transformable(transform),
            contentAlignment = Alignment.Center,
        ) {
            bitmap?.let {
                Image(
                    it.asImageBitmap(),
                    contentDescription = "image viewer",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        },
                )
            } ?: Text("[image failed to decode]", color = Color.White, fontFamily = piMono)

            // Top bar: Save (system share/save sheet) + Close. The buttons consume
            // their own taps, so they don't trigger the tap-to-dismiss on the
            // background. Pinch/drag still zooms and pans the image.
            Row(
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (bitmap != null) {
                    TextButton(onClick = { saveImage(ctx, scope, image) }) {
                        Text("Save", color = Color.White, fontFamily = piMono)
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("Close", color = Color.White, fontFamily = piMono)
                }
            }
        }
    }
}

/** Write the image to cache/shared/ and offer the system share/save sheet — the
 *  same path FileDownloadDialog uses (covers "Save to Files", gallery, sharing).
 *  Decode + write happen off the main thread. */
private fun saveImage(ctx: Context, scope: CoroutineScope, image: TtyBlock.Image) {
    scope.launch {
        val uri = withContext(Dispatchers.IO) {
            try {
                val dir = File(ctx.cacheDir, "shared").apply { mkdirs() }
                val ext = when (image.mimeType) {
                    "image/jpeg" -> "jpg"
                    "image/gif" -> "gif"
                    "image/webp" -> "webp"
                    else -> "png"
                }
                val out = File(dir, "pi-image.$ext")
                out.writeBytes(Base64.decode(image.base64, Base64.DEFAULT))
                FileProvider.getUriForFile(ctx, ctx.packageName + ".updates", out)
            } catch (_: Exception) {
                null
            }
        }
        if (uri != null) {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = image.mimeType.ifBlank { "image/png" }
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try { ctx.startActivity(Intent.createChooser(send, "Save / Share image")) } catch (_: Exception) {}
        }
    }
}
