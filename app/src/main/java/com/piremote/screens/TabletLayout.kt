package com.piremote.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.DraggableState
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.piremote.AppState
import com.piremote.*
import com.piremote.theme.*
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Two-pane tablet layout: session sidebar on the left, chat on the right.
 * Shown when the window width is medium or expanded.
 */
@Composable
fun TabletLayout(
    vm: ChatViewModel,
    st: ChatUIState,
    status: ConnectionStatus,
    busy: Boolean,
    sessions: List<RemoteSession>,
    selectedSession: String,
    compacting: Boolean,
    notifyBanners: List<BannerMessage>,
    uiTitle: String?,
    clientCount: Int,
    savedSessions: List<SavedSession>,
    renderFrame: RenderFrame?,
) {
    // Dialog requests (same as phone)
    val uiRequests = st.uiRequests.collectAsState()
    val selReq = uiRequests.value.firstOrNull { it.method in listOf("select", "confirm") }
    val inpReq = uiRequests.value.firstOrNull { it.method in listOf("input", "editor") }

    fun uiRespond(id: String, value: String) = AppState.ws.sendUIResponse(id, value = value)
    fun uiCancelled(id: String) = AppState.ws.sendUIResponse(id, cancelled = true)
    fun renderInput(id: String, value: String) = AppState.ws.sendInput(id, value)

    // Folder picker state (single source of truth — the sidebar only renders it)
    var showFolderPicker by remember { mutableStateOf(false) }
    var dirCreating by remember { mutableStateOf(false) }
    var dirCreateError by remember { mutableStateOf<String?>(null) }
    var mkdirRefreshToken by remember { mutableStateOf(0L) }
    val dirsResp by st.hostDirs.collectAsState()

    // On mkdir result: refresh the current listing on success, surface the error on failure
    LaunchedEffect(st.mkdirResult) {
        st.mkdirResult.collect { r ->
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

    // The sidebar and the chat area, as values — the two layouts below place the
    // same content differently rather than duplicating either call site.
    val sidebar: @Composable (Modifier, () -> Unit) -> Unit = { sidebarModifier, onActed ->
        TabletSidebar(
            sessions = sessions,
            selectedSession = selectedSession,
            compacting = compacting,
            clientCount = clientCount,
            savedSessions = savedSessions,
            onSessionSelect = { id ->
                vm.setSelectedSession(id)
                vm.showChatScreen()
                onActed()
            },
            onNewSession = { vm.spawnPeer(); onActed() },
            onDisconnect = { vm.disconnect() },
            onCloseSession = vm::closeSession,
            onSavedSessionTap = { path -> vm.spawnPeerWithSession(path); onActed() },
            onBrowseFolders = {
                showFolderPicker = true
                dirCreating = false
                dirCreateError = null
                vm.browseHostDirs("")
            },
            onDirNavigate = { path ->
                dirCreateError = null
                vm.browseHostDirs(path)
            },
            onDirSelect = { path ->
                vm.spawnPeer(path)
                showFolderPicker = false
                onActed()
            },
            onDirCreate = { dirPath, name ->
                dirCreating = true
                dirCreateError = null
                vm.createFolder(dirPath, name)
            },
            onDirDismiss = { showFolderPicker = false },
            showFolderPicker = showFolderPicker,
            dirBasePath = dirsResp?.basePath ?: "",
            dirListing = dirsResp?.dirs,
            dirCreating = dirCreating,
            dirCreateError = dirCreateError,
            mkdirRefreshToken = mkdirRefreshToken,
            modifier = sidebarModifier,
        )
    }
    val chatArea: @Composable () -> Unit = {
        Box(modifier = Modifier.fillMaxSize()) {
            ChatScreen(
                vm = vm,
                status = status,
                busy = busy,
                sessions = sessions,
                selectedSession = selectedSession,
                notifyBanners = notifyBanners,
                uiTitle = uiTitle,
                clientCount = clientCount,
                showSessionSelector = false,
            )

            // Overlay dialogs (same as phone)
            selReq?.let { req -> SelectDialog(req, ::uiRespond, ::uiCancelled) }
            inpReq?.let { req -> InputDialog(req, ::uiRespond, ::uiCancelled) }

            // Full-screen Terminal render overlay
            renderFrame?.let { frame ->
                TerminalRenderView(frame) { value ->
                    renderInput(frame.id, value)
                }
            }
        }
    }

    // A phone turned sideways lands in this layout too (>= 600dp wide), but it
    // has barely 400dp of height and every column matters — so there the sidebar
    // becomes a drawer that slides out of the terminal's way. A real tablet
    // (>= 480dp tall in landscape) keeps both panes docked.
    val slidable = LocalConfiguration.current.screenHeightDp < 480

    if (slidable) {
        val density = LocalDensity.current
        val sheetWidth = 300.dp
        val sheetPx = with(density) { sheetWidth.toPx() }
        val scope = rememberCoroutineScope()
        // Panel x-offset in px: -sheetPx parked off-screen, 0 fully out. Hand-rolled
        // rather than ModalNavigationDrawer, whose closed anchor is its 240dp
        // MinimumDrawerWidth, not the sheet's real width — a 300dp sidebar parks
        // with 60dp still on screen. An Animatable also lets the drag follow the
        // finger and hand its velocity to the settle animation.
        val slide = remember(sheetPx) { Animatable(-sheetPx) }
        // Gesture-level state, kept out of the offset read path: the offset is read
        // in layout/draw lambdas so a drag doesn't recompose the terminal.
        var open by remember { mutableStateOf(false) }
        fun settle(toOpen: Boolean, velocity: Float = 0f) {
            open = toOpen
            scope.launch {
                slide.animateTo(if (toOpen) 0f else -sheetPx, tween(220), initialVelocity = velocity)
            }
        }
        val drag = rememberDraggableState { delta ->
            scope.launch { slide.snapTo((slide.value + delta).coerceIn(-sheetPx, 0f)) }
        }
        // Back closes the panel before MainActivity's handler offers to disconnect.
        BackHandler(enabled = open) { settle(false) }

        // clipToBounds matters: in landscape this Box starts inside the display
        // cutout inset, and an unclipped child still paints outside its parent —
        // the parked panel stayed visible in the black band to the left of the
        // window content.
        Box(modifier = Modifier.fillMaxSize().clipToBounds()) {
            chatArea()

            // Scrim: always composed so the dim tracks the drag, but only clickable
            // once open — closed, taps fall straight through to the terminal.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (open) Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Button,
                            onClickLabel = "close sessions sidebar",
                        ) { settle(false) } else Modifier
                    )
                    .drawBehind {
                        val fraction = 1f + slide.value / sheetPx      // 0 parked, 1 open
                        if (fraction > 0f) drawRect(Color.Black, alpha = 0.5f * fraction)
                    }
            )

            DrawerEdgeHandle(
                onOpen = { settle(true) },
                dragState = drag,
                onDragStopped = { velocity ->
                    settle(velocity > 300f || slide.value > -sheetPx / 2, velocity)
                },
            )

            sidebar(
                Modifier
                    .width(sheetWidth)
                    .fillMaxHeight()
                    .offset { IntOffset(slide.value.roundToInt(), 0) }
                    .background(bgSecondary)
                    .border(0.5.dp, border, RoundedCornerShape(0.dp))
                    .draggable(
                        orientation = Orientation.Horizontal,
                        state = drag,
                        onDragStopped = { velocity ->
                            settle(velocity > -300f && slide.value > -sheetPx / 2, velocity)
                        },
                    ),
                { settle(false) },
            )
        }
    } else {
        Row(modifier = Modifier.fillMaxSize()) {
            sidebar(
                Modifier
                    .width(300.dp)
                    .fillMaxHeight()
                    .background(bgSecondary)
                    .border(0.5.dp, border, RoundedCornerShape(0.dp)),
                {},
            )
            // Divider between sidebar and chat
            Box(modifier = Modifier.fillMaxHeight().width(0.5.dp).background(border))
            Box(modifier = Modifier.weight(1f).fillMaxSize()) { chatArea() }
        }
    }
}

