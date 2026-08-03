package com.piremote.screens

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.piremote.*
import com.piremote.R
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.Role
import com.piremote.theme.*
import com.piremote.tty.parseAnsiLine
import com.piremote.tty.parseEdits
import com.piremote.tty.parseToolArgs
import com.piremote.tty.parseWriteContent
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ── Pi Terminal Font Family ────────────────────────────────────────────

// DejaVu Sans Mono: a real terminal font whose box-drawing glyphs (─ │ ┌ ┐ etc.)
// span the full cell, so horizontal rules render solid instead of dashed — the
// default Android FontFamily.Monospace leaves side gaps that break long lines.
val piMono = FontFamily(
    Font(R.font.dejavu_sans_mono, FontWeight.Normal),
    Font(R.font.dejavu_sans_mono_bold, FontWeight.Bold),
)

// ── Pi Terminal Border Helpers ─────────────────────────────────────────

/** Draw a single-line terminal-style top border: ┌─ title ──────┐ */
@Composable
fun PiTopBorder(label: String? = null, color: Color = border) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text("┌", color = color, fontFamily = piMono, fontSize = 11.sp)
        if (!label.isNullOrEmpty()) {
            Text("─ $label ──", color = color, fontFamily = piMono, fontSize = 11.sp)
        }
        Spacer(Modifier.weight(1f))
        Text("┐", color = color, fontFamily = piMono, fontSize = 11.sp)
    }
}

/** Draw a single-line terminal-style bottom border: └──────────┘ */
@Composable
fun PiBottomBorder(color: Color = border) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text("└", color = color, fontFamily = piMono, fontSize = 11.sp)
        Spacer(Modifier.weight(1f))
        Text("┘", color = color, fontFamily = piMono, fontSize = 11.sp)
    }
}

/** Left gutter with sidebar: │ content */
@Composable
fun PiGutter(
    color: Color = border,
    content: @Composable ColumnScope.() -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("│", color = color, fontFamily = piMono, fontSize = 11.sp)
        Column(modifier = Modifier.weight(1f)) { content() }
    }
}

/** Full bordered box: ┌─ header ─┐ / │ body │ / └──────┘ */
@Composable
fun PiBox(
    header: String? = null,
    borderColor: Color = border,
    content: @Composable ColumnScope.() -> Unit
) {
    val showBorder = !header.isNullOrEmpty() || borderColor != borderMuted
    Column {
        if (showBorder) {
            PiTopBorder(header, borderColor)
        }
        PiGutter(borderColor, content)
        if (showBorder) {
            PiBottomBorder(borderColor)
        }
    }
}

// ── Rounded box variant (Claude Code style: ╭─╮│╰─╯) ───────────────────

@Composable
fun PiRoundedTopBorder(label: String? = null, color: Color = border) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text("╭", color = color, fontFamily = piMono, fontSize = 11.sp)
        if (!label.isNullOrEmpty()) {
            Text("─ $label ──", color = color, fontFamily = piMono, fontSize = 11.sp)
        }
        Spacer(Modifier.weight(1f))
        Text("╮", color = color, fontFamily = piMono, fontSize = 11.sp)
    }
}

@Composable
fun PiRoundedBottomBorder(color: Color = border) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text("╰", color = color, fontFamily = piMono, fontSize = 11.sp)
        Spacer(Modifier.weight(1f))
        Text("╯", color = color, fontFamily = piMono, fontSize = 11.sp)
    }
}

/** Rounded-corner bordered box: ╭─ header ─╮ / │ body │ / ╰──────╯ */
@Composable
fun PiRoundedBox(
    header: String? = null,
    borderColor: Color = border,
    content: @Composable ColumnScope.() -> Unit
) {
    Column {
        PiRoundedTopBorder(header, borderColor)
        PiGutter(borderColor, content)
        PiRoundedBottomBorder(borderColor)
    }
}

// ── Pi-Style Connect Screen (Menu) ─────────────────────────────────────

