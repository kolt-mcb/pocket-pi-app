package com.piremote

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import com.piremote.db.ChatDatabase
import com.piremote.db.ChatMessageEntity

val Context.dataStore by preferencesDataStore(name = "settings")
val KEY_URL = stringPreferencesKey("last_server_url")
val KEY_URL_HISTORY = stringSetPreferencesKey("url_history")

sealed class ConnectionStatus {
    data object Disconnected : ConnectionStatus()
    data object Connecting : ConnectionStatus()
    data object Connected : ConnectionStatus()
    data class Error(val message: String) : ConnectionStatus()
}

/** Which sub-screen to show while connected */
enum class ConnectedScreen { Chat, Sessions }
enum class MessageToolType { User, Assistant, ToolResult, Streaming, Thinking, Custom }
object MsgId {
    private val counter = AtomicInteger(0)
    private val epoch = System.currentTimeMillis()
    fun next(): String = "$epoch-${counter.incrementAndGet()}"
}

data class ChatImage(
    val data: String,          // base64-encoded image data
    val mimeType: String       // e.g. "image/png", "image/jpeg"
)

data class ChatMessage(
    val id: String = MsgId.next(),
    val toolCallId: String = "",
    val type: MessageToolType = MessageToolType.User,
    val toolName: String = "",
    val toolArgs: String = "",
    val content: String = "",
    val isError: Boolean = false,
    // Host-rendered ANSI+OSC stream (PROTOCOL.md "single stream field"): the
    // complete presentation of this message, rendered by the host at the
    // phone's width. When set, the UI feeds it through the TTY parser verbatim
    // — no client-side rendering at all. [streamExpanded] is the expanded
    // variant for tool results (tap-to-expand); null means same as [stream].
    val stream: String? = null,
    val streamExpanded: String? = null,
    // Host-rendered ANSI lines for an extension's own Component (custom messages
    // and tool results), rendered at the phone's width. When set, the UI shows
    // these verbatim instead of [content], mirroring the extension's presentation.
    // Predates [stream]; kept for older hosts.
    val ansiLines: List<String>? = null,
    // Images from tool results (e.g. `read` on image files) or user attachments.
    // Each entry carries base64 data + MIME type.
    val images: List<ChatImage> = emptyList(),
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Single source of truth for the UI.
 *
 * Every `collectAsState()` lives here — the Activity collects exactly zero
 * flows itself. This keeps the Compose body readable ("readability counts")
 * and avoids the Activity reaching past the ViewModel into the raw
 * PiWebSocket ("namespaces are one honking great idea").
 *
 * The `*Flow` properties are passed in as raw flows; callers use
 * `collectAsState()` on whichever property they consume. Because each
 * composable reads only the properties it needs, the compiler can still
 * skip recomposing subtrees that don't use the changed property. The
 * consolidation here is purely about *where* the collection happens —
 * the ViewModel, not the Activity.
 */
class ChatUIState(
    val serverUrl: MutableStateFlow<String>,
    val inputText: MutableStateFlow<String>,
    val messages: StateFlow<List<ChatMessage>>,
    val streamingText: StateFlow<String>,
    val status: StateFlow<ConnectionStatus>,
    val busy: StateFlow<Boolean>,
    val urlHistory: StateFlow<Set<String>>,
    val selectedSession: StateFlow<String>,
    // ── Global (non-per-agent) flows forwarded from PiWebSocket ──
    val sessions: StateFlow<List<RemoteSession>>,
    val uiRequests: StateFlow<List<ExtensionUIRequest>>,
    val statuses: StateFlow<Map<String, String>>,
    val widgets: StateFlow<Map<String, List<String>>>,
    val compacting: StateFlow<Boolean>,
    val notifyBanners: StateFlow<List<BannerMessage>>,
    val uiTitle: StateFlow<String?>,
    val clientCount: StateFlow<Int>,
    val turnSummary: StateFlow<PiWebSocket.TurnSummary?>,
    val savedSessions: StateFlow<List<SavedSession>>,
    val renderFrame: StateFlow<RenderFrame?>,
    // Host directory browser
    val hostDirs: StateFlow<HostDirsResponse?>,
    val hostDirsError: StateFlow<String?>,
    // Host directory browser: mkdir results
    val mkdirResult: Flow<MkdirResult>,
)

class ChatViewModel(private val _ws: PiWebSocket, private val _ctx: Context) : ViewModel() {
    private val _dao = ChatDatabase.getInstance(_ctx).chatDao()
    val _url = MutableStateFlow("")
    val _inp = MutableStateFlow("")
    val _urlHistory = MutableStateFlow<Set<String>>(emptySet())
    private val _selectedSession = MutableStateFlow("")
    private val _connectedScreen = MutableStateFlow(ConnectedScreen.Chat)

    // Scope for everything tied to the current pi connection — persistence
    // collector, session auto-select, FGS notification updater. connect()
    // cancels the previous one before opening a new one, so reconnects
    // don't stack observers; disconnect() cancels for good.
    private var connectionScope: CoroutineScope? = null

    val connectedScreen = _connectedScreen
    fun showSessionsScreen() { _connectedScreen.value = ConnectedScreen.Sessions }
    fun showChatScreen() { _connectedScreen.value = ConnectedScreen.Chat }
    // ── Screen mirror ──
    val mirrorFrame: StateFlow<MirrorFrame?> get() = _ws.mirrorFrameFlow
    fun setMirror(on: Boolean, agentId: String = "") { _ws.setMirror(on, agentId) }
    fun sendMirrorInput(data: String, agentId: String = "") { _ws.sendMirrorInput(data, agentId) }

    // ── Host-pushed files ──
    val fileDownload: StateFlow<FileDownload?> get() = _ws.fileDownloadFlow
    fun clearFileDownload() { _ws.clearFileDownload() }

    val st = ChatUIState(
        serverUrl = _url,
        inputText = _inp,
        messages = _ws.messageFlow,
        streamingText = _ws.assistingTextFlow,
        status = _ws.statusFlow,
        busy = _ws.busyFlow,
        urlHistory = _urlHistory,
        selectedSession = _selectedSession,
        // Global flows — forwarded so the Activity never touches PiWebSocket directly.
        sessions = _ws.sessionListFlow,
        uiRequests = _ws.uiRequestFlow,
        statuses = _ws.statusesFlow,
        widgets = _ws.widgetsFlow,
        compacting = _ws.compactingFlow,
        notifyBanners = _ws.notifyBannerFlow,
        uiTitle = _ws.uiTitleFlow,
        clientCount = _ws.clientCountFlow,
        turnSummary = _ws.turnSummaryFlow,
        savedSessions = _ws.savedSessionsFlow,
        renderFrame = _ws.renderFrameFlow,
        mkdirResult = _ws.mkdirResultFlow,
        hostDirs = _ws.hostDirsFlow,
        hostDirsError = _ws.hostDirsErrorFlow,
    )

    fun setSelectedSession(id: String) {
        _selectedSession.value = id
        // Tell the WS which agent's flows the UI is showing. Drives messageFlow,
        // assistingTextFlow, busyFlow, turnSummaryFlow all at once.
        _ws.selectAgent(id)
    }

    fun setServerUrl(u: String) { _url.value = u }
    fun setInputText(t: String) { _inp.value = t }
    /** Call from a coroutine scope — never call from main thread directly. */
    suspend fun loadUrlHistory() {
        try {
            val prefs = _ctx.dataStore.data.first()
            _urlHistory.value = prefs[KEY_URL_HISTORY] ?: emptySet()
        } catch (_: Throwable) {}
    }

    fun connect() {
        val u = _url.value.trim()
        if (u.isBlank()) return   // don't open a socket to "" or pollute URL history
        _url.value = u
        // Persist URL & history in background to avoid blocking the UI thread.
        viewModelScope.launch {
            try {
                val prefs = _ctx.dataStore.data.first()
                val history = (prefs[KEY_URL_HISTORY] ?: emptySet()).toList().toMutableList()
                history.remove(u); history.add(0, u)
                while (history.size > 10) history.removeAt(history.lastIndex)
                val finalHistory = history.toSet()
                _ctx.dataStore.edit { it[KEY_URL] = u; it[KEY_URL_HISTORY] = finalHistory }
                _urlHistory.value = finalHistory
            } catch (_: Throwable) {}
        }

        // Cancel any observers from a prior connect(). Without this, every
        // reconnect stacked another set of persistence/session/FGS collectors
        // on top of the previous ones.
        connectionScope?.cancel()
        // Parent the SupervisorJob to viewModelScope's Job so onCleared() (which
        // cancels viewModelScope) also tears these collectors down — a bare
        // SupervisorJob() would orphan them and leak past the ViewModel.
        val scope = CoroutineScope(viewModelScope.coroutineContext + SupervisorJob(viewModelScope.coroutineContext[Job]))
        connectionScope = scope

        // Load saved messages from DB (IO thread for Room)
        scope.launch {
            val saved = withContext(kotlinx.coroutines.Dispatchers.IO) {
                _dao.getAllByServerUrl(u).distinctBy { it.msgId }
            }
            val rebuilt = saved.map { e ->
                val t = when(e.type) {
                    "User" -> MessageToolType.User
                    "Assistant" -> MessageToolType.Assistant
                    "ToolResult" -> MessageToolType.ToolResult
                    "Thinking" -> MessageToolType.Thinking
                    else -> MessageToolType.Streaming
                }
                ChatMessage(id = e.msgId, toolCallId = e.toolCallId, type = t, toolName = e.toolName, content = e.content, isError = e.isError, timestamp = e.timestamp)
            }
            if (rebuilt.isNotEmpty()) _ws.repoMessages(rebuilt)
        }
        // Persist on each turn-end (busy → false) rather than on every streaming
        // token. Upserts the full message list; the unique (url, msgId) index lets
        // REPLACE update rows in place, so final assistant/tool content is saved.
        // (The old per-emission path inserted each message once as an empty
        // Streaming placeholder and never updated it, and re-scanned the whole
        // table on every text delta.) Transient Streaming bubbles are skipped.
        scope.launch {
            _ws.busyFlow.collect { busy ->
                if (busy) return@collect
                val entities = _ws.messageFlow.value.mapIndexedNotNull { idx, m ->
                    if (m.type == MessageToolType.Streaming) null
                    else ChatMessageEntity(
                        msgId = m.id, url = u, seq = idx,
                        type = m.type.name, toolCallId = m.toolCallId,
                        toolName = m.toolName, content = m.content, isError = m.isError,
                        timestamp = m.timestamp
                    )
                }
                // Skip empty lists: a turn-end can fire before history lands, and
                // replaceAllForUrl on an empty list would wipe the restored chat.
                if (entities.isEmpty()) return@collect
                try { _dao.replaceAllForUrl(u, entities) } catch (_: Throwable) {}
            }
        }
        // When the host replaces the session (/new, /resume), the in-memory chat
        // is already cleared in PiWebSocket; also wipe the persisted DB history
        // for this server so a later reconnect's repoMessages can't repaint the
        // old conversation over the now-empty session.
        scope.launch {
            _ws.sessionResetFlow.collect {
                try { _dao.deleteByServerUrl(u) } catch (_: Throwable) {}
            }
        }
        // Auto-select the host (self) session whenever the session list updates and
        // nothing is currently selected (or the selection vanished).
        scope.launch {
            _ws.sessionListFlow.collect { sessions ->
                val current = _selectedSession.value
                val stillPresent = sessions.any { it.id == current }
                if (current.isBlank() || !stillPresent) {
                    val self = sessions.firstOrNull { it.isSelf } ?: sessions.firstOrNull()
                    val id = self?.id ?: ""
                    _selectedSession.value = id
                    if (id.isNotBlank()) _ws.selectAgent(id)
                }
            }
        }
        // Foreground service: start on connect, update on busy/message changes, stop on disconnect.
        // collectLatest auto-cancels the inner combine() when status leaves Connected, so the
        // FGS-updater coroutine doesn't leak across status transitions.
        scope.launch {
            val host = extractHost(u)
            _ws.statusFlow.collectLatest { st ->
                when (st) {
                    is ConnectionStatus.Connected -> {
                        try { PiService.start(_ctx, host) } catch (_: Exception) {}
                        combine(_ws.busyFlow, _ws.messageFlow) { busy, msgs -> busy to msgs.size }
                            .collect { (busy, count) ->
                                try { PiService.start(_ctx, host, busy, count) } catch (_: Exception) {}
                            }
                    }
                    is ConnectionStatus.Disconnected, is ConnectionStatus.Error -> {
                        try { PiService.stop(_ctx) } catch (_: Exception) {}
                    }
                    else -> {}
                }
            }
        }
        // "Pi is ready" notification — only fires when the app is backgrounded
        // so the foreground chat doesn't ping over its own view. Foreground
        // state read off ProcessLifecycleOwner at emit time, not subscribed,
        // so we don't race the OS lifecycle event ordering.
        scope.launch {
            val host = extractHost(u)
            _ws.agentDoneFlow.collect { done ->
                val isForeground = ProcessLifecycleOwner.get().lifecycle.currentState
                    .isAtLeast(Lifecycle.State.STARTED)
                if (isForeground) return@collect
                val summary = done.summary?.let { s ->
                    "${s.totalCalls} tool${if (s.totalCalls != 1) "s" else ""} used"
                }
                try { PiService.notifyDone(_ctx, host, summary, done.durationMs) } catch (_: Exception) {}
            }
        }
        _connectedScreen.value = ConnectedScreen.Chat
        _ws.connect(u)
    }

    fun disconnect() {
        // Tear down the per-connection observers (persistence, session-select,
        // FGS updater) so reconnects don't stack new ones on top.
        connectionScope?.cancel()
        connectionScope = null
        // Keep the persisted chat — connect() rebuilds it via _ws.repoMessages
        // when the user reconnects to the same URL.
        _ws.disconnect()
    }

    fun sendPrompt() {
        val t = _inp.value.trim()
        if (t.isNotEmpty()) {
            _ws.sendPrompt(t, _selectedSession.value)
            _inp.value = ""
            _pendingImages.value = emptyList()
        }
    }
    /** Send a prompt with attached images (base64 data URIs). The server's
     *  handleHostCmd strips the data-URI prefix and forwards raw base64 + MIME.
     *  Also adds the images to the local chat as a User message so they show
     *  inline before the agent processes them. */
    fun sendPromptWithImages() {
        val t = _inp.value.trim()
        val images = _pendingImages.value
        if (t.isNotEmpty() || images.isNotEmpty()) {
            _ws.sendPromptWithImages(t, images, _selectedSession.value)
            // Show the images locally in the chat. _pendingImages holds full
            // data: URIs (data:<mime>;base64,<data>), but ChatImage.data is raw
            // base64 (decoded directly by base64ToBitmap), so split off the
            // prefix here — mirroring the host's strip in handleHostCmd.
            if (images.isNotEmpty()) {
                val chatImages = images.map { uri ->
                    ChatImage(
                        data = uri.substringAfter("base64,"),
                        mimeType = uri.substringAfter("data:").substringBefore(";").ifBlank { "image/jpeg" },
                    )
                }
                // Atomic update: this runs on the main thread while the socket
                // reader thread also appends to the same flow.
                _ws.ensureAgent(_selectedSession.value).messages.update {
                    it + ChatMessage(type = MessageToolType.User, content = t, images = chatImages)
                }
            }
            _inp.value = ""
            _pendingImages.value = emptyList()
        }
    }
    /** Images the user has selected but hasn't sent yet. */
    val _pendingImages = MutableStateFlow<List<String>>(emptyList())
    fun setPendingImages(uris: List<String>) { _pendingImages.value = uris }
    fun addPendingImage(uri: String) { _pendingImages.value = _pendingImages.value + uri }
    fun removePendingImage(uri: String) { _pendingImages.value = _pendingImages.value - uri }
    fun sendSteer() {
        val t = _inp.value.trim()
        if (t.isNotEmpty()) { _ws.sendSteer(t, _selectedSession.value); _inp.value = "" }
    }
    fun sendFollowUp() {
        val t = _inp.value.trim()
        if (t.isNotEmpty()) { _ws.sendFollowUp(t, _selectedSession.value); _inp.value = "" }
    }
    /** Send a slash command. Handles local commands (like /disconnect) and forwards
     *  remote ones (/compact, /new, /reload, /quit, /resume) to the host extension. */
    fun sendSlashCommand(command: String, args: String = "") {
        // Local-only commands handled on the device
        if (command == "disconnect") {
            disconnect()
            return
        }
        _ws.sendSlashCommand(command, args, _selectedSession.value)
    }
    /** Report the chat area's width (monospace columns) so the host renders
     *  extension components to fit the phone. */
    fun reportViewport(cols: Int) { _ws.reportViewport(cols) }

    /** Spawn a new pi peer process on the host. Routes to PiWebSocket.sendSpawnPeer.
     *  If [cwd] is non-blank, the new pi starts in that directory. */
    fun spawnPeer(cwd: String = "") { _ws.sendSpawnPeer(cwd = cwd) }

    /** Spawn a peer resuming a specific saved session — equivalent to
     *  `pi --session <path>` on the host. The new pi joins the host as a
     *  peer; the app shows it as a new tab pre-loaded with that history. */
    fun spawnPeerWithSession(path: String, cwd: String = "") { _ws.sendSpawnPeer(path, cwd) }

    /** Request the host's directory listing for the folder picker. Response
     *  arrives in [st.hostDirs] / [st.hostDirsError]. */
    fun browseHostDirs(basePath: String = "") { _ws.sendListHostDirs(basePath) }

    /** Create a directory on the host under [dirPath] named [name].
     *  Result arrives on [st.mkdirResult]. */
    fun createFolder(dirPath: String, name: String) {
        _ws.sendCreateDir(dirPath, name, System.nanoTime())
    }

    /** Refresh the saved-session list. The browser screen calls this on
     *  entry; the response shows up in [PiWebSocket.savedSessionsFlow]. */
    fun refreshSavedSessions() { _ws.sendGetSavedSessions() }

    /** Send /quit targeted at a specific peer agent to close that session. */
    fun closeSession(id: String) { _ws.sendSlashCommand("quit", "", id) }
    class Factory(private val ws: PiWebSocket, private val ctx: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(c: Class<T>): T = ChatViewModel(ws, ctx) as T
    }

    override fun onCleared() {
        // Tear down per-connection collectors if the VM is cleared while still
        // connected (Activity finished without routing through disconnect()).
        connectionScope?.cancel()
        connectionScope = null
        super.onCleared()
    }

    private fun extractHost(url: String): String {
        // Strip scheme, path, and query so the `?token=...` secret never lands
        // in the foreground-service / "pi is ready" notification text.
        return try {
            url.substringAfter("://").substringBefore("/").substringBefore("?")
        } catch (_: Exception) { "Pi" }
    }
}
