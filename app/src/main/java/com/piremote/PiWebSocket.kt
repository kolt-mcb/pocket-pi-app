package com.piremote

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import androidx.compose.ui.graphics.Color
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.net.URI
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class PiWebSocket : WebSocketListener() {
    private var sock: WebSocket? = null
    private var pendingUrl: String = ""
    private var retryCount = 0

    /** Build an OkHttpClient suited to the URL: a plain client for `ws://`, or
     *  a TLS client with **fingerprint-pinned** trust for `wss://?...&fp=<sha256>`.
     *  Pinning by SHA-256 means we accept any cert whose DER hashes to the
     *  fingerprint the QR carried — no public CA, no hostname matching needed
     *  (we connect by IP). The pin is the *only* trust anchor, so the channel
     *  is end-to-end encrypted between this app and the cert holder. */
    // OkHttpClient is designed to be shared; build one per distinct URL and
    // reuse it so retries don't leak a dispatcher + connection pool per attempt.
    private val clientCache = java.util.concurrent.ConcurrentHashMap<String, OkHttpClient>()
    private fun clientForUrl(url: String): OkHttpClient =
        clientCache.getOrPut(url) { buildClient(url) }
    private fun buildClient(url: String): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
        val uri = try { URI(url) } catch (_: Exception) { null }
        val fp = uri?.query
            ?.split('&')
            ?.firstOrNull { it.startsWith("fp=") }
            ?.substringAfter("fp=")
            ?.lowercase()
        if (uri?.scheme?.equals("wss", ignoreCase = true) == true && !fp.isNullOrBlank()) {
            val tm = PinningTrustManager(fp)
            val ssl = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf<TrustManager>(tm), null)
            }
            builder.sslSocketFactory(ssl.socketFactory, tm)
            // We're pinning by cert content — hostname/IP match is moot.
            builder.hostnameVerifier { _, _ -> true }
        }
        return builder.build()
    }

    // ── Per-agent state ────────────────────────────────────────────────
    // Each connected pi agent (host or peer) gets its own AgentState bag so
    // that two pis streaming in parallel don't smash each other's text
    // accumulators. The selected agent's flows are surfaced as the back-compat
    // messageFlow / busyFlow / etc. below, so the UI subscribes the same way
    // it did before — it just now follows whichever tab is active.
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    class AgentState(val id: String) {
        // Display name + kind cache — populated from session_list. Optional;
        // events that arrive before session_list still get a working state.
        var name: String = ""
        var isSelf: Boolean = false
        // Last-seen pi session id. When it changes, the host replaced the
        // session (/new, /resume) and we clear this agent's stale chat.
        var sessionId: String = ""

        val messages = MutableStateFlow<List<ChatMessage>>(emptyList())
        val assistingText = MutableStateFlow("")
        val thinking = MutableStateFlow("")
        val busy = MutableStateFlow(false)
        val turnSummary = MutableStateFlow<TurnSummary?>(null)

        // Streaming bookkeeping — was global before; now per-agent so two
        // simultaneous text streams don't interfere.
        var stxt = ""
        val tbufs = mutableMapOf<String, StringBuilder>()
        val turnToolCalls = mutableListOf<String>()
        var agentStartedAt = 0L
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    // Pending auto-reconnect work. Tracked so disconnect() can cancel just the
    // retry without tearing down `scope` (which hosts the long-lived stateIn
    // sharing coroutines for messageFlow/busyFlow/etc. — cancelling it freezes
    // the whole UI permanently, since this object is a process-lifetime singleton).
    @Volatile private var reconnectJob: Job? = null
    private val agentMap = java.util.concurrent.ConcurrentHashMap<String, AgentState>()
    private val _agents = MutableStateFlow<List<AgentState>>(emptyList())
    val agentsFlow: StateFlow<List<AgentState>> get() = _agents

    // Which agent's chat the UI is currently showing. Null until the first
    // event arrives. Survives across reconnects so the user's tab choice sticks.
    private val _selectedAgentId = MutableStateFlow<String?>(null)
    val selectedAgentIdFlow: StateFlow<String?> get() = _selectedAgentId

    fun selectAgent(id: String) { if (agentMap.containsKey(id)) _selectedAgentId.value = id }

    /** Get or create the per-agent state for [id]. Newly-created agents auto-
     *  publish to agentsFlow and become the default selection if none yet.
     *  `internal` so the ViewModel can locally echo a just-sent message (e.g. a
     *  user prompt with image attachments) onto the active agent before the host
     *  round-trips it back. */
    // Synchronized because ensureAgent is called from both the OkHttp reader
    // thread (dispatch) and the main thread (ViewModel local echo). ConcurrentHashMap's
    // getOrPut extension is get-then-put (not atomic), so the lock is what makes
    // create-once + the _agents publish race-free.
    internal fun ensureAgent(id: String): AgentState =
        synchronized(agentMap) {
            agentMap.getOrPut(id) {
                val s = AgentState(id)
                _agents.value = agentMap.values.toList()
                if (_selectedAgentId.value == null) _selectedAgentId.value = id
                s
            }
        }

    // ── Back-compat flows: follow the selected agent ──────────────────
    // Defined as derived flatMapLatest of (_selectedAgentId → agent.<flow>) so
    // existing UI code that collected messageFlow/busyFlow/etc. transparently
    // sees the active tab's stream.
    // Shared fallback flows — one per type, reused whenever the agent is missing.
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun <T> agentFlow(selector: (AgentState) -> StateFlow<T>, fallbackFlow: StateFlow<T>): StateFlow<T> =
        _selectedAgentId.flatMapLatest { id ->
            // id is null until the first event; ConcurrentHashMap.get(null) throws
            // (unlike the old LinkedHashMap), so guard the null key explicitly.
            selector((id?.let { agentMap[it] }) ?: return@flatMapLatest fallbackFlow)
        }.stateIn(scope, SharingStarted.Eagerly, fallbackFlow.value)

    private val _fallbackMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    private val _fallbackAssistingText = MutableStateFlow("")
    private val _fallbackBusy = MutableStateFlow(false)
    private val _fallbackTurnSummary = MutableStateFlow<TurnSummary?>(null)

    val messageFlow: StateFlow<List<ChatMessage>> = agentFlow({ it.messages }, _fallbackMessages)
    val assistingTextFlow: StateFlow<String> = agentFlow({ it.assistingText }, _fallbackAssistingText)
    val busyFlow: StateFlow<Boolean> = agentFlow({ it.busy }, _fallbackBusy)
    val turnSummaryFlow: StateFlow<TurnSummary?> = agentFlow({ it.turnSummary }, _fallbackTurnSummary)

    // ── Global state (not per-agent) ──────────────────────────────────
    private val _sessions = MutableStateFlow<List<RemoteSession>>(emptyList())
    val sessionListFlow: StateFlow<List<RemoteSession>> get() = _sessions
    private val _savedSessions = MutableStateFlow<List<SavedSession>>(emptyList())
    val savedSessionsFlow: StateFlow<List<SavedSession>> get() = _savedSessions
    // Host directory browser: response and error states
    private val _hostDirs = MutableStateFlow<HostDirsResponse?>(null)
    val hostDirsFlow: StateFlow<HostDirsResponse?> get() = _hostDirs
    private val _hostDirsError = MutableStateFlow<String?>(null)
    val hostDirsErrorFlow: StateFlow<String?> get() = _hostDirsError
    // Host directory browser: mkdir operation results
    private val _mkdirId = MutableStateFlow<Long>(0)
    val mkdirIdFlow: StateFlow<Long> get() = _mkdirId
    private val _mkdirResult = MutableSharedFlow<MkdirResult>(extraBufferCapacity = 1)
    val mkdirResultFlow: Flow<MkdirResult> get() = _mkdirResult
    // Fires when the host replaces the self session (/new, /resume). The
    // ViewModel wipes the persisted DB history so a later reconnect can't
    // re-inject the old conversation over the now-empty session.
    private val _sessionReset = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    val sessionResetFlow: SharedFlow<Unit> get() = _sessionReset
    // Extension UI: pending dialog requests awaiting response
    private val _uiRequests = MutableStateFlow<List<ExtensionUIRequest>>(emptyList())
    val uiRequestFlow: StateFlow<List<ExtensionUIRequest>> get() = _uiRequests
    // Extension UI: fire-and-forget status indicators
    private val _statuses = MutableStateFlow<Map<String, String>>(emptyMap())
    val statusesFlow: StateFlow<Map<String, String>> get() = _statuses
    // Extension UI: widgets (above/below editor)
    private val _widgets = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val widgetsFlow: StateFlow<Map<String, List<String>>> get() = _widgets
    // Compaction status
    private val _compacting = MutableStateFlow(false)
    val compactingFlow: StateFlow<Boolean> get() = _compacting
    // Notify banners (extension_ui notify)
    private val _notifyBanners = MutableStateFlow<List<BannerMessage>>(emptyList())
    val notifyBannerFlow: StateFlow<List<BannerMessage>> get() = _notifyBanners
    // Extension UI title
    private val _uiTitle = MutableStateFlow<String?>(null)
    val uiTitleFlow: StateFlow<String?> get() = _uiTitle
    // Connected client count
    private val _clientCount = MutableStateFlow(0)
    val clientCountFlow: StateFlow<Int> get() = _clientCount
    // Generic TUI render frames (any Pi extension component)
    private val _renderFrame = MutableStateFlow<RenderFrame?>(null)
    val renderFrameFlow: StateFlow<RenderFrame?> get() = _renderFrame
    // Screen mirror: composed terminal frames from the host TUI (fork onFrame hook)
    private val _mirrorFrame = MutableStateFlow<MirrorFrame?>(null)
    val mirrorFrameFlow: StateFlow<MirrorFrame?> get() = _mirrorFrame
    // Reconstructed mirror line buffer, for applying row-level diffs (mirror_diff).
    // The host sends a full mirror_frame keyframe on subscribe, then diffs against
    // exactly what it last sent us, so this stays in sync. Touched only on the
    // single OkHttp reader thread (dispatch), so no synchronization needed.
    private var mirrorBuf: MutableList<String> = mutableListOf()
    private var mirrorBufAgent: String = ""
    // PiPerf: nanoTime of the last keystroke sent to the host. On the next mirror
    // frame we log the gap (echo round-trip = network + host compose), then clear it.
    // Touched from the IME (send) and the reader thread (frame) — volatile is enough
    // for a single timestamp read/write.
    @Volatile private var lastInputSentNanos = 0L
    // Files pushed by the host (piRemote.sendFile) awaiting a save/share decision
    private val _fileDownload = MutableStateFlow<FileDownload?>(null)
    val fileDownloadFlow: StateFlow<FileDownload?> get() = _fileDownload
    fun clearFileDownload() { _fileDownload.value = null }
    // ── Theme ▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸▸
    // Themed palette from the extension (Pi-studio-style). Populated once
    // the host Pi broadcasts theme_info on connect. The Android app mirrors
    // the theme so the phone UI matches the Pi terminal it talks to.
    private val _remoteTheme = MutableStateFlow<com.piremote.theme.PiRemoteTheme?>(null)
    val remoteThemeFlow: StateFlow<com.piremote.theme.PiRemoteTheme?> get() = _remoteTheme
    // Theme types are in com.piremote.theme — kept fully qualified here to
    // avoid cluttering the import section of this already-large file with
    // types used only by parseRemoteTheme(). The theme package is a separate
    // namespace for palette data; keeping the boundary explicit.

    private val _s = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Disconnected)
    val statusFlow: StateFlow<ConnectionStatus> get() = _s

    // _busy / _a / _thinking / _turnSummary / stxt / tbufs / turnToolCalls /
    // agentStartedAt were moved into AgentState above. Per-agent now.

    // One-shot signal emitted on agent_end so the VM can post a "pi is ready"
    // notification when the app is backgrounded. SharedFlow (not StateFlow)
    // because each turn-end is its own event, not a state we re-emit.
    private val _agentDone = MutableSharedFlow<AgentDoneEvent>(extraBufferCapacity = 4)
    val agentDoneFlow: SharedFlow<AgentDoneEvent> get() = _agentDone

    data class AgentDoneEvent(
        val summary: TurnSummary?,
        val durationMs: Long
    )

    data class TurnSummary(
        val toolsUsed: List<ToolCallSummary>,
        val totalCalls: Int
    )
    data class ToolCallSummary(val name: String, val count: Int)

    fun connect(url: String) {
        pendingUrl = url
        retryCount = 0
        doConnect(url)
    }
    private fun doConnect(url: String) {
        val u = url.trim()
        if (u.isNotBlank()) {
            try {
                Request.Builder().url(u).build()
            } catch (e: Exception) {
                _s.value = ConnectionStatus.Error("Invalid URL: " + e.message)
                return
            }
        } else {
            _s.value = ConnectionStatus.Error("URL is empty")
            return
        }
        if (_s.value == ConnectionStatus.Disconnected || _s.value is ConnectionStatus.Error) {
            _s.value = ConnectionStatus.Connecting
        }
        // Abandon any prior socket first so its callbacks (now stale) don't
        // interleave events from two connections or trigger a phantom reconnect.
        sock?.cancel()
        sock = clientForUrl(u).newWebSocket(Request.Builder().url(u).build(), this)
    }
    private fun reconnect() {
        if (pendingUrl.isBlank()) return
        if (_s.value == ConnectionStatus.Connecting) return
        _s.value = ConnectionStatus.Connecting
        sock?.cancel()
        sock = clientForUrl(pendingUrl).newWebSocket(Request.Builder().url(pendingUrl).build(), this)
    }
    fun disconnect() {
        pendingUrl = ""
        retryCount = 0
        reconnectJob?.cancel()
        reconnectJob = null
        sock?.close(1000, null)
        // NOTE: do NOT cancel `scope` — it hosts the stateIn sharing coroutines
        // for the UI-facing flows, and this object is reused across reconnects.
    }

    // Push a transient notify banner (max 5 kept, auto-expires). Used for host
    // notify events and to surface dropped sends when the socket is closed/
    // backpressured. Errors linger longer than info so they can be read.
    private var bannerSeq = 0L
    private fun pushBanner(msg: String, type: String = "info") {
        val banner = BannerMessage(msg, type, ++bannerSeq)
        val list = _notifyBanners.value.toMutableList()
        list.add(banner)
        while (list.size > 5) list.removeAt(0)
        _notifyBanners.value = list
        scope.launch {
            delay(if (type == "error") 12_000 else 6_000)
            _notifyBanners.value = _notifyBanners.value.filterNot { it.id == banner.id }
        }
    }

    fun sendPrompt(txt: String, targetAgentId: String = "") {
        send("prompt", txt, targetAgentId)
    }
    /** Send a prompt with image attachments. Images are sent as data URIs
     *  (data:image/png;base64,...) which the server strips and forwards as
     *  raw base64 + MIME type. */
    fun sendPromptWithImages(txt: String, images: List<String>, targetAgentId: String = "") {
        if (images.isEmpty()) { send("prompt", txt, targetAgentId); return }
        val target = if (targetAgentId.isNotBlank()) ",\"targetAgentId\":\"${Js.e(targetAgentId)}\"" else ""
        val imgs = images.joinToString(",") { "\"${Js.e(it)}\"" }
        val ok = sock?.send("{\"type\":\"prompt\",\"message\":\"${Js.e(txt)}\",\"images\":[$imgs]$target}") ?: false
        if (!ok) pushBanner("Not connected — message not sent", "error")
    }
    fun sendSteer(txt: String, targetAgentId: String = "") {
        send("steer", txt, targetAgentId)
    }
    fun sendFollowUp(txt: String, targetAgentId: String = "") {
        send("follow_up", txt, targetAgentId)
    }
    // Send a slash command to the host for remote execution.
    // The extension intercepts supported commands (/compact, /new, /reload, /quit)
    // via withCommandContext. Unsupported commands get a notify banner.
    fun sendSlashCommand(name: String, args: String = "", targetAgentId: String = "") {
        val target = if (targetAgentId.isNotBlank()) ",\"targetAgentId\":\"${Js.e(targetAgentId)}\"" else ""
        val a = if (args.isNotBlank()) ",\"args\":\"${Js.e(args)}\"" else ""
        val json = "{\"type\":\"slash_command\",\"command\":\"${Js.e(name)}\"$a$target}"
        sock?.send(json)
    }
    /** Spawn a second pi process on the host. The new pi auto-loads this
     *  same extension, finds the WS port busy, falls back to peer mode, and
     *  joins as a separate agent — which the app surfaces as a new tab.
     *
     *  This is the correct path to "new session in the app": pi's extension
     *  runtime can't survive ctx.newSession() (state.staleMessage is set and
     *  never cleared), so we sidestep it by getting a fresh process.
     *
     *  If [sessionPath] is non-blank, the new pi is invoked with
     *  `--session <path>` to resume that saved session — this is how the
     *  saved-session browser surfaces a "tap to resume" action. */
    /** Spawn a second pi process on the host with an optional working directory.
     *  [sessionPath] resumes a saved session. [cwd] sets the starting directory.
     *  Reply is type=extension_ui_request with the notify banner. */
    fun sendSpawnPeer(sessionPath: String = "", cwd: String = "") {
        val parts = mutableListOf<String>()
        if (sessionPath.isNotBlank()) parts.add("\"sessionPath\":\"${Js.e(sessionPath)}\"")
        if (cwd.isNotBlank()) parts.add("\"cwd\":\"${Js.e(cwd)}\"")
        val fields = if (parts.isEmpty()) "" else ",${parts.joinToString(",")}"
        sock?.send("{\"type\":\"spawn_peer\"$fields}")
    }

    /** Ask the host for a directory listing. Reply arrives as type=host_dirs;
     *  errors arrive as type=host_dirs_error. */
    fun sendListHostDirs(basePath: String = "") {
        sock?.send(if (basePath.isNotBlank())
            "{\"type\":\"list_host_dirs\",\"base\":\"${Js.e(basePath)}\"}"
        else
            "{\"type\":\"list_host_dirs\"}"
        )
    }

    /** Ask the host to create a directory [name] under [dirPath].
     *  Increments [mkdirIdFlow] so callers can correlate the async result.
     *  Reply arrives as type=mkdir or type=mkdir_error. */
    fun sendCreateDir(dirPath: String, name: String, id: Long) {
        _mkdirId.value = id
        val json = "{\"type\":\"create_dir\",\"id\":$id,\"path\":\"${Js.e(dirPath)}\",\"name\":\"${Js.e(name)}\"}"
        sock?.send(json)
    }

    /** Ask the host for its list of saved pi sessions. Reply arrives as
     *  type=saved_sessions; surfaced via [savedSessionsFlow]. */
    fun sendGetSavedSessions() {
        sock?.send("{\"type\":\"get_saved_sessions\"}")
    }

    // Report the phone's content width (in monospace columns) so the host
    // re-renders extension components to fit the device. The width is often known
    // before the socket opens, so we remember it and flush on connect; the send
    // is deduped to one per value and reset on disconnect (see onOpen/onClosed).
    private var lastReportedCols = -1
    private var desiredCols = -1
    fun reportViewport(cols: Int) {
        if (cols <= 0) return
        desiredCols = cols
        flushViewport()
    }
    private fun flushViewport() {
        val c = desiredCols
        if (c <= 0 || c == lastReportedCols) return
        if (Log.isLoggable("PiPerf", Log.DEBUG)) Log.d("PiPerf", "viewport_send cols=$c (was $lastReportedCols)")
        if (sock?.send("{\"type\":\"viewport\",\"cols\":$c}") == true) lastReportedCols = c
    }
    // UI protocol: send response for extension_ui_request dialogs
    fun sendUIResponse(id: String, value: String? = null, confirmed: Boolean? = null, cancelled: Boolean = false) {
        val escId = Js.e(id)
        var json = "{\"type\":\"extension_ui_response\",\"id\":\"" + escId + "\""
        if (cancelled) json += ",\"cancelled\":true"
        else if (confirmed != null) json += ",\"confirmed\":$confirmed"
        else if (value != null) json += ",\"value\":\"" + Js.e(value) + "\""
        json += "}"
        sock?.send(json)
        _uiRequests.value = _uiRequests.value.filter { it.id != id }
    }

    /**
     * Re-inject saved DB messages on reconnect. The DB schema doesn't track
     * agentId, so all saved messages land on a single placeholder "self"
     * AgentState. When session_list arrives and identifies the real self
     * agent, handleSessions migrates these messages onto that agent.
     *
     * Skipped once the host has replayed authoritative history this connection
     * (see [handleHistory]): the server's copy is the source of truth, and a
     * late DB read landing after the history frame would otherwise re-create the
     * placeholder and prepend stale rows on the next session_list merge.
     */
    fun repoMessages(saved: List<ChatMessage>) {
        if (saved.isEmpty() || historyApplied) return
        val placeholder = ensureAgent(REPO_PLACEHOLDER_ID)
        placeholder.name = "pi"
        placeholder.isSelf = true
        placeholder.messages.value = placeholder.messages.value + saved
    }

    // True once the host sent a `history` frame on the current connection. Reset
    // on every (re)open so a fresh socket replays history again. Hosts too old to
    // send history leave this false, so the DB-cache restore path still works.
    @Volatile private var historyApplied = false
    companion object {
        // Sentinel ID used for saved-message restore before any session_list
        // arrives. handleSessions promotes this state onto the real self agent
        // once its id is known.
        const val REPO_PLACEHOLDER_ID = "__repo_placeholder__"
        // Theme name check — compiled once, reused per connect.
        private val THEME_LIGHT_RE = Regex("\\blight\\b", RegexOption.IGNORE_CASE)
        // Message types that carry an agentId and must be routed to the correct AgentState.
        private val PER_AGENT_TYPES = setOf(
            "agent_start", "agent_end",
            "message_start", "message_end", "message_update",
            "tool_start", "tool_update", "tool_end",
            "turn_start", "turn_end"
        )
    }
    private fun send(type: String, txt: String, targetAgentId: String = "") {
        val target = if (targetAgentId.isNotBlank()) ",\"targetAgentId\":\"${Js.e(targetAgentId)}\"" else ""
        val ok = sock?.send("{\"type\":\"$type\",\"message\":\"${Js.e(txt)}\"$target}") ?: false
        if (!ok) pushBanner("Not connected — message not sent", "error")
    }

    // ── Render Protocol ──
    // Send input from rendered TUI components back to the extension
    fun sendInput(renderId: String, value: String) {
        val json = "{\"type\":\"input\",\"id\":\"${Js.e(renderId)}\",\"value\":\"${Js.e(value)}\"}"
        sock?.send(json)
    }

    // ── Screen Mirror ──
    /** Subscribe/unsubscribe to composed terminal frames of one agent (blank = host). */
    fun setMirror(on: Boolean, agentId: String = "") {
        val target = if (agentId.isNotBlank()) ",\"agentId\":\"${Js.e(agentId)}\"" else ""
        sock?.send("{\"type\":\"mirror\",\"on\":$on$target}")
    }
    /** Raw input for the mirrored TUI: keys and SGR-encoded taps (ESC sequences included). */
    fun sendMirrorInput(data: String, agentId: String = "") {
        lastInputSentNanos = System.nanoTime()   // PiPerf: start the echo-RTT clock
        val target = if (agentId.isNotBlank()) ",\"agentId\":\"${Js.e(agentId)}\"" else ""
        sock?.send("{\"type\":\"mirror_input\",\"data\":\"${Js.e(data)}\"$target}")
    }

    /** PiPerf: log keystroke→frame latency (network + host compose) and clear the
     *  clock. [changed] is the number of rows this frame touched (full size for a
     *  keyframe, diff row count for a diff) — a heavy frame is its own signal. */
    private fun logEchoRtt(lineCount: Int, changed: Int) {
        val t = lastInputSentNanos
        if (t == 0L) return
        lastInputSentNanos = 0L
        if (!Log.isLoggable("PiPerf", Log.DEBUG)) return
        val ms = (System.nanoTime() - t) / 1_000_000.0
        Log.d("PiPerf", "echo_rtt=%.1fms lines=%d changed=%d".format(ms, lineCount, changed))
    }

    override fun onOpen(ws: WebSocket, r: okhttp3.Response) {
        if (ws !== sock) { ws.cancel(); return }   // stale socket from an abandoned connect
        _s.value = ConnectionStatus.Connected
        retryCount = 0
        // Re-report the viewport on this fresh connection — the new host needs our
        // width. Reset here (not in onClosed) so it covers both terminal paths:
        // a network drop goes through onFailure, which never runs onClosed.
        lastReportedCols = -1
        // A new socket gets a fresh history replay from the host; until it lands,
        // the DB-cache restore is allowed to populate the chat (and is overridden
        // when the authoritative history arrives moments later).
        historyApplied = false
        // Capability handshake — FIRST message so it can cancel the host's
        // deferred history replay. This app is mirror-only (it renders the screen
        // mirror, not a message-list scrollback), so the host skips shipping the
        // full conversation history — the bulk of connect latency on a slow
        // link. Hosts that don't know the type ignore it and send history.
        sock?.send("{\"type\":\"client_hello\",\"mirrorOnly\":true,\"diff\":true,\"deflate\":true,\"mirrorImages\":true}")
        // Request the session list on connect. No command list is requested:
        // typing "/" shows pi's own command menu in the mirrored frame.
        sock?.send("{\"type\":\"get_sessions\"}")
        // Flush the device width now that the socket is open (it's usually known
        // before connect, so the initial reportViewport call couldn't send it).
        flushViewport()
    }
    override fun onFailure(ws: WebSocket, t: Throwable, r: okhttp3.Response?) {
        if (ws !== sock) return   // stale socket we already abandoned
        val msg = t.message ?: "Connection failed"
        // Auto-reconnect with increasing backoff: 2s, 4s, 8s, 16s, then capped
        // at 32s. Runs on `scope` so it's cancellable and consistent with the
        // rest of the class rather than spawning a raw thread per retry.
        if (retryCount < 10 && pendingUrl.isNotBlank()) {
            retryCount++
            // Reflect the link is down while we back off, instead of leaving the
            // UI (and FGS notification) claiming a live "Connected" for up to ~32s.
            _s.value = ConnectionStatus.Connecting
            reconnectJob = scope.launch {
                delay(2000L shl minOf(retryCount - 1, 4))
                reconnect()
            }
        } else {
            _s.value = ConnectionStatus.Error(msg)
        }
    }
    // Wire throughput accounting (1s window). Enable with:
    //   adb shell setprop log.tag.PiWireStats DEBUG
    // The accounting itself is trivial; the isLoggable check runs ~once/sec.
    private var wireBytes = 0L
    private var wireFrames = 0
    private var wireLogAt = 0L
    private fun accountWire(len: Int) {
        wireBytes += len
        wireFrames++
        val now = System.currentTimeMillis()
        if (now - wireLogAt >= 1000) {
            if (Log.isLoggable("PiWireStats", Log.DEBUG)) {
                Log.d("PiWireStats", "${wireBytes / 1024} KiB/s · $wireFrames frames/s")
            }
            wireBytes = 0; wireFrames = 0; wireLogAt = now
        }
    }

    // Binary frames are deflated mirror payloads (host compresses large ones).
    // Inflate (with a decompression-bomb cap), then dispatch as normal JSON.
    override fun onMessage(ws: WebSocket, bytes: ByteString) {
        if (ws !== sock) return
        val txt = try {
            com.piremote.tty.inflateZlib(bytes.toByteArray())
        } catch (t: Throwable) {
            Log.e("PiWebSocket", "inflate error (${bytes.size} bytes)", t)
            return
        }
        accountWire(txt.length)
        try {
            dispatch(txt)
        } catch (t: Throwable) {
            Log.e("PiWebSocket", "dispatch error on inflated frame: ${txt.take(200)}", t)
        }
    }

    override fun onMessage(ws: WebSocket, txt: String) {
        if (ws !== sock) return   // ignore frames from a stale socket
        accountWire(txt.length)
        try {
            dispatch(txt)
        } catch (t: Throwable) {
            // A single malformed/unexpected frame must not flip the connection to
            // Error — the socket is still healthy and the next frame may be fine.
            // (Setting Error here also tore down the foreground service mid-session.)
            // Log loudly with a truncated prefix and keep going.
            Log.e("PiWebSocket", "dispatch error on frame: ${txt.take(200)}", t)
        }
    }
    override fun onClosed(ws: WebSocket, code: Int, reason: String) {
        if (ws !== sock) return   // stale socket we already abandoned
        _s.value = ConnectionStatus.Disconnected
        // Clear busy on every known agent — no global busy flag now.
        agentMap.values.forEach { it.busy.value = false }
        // A server-initiated close lands here (not onFailure). The host closes
        // client sockets when the session is replaced (/new, /resume) and then
        // rebinds a fresh server, so reconnect to land on the new session.
        // disconnect() clears pendingUrl, so a user-initiated close won't reconnect.
        // A clean close is NOT a failure: reset retryCount so repeated /new never
        // counts toward the give-up limit (that's what left the app stuck before).
        if (pendingUrl.isNotBlank()) {
            retryCount = 0
            reconnectJob = scope.launch {
                delay(600)
                reconnect()
            }
        }
    }

    private fun dispatch(raw: String) {
        val j = JP.p(raw) ?: return
        val tp = Js.gets(j, "type") ?: return

        // Per-agent events: route to the right AgentState by event.agentId.
        // Extension stamps agentId on every agent_*/message_*/tool_*/turn_*
        // event before broadcast (see extension.ts emitAgentEvent).
        if (tp in PER_AGENT_TYPES) {
            val agentId = Js.gets(j, "agentId")
            if (agentId.isNullOrBlank()) return  // can't route without it
            val state = ensureAgent(agentId)
            when (tp) {
                "agent_start" -> {
                    state.busy.value = true
                    state.stxt = ""
                    state.tbufs.clear()
                    state.thinking.value = ""
                    state.turnToolCalls.clear()
                    state.agentStartedAt = System.currentTimeMillis()
                }
                "agent_end" -> {
                    state.busy.value = false
                    es(state, null)
                    buildTurnSummary(state)
                    val dur = if (state.agentStartedAt > 0) System.currentTimeMillis() - state.agentStartedAt else 0L
                    _agentDone.tryEmit(AgentDoneEvent(summary = state.turnSummary.value, durationMs = dur))
                    state.agentStartedAt = 0L
                }
                "message_start" -> msgStart(state, j)
                "message_end"   -> { val mm = JP.nj(j, "message"); if (mm != null) msgEnd(state, mm) }
                "message_update" -> msgUpd(state, j)
                "tool_start"  -> toolStart(state, j)
                "tool_update" -> toolUpdate(state, j)
                "tool_end"    -> toolEnd(state, j)
                // turn_start/turn_end no-op for now — could track turn index per agent
            }
            return
        }

        when (tp) {
            "compaction_start" -> _compacting.value = true
            "compaction_end" -> _compacting.value = false
            "extension_ui_request" -> handleExtensionUI(j)
            "extension_ui_dismiss" -> {
                val id = Js.gets(j, "id") ?: return
                _uiRequests.value = _uiRequests.value.filter { it.id != id }
            }
            "history" -> handleHistory(j)
            "session_list" -> handleSessions(j)
            "saved_sessions" -> handleSavedSessions(j)
            "host_dirs" -> handleHostDirs(j)
            "host_dirs_error" -> { _hostDirs.value = null; _hostDirsError.value = Js.gets(j, "message") ?: "unknown error" }
            "mkdir" -> handleMkdir(j)
            "mkdir_error" -> handleMkdirError(j)
            "connected" -> {
                val count = (j["clients"] as? Number)?.toInt() ?: 0
                _clientCount.value = count
            }
            // Generic TUI render frames (any Pi extension component)
            "render" -> {
                val id = Js.gets(j, "id") ?: ""
                val ansiLinesRaw = j["lines"]
                val ansiLines = if (ansiLinesRaw is List<*>) ansiLinesRaw else emptyList<Any>()
                val inputMode = Js.gets(j, "inputMode") ?: "none"
                val title = Js.gets(j, "title") ?: ""
                val dismiss = j["dismiss"] as? Boolean == true
                // Per-line tap values: non-empty entry => the line is tappable and
                // the entry is the string sent back via sendInput on tap. Optional
                // for backward compat; renderers that don't set it get plain text.
                val tapValuesRaw = j["tapValues"]
                val tapValues = (tapValuesRaw as? List<*>)?.map { it?.toString() ?: "" } ?: emptyList()
                // Empty lines or explicit dismiss → clear the render frame
                val resolved = ansiLines.filterIsInstance<String>()
                if (dismiss || resolved.isEmpty()) {
                    _renderFrame.value = null
                } else {
                    _renderFrame.value = RenderFrame(
                        id = id,
                        ansiLines = resolved,
                        inputMode = inputMode,
                        title = title,
                        tapValues = tapValues
                    )
                }
            }
            // Composed terminal frames from the host TUI (screen mirror).
            // A full keyframe: replaces the buffer the diffs are applied to.
            "mirror_frame" -> {
                val lines = (j["lines"] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
                val cur = j["cursor"] as? Map<*, *>
                val agentId = Js.gets(j, "agentId") ?: ""
                mirrorBuf = lines.toMutableList()
                mirrorBufAgent = agentId
                logEchoRtt(lines.size, lines.size)
                _mirrorFrame.value = MirrorFrame(
                    seq = (j["seq"] as? Number)?.toInt() ?: 0,
                    agentId = agentId,
                    lines = lines,
                    cursorRow = (cur?.get("row") as? Number)?.toInt() ?: -1,
                    cursorCol = (cur?.get("col") as? Number)?.toInt() ?: -1,
                    width = (j["width"] as? Number)?.toInt() ?: 80,
                    height = (j["height"] as? Number)?.toInt() ?: 24,
                    recvNanos = System.nanoTime(),
                )
            }
            // Row-level mirror diff: only the changed rows, applied over the buffer
            // seeded by the last keyframe. Ignored if it's for a different agent
            // than our buffer holds — a resubscribe sends a fresh keyframe to resync.
            "mirror_diff" -> {
                val agentId = Js.gets(j, "agentId") ?: ""
                if (agentId == mirrorBufAgent) {
                    val lineCount = (j["lineCount"] as? Number)?.toInt() ?: mirrorBuf.size
                    val rows = (j["rows"] as? List<*>)?.mapNotNull { row ->
                        val m = row as? Map<*, *> ?: return@mapNotNull null
                        val i = (m["i"] as? Number)?.toInt() ?: return@mapNotNull null
                        i to (m["t"] as? String ?: "")
                    } ?: emptyList()
                    com.piremote.tty.applyMirrorDiff(mirrorBuf, lineCount, rows)
                    val cur = j["cursor"] as? Map<*, *>
                    logEchoRtt(mirrorBuf.size, rows.size)
                    _mirrorFrame.value = MirrorFrame(
                        seq = (j["seq"] as? Number)?.toInt() ?: 0,
                        agentId = agentId,
                        lines = mirrorBuf.toList(),
                        cursorRow = (cur?.get("row") as? Number)?.toInt() ?: -1,
                        cursorCol = (cur?.get("col") as? Number)?.toInt() ?: -1,
                        width = (j["width"] as? Number)?.toInt() ?: 80,
                        height = (j["height"] as? Number)?.toInt() ?: 24,
                        recvNanos = System.nanoTime(),
                    )
                }
            }
            // A file pushed from the host for the user to save/share
            "file" -> {
                val data = Js.gets(j, "data") ?: ""
                // No size cap — device RAM / process limits dominate anyway.
                if (data.isNotEmpty()) {
                    // agentId is stamped by the host (or peer-forward) so the user
                    // sees which agent sent it. Resolve to a display name.
                    val agentId = Js.gets(j, "agentId") ?: ""
                    val source = if (agentId.isNotBlank())
                        (_sessions.value.firstOrNull { it.id == agentId }?.name
                            ?: agentMap[agentId]?.name?.ifBlank { null } ?: "")
                    else ""
                    _fileDownload.value = FileDownload(
                        name = (Js.gets(j, "name") ?: "download.bin").substringAfterLast('/'),
                        mimeType = Js.gets(j, "mimeType") ?: "application/octet-stream",
                        data = data,
                        source = source,
                    )
                }
            }
            // Remote theme — the phone UI mirrors the Pi terminal's palette.
            "theme_info" -> {
                val theme = parseRemoteTheme(JP.nj(j, "theme"))
                theme?.let { _remoteTheme.value = it }
            }
        }
    }

    /** Parse a Pi theme JSON blob (from the extension) into PiRemoteTheme.
     *  Handles {colors:{...}} and {vars:{...},colors:{...}} forms. */
    private fun parseRemoteTheme(t: Map<*, *>?): com.piremote.theme.PiRemoteTheme? {
        if (t == null) return null
        val colors = t["colors"] as? Map<*, *> ?: return null
        val vars: Map<*, *> = t["vars"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
        fun hexToColor(s: String): Color? {
            return try {
                if (!s.startsWith('#') || s.length != 7) return null
                val h = s.substring(1)
                val r = h.substring(0, 2).toInt(16) shl 16
                val g = h.substring(2, 4).toInt(16) shl 8
                val b = h.substring(4, 6).toInt(16)
                Color(r or g or b or -0x1000000)
            } catch (_: Exception) { null }
        }
        fun resolveRaw(v: Any?): Color? = when {
            v is String -> hexToColor(v) ?: vars[v]?.let { resolveRaw(it) }
            v is Number -> Color(v.toInt())
            else -> null
        }
        fun resolve(key: String): Color? = resolveRaw(colors[key])
        // Prefer the host's explicit isLight flag (luminance-derived). Fall back
        // to a word-boundary name check so "Twilight"/"Delight" aren't misread.
        val isLight = (t["isLight"] as? Boolean)
            ?: (Js.gets(t, "name")?.let {
                THEME_LIGHT_RE.containsMatchIn(it)
            } == true)
        val cs = if (isLight) com.piremote.theme.ColorScheme.LIGHT
                 else com.piremote.theme.ColorScheme.DARK
        // Base palette supplies the surfaces a Pi theme doesn't carry (its page
        // background lives outside the theme's color roles); synced colors below
        // overlay it. Picking the matching light/dark base keeps text readable.
        val dflt = if (isLight) com.piremote.theme.PiRemoteTheme.defaultLight
                   else com.piremote.theme.PiRemoteTheme.defaultDark
        return com.piremote.theme.PiRemoteTheme(
            colorScheme = cs,
            bg = resolve("bg") ?: resolve("background") ?: dflt.bg,
            bgSecondary = resolve("surface") ?: dflt.bgSecondary,
            bgTertiary = resolve("surfaceVar") ?: dflt.bgTertiary,
            footerBg = resolve("footerBg") ?: dflt.footerBg,
            border = resolve("border") ?: dflt.border,
            borderAccent = resolve("borderAccent") ?: dflt.borderAccent,
            borderMuted = resolve("borderMuted") ?: dflt.borderMuted,
            textPrimary = resolve("text") ?: dflt.textPrimary,
            textSecondary = resolve("muted") ?: dflt.textSecondary,
            textMuted = resolve("dim") ?: dflt.textMuted,
            accent = resolve("accent") ?: dflt.accent,
            success = resolve("success") ?: dflt.success,
            error = resolve("error") ?: dflt.error,
            warning = resolve("warning") ?: dflt.warning,
            userBubbleBg = resolve("userMessageBg") ?: dflt.userBubbleBg,
            userBubbleText = resolve("userMessageText") ?: dflt.userBubbleText,
            assistantText = resolve("text") ?: dflt.assistantText,
            selectedBg = resolve("selectedBg") ?: dflt.selectedBg,
            toolBorder = resolve("success") ?: dflt.toolBorder,
            toolPending = resolve("toolPendingBg") ?: dflt.toolPending,
            toolErrorBg = resolve("toolErrorBg") ?: dflt.toolErrorBg,
            toolSuccessBg = resolve("toolSuccessBg") ?: dflt.toolSuccessBg,
            toolTitle = resolve("text") ?: dflt.toolTitle,
            thinkingColor = resolve("thinkingText") ?: dflt.thinkingColor,
            thinkingBorder = resolve("thinkingMedium") ?: dflt.thinkingBorder,
            thinkingLow = resolve("thinkingLow") ?: dflt.thinkingLow,
            thinkingMedium = resolve("thinkingMedium") ?: dflt.thinkingMedium,
            thinkingHigh = resolve("thinkingHigh") ?: dflt.thinkingHigh,
            footerText = resolve("dim") ?: dflt.footerText,
        )
    }

    private fun msgStart(state: AgentState, j: Map<*, *>) {
        val mm = JP.nj(j, "message") ?: return
        val role = Js.gets(mm, "role") ?: return
        // pi normalizes message.content into an array of typed blocks
        // ([{type:"text",text:"hi"}, {type:"image",...}, ...]); plain-string
        // content also arrives as a plain string on some paths; extractText handles both.
        val content = extractText(mm)
        if (role == "user") {
            // Check for images: first in explicit `images` field, then in content array.
            // Server's message_end attaches an `images` array; message_start may
            // have images inline in the content array.
            var images = parseChatImages(mm["images"])
            if (images.isEmpty()) {
                images = parseChatImages(mm) // checks mm["content"] array
            }
            // Host-rendered presentation of the user bubble (PROTOCOL.md stream).
            val stream = Js.gets(mm, "stream")
            // Avoid duplicates: if the user just sent this message with images
            // (via sendPromptWithImages), a local ChatMessage already exists.
            // Also check for text-only duplicates.
            val last = state.messages.value.lastOrNull()
            val isDup = last?.type == MessageToolType.User &&
                (last.images.isNotEmpty() || (last.content == content && images.isEmpty()))
            if (!isDup) {
                state.messages.value = state.messages.value + ChatMessage(
                    type = MessageToolType.User, content = content, images = images,
                    stream = stream,
                )
            } else if (stream != null && last != null && last.stream == null) {
                // Locally-echoed bubble: upgrade it with the host's rendering.
                state.messages.value = state.messages.value.dropLast(1) +
                    last.copy(stream = stream)
            }
        }
        if (role == "toolResult") {
            val tid = Js.gets(mm, "toolCallId") ?: ""
            // Skip if tool_end already created this ToolResult from streaming
            if (!state.messages.value.any { it.toolCallId == tid && it.type == MessageToolType.ToolResult }) {
                state.messages.value = state.messages.value + ChatMessage(
                    type = MessageToolType.ToolResult,
                    toolCallId = tid,
                    toolName = Js.gets(mm, "toolName") ?: "?",
                    content = content,
                    isError = Js.getBool(mm, "isError") ?: false
                )
            }
        }
        // assistant prose is handled via streaming (text_delta → text_end → es()),
        // so skip creating an (empty) Assistant here on msgStart.
    }

    // Extract a plain-text rendering of a pi Message's content field.
    // Pi's wire format is normally an array of typed content blocks:
    //   [{type:"text",text:"..."}, {type:"thinking",thinking:"..."}, {type:"image",...}, ...]
    // Plain strings show up on some paths and tool results. This helper
    // accepts both shapes and returns the concatenated text portion (skipping
    // thinking blocks — those are surfaced separately via the streaming protocol).
    private fun extractText(message: Map<*, *>): String {
        val c = message["content"] ?: return ""
        if (c is String) return c
        if (c is List<*>) {
            return c.mapNotNull { block ->
                if (block !is Map<*, *>) return@mapNotNull null
                when (block["type"] as? String) {
                    "text" -> block["text"] as? String
                    else  -> null   // skip thinking, image, tool_use, …
                }
            }.joinToString("")
        }
        return ""
    }

    private fun msgEnd(state: AgentState, j: Map<*, *>) {
        val role = Js.gets(j, "role")
        if (role == "assistant") es(state, null)
        val stream = Js.gets(j, "stream")
        if (role != "user") {
            // Custom-message entries carry the host's own rendering — a stream
            // on new hosts, ANSI lines on older ones. Show inline verbatim.
            val lines = (j["ansiLines"] as? List<*>)?.filterIsInstance<String>()
                ?.takeIf { it.isNotEmpty() }
            if (stream != null || lines != null) {
                state.messages.value = state.messages.value + ChatMessage(
                    type = MessageToolType.Custom,
                    content = Js.gets(j, "customType") ?: "",
                    stream = stream,
                    ansiLines = lines,
                )
            }
            return
        }
        // User messages: hosts attach the rendered stream (with inline images)
        // plus any oversize images as a structured array; older hosts send
        // images only. The bubble usually already exists from message_start or
        // a local echo — upgrade it in place instead of appending a duplicate.
        val userImages = parseChatImages(j["images"])
            .ifEmpty { if (stream == null) parseChatImages(j) else emptyList() }
        if (stream == null && userImages.isEmpty()) return
        val content = extractText(j)
        val ml = state.messages.value.toMutableList()
        val idx = ml.indexOfLast { it.type == MessageToolType.User }
        val existing = ml.getOrNull(idx)
        if (existing != null && existing.content == content) {
            ml[idx] = existing.copy(
                stream = stream ?: existing.stream,
                images = if (userImages.isNotEmpty()) userImages else existing.images,
            )
            state.messages.value = ml
        } else {
            state.messages.value = ml + ChatMessage(
                type = MessageToolType.User,
                content = content,
                images = userImages,
                stream = stream,
            )
        }
    }

    private fun msgUpd(state: AgentState, j: Map<*, *>) {
        val ev = Js.gets(j, "eventType") ?: return
        if (ev == "text_start") { state.stxt = ""; addP(state) }
        if (ev == "text_delta") { val d = Js.gets(j, "delta") ?: return; state.stxt += d; pushSt(state, state.stxt); state.assistingText.value = state.stxt }
        if (ev == "text_end") es(state, state.stxt, Js.gets(j, "stream"))
        // Styled streaming: the host periodically re-renders the in-flight
        // text/thinking block and ships a snapshot; show it on the Streaming
        // bubble so markdown styling appears while the text streams.
        if (ev == "ansi_snapshot") {
            val stream = Js.gets(j, "stream") ?: return
            val ml = state.messages.value.toMutableList()
            val idx = ml.indexOfLast { it.type == MessageToolType.Streaming }
            if (idx >= 0) { ml[idx] = ml[idx].copy(stream = stream); state.messages.value = ml }
        }
        if (ev == "thinking_start") state.thinking.value = ""
        if (ev == "thinking_delta") { val d = Js.gets(j, "delta") ?: return; state.thinking.value += d; state.assistingText.value = state.thinking.value }
        if (ev == "thinking_end") {
            // Convert thinking text to a Thinking message type instead of dropping it
            val thinkingText = state.thinking.value
            if (thinkingText.isNotBlank()) {
                state.messages.value = state.messages.value + ChatMessage(
                    type = MessageToolType.Thinking, content = thinkingText,
                    stream = Js.gets(j, "stream"),
                )
            } else {
                // Remove blank streaming placeholders
                state.messages.value = state.messages.value.filterNot { it.type == MessageToolType.Streaming && it.content.isBlank() }
            }
            state.thinking.value = ""
            // Drop any thinking ansi_snapshot left on the Streaming bubble so
            // the upcoming text stream doesn't show stale thinking rendering.
            run {
                val ml = state.messages.value.toMutableList()
                val idx = ml.indexOfLast { it.type == MessageToolType.Streaming }
                if (idx >= 0 && ml[idx].stream != null) {
                    ml[idx] = ml[idx].copy(stream = null)
                    state.messages.value = ml
                }
            }
            // Don't clear stxt here: pi can interleave thinking and text streams,
            // and thinking_end often arrives AFTER text_delta has populated stxt
            // (e.g. Qwen3.6: thinking_start → text_start → text_delta → thinking_end
            //  → text_end). Wiping stxt mid-stream made text_end see empty content,
            // drop the Streaming bubble, and never produce the Assistant message.
            // The text stream's own text_end → es(stxt) clears stxt at the right time.
        }
        if (ev == "done") es(state, null)
        if (ev == "error") { es(state, "Err: " + (Js.gets(j, "message") ?: "")) }
    }

    private fun toolStart(state: AgentState, j: Map<*, *>) {
        val id = Js.gets(j, "toolCallId") ?: ""
        val nm = Js.gets(j, "toolName") ?: "?"
        // Re-serialize the parsed args back to JSON. j["args"] is a Map from
        // JP, and the old `.toString()` produced Kotlin's `{k=v}` format that
        // can't be re-parsed downstream — every renderer that tried fell back
        // to a 60-char preview.
        val argsJson = Js.write(j["args"])
        state.tbufs[id] = StringBuilder()
        state.turnToolCalls.add(nm)
        state.messages.value = state.messages.value + ChatMessage(toolCallId = id, type = MessageToolType.Streaming, toolName = nm, toolArgs = argsJson)
    }

    private fun toolUpdate(state: AgentState, j: Map<*, *>) {
        val id = Js.gets(j, "toolCallId") ?: ""
        val content = Js.gets(j, "content") ?: ""
        val buf = state.tbufs[id]
        if (buf != null) {
            buf.append(content)
            val ml = state.messages.value.toMutableList()
            val idx = ml.indexOfLast { it.toolCallId == id && it.type == MessageToolType.Streaming }
            if (idx >= 0) {
                ml[idx] = ml[idx].copy(content = buf.toString())
                state.messages.value = ml
            }
        }
    }

    private fun toolEnd(state: AgentState, j: Map<*, *>) {
        val id = Js.gets(j, "toolCallId") ?: ""
        val nm = Js.gets(j, "toolName") ?: "?"
        val remoteContent = Js.gets(j, "content") ?: ""
        val isError = (j["isError"] as? Boolean) ?: false
        val bufferedContent = state.tbufs[id]?.toString().orEmpty()
        val content = if (remoteContent.isNotBlank()) remoteContent else bufferedContent
        // Host-rendered tool display (header + args + diff + result, with the
        // expanded variant for tap-to-expand). Older hosts send ANSI lines for
        // just the result renderer instead.
        val stream = Js.gets(j, "stream")
        val streamExpanded = Js.gets(j, "streamExpanded")
        val ansiLines = (j["ansiLines"] as? List<*>)?.filterIsInstance<String>()?.takeIf { it.isNotEmpty() }
        // Oversize images the host couldn't embed in the stream (or, on older
        // hosts, all tool-result images).
        val images = parseChatImages(j["images"])

        val ml = state.messages.value.toMutableList()
        val idx = ml.indexOfLast { it.toolCallId == id && it.type == MessageToolType.Streaming }
        if (idx >= 0) {
            ml[idx] = ChatMessage(
                id = ml[idx].id, toolCallId = id,
                type = MessageToolType.ToolResult,
                toolName = nm, toolArgs = ml[idx].toolArgs,
                content = content,
                isError = isError,
                stream = stream,
                streamExpanded = streamExpanded,
                ansiLines = ansiLines,
                images = images,
                timestamp = ml[idx].timestamp
            )
            state.messages.value = ml
        } else {
            state.messages.value = ml + ChatMessage(
                toolCallId = id, type = MessageToolType.ToolResult,
                toolName = nm, content = content, isError = isError,
                stream = stream, streamExpanded = streamExpanded,
                ansiLines = ansiLines, images = images
            )
        }
        state.tbufs.remove(id)
    }

    /** Parse images array from a JSON message/tool result. */
    private fun parseChatImages(raw: Any?): List<ChatImage> {
        val arr = raw as? List<*> ?: return emptyList()
        return arr.mapNotNull { block ->
            if (block !is Map<*, *>) return@mapNotNull null
            val data = (block["data"] as? String)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val mime = Js.gets(block, "mimeType") ?: "image/png"
            ChatImage(data = data, mimeType = mime)
        }
    }

    // Build a compact summary of tools used during the last turn
    private fun buildTurnSummary(state: AgentState) {
        if (state.turnToolCalls.isEmpty()) {
            state.turnSummary.value = null
            return
        }
        val counts = state.turnToolCalls.groupingBy { it }.eachCount()
        val toolsUsed = counts.entries
            .sortedByDescending { it.value }
            .map { (name, count) -> ToolCallSummary(name, count) }
        state.turnSummary.value = TurnSummary(
            toolsUsed = toolsUsed,
            totalCalls = state.turnToolCalls.size
        )
    }

    private fun addP(state: AgentState) {
        if (!state.messages.value.any { it.type == MessageToolType.Streaming && it.content == "" })
            state.messages.value = state.messages.value + ChatMessage(type = MessageToolType.Streaming)
    }
    private fun pushSt(state: AgentState, t: String) {
        // Match the last Streaming bubble unconditionally — pushSt is called with
        // the *accumulated* delta text, and predicating on content=="" meant only
        // the first delta ever landed in the persisted message; subsequent deltas
        // silently no-op'd and the bubble froze at the first chunk's text.
        val ml = state.messages.value.toMutableList()
        val idx = ml.indexOfLast { it.type == MessageToolType.Streaming }
        if (idx >= 0) { ml[idx] = ml[idx].copy(content = t); state.messages.value = ml }
    }
    private fun es(state: AgentState, finalText: String?, stream: String? = null) {
        val sub = finalText ?: state.stxt
        if (sub.isNotBlank()) {
            val ml = state.messages.value.toMutableList()
            val idx = ml.indexOfLast { it.type == MessageToolType.Streaming }
            // Always transition the last Streaming bubble in-place; the old
            // isEmpty() guard caused a second Assistant message to be appended
            // when the bubble had already been populated by pushSt.
            // [stream] is the host's final rendering — it REPLACES any interim
            // ansi_snapshot (null clears it: a stale snapshot may be missing
            // the tail of the message, plain content is at least complete).
            if (idx >= 0) {
                ml[idx] = ml[idx].copy(type = MessageToolType.Assistant, content = sub, stream = stream)
                state.messages.value = ml
            } else { state.messages.value = ml + ChatMessage(type = MessageToolType.Assistant, content = sub, stream = stream) }
        } else {
            state.messages.value = state.messages.value.filterNot { it.type == MessageToolType.Streaming && it.content.isBlank() }
        }
        state.stxt = ""
        state.assistingText.value = ""
    }
    /**
     * Apply a full conversation replay from the host. Sent on every (re)connect
     * so the phone shows the whole thread — including turns that happened in the
     * terminal or on another device before this client joined — not just events
     * from the moment it connected.
     *
     * The frame is authoritative: it REPLACES the target agent's message list
     * (rather than appending) so a reconnect repaints cleanly without duplicating
     * the live events that follow. Each item is a pre-shaped bubble; see the
     * extension's sendHistory() for the wire format.
     */
    private fun handleHistory(j: Map<*, *>) {
        val agentId = Js.gets(j, "agentId")
        if (agentId.isNullOrBlank()) return
        val arr = j["messages"] as? List<*> ?: return
        val rebuilt = arr.mapNotNull { item ->
            if (item !is Map<*, *>) return@mapNotNull null
            val role = Js.gets(item, "role") ?: return@mapNotNull null
            val stream = Js.gets(item, "stream")
            val streamExpanded = Js.gets(item, "streamExpanded")
            val ansiLines = (item["ansiLines"] as? List<*>)
                ?.filterIsInstance<String>()?.takeIf { it.isNotEmpty() }
            val images = parseChatImages(item["images"])
            when (role) {
                "user" -> ChatMessage(
                    type = MessageToolType.User,
                    content = Js.gets(item, "content") ?: "",
                    images = images,
                    stream = stream,
                )
                "assistant" -> ChatMessage(
                    type = MessageToolType.Assistant,
                    content = Js.gets(item, "content") ?: "",
                    stream = stream,
                )
                "thinking" -> ChatMessage(
                    type = MessageToolType.Thinking,
                    content = Js.gets(item, "content") ?: "",
                    stream = stream,
                )
                "tool" -> ChatMessage(
                    type = MessageToolType.ToolResult,
                    toolCallId = Js.gets(item, "toolCallId") ?: "",
                    toolName = Js.gets(item, "toolName") ?: "?",
                    toolArgs = Js.gets(item, "toolArgs") ?: "",
                    content = Js.gets(item, "content") ?: "",
                    isError = Js.getBool(item, "isError") ?: false,
                    stream = stream,
                    streamExpanded = streamExpanded,
                    ansiLines = ansiLines,
                    images = images,
                )
                "custom" -> if (stream != null || ansiLines != null) ChatMessage(
                    type = MessageToolType.Custom,
                    content = Js.gets(item, "customType") ?: "",
                    stream = stream,
                    ansiLines = ansiLines,
                ) else null
                else -> null
            }
        }

        val state = ensureAgent(agentId)
        state.isSelf = true
        val sid = Js.gets(j, "sessionId")
        if (!sid.isNullOrBlank()) state.sessionId = sid
        // Replace — the host's copy is the source of truth for everything up to
        // the connect point. Live message_*/tool_* events append after this.
        state.messages.value = rebuilt
        historyApplied = true

        // Drop the DB-restore placeholder, if any: its rows are now superseded by
        // this authoritative replay, and leaving it would let handleSessions
        // prepend the stale copy on the next session_list merge.
        if (agentMap.containsKey(REPO_PLACEHOLDER_ID)) {
            agentMap.remove(REPO_PLACEHOLDER_ID)
            _agents.value = agentMap.values.toList()
            if (_selectedAgentId.value == REPO_PLACEHOLDER_ID) _selectedAgentId.value = agentId
        }
    }

    private fun handleSessions(j: Map<*, *>) {
        val sessionsArr = j["sessions"] ?: return
        if (sessionsArr !is List<*>) return
        val list = sessionsArr.mapNotNull { s ->
            if (s !is Map<*, *>) return@mapNotNull null
            RemoteSession(
                id = (s["id"] as? String) ?: "",
                name = (s["name"] as? String) ?: "",
                kind = (s["kind"] as? String) ?: "peer",
                status = (s["status"] as? String) ?: "idle",
                lastActivity = ((s["lastActivity"] as? Number) ?: 0).toLong(),
                messageCount = ((s["messageCount"] as? Number) ?: 0).toInt(),
                turnIndex = ((s["turnIndex"] as? Number) ?: 0).toInt(),
                sessionId = (s["sessionId"] as? String) ?: ""
            )
        }
        _sessions.value = list

        // Make sure every connected pi has an AgentState bucket, with name/
        // kind populated from the session list so the tab row can label them.
        for (s in list) {
            if (s.id.isBlank()) continue
            val a = ensureAgent(s.id)
            a.name = s.name
            a.isSelf = s.isSelf
            // Sync busy from the server's session_list so reconnects don't
            // leave the app thinking idle when the agent is actually running.
            // Without this, the textfield enables and a regular prompt hits
            // "Agent is already processing" because deliverAs is undefined.
            a.busy.value = s.isActive
            // Host replaced the session (/new, /resume): the session id changed
            // from a known prior value → drop this agent's stale chat so the
            // fresh session shows empty. Skip on first sight (blank prior id) so
            // a normal reconnect (and DB-restored history) isn't wiped.
            if (s.sessionId.isNotBlank() && a.sessionId.isNotBlank() && a.sessionId != s.sessionId) {
                a.messages.value = emptyList()
                a.assistingText.value = ""
                a.thinking.value = ""
                a.turnSummary.value = null
                a.stxt = ""
                a.tbufs.clear()
                a.turnToolCalls.clear()
                // Self session replaced → also wipe persisted DB history so a
                // later reconnect's repoMessages doesn't repaint the old chat.
                if (s.isSelf) _sessionReset.tryEmit(Unit)
            }
            if (s.sessionId.isNotBlank()) a.sessionId = s.sessionId
        }
        // If the placeholder agent (from saved-DB restore) is still around,
        // promote it onto the real self agent now that we know its id.
        val placeholder = agentMap[REPO_PLACEHOLDER_ID]
        val self = list.firstOrNull { it.isSelf }
        if (placeholder != null && self != null && self.id != REPO_PLACEHOLDER_ID) {
            val selfState = ensureAgent(self.id)
            // Merge: prepend placeholder's saved history before any live
            // messages the self agent has already accumulated.
            selfState.messages.value = placeholder.messages.value + selfState.messages.value
            agentMap.remove(REPO_PLACEHOLDER_ID)
            _agents.value = agentMap.values.toList()
            if (_selectedAgentId.value == REPO_PLACEHOLDER_ID) _selectedAgentId.value = self.id
        }
        // Prune agents absent from the session list — e.g. previous host
        // processes (each pi restart mints a new self id) or closed peers.
        // Without this, dead tabs linger and the selection can get stuck on a
        // vanished agent, so messages silently go nowhere.
        val liveIds = list.map { it.id }.toSet()
        val stale = agentMap.keys.filter { it != REPO_PLACEHOLDER_ID && it !in liveIds }
        if (stale.isNotEmpty()) {
            stale.forEach { agentMap.remove(it) }
            _agents.value = agentMap.values.toList()
        }
        // Keep the current selection only if it points at a live session;
        // otherwise fall back to the self agent (so sends reach the live host).
        val curr = _selectedAgentId.value
        if (curr == null || curr !in liveIds) {
            _selectedAgentId.value = self?.id ?: list.firstOrNull()?.id
        }
    }

    private fun handleSavedSessions(j: Map<*, *>) {
        val arr = j["sessions"] as? List<*> ?: return
        _savedSessions.value = arr.mapNotNull { s ->
            if (s !is Map<*, *>) return@mapNotNull null
            SavedSession(
                path = (s["path"] as? String) ?: return@mapNotNull null,
                name = (s["name"] as? String) ?: "",
                firstMessage = (s["firstMessage"] as? String) ?: "",
                messageCount = ((s["messageCount"] as? Number) ?: 0).toInt(),
                modified = ((s["modified"] as? Number) ?: 0).toLong(),
                status = (s["status"] as? String) ?: "completed",
                lastAssistantMessage = (s["lastAssistantMessage"] as? String) ?: ""
            )
        }
    }

    private fun handleHostDirs(j: Map<*, *>) {
        val arr = j["dirs"] as? List<*> ?: return
        val path = Js.gets(j, "path") ?: ""
        val dirs = arr.mapNotNull { d ->
            if (d !is Map<*, *>) return@mapNotNull null
            HostDir(
                name = (d["name"] as? String) ?: "",
                path = (d["path"] as? String) ?: ""
            )
        }
        _hostDirs.value = HostDirsResponse(dirs, path)
        _hostDirsError.value = null
    }

    private fun handleMkdir(j: Map<*, *>) {
        val id = Js.gets(j, "id")?.toLongOrNull() ?: return
        val path = Js.gets(j, "path") ?: ""
        val name = Js.gets(j, "name") ?: ""
        _mkdirResult.tryEmit(MkdirResult(id, path, name, true))
    }

    private fun handleMkdirError(j: Map<*, *>) {
        val id = Js.gets(j, "id")?.toLongOrNull() ?: return
        val path = Js.gets(j, "path") ?: ""
        val name = Js.gets(j, "name") ?: ""
        _mkdirResult.tryEmit(MkdirResult(id, path, name, false, Js.gets(j, "message") ?: "unknown error"))
    }

    private fun handleExtensionUI(j: Map<*, *>) {
        val method = Js.gets(j, "method") ?: return
        val id = Js.gets(j, "id") ?: return

        // Fire-and-forget methods
        if (method == "notify") {
            // Prefer "message" (canonical field); fall back to "content" only if
            // it actually exists as a non-null value. If neither is present, use
            // a default. This avoids accidentally passing null when a non-string
            // field (e.g. an integer) occupies one of the slots.
            val msg = when {
                "message" in j -> j["message"]?.toString() ?: "Notification"
                "content" in j -> j["content"]?.toString() ?: "Notification"
                else -> "Notification"
            }
            val nt = Js.gets(j, "notifyType") ?: Js.gets(j, "type") ?: "info"
            pushBanner(msg, nt)
            return
        }
        if (method == "setTitle") {
            val title = Js.gets(j, "title")
            _uiTitle.value = title
            return
        }
        if (method == "setStatus") {
            val key = Js.gets(j, "statusKey").takeUnless { it.isNullOrEmpty() } ?: j["key"]?.toString() ?: return
            val text = Js.gets(j, "statusText").takeUnless { it.isNullOrEmpty() } ?: j["text"]?.toString()
            val current = _statuses.value.toMutableMap()
            if (text != null && text != "null") current[key] = text else current.remove(key)
            _statuses.value = current
            return
        }
        if (method == "setWidget") {
            val key = Js.gets(j, "widgetKey") ?: return
            val lines = j["widgetLines"] as? List<*>
            val current = _widgets.value.toMutableMap()
            if (lines != null) current[key] = lines.map { it?.toString() ?: "" } else current.remove(key)
            _widgets.value = current
            return
        }
        // Dialog methods: add to pending requests
        val rawOpts = j["options"] as? List<*>
        val options = rawOpts?.map { it?.toString() ?: "" } ?: emptyList()

        val req = ExtensionUIRequest(
            id = id,
            method = method,
            title = Js.gets(j, "title") ?: "",
            message = Js.gets(j, "message"),
            options = options,
            placeholder = Js.gets(j, "placeholder"),
            prefill = Js.gets(j, "prefill")
        )

        _uiRequests.value = _uiRequests.value.toMutableList().apply { add(req) }
    }
}

// ── JSON helpers (hand-rolled parser) ──

object JP {
    fun p(s: String): Map<String, Any?>? = try { val p = PS(s.trim()); p.po() } catch (_: Throwable) { null }
    fun nj(p: Map<*, *>, k: String): Map<*, *>? = if (p[k] is Map<*, *>) p[k] as? Map<*, *> else null
}

class PS(val s: String) {
    var pp = 0; private val n = s.length
    private fun ws() { while (pp < n && s[pp].isWhitespace()) pp++ }
    private fun we(c: Char) { if (pp >= n || s[pp] != c) error("need $c"); pp++ }
    fun po(): Map<String, Any?> {
        ws(); we('{'); ws(); val m = mutableMapOf<String, Any?>()
        if (pp < n && s[pp] == '}') { pp++; return m }
        while (true) {
            ws(); val k = ps(); ws(); we(':'); ws()
            m[k] = pv()
            ws()
            if (pp < n && s[pp] == ',') { pp++; continue }
            we('}'); return m
        }
    }
    private fun pv(): Any? {
        ws()
        return if (pp >= n) "" else {
            if (s[pp] == '"') ps()
            else if (s[pp] == '{') po()
            else if (s[pp] == '[') pa()
            else if (s.startsWith("null", pp)) { pp += 4; null }
            else if (s.startsWith("true", pp)) { pp += 4; true }
            else if (s.startsWith("false", pp)) { pp += 5; false }
            else pn()
        }
    }
    private fun pa(): List<Any?> {
        ws(); we('['); ws()
        val list = mutableListOf<Any?>()
        if (pp < n && s[pp] == ']') { pp++; return list }
        while (true) {
            ws(); list.add(pv()); ws()
            if (pp < n && s[pp] == ',') { pp++; continue }
            we(']'); return list
        }
    }
    fun ps(): String {
        ws(); we('"'); val sb = StringBuilder()
        while (pp < n) {
            val c = s[pp]
            if (c == '\\') {
                pp++; val ec = s[pp]
                if (ec == 'u' && pp + 4 < n) {
                    val code = s.substring(pp + 1, pp + 5).toIntOrNull(16)
                    if (code != null) { sb.append(code.toChar()); pp += 4 }
                    else sb.append(ec)
                } else {
                    sb.append(when(ec) { '"' -> '"'; '\\' -> '\\'; '/' -> '/'; 'b' -> '\b'; 'f' -> ''; 'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'; else -> ec })
                }
            } else if (c == '"') { pp++; return sb.toString() }
            else sb.append(c)
            pp++
        }
        return sb.toString()
    }
    private fun pn(): Any {
        val st = pp
        while (pp < n && (s[pp].isDigit() || s[pp] == '-' || s[pp] == '.' || s[pp] == 'e' || s[pp] == 'E' || s[pp] == '+')) pp++
        val raw = s.substring(st, pp)
        return if (raw.contains('.') || raw.contains('e') || raw.contains('E')) {
            raw.toDoubleOrNull() ?: raw
        } else {
            raw.toLongOrNull() ?: raw
        }
    }
}

// JSON utility helpers
object Js {
    fun gets(m: Map<*, *>, k: String): String? {
        val v = m[k] ?: return null
        return if (v is String) v else if (v is Number) v.toString() else if (v is Boolean) v.toString() else null
    }
    fun getBool(m: Map<*, *>, k: String): Boolean? = m[k] as? Boolean
    fun e(s: String): String {
        val basic = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
        // JSON forbids unescaped control characters; mirror input carries ESC
        // and other control bytes, so escape the remainder as \u00XX.
        if (basic.none { it.code < 0x20 }) return basic
        val sb = StringBuilder(basic.length + 8)
        for (ch in basic) {
            if (ch.code < 0x20) sb.append("\\u%04x".format(ch.code)) else sb.append(ch)
        }
        return sb.toString()
    }
    /** Round-trip a value parsed by JP back into a JSON string. Counterpart to
     *  PS.pv() — handles the same primitive set the parser produces. */
    fun write(v: Any?): String = when (v) {
        null -> "null"
        is Boolean -> v.toString()
        is Number -> v.toString()
        is String -> "\"${e(v)}\""
        is Map<*, *> -> v.entries.joinToString(",", "{", "}") { (k, vv) -> "\"${e(k.toString())}\":${write(vv)}" }
        is List<*> -> v.joinToString(",", "[", "]") { write(it) }
        else -> "\"${e(v.toString())}\""
    }
}

// ── Extension UI ──

data class ExtensionUIRequest(
    val id: String,
    val method: String,       // "select" | "confirm" | "input" | "editor"
    val title: String = "",
    val message: String? = null,
    val options: List<String> = emptyList(),
    val placeholder: String? = null,
    val prefill: String? = null
)

// ── Host directory browser ──

data class HostDir(
    val name: String,
    val path: String
)

/** Response from the host's directory listing: the directories found under a path. */
data class HostDirsResponse(
    val dirs: List<HostDir>,
    val basePath: String
)

/** Result of a mkdir operation — one-shot, identified by the caller-supplied id. */
data class MkdirResult(
    val id: Long,
    val path: String,
    val name: String,
    val success: Boolean,
    val errorMessage: String? = null
)

// ── Session model ──

/** A saved pi session — name, first message preview, message count, last
 *  modified epoch ms. Tap-to-resume from the saved-session browser sends
 *  spawn_peer with sessionPath = [path], which the extension launches as
 *  `pi --session <path>` in a new process. */
data class SavedSession(
    val path: String,
    val name: String,
    val firstMessage: String,
    val messageCount: Int,
    val modified: Long,
    val status: String = "completed",  // "working" | "completed" | "archived"
    val lastAssistantMessage: String = ""  // model's last reply (shown when waiting for user)
)

data class RemoteSession(
    val id: String,
    val name: String,
    val kind: String = "peer",
    val status: String,
    val lastActivity: Long,
    val messageCount: Int,
    val turnIndex: Int,
    val sessionId: String = ""  // pi session file id; changes on /new, /resume
) {
    val isActive: Boolean = status == "busy"
    val isSelf: Boolean = kind == "self"
}

/** Banner/toast from extension_ui notify() */
data class BannerMessage(
    val content: String,
    val type: String,        // "info" | "warning" | "error"
    val id: Long = 0         // identity for timed expiry (distinguishes equal texts)
)

/** One composed terminal frame from a mirrored pi TUI (host or peer). */
data class MirrorFrame(
    val seq: Int,
    val agentId: String,         // which agent's screen this is
    val lines: List<String>,     // full composed buffer, ANSI-styled
    val cursorRow: Int,          // -1 when no cursor
    val cursorCol: Int,
    val width: Int,              // host terminal columns
    val height: Int,             // host terminal rows (viewport = bottom rows)
    // PiPerf: nanoTime when this frame was decoded off the socket. Used to measure
    // received→rendered (UI commit) latency on the main thread. 0 = not stamped.
    val recvNanos: Long = 0L,
)

/** A file pushed from the host, awaiting a user save/share decision. */
data class FileDownload(
    val name: String,
    val mimeType: String,
    val data: String,            // base64
    val source: String = "",     // sending agent's display name (peer-aware), "" if host/unknown
)

/** Generic TUI render frame — any Pi extension UI component */
data class RenderFrame(
    val id: String,              // component ID for response matching
    val ansiLines: List<String>, // rendered ANSI-colored text lines
    val inputMode: String,       // "none" | "text" | "keys"
    val title: String = "",
    // Optional parallel array to [ansiLines]: lines whose corresponding entry
    // is non-empty are tappable, and the entry is the value sent back via
    // sendInput on tap. Empty/missing => the line is plain text. Lets a
    // render-frame menu (e.g. a session picker) be touch-driven without
    // building per-extension UI on the phone.
    val tapValues: List<String> = emptyList()
)

/**
 * TrustManager that accepts a server cert *only* if its SHA-256 fingerprint
 * (over the DER-encoded leaf) matches [expectedHex]. Used to pin the
 * self-signed TLS cert the pi-remote-control extension generates: the phone
 * scans the cert's fingerprint out of the QR alongside the auth token, and
 * this manager rejects any other cert (including a CA-signed one) — closing
 * the LAN-sniff attack surface even without a real PKI.
 *
 * Hostname matching is left to the OkHttp client's hostname verifier, which
 * is set to a no-op when we use this manager: the cert is bound by content,
 * not by name, so the IP-vs-CN mismatch that's normal here is fine.
 */
private class PinningTrustManager(expectedHex: String) : X509TrustManager {
    private val expected = expectedHex.lowercase()

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        if (chain.isEmpty()) throw CertificateException("empty server cert chain")
        val leaf = chain[0]
        val digest = MessageDigest.getInstance("SHA-256").digest(leaf.encoded)
        val actual = digest.joinToString("") { "%02x".format(it) }
        if (actual != expected) {
            throw CertificateException(
                "Pinned cert fingerprint mismatch — expected sha256:${expected.take(8)}…, got sha256:${actual.take(8)}…"
            )
        }
    }

    // Server-side TLS only; we don't validate client certs.
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}

    // Returning an empty array tells Android we don't trust any CA — every
    // cert must match the pin or be rejected.
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