@Composable
fun ConnectScreen(
    vm: ChatViewModel, url: String, input: String, messages: List<ChatMessage>,
    assist: String, status: ConnectionStatus, urlHistory: Set<String>,
    sessions: List<RemoteSession> = emptyList()
) {
    // Key on url so the field populates once DataStore loads the saved URL
    // (which arrives asynchronously, after this screen first composes). Typing
    // only mutates `t`, never `url`, so this never clobbers user input.
    var t by remember(url) { mutableStateOf(url) }
    var showScanner by remember { mutableStateOf(false) }

    if (showScanner) {
        com.piremote.scan.QrScanner(
            initialUrl = t,
            onConnected = { scanned: String ->
                val wsUrl = if (scanned.startsWith("piremote://")) scanned.replaceFirst("piremote://", "ws://") else scanned
                t = wsUrl
                vm.setServerUrl(wsUrl); vm.connect(); showScanner = false
            },
            onClose = { showScanner = false }
        )
        return
    }

    Box(modifier = Modifier.fillMaxSize().background(bg)) {
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            // Header + Options menu + URL input + Connect + status + Quick
            // Start — shared with the tablet connect screen (ConnectPanel.kt).
            ConnectPanel(
                vm = vm,
                urlText = t,
                onUrlTextChange = { t = it },
                status = status,
                urlHistory = urlHistory,
                subtitle = "Control your Pi agent from your phone",
                onScanRequest = { showScanner = true },
                contentPadding = 4.dp,
            )

            // Fixed spacer: weight() has no effect inside a scrollable column.
            Spacer(Modifier.height(48.dp))
        }

        // Tappable version display in bottom-right doubles as the update
        // entry point. Tap → fetches GitHub Releases → install prompt if a
        // newer build exists. The component owns its own dialog state.
        UpdateAffordance(modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp))
    }
}

/**
 * Bottom-corner version chip + update flow. Shows "vN · sha", tap to check
 * GitHub Releases for a newer build. On hit, prompts to install; on download
 * tap, fires the OS package installer with the new APK.
 */
@Composable
fun UpdateAffordance(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val updater = remember { UpdateChecker(ctx.applicationContext) }
    val currentCode = remember { updater.currentVersionCode() }
    val currentName = remember { updater.currentVersionName() }
    val scope = rememberCoroutineScope()

    var checking by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var latest by remember { mutableStateOf<LatestRelease?>(null) }
    var downloading by remember { mutableStateOf(false) }
    var downloadPct by remember { mutableStateOf(0) }

    // Brief "up to date" / error message auto-clears after a couple seconds.
    LaunchedEffect(status) {
        if (status != null) {
            kotlinx.coroutines.delay(2500); status = null
        }
    }

    Column(horizontalAlignment = Alignment.End, modifier = modifier) {
        // Bordered chip (PiTerminalChip-style) so the update entry point reads
        // as tappable instead of a stray version string.
        Text(
            text = "v$currentCode · $currentName · updates",
            color = textMuted,
            fontFamily = piMono, fontSize = 10.sp,
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .border(0.5.dp, borderMuted, RoundedCornerShape(0.dp))
                .clickable(
                    enabled = !checking && !downloading,
                    role = Role.Button,
                    onClickLabel = "check for updates"
                ) {
                    checking = true
                    scope.launch {
                        val r = updater.fetchLatest()
                        checking = false
                        if (r == null) {
                            status = "couldn't reach updates"
                        } else if (r.versionCode <= currentCode) {
                            status = "up to date"
                        } else {
                            latest = r
                        }
                    }
                }
                .padding(horizontal = 5.dp, vertical = 2.dp)
        )
        if (checking) {
            Text("checking…", color = textMuted, fontFamily = piMono, fontSize = 10.sp)
        } else if (status != null) {
            Text(status!!, color = textMuted, fontFamily = piMono, fontSize = 10.sp)
        }
    }

    // "Update available" dialog. Confirms before downloading 40MB.
    latest?.let { r ->
        if (!downloading) {
            AlertDialog(
                onDismissRequest = { latest = null },
                title = { Text("Update available", fontFamily = piMono, fontSize = 14.sp) },
                text = {
                    Column {
                        Text("v$currentCode → v${r.versionCode}", fontFamily = piMono, fontSize = 12.sp)
                        Spacer(Modifier.height(2.dp))
                        Text(r.versionName, color = textMuted, fontFamily = piMono, fontSize = 10.sp)
                        if (r.publishedAt.isNotBlank()) {
                            Text("published ${r.publishedAt.take(10)}", color = textMuted, fontFamily = piMono, fontSize = 10.sp)
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        downloading = true
                        downloadPct = 0
                        scope.launch {
                            val file = updater.downloadApk(r) { pct -> downloadPct = pct }
                            downloading = false
                            latest = null
                            if (file != null) {
                                updater.launchInstaller(file)
                            } else {
                                status = "download failed"
                            }
                        }
                    }) { Text("Install") }
                },
                dismissButton = {
                    TextButton(onClick = { latest = null }) {
                        Text("Later")
                    }
                }
            )
        }
    }

    // Download progress dialog.
    if (downloading) {
        AlertDialog(
            onDismissRequest = { /* not dismissible mid-download */ },
            title = { Text("Downloading", fontFamily = piMono, fontSize = 14.sp) },
            text = {
                Column {
                    LinearProgressIndicator(
                        progress = { downloadPct / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(6.dp))
                    Text("$downloadPct%", color = textMuted, fontFamily = piMono, fontSize = 11.sp)
                }
            },
            confirmButton = {},
        )
    }
}

/** Terminal-style menu item */
@Composable
fun PiMenuItem(label: String, title: String, enabled: Boolean = true, action: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .minimumInteractiveComponentSize()
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = title) { action() }
            .padding(vertical = 3.dp, horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("  ", color = textMuted, fontFamily = piMono, fontSize = 11.sp)
        Text("[${label}]", color = textMuted, fontFamily = piMono, fontSize = 11.sp)
        Text(title, color = if (enabled) textSecondary else textMuted, fontFamily = piMono, fontSize = 12.sp)
    }
}