/**
 * Grab handle for the landscape sidebar: drag it out (or tap it) to slide the
 * panel in. Deliberately a small pill rather than a full-height edge strip — an
 * edge strip would swallow taps on the terminal's first column all the way down
 * the screen; this only covers ~56dp of it.
 */
@Composable
private fun BoxScope.DrawerEdgeHandle(
    onOpen: () -> Unit,
    dragState: DraggableState,
    onDragStopped: (Float) -> Unit,
) {
    val shape = RoundedCornerShape(topEnd = 7.dp, bottomEnd = 7.dp)
    Box(
        modifier = Modifier
            .align(Alignment.CenterStart)
            .size(width = 14.dp, height = 56.dp)
            .clip(shape)
            .background(bgSecondary)
            .border(0.5.dp, border, shape)
            .clickable(role = Role.Button, onClickLabel = "open sessions sidebar") { onOpen() }
            .draggable(
                orientation = Orientation.Horizontal,
                state = dragState,
                onDragStopped = { velocity -> onDragStopped(velocity) },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text("\u22ee", color = textMuted, fontFamily = piMono, fontSize = 11.sp)
    }
}


/**
 * Tablet session sidebar. Combines active sessions, saved sessions, and
 * connection controls into a scrollable vertical panel.
 */
@Composable
fun TabletSidebar(
    sessions: List<RemoteSession>,
    selectedSession: String,
    compacting: Boolean,
    clientCount: Int,
    savedSessions: List<SavedSession>,
    onSessionSelect: (String) -> Unit,
    onNewSession: () -> Unit,
    onDisconnect: () -> Unit,
    onCloseSession: (String) -> Unit,
    onSavedSessionTap: (String) -> Unit,
    onBrowseFolders: () -> Unit = {},
    onDirNavigate: (String) -> Unit = {},
    onDirSelect: (String) -> Unit = {},
    onDirCreate: (String, String) -> Unit = { _, _ -> },
    onDirDismiss: () -> Unit = {},
    showFolderPicker: Boolean = false,
    dirBasePath: String = "",
    dirListing: List<HostDir>? = null,
    dirCreating: Boolean = false,
    dirCreateError: String? = null,
    mkdirRefreshToken: Long = 0,
    modifier: Modifier = Modifier,
) {
    var selectedCategory by remember { mutableStateOf("all") }
    val filteredSessions = if (selectedCategory == "all") savedSessions else savedSessions.filter { it.status == selectedCategory }
    val counts = savedSessions.groupingBy { it.status }.eachCount()
    val totalCount = savedSessions.size

    // Dialog lives outside the LazyColumn so it composes regardless of scroll position
    if (showFolderPicker) {
        FolderPickerDialog(
            dirs = dirListing,
            currentDir = dirBasePath,
            onNavigate = onDirNavigate,
            onSelect = onDirSelect,
            onDismiss = onDirDismiss,
            onCreateFolder = onDirCreate,
            creating = dirCreating,
            createError = dirCreateError,
            dirRefresh = DirRefresh(mkdirRefreshToken)
        )
    }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // ── Connection header ────────────────────────────────
        item {
            PiBox(header = "Pi Remote", borderColor = accent) {
                Column(modifier = Modifier.padding(vertical = 4.dp, horizontal = 8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Terminal Mode",
                            color = accent,
                            fontFamily = piMono,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.weight(1f))
                        Box(
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .clickable(role = Role.Button, onClickLabel = "disconnect") { onDisconnect() },
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                modifier = Modifier
                                    .border(1.dp, error, RoundedCornerShape(0.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text("[Disconnect]", color = error, fontFamily = piMono, fontSize = 10.sp)
                            }
                        }
                    }
                    if (sessions.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "${sessions.size} agent${if (sessions.size != 1) "s" else ""} connected  •  ${clientCount} viewer${if (clientCount != 1) "s" else ""}",
                            color = textMuted, fontFamily = piMono, fontSize = 10.sp,
                        )
                    }
                }
            }
        }

        // ── Active sessions header + [+ New] ─────────────────
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp, alignment = Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Active sessions", color = textSecondary, fontFamily = piMono, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    Box(
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .clickable(
                                enabled = sessions.isNotEmpty(),
                                role = Role.Button,
                                onClickLabel = "browse host folders",
                            ) { onBrowseFolders() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .border(0.5.dp, textMuted, RoundedCornerShape(0.dp))
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        ) {
                            Text("Browse", color = textMuted, fontFamily = piMono, fontSize = 10.sp)
                        }
                    }
                    Box(
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .clickable(
                                enabled = sessions.isNotEmpty(),
                                role = Role.Button,
                                onClickLabel = "new session",
                            ) { onNewSession() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .border(0.5.dp, accent, RoundedCornerShape(0.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text("[+ New]", color = accent, fontFamily = piMono, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }

        // ── Active session items ─────────────────────────────
        if (sessions.isEmpty()) {
            item {
                PiBox(borderColor = borderMuted) {
                    Column(modifier = Modifier.padding(vertical = 8.dp, horizontal = 8.dp)) {
                        Text("No sessions", color = textMuted, fontFamily = piMono, fontSize = 10.sp)
                        Text("will appear when agents connect", color = textMuted, fontFamily = piMono, fontSize = 10.sp)
                    }
                }
            }
        } else {
            items(sessions, key = { it.id }) { session ->
                SidebarSessionItem(
                    session = session,
                    isSelected = session.id == selectedSession,
                    isBusy = session.status == "busy",
                    onSelect = { onSessionSelect(session.id) },
                    onClose = { onCloseSession(session.id) },
                )
            }
        }

        // Divider
        item {
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = borderMuted)
            Spacer(Modifier.height(4.dp))
        }

        // ── Saved sessions header ────────────────────────────
        item {
            Text("Saved sessions", color = textSecondary, fontFamily = piMono, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
        }

        // ── Category filter tabs (shared with phone SessionsScreen) ──
        item {
            SessionCategoryTabs(
                selected = selectedCategory,
                counts = counts,
                totalCount = totalCount,
                onSelect = { selectedCategory = it },
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }

        // ── Saved session items ──────────────────────────────
        if (filteredSessions.isEmpty()) {
            item {
                Text(
                    if (totalCount == 0) "No saved sessions yet." else "No $selectedCategory sessions.",
                    color = textMuted, fontFamily = piMono, fontSize = 10.sp,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        } else {
            items(filteredSessions, key = { it.path }) { saved ->
                // Shared with the phone saved-sessions list (ConnectPanel.kt);
                // compact = true tightens spacing for the 300dp sidebar.
                SavedSessionRow(s = saved, compact = true, onTap = { onSavedSessionTap(saved.path) })
            }
        }

        // Spacer to push content up
        item { Spacer(Modifier.height(16.dp)) }
    }
}

/** Compact session item for the sidebar. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun SidebarSessionItem(
    session: RemoteSession,
    isSelected: Boolean,
    isBusy: Boolean,
    onSelect: () -> Unit,
    onClose: () -> Unit,
) {
    val borderColor = when {
        isSelected -> accent
        isBusy -> thinkingBorder.copy(alpha = 0.7f)
        else -> borderMuted
    }

    PiBox(
        header = sessionLabel(session.name, 20),
        borderColor = borderColor,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .combinedClickable(
                    onClick = { onSelect() },
                    onClickLabel = "select session",
                    onLongClick = { if (session.kind != "self" && !isBusy) onClose() },
                    onLongClickLabel = "close session",
                    role = Role.Button,
                )
                .padding(vertical = 3.dp, horizontal = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Status dot
            Box(modifier = Modifier.size(5.dp).background(
                when {
                    isBusy -> thinkingBorder
                    isSelected -> accent
                    else -> success
                },
                CircleShape,
            ))
            // Status text
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    when {
                        isBusy -> "◉ busy"
                        isSelected -> "● active"
                        else -> "○ idle"
                    },
                    color = when {
                        isBusy -> thinkingBorder
                        isSelected -> accent
                        else -> success
                    },
                    fontFamily = piMono, fontSize = 10.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                )
                Text(
                    "${session.messageCount} msgs · ${sessionTime(session.lastActivity)}",
                    color = textMuted, fontFamily = piMono, fontSize = 10.sp,
                )
            }
            // Kind badge
            Text(
                if (session.kind == "self") "(host)" else "(peer)",
                color = textMuted, fontFamily = piMono, fontSize = 10.sp,
            )
        }
    }
}

