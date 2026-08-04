package com.piremote.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.piremote.*
import com.piremote.theme.*

// ── Shared connect panel (phone + tablet) ──────────────────────────────

/**
 * The connect UI shared by [ConnectScreen] (phone) and [TabletConnectScreen]
 * (tablet right pane): header + Options menu + URL input + recents + Connect
 * button + connection status + Quick Start box. Only the subtitle, the scan
 * entry point, and the content padding differ per form factor.
 *
 * The URL text is hoisted ([urlText]/[onUrlTextChange]) so each screen can
 * also feed it to its QR-scanner path.
 */
@Composable
fun ConnectPanel(
    vm: ChatViewModel,
    urlText: String,
    onUrlTextChange: (String) -> Unit,
    status: ConnectionStatus,
    urlHistory: Set<String>,
    subtitle: String,
    onScanRequest: () -> Unit,
    contentPadding: Dp = 4.dp,
) {
    var inputMode by remember { mutableStateOf(false) }
    val connect = {
        val u = urlText.ifEmpty { "" }
        vm.setServerUrl(u); vm.connect()
    }

    // Header bar
    PiBox(header = "Pi Remote", borderColor = accent) {
        Column(modifier = Modifier.padding(vertical = 6.dp, horizontal = contentPadding)) {
            Text(
                "Pi Remote Control — Terminal Mode",
                color = accent, fontFamily = piMono, fontSize = 13.sp, fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                subtitle,
                color = textMuted, fontFamily = piMono, fontSize = 11.sp
            )
        }
    }
    Spacer(Modifier.height(12.dp))

    // Menu
    PiBox(header = "Options") {
        Column(modifier = Modifier.padding(vertical = 4.dp, horizontal = contentPadding)) {
            PiMenuItem(label = "1", title = "Connect to Pi server", action = { inputMode = true })
            PiMenuItem(label = "2", title = "Scan QR code", action = { onScanRequest() })
            PiMenuItem(
                label = "3",
                title = if (urlHistory.isEmpty()) "Recent connections (none)" else "Recent connections",
                enabled = urlHistory.isNotEmpty(),
                action = { inputMode = true }
            )

            // URL input area
            if (inputMode) {
                Spacer(Modifier.height(8.dp))
                PiBox(header = "URL", borderColor = accent) {
                    Column(modifier = Modifier.padding(vertical = 4.dp, horizontal = contentPadding)) {
                        TextField(
                            value = urlText,
                            onValueChange = { onUrlTextChange(it) },
                            placeholder = { Text("ws://<IP>:<port>", color = textMuted, fontFamily = piMono) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = bg,
                                unfocusedContainerColor = bg,
                                focusedIndicatorColor = accent,
                                unfocusedIndicatorColor = border,
                                focusedTextColor = textPrimary,
                                unfocusedTextColor = textPrimary,
                                cursorColor = accent
                            ),
                            textStyle = LocalTextStyle.current.copy(color = textPrimary, fontFamily = piMono, fontSize = 12.sp),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(onGo = { connect() })
                        )

                        // Recent connections
                        if (urlHistory.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            Text("Recent:", color = textMuted, fontFamily = piMono, fontSize = 10.sp)
                            Spacer(Modifier.height(3.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                                urlHistory.take(5).forEach { histUrl ->
                                    PiTerminalChip(histUrl, onClick = { onUrlTextChange(histUrl) })
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                // Connect button — disabled (dimmed) until a URL is entered
                val canConnect = urlText.isNotBlank()
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .border(1.dp, if (canConnect) accent else borderMuted, RoundedCornerShape(0.dp))
                        .clickable(enabled = canConnect, role = Role.Button, onClickLabel = "connect to Pi server") { connect() }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Connect", color = if (canConnect) accent else textMuted, fontFamily = piMono, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }

            // Connection status
            Spacer(Modifier.height(10.dp))
            when {
                status is ConnectionStatus.Connecting -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(color = accent, modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
                        Text("Connecting...", color = accent, fontFamily = piMono, fontSize = 11.sp)
                    }
                }
                status is ConnectionStatus.Error -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text("✕ ${status.message}", color = error, fontFamily = piMono, fontSize = 11.sp, modifier = Modifier.weight(1f))
                        Box(
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .border(1.dp, error, RoundedCornerShape(0.dp))
                                .clickable(role = Role.Button, onClickLabel = "retry connection") { vm.connect() }
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text("Retry", color = error, fontFamily = piMono, fontSize = 10.sp)
                        }
                    }
                }
                status is ConnectionStatus.Connected -> {
                    Text("● Connected", color = success, fontFamily = piMono, fontSize = 11.sp)
                }
            }
        }
    }

    Spacer(Modifier.height(12.dp))

    // Quick Start Guide
    PiBox(header = "Quick Start", borderColor = borderMuted) {
        Column(modifier = Modifier.padding(vertical = 4.dp, horizontal = contentPadding)) {
            Text("1  pi install git:github.com/kolt-mcb/pocket-pi", color = textSecondary, fontFamily = piMono, fontSize = 10.sp)
            Text("2  Run:  pi   (extension auto-loads; QR + URL print on startup)", color = textSecondary, fontFamily = piMono, fontSize = 10.sp)
            Text("3  Scan the QR or paste the ws://…?token=…  URL above", color = textSecondary, fontFamily = piMono, fontSize = 10.sp)
        }
    }
}

// ── Shared saved-session row (phone list + tablet sidebar) ─────────────

/**
 * Row in a saved-sessions list. Tap to spawn `pi --session <path>` as a peer.
 * Used by the phone [SessionsScreen] and (with [compact] = true) the tablet
 * sidebar — same content everywhere: status badge + last-assistant-message
 * preview + message-count/time meta line.
 */
@Composable
fun SavedSessionRow(s: SavedSession, compact: Boolean = false, onTap: () -> Unit) {
    val label = s.name.ifBlank { s.firstMessage }.ifBlank { s.path.substringAfterLast('/') }
    val trimmed = sessionLabel(label, if (compact) 25 else 40)
    val whenStr = sessionTime(s.modified)

    // Status badge color + icon
    val statusColor = when (s.status) {
        "working" -> thinkingBorder
        "archived" -> textMuted
        else -> success
    }
    val statusLabel = when (s.status) {
        "working" -> "working"
        "archived" -> "archived"
        else -> "completed"
    }
    val statusIcon = when (s.status) {
        "working" -> "◉"
        "archived" -> "⊘"
        else -> "✓"
    }

    Row(
        modifier = Modifier.fillMaxWidth()
            .minimumInteractiveComponentSize()
            .clickable(role = Role.Button, onClickLabel = "resume session as a new tab") { onTap() }
            .padding(
                horizontal = if (compact) 10.dp else 6.dp,
                vertical = if (compact) 3.dp else 4.dp
            ),
        verticalAlignment = Alignment.Top
    ) {
        Text("▸ ", color = accent, fontFamily = piMono, fontSize = 11.sp)
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 6.dp)) {
                Text(
                    trimmed, color = textPrimary, fontFamily = piMono,
                    fontSize = if (compact) 10.sp else 12.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                // Status badge — only show non-default (non-completed) status
                if (s.status != "completed") {
                    Box(
                        modifier = Modifier
                            .border(0.5.dp, statusColor, RoundedCornerShape(0.dp))
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    ) {
                        Text(
                            "$statusIcon $statusLabel",
                            color = statusColor, fontFamily = piMono, fontSize = 10.sp
                        )
                    }
                }
            }
            // Model's last reply preview — shown when available so you can see
            // what the agent is waiting on without opening the session.
            if (s.lastAssistantMessage.isNotBlank()) {
                val preview = s.lastAssistantMessage.trim().take(140) +
                    if (s.lastAssistantMessage.length > 140) "…" else ""
                Text(
                    "π ${preview}",
                    color = thinkingLow,
                    fontFamily = piMono, fontSize = 10.sp,
                    fontStyle = FontStyle.Italic,
                    maxLines = if (compact) 1 else 2
                )
            }
            Text("${s.messageCount} msg${if (s.messageCount != 1) "s" else ""} · $whenStr",
                color = textMuted, fontFamily = piMono, fontSize = 10.sp, maxLines = 1)
        }
    }
}

// ── Shared category filter tab row (phone list + tablet sidebar) ───────

/**
 * Saved-session category filter tabs — one composable for both form factors
 * so labels/counts render identically. Compact visuals (small bordered chip)
 * with a full ≥48dp hit area per tab.
 */
@Composable
fun SessionCategoryTabs(
    selected: String,
    counts: Map<String, Int>,
    totalCount: Int,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Scrollable: four min-48dp tabs with double-digit counts overflow narrow
    // containers (phone portrait, 300dp tablet sidebar) — scroll, don't clip.
    Row(
        modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val categories = listOf(
            "all" to "All",
            "working" to "Working",
            "completed" to "Completed",
            "archived" to "Archived"
        )
        categories.forEach { (key, label) ->
            val isActive = key == selected
            val count = if (key == "all") totalCount else (counts[key] ?: 0)
            val color = if (isActive) accent else textMuted
            val bgC = if (isActive) selectedBg else Color.Transparent
            Box(
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .clickable(role = Role.Button, onClickLabel = "show $label sessions") { onSelect(key) },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .background(bgC)
                        .border(1.dp, if (isActive) accent else borderMuted, RoundedCornerShape(0.dp))
                        .padding(horizontal = 7.dp, vertical = 3.dp)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(label, color = color, fontFamily = piMono, fontSize = 10.sp,
                            maxLines = 1, softWrap = false,
                            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal)
                        Text("($count)", color = color, fontFamily = piMono, fontSize = 10.sp,
                            maxLines = 1, softWrap = false)
                    }
                }
            }
        }
    }
}