/** Terminal-style chip for URLs */
@Composable
fun PiTerminalChip(label: String, onClick: () -> Unit) {
    val short = label.take(22) + if (label.length > 22) "…" else ""
    Text(
        short,
        color = accent, fontFamily = piMono, fontSize = 10.sp,
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .clickable(role = Role.Button, onClickLabel = label) { onClick() }
            .border(0.5.dp, borderAccent, RoundedCornerShape(0.dp))
            .padding(horizontal = 5.dp, vertical = 2.dp)
    )
}

// ── Pi Terminal Sessions Screen ────────────────────────────────────────

@Composable
fun SessionsScreen(
    vm: ChatViewModel,
    sessions: List<RemoteSession>,
    selectedSession: String,
    compacting: Boolean = false,
    clientCount: Int = 0,
    savedSessions: List<SavedSession> = emptyList()
) {
    val activeSession = sessions.find { it.id == selectedSession }
    var selectedCategory by remember { mutableStateOf("all") }

    // Refresh the saved-session list once when this screen is opened. The
    // response repopulates savedSessionsFlow → savedSessions param.
    // Uses key = sessions.size so it fires on first entry AND when the
    // session list changes (meaning a new peer may have joined/left).
    LaunchedEffect(sessions.size) {
        vm.refreshSavedSessions()
    }

    // Filter saved sessions by selected category
    val filteredSessions = if (selectedCategory == "all") {
        savedSessions
    } else {
        savedSessions.filter { it.status == selectedCategory }
    }

    // Count per category for tab badges
    val counts = savedSessions.groupingBy { it.status }.eachCount()
    val totalCount = savedSessions.size

    // Folder picker state — same flow as the tablet sidebar (TabletLayout):
    // browse host dirs, optionally mkdir, then spawn the peer in the chosen dir.
    var showFolderPicker by remember { mutableStateOf(false) }
    var dirCreating by remember { mutableStateOf(false) }
    var dirCreateError by remember { mutableStateOf<String?>(null) }
    var mkdirRefreshToken by remember { mutableStateOf(0L) }
    val dirsResp by vm.st.hostDirs.collectAsState()

    // On mkdir result: refresh the current listing on success, surface the error on failure
    LaunchedEffect(Unit) {
        vm.st.mkdirResult.collect { r ->
            dirCreating = false
            if (r.success) {
                dirCreateError = null
                mkdirRefreshToken++
                vm.browseHostDirs(dirsResp?.basePath ?: "")
            } else {
                dirCreateError = r.errorMessage ?: "Could not create folder"
            }
        }
    }

    if (showFolderPicker) {
        FolderPickerDialog(
            dirs = dirsResp?.dirs,
            currentDir = dirsResp?.basePath ?: "",
            onNavigate = { path ->
                dirCreateError = null
                vm.browseHostDirs(path)
            },
            onSelect = { path ->
                vm.spawnPeer(path)
                showFolderPicker = false
            },
            onDismiss = { showFolderPicker = false },
            onCreateFolder = { dirPath, name ->
                dirCreating = true
                dirCreateError = null
                vm.createFolder(dirPath, name)
            },
            creating = dirCreating,
            createError = dirCreateError,
            dirRefresh = DirRefresh(mkdirRefreshToken)
        )
    }

    // One LazyColumn for the whole screen. The previous nested-list layout
    // (bounded active list + bounded saved list + weighted spacers) measured
    // the unweighted Saved box first, so a full saved list starved the active
    // list down to one clipped card while the screen showed dead space.
    Box(modifier = Modifier.fillMaxSize().background(bg)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            contentPadding = PaddingValues(bottom = 12.dp),
        ) {
            // Header
            item(key = "header") {
                PiBox(header = "Sessions", borderColor = accent) {
                    Column(modifier = Modifier.padding(vertical = 6.dp, horizontal = 4.dp)) {
                        Text(
                            "Active Sessions — Tap to switch", color = accent,
                            fontFamily = piMono, fontSize = 13.sp, fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "${sessions.size} agent${if (sessions.size != 1) "s" else ""} connected  •  ${clientCount} viewer${if (clientCount != 1) "s" else ""}",
                            color = textMuted, fontFamily = piMono, fontSize = 11.sp
                        )
                    }
                }
            }

            // [+ New session] — spawns a separate pi process on the host
            // (peer mode) instead of calling /new on the current pi. The old
            // /new path went through ctx.newSession(), which permanently
            // invalidates the extension's runtime — every subsequent slash
            // command then failed until pi was restarted. Spawning a new
            // process sidesteps that entirely: each pi has its own
            // extension lifecycle, and the new one joins this host as a
            // peer agent → shows up as a separate tab.
            item(key = "new-session") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End)
                ) {
                    // Spawn in a chosen host directory (opens the folder picker).
                    Box(
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .border(0.5.dp, borderMuted, RoundedCornerShape(0.dp))
                            .clickable(enabled = sessions.isNotEmpty(), role = Role.Button, onClickLabel = "start new session in a chosen folder") {
                                showFolderPicker = true
                                dirCreating = false
                                dirCreateError = null
                                vm.browseHostDirs("")
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            "[+ in folder…]",
                            color = if (sessions.isNotEmpty()) textPrimary else textMuted,
                            fontFamily = piMono, fontSize = 11.sp, fontWeight = FontWeight.Medium
                        )
                    }
                    Box(
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .border(0.5.dp, accent, RoundedCornerShape(0.dp))
                            .clickable(enabled = sessions.isNotEmpty(), role = Role.Button, onClickLabel = "start new session") {
                                vm.spawnPeer()
                                // Don't navigate to chat yet — the peer takes a
                                // moment to boot and join. The notify banner
                                // ("Launching a new pi peer…") will appear; the
                                // user can stay on the Sessions screen to watch
                                // the new tab appear in the pill row.
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            "[+ New session]",
                            color = if (sessions.isNotEmpty()) accent else textMuted,
                            fontFamily = piMono, fontSize = 11.sp, fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // Active sessions
            if (sessions.isEmpty()) {
                item(key = "no-sessions") {
                    PiBox(borderColor = borderMuted) {
                        Column(modifier = Modifier.padding(vertical = 12.dp, horizontal = 10.dp)) {
                            Text("No sessions available", color = textMuted, fontFamily = piMono, fontSize = 12.sp)
                            Spacer(Modifier.height(4.dp))
                            Text("Sessions will appear when agents connect", color = textMuted, fontFamily = piMono, fontSize = 10.sp)
                        }
                    }
                }
            } else {
                items(sessions, key = { it.id }) { session ->
                    val isSelected = session.id == selectedSession
                    val isBusy = session.status == "busy"
                    Box(modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)) {
                        SessionCard(vm, session, isSelected, isBusy) { vm.setSelectedSession(session.id); vm.showChatScreen() }
                    }
                }
            }

            item(key = "saved-gap") { Spacer(Modifier.height(8.dp)) }

            // ── Saved sessions ────────────────────────────────────────
            // Tap any row to spawn `pi --session <path>` as a peer. The
            // resumed pi joins as a new agent, surfaces as a new tab.
            // (Direct ctx.switchSession can't be used from an extension —
            // see PR #20 / commit message.) The bordered box is drawn as
            // separate top/gutter/bottom items so its rows lazy-load inside
            // the single screen list.
            item(key = "saved-top") { PiTopBorder("Saved sessions", borderMuted) }
            item(key = "saved-tabs") {
                PiGutter(borderMuted) {
                    Column(modifier = Modifier.padding(vertical = 4.dp, horizontal = 4.dp)) {
                        SessionCategoryTabs(
                            selected = selectedCategory,
                            counts = counts,
                            totalCount = totalCount,
                            onSelect = { selectedCategory = it },
                        )
                        Spacer(Modifier.height(4.dp))
                        if (filteredSessions.isEmpty()) {
                            Text(
                                if (totalCount == 0) "No saved sessions yet."
                                else "No $selectedCategory sessions.",
                                color = textMuted, fontFamily = piMono, fontSize = 11.sp,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp)
                            )
                        } else {
                            Text(
                                "${filteredSessions.size} session${if (filteredSessions.size != 1) "s" else ""} · tap to resume as a new tab",
                                color = textMuted, fontFamily = piMono, fontSize = 10.sp,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }
            items(filteredSessions, key = { it.path }) { saved ->
                PiGutter(borderMuted) {
                    SavedSessionRow(saved) {
                        vm.spawnPeerWithSession(saved.path)
                    }
                }
            }
            item(key = "saved-bottom") { PiBottomBorder(borderMuted) }

            item(key = "hint-gap") { Spacer(Modifier.height(8.dp)) }

            // Nav hint
            item(key = "nav-hint") {
                PiBox(borderColor = borderMuted) {
                    Column(modifier = Modifier.padding(vertical = 6.dp, horizontal = 10.dp)) {
                        Text("▸ Back: disconnect", color = textMuted, fontFamily = piMono, fontSize = 10.sp)
                        Text("▸ Long-press peer: close session", color = textMuted, fontFamily = piMono, fontSize = 10.sp)
                    }
                }
            }
        }
    }
}

/** Card for a single session in the sessions list */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SessionCard(
    vm: ChatViewModel,
    session: RemoteSession,
    isSelected: Boolean,
    isBusy: Boolean,
    onClick: () -> Unit
) {
    var showCloseConfirm by remember { mutableStateOf(false) }

    PiBox(
        header = sessionLabel(session.name, 16),
        borderColor = if (isSelected) accent else if (isBusy) thinkingBorder.copy(alpha = 0.7f) else borderMuted
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .combinedClickable(
                    role = Role.Button,
                    onClickLabel = "switch to this session",
                    onClick = { onClick() },
                    onLongClick = {
                        // Don't allow closing the host (self) session
                        if (session.kind != "self" && !isBusy) {
                            showCloseConfirm = true
                        }
                    }
                )
                .padding(vertical = 6.dp, horizontal = 8.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                // Status dot
                Box(modifier = Modifier.size(7.dp).background(
                    when {
                        isBusy -> thinkingBorder
                        isSelected -> accent
                        else -> success
                    },
                    CircleShape
                ))

                // Name and kind
                Column(modifier = Modifier.weight(1f)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        when {
                            isBusy -> Text("◉ busy", color = thinkingBorder, fontFamily = piMono, fontSize = 10.sp, fontStyle = FontStyle.Italic, fontWeight = FontWeight.SemiBold)
                            isSelected -> Text("● active", color = accent, fontFamily = piMono, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            else -> Text("○ idle", color = success, fontFamily = piMono, fontSize = 10.sp)
                        }
                        if (session.kind == "self") {
                            Text("(host)", color = textMuted, fontFamily = piMono, fontSize = 10.sp)
                        } else {
                            Text("(peer)", color = textMuted, fontFamily = piMono, fontSize = 10.sp)
                        }
                    }
                }

                // Status label
                when {
                    isBusy -> Text("◉ working", color = thinkingMedium, fontFamily = piMono, fontSize = 10.sp)
                    isSelected -> Text("✓", color = accent, fontFamily = piMono, fontSize = 12.sp)
                }
            }

            Spacer(Modifier.height(6.dp))

            // Stats row
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${session.messageCount} msgs", color = textMuted, fontFamily = piMono, fontSize = 10.sp)
                Text("turn ${session.turnIndex}", color = textMuted, fontFamily = piMono, fontSize = 10.sp)
                Spacer(Modifier.weight(1f))
                Text(sessionTime(session.lastActivity), color = textMuted, fontFamily = piMono, fontSize = 10.sp)
            }

            if (isBusy) {
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(8.dp), color = thinkingBorder, strokeWidth = 1.5.dp)
                    Text("agent is processing...", color = thinkingBorder, fontFamily = piMono, fontSize = 10.sp, fontStyle = FontStyle.Italic)
                }
            }
        }
    }

    // Close confirmation dialog
    if (showCloseConfirm) {
        AlertDialog(
            onDismissRequest = { showCloseConfirm = false },
            title = { Text("Close session", fontFamily = piMono, fontSize = 14.sp) },
            text = {
                Text(
                    "This will quit peer agent:\n${sessionLabel(session.name, 30)}\n\nThis cannot be undone.",
                    fontFamily = piMono, fontSize = 12.sp
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { vm.closeSession(session.id); showCloseConfirm = false }
                ) {
                    Text("Close", color = error, fontFamily = piMono)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCloseConfirm = false }) {
                    Text("Cancel", color = textMuted, fontFamily = piMono)
                }
            }
        )
    }
}

// ── Pi Terminal Header ─────────────────────────────────────────────────

@Composable
fun PiHeader(status: ConnectionStatus, busy: Boolean, disconnect: () -> Unit, title: String? = null) {
    Column(modifier = Modifier.fillMaxWidth().background(bgSecondary)) {
        HorizontalDivider(color = border, thickness = 1.dp)
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(7.dp).background(
                    when (status) {
                        is ConnectionStatus.Connected -> if (busy) thinkingBorder else success
                        is ConnectionStatus.Connecting -> accent
                        is ConnectionStatus.Error -> error
                        else -> textMuted
                    },
                    CircleShape
                ))
                Text(
                    title?.take(30) ?: "Pi Remote",
                    color = accent, fontFamily = piMono, fontSize = 12.sp, fontWeight = FontWeight.Bold
                )
                if (busy) Text("(◉ thinking)", color = thinkingBorder, fontFamily = piMono, fontSize = 10.sp, fontStyle = FontStyle.Italic)
            }
            Box(
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .border(1.dp, error, RoundedCornerShape(0.dp))
                    .clickable(role = Role.Button, onClickLabel = "disconnect from host") { disconnect() }
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text("[Disconnect]", color = error, fontFamily = piMono, fontSize = 10.sp)
            }
        }
    }
}

// ── Pi Terminal Session Selector ───────────────────────────────────────

@Composable
fun PiSessionSelector(sessions: List<RemoteSession>, selected: String, onSelect: (String) -> Unit) {
    if (sessions.size < 2) {
        sessions.firstOrNull()?.let { sess ->
            val isActive = sess.status == "busy"
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp).background(bgSecondary),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.size(5.dp).background(if (isActive) thinkingBorder else success, CircleShape))
                // Session names derive from the first user message on the host —
                // can be a multi-line paste. One ellipsized line, newlines stripped.
                Text(
                    sessionLabel(sess.name, 60),
                    color = textPrimary, fontFamily = piMono, fontSize = 11.sp,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(3f, fill = false)
                )
                Text(if (isActive) "◉ busy" else "○ idle",
                    color = if (isActive) thinkingBorder else textMuted, fontFamily = piMono, fontSize = 10.sp)
                Spacer(Modifier.weight(1f))
                Text("${sess.messageCount} msgs • ${sessionTime(sess.lastActivity)}",
                    color = textMuted, fontFamily = piMono, fontSize = 10.sp)
            }
        }
        return
    }
    
    PiBox(header = "Sessions", borderColor = borderMuted) {
        // Scrollable: with many sessions the min-48dp tabs overflow the screen;
        // without scroll the last tab collapsed to a one-column sliver whose
        // label wrapped vertically and stretched the whole box.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            sessions.forEachIndexed { i, sess ->
                val isSelected = sess.id == selected
                val isActive = sess.status == "busy"
                
                Box(
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .clickable(role = Role.Button, onClickLabel = "switch to ${sessionLabel(sess.name, 10)}") { onSelect(sess.id) }
                        .background(if (isSelected) selectedBg else Color.Transparent)
                        .border(1.dp, if (isSelected) accent else borderMuted, RoundedCornerShape(0.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(5.dp).background(if (isActive) thinkingBorder else success, CircleShape))
                        Text(
                            sessionLabel(sess.name, 10),
                            color = if (isSelected) textPrimary else textSecondary,
                            fontFamily = piMono, fontSize = 10.sp,
                            maxLines = 1, softWrap = false,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }
        }
    }
}

// ── Pi Terminal Chat Screen ────────────────────────────────────────────

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ChatScreen(
    vm: ChatViewModel,
    status: ConnectionStatus, busy: Boolean,
    sessions: List<RemoteSession> = emptyList(),
    selectedSession: String = "",
    notifyBanners: List<BannerMessage> = emptyList(),
    uiTitle: String? = null,
    clientCount: Int = 0,
    showSessionSelector: Boolean = true,
) {
        // Mirror state computed up front: when a live frame is showing, the
        // mirror already renders pi's FULL screen (its loader, status line,
        // widgets, footer), so we suppress the app's duplicate chrome below to
        // avoid a second "Working..." spinner and stray separators.
        val mirrorFrame by vm.mirrorFrame.collectAsState()
        val selfId = sessions.firstOrNull { it.isSelf }?.id
        val chosen = sessions.firstOrNull { it.id == selectedSession }
            ?: sessions.firstOrNull { it.isSelf }
            ?: sessions.firstOrNull()
        // The host's OWN session subscribes with a blank agentId so it always
        // takes the self/clamp path; peers subscribe by their id.
        val mirrorTarget = if (chosen == null || chosen.isSelf) "" else chosen.id
        // Subscribe to the mirror only while this screen is in the FOREGROUND.
        // When the app is backgrounded the host keeps pumping full-screen frames
        // over the (metered, slow) link to a view nobody's looking at — so unsubscribe
        // on ON_STOP and re-subscribe on ON_START (the host sends an immediate
        // snapshot on subscribe, so the view refreshes at once on return).
        val lifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(mirrorTarget, lifecycleOwner) {
            // Subscribe immediately on (re)compose — we're in the foreground here.
            // Relying on the observer's ON_START alone failed to fire on the initial
            // composition, so the mirror never subscribed until a tab switch.
            vm.setMirror(true, mirrorTarget)
            // Then track foreground/background to pause the stream while backgrounded.
            var started = true   // we just subscribed above; skip the add-time ON_START
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> if (!started) { vm.setMirror(true, mirrorTarget); started = true }
                    Lifecycle.Event.ON_STOP -> { vm.setMirror(false, mirrorTarget); started = false }
                    else -> {}
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
                vm.setMirror(false, mirrorTarget)
            }
        }
        val liveFrame = mirrorFrame?.takeIf {
            if (mirrorTarget.isBlank()) (selfId == null || it.agentId == selfId) else it.agentId == mirrorTarget
        }

        Column(modifier = Modifier.fillMaxSize().background(bg).imePadding()) {
        PiHeader(status, busy, { vm.disconnect() }, uiTitle?.take(30))

        // Host notify() messages and dropped-send warnings.
        if (notifyBanners.isNotEmpty()) {
            Column(modifier = Modifier.fillMaxWidth()) {
                notifyBanners.takeLast(3).forEach { b -> NotifyBanner(b.content, b.type) }
            }
        }

        if (showSessionSelector && sessions.isNotEmpty()) {
            PiSessionSelector(
                sessions,
                if (selectedSession.isNotBlank()) selectedSession else sessions.firstOrNull()?.id ?: "",
                { vm.setSelectedSession(it) }
            )
        }

        // Shared keystroke-capture focus: tapping the mirror raises the soft
        // keyboard by focusing the (invisible) capture field in the bar below.
        val ttyFocus = remember { FocusRequester() }
        val keyboardController = LocalSoftwareKeyboardController.current
        val openKeyboard: () -> Unit = {
            // requestFocus() shows the IME when unfocused; show() re-raises it if
            // the field is already focused but the keyboard was dismissed.
            try { ttyFocus.requestFocus() } catch (_: Exception) {}
            keyboardController?.show()
        }

        // The mirror IS the session view — it renders pi's whole screen (status,
        // widgets, loader, footer). A neutral placeholder shows until the first
        // frame arrives (no UI flash).
        BoxWithConstraints(modifier = Modifier.weight(1f)) {
            // Measured monospace metrics feed the host so it renders at our width.
            val metrics = rememberTtyMetrics(maxWidth)
            LaunchedEffect(metrics.cols) { vm.reportViewport(metrics.cols) }
            if (liveFrame != null) {
                MirrorSurface(
                    frame = liveFrame,
                    modifier = Modifier.fillMaxSize(),
                    onInput = { vm.sendMirrorInput(it, mirrorTarget) },
                    onRequestKeyboard = openKeyboard,
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "connecting to session…",
                        color = textMuted,
                        fontFamily = piMono,
                        fontSize = 13.sp,
                    )
                }
            }
        }

        // Type straight into pi's terminal: a special-key row (esc/ctrl/alt/arrows)
        // above the keyboard plus hidden keystroke capture, both forwarding raw
        // bytes to the mirror's PTY. No separate chat box — pi echoes what you type.
        if (status is ConnectionStatus.Connected) {
            TtyKeyboardBar(vm = vm, agentId = mirrorTarget, focusRequester = ttyFocus)
        }
        // No app footer: pi's own footer (cwd, model, remote status) is in the mirror.
    }
}

/** Opaque token — increment to reset folder picker state (used after mkdir success). */
data class DirRefresh(val token: Long)

/** Folder picker dialog — browse host directories and optionally create new ones.
 *  [dirs] is null until the first listing arrives (loading); an empty list means
 *  the directory has no subdirectories. */
@Composable
fun FolderPickerDialog(
    dirs: List<HostDir>?,
    currentDir: String,
    onNavigate: (String) -> Unit,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    onCreateFolder: (dirPath: String, name: String) -> Unit = { _, _ -> },
    creating: Boolean = false,
    createError: String? = null,
    dirRefresh: DirRefresh = DirRefresh(0)
) {
    // Input clears when parent increments dirRefresh.token (e.g. after mkdir success)
    var folderName by remember(dirRefresh) { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { onDismiss() },
        title = {
            Column {
                Text("Select working directory", fontFamily = piMono, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(2.dp))
                Text(currentDir, color = textMuted, fontFamily = piMono, fontSize = 10.sp, maxLines = 1)
            }
        },
        text = {
            Column {
                // ── New folder controls ────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = {
                            val name = folderName.trim()
                            if (name.isNotBlank() && !creating) onCreateFolder(currentDir, name)
                        },
                        enabled = folderName.isNotBlank() && !creating,
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = bg.copy(alpha = 0.3f),
                            contentColor = accent
                        ),
                        modifier = Modifier.width(80.dp)
                    ) {
                        Text(if (creating) "•••" else "+", fontFamily = piMono, fontSize = 10.sp)
                    }
                    TextField(
                        value = folderName,
                        onValueChange = { folderName = it },
                        placeholder = { Text("Folder name", color = textMuted, fontFamily = piMono, fontSize = 10.sp) },
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = bg, unfocusedContainerColor = bg,
                            focusedIndicatorColor = accent, unfocusedIndicatorColor = border,
                            focusedTextColor = textPrimary, unfocusedTextColor = textPrimary
                        ),
                        textStyle = LocalTextStyle.current.copy(color = textPrimary, fontFamily = piMono, fontSize = 10.sp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            val name = folderName.trim()
                            if (name.isNotBlank() && !creating) onCreateFolder(currentDir, name)
                        })
                    )
                }
                createError?.let { err ->
                    Spacer(Modifier.height(4.dp))
                    Text(err, color = error, fontFamily = piMono, fontSize = 10.sp)
                }
                Spacer(Modifier.height(6.dp))
                // ── Directory listing ──────────────────────────
                if (dirs == null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Loading…", color = textMuted, fontFamily = piMono, fontSize = 11.sp)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 400.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        if (currentDir != "/") {
                            item {
                                PiBox(borderColor = borderMuted) {
                                    Row(
                                        Modifier.fillMaxWidth()
                                            .minimumInteractiveComponentSize()
                                            .clickable(role = Role.Button, onClickLabel = "go to parent folder") {
                                                val parent = currentDir.substringBeforeLast("/").takeIf { it.isNotBlank() } ?: "/"
                                                onNavigate(parent)
                                            }.padding(horizontal = 6.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("↑", color = textMuted, fontFamily = piMono, fontSize = 11.sp)
                                        Text("  .. (parent)", color = textMuted, fontFamily = piMono, fontSize = 10.sp)
                                    }
                                }
                            }
                        }
                        if (dirs.isEmpty()) {
                            item {
                                Text(
                                    "No subfolders", color = textMuted, fontFamily = piMono, fontSize = 10.sp,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                                )
                            }
                        }
                        items(dirs, key = { it.path }) { dir ->
                            PiBox(header = dir.name, borderColor = borderMuted) {
                                Row(
                                    Modifier.fillMaxWidth()
                                        .minimumInteractiveComponentSize()
                                        .clickable(role = Role.Button, onClickLabel = "select ${dir.name}") { onSelect(dir.path) }
                                        .padding(horizontal = 6.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("▸ ", color = accent, fontFamily = piMono, fontSize = 11.sp)
                                    Column {
                                        Text(dir.path, color = textSecondary, fontFamily = piMono, fontSize = 10.sp, maxLines = 1)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = { onDismiss() }) {
                Text("Cancel", color = textMuted, fontFamily = piMono, fontSize = 11.sp)
            }
        }
    )
}

// ── Helpers ────────────────────────────────────────────────────────────

/**
 * Sanitize a host-derived session name for one-line display. Names come from
 * the first user message, which can be a multi-line paste of arbitrary text.
 */
fun sessionLabel(name: String, max: Int = 40): String {
    val flat = name.replace('\n', ' ').replace('\r', ' ').trim()
    return if (flat.length > max) flat.take(max - 1) + "…" else flat
}

/** Pi-style session time formatting */
val fullTimeFormat = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
fun sessionTime(ts: Long): String {
    val now = java.lang.System.currentTimeMillis()
    val diff = now - ts
    if (diff < 60_000) return "just now"
    if (diff < 3_600_000) return "${diff / 60_000}m ago"
    if (diff < 86_400_000) {
        val h = diff / 3_600_000
        val m = (diff % 3_600_000) / 60_000
        return "${h}h ${m}m ago"
    }
    return fullTimeFormat.format(java.util.Date(ts))
}

