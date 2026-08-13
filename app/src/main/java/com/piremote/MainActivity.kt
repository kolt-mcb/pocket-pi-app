package com.piremote

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.piremote.screens.ConnectScreen
import com.piremote.screens.ChatScreen
import com.piremote.screens.SessionsScreen
import com.piremote.screens.SelectDialog
import com.piremote.screens.InputDialog
import com.piremote.screens.TabletLayout
import com.piremote.screens.TabletConnectScreen
import com.piremote.screens.TerminalRenderView
import com.piremote.screens.FileDownloadDialog
import com.piremote.screens.clearImageCaches
import com.piremote.screens.piMono
import com.piremote.theme.error
import com.piremote.theme.textMuted
import androidx.compose.ui.unit.sp
import com.piremote.AppState
import com.piremote.theme.PiRemoteTheme
import com.piremote.theme.ThemeManager
import kotlinx.coroutines.flow.first

class MainActivity : ComponentActivity() {
    // Share PiWebSocket with test receiver so broadcasts work even via ADB
    private val ws = AppState.ws

    // Runtime permission launcher for POST_NOTIFICATIONS (API 33+)
    private val notificationsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(this, "Notification permission needed for connection status", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestNotificationsPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasPerm = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            if (!hasPerm) {
                notificationsPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationsPermission()
        setContent {
            PiRemoteTheme {
                Box(modifier = Modifier.fillMaxSize().safeContentPadding()) {
                // Pass applicationContext, not the Activity — the ViewModel
                // outlives the Activity across config changes, and its long-lived
                // collectors hold this Context; an Activity ref would leak the view tree.
                val vm: ChatViewModel = viewModel(factory = ChatViewModel.Factory(ws, applicationContext))
                val st = vm.st
                // Collect flows from the single state object — the Activity
                // never reaches past the ViewModel into PiWebSocket directly.
                val url by st.serverUrl.collectAsState()
                // NOTE: inputText / messages / streamingText are deliberately NOT
                // collected here — messages re-emits on every streamed token and
                // inputText on every keystroke, which would invalidate this root
                // scope each time. They're collected inside the (disconnected)
                // branches that actually render them.
                val status by st.status.collectAsState()
                val busy by st.busy.collectAsState()
                val urlHistory by st.urlHistory.collectAsState()
                val sessions by st.sessions.collectAsState()
                val selectedSession by st.selectedSession.collectAsState()
                val uiRequests by st.uiRequests.collectAsState()
                val compacting by st.compacting.collectAsState()
                val notifyBanners by st.notifyBanners.collectAsState()
                val uiTitle by st.uiTitle.collectAsState()
                val clientCount by st.clientCount.collectAsState()
                val savedSessions by st.savedSessions.collectAsState()
                val renderFrame by st.renderFrame.collectAsState()

                // Mirror the host Pi's active theme into the app palette.
                LaunchedEffect(Unit) {
                    ws.remoteThemeFlow.collect { remote ->
                        remote?.let { ThemeManager.applyRemote(it) }
                    }
                }

                // Load DataStore on startup
                LaunchedEffect(Unit) {
                    vm.loadUrlHistory()
                    val prefs = dataStore.data.first()
                    val lastUrl = prefs[KEY_URL] ?: ""
                    if (lastUrl.isNotBlank()) vm.setServerUrl(lastUrl)
                }

                // Detect tablet: width ≥ 600dp (matches WindowSizeClass.MEDIUM threshold).
                val isTablet = LocalConfiguration.current.screenWidthDp >= 600

                // Back-press handling differs by layout:
                // Phone: Chat → Sessions → Disconnect
                // Tablet: sidebar is always visible, so back asks to disconnect
                val currentScreen by vm.connectedScreen.collectAsState()
                var showDisconnectConfirm by remember { mutableStateOf(false) }
                if (status == ConnectionStatus.Connected) {
                    BackHandler {
                        if (isTablet) {
                            showDisconnectConfirm = true
                        } else {
                            when (currentScreen) {
                                ConnectedScreen.Chat -> vm.showSessionsScreen()
                                ConnectedScreen.Sessions -> vm.disconnect()
                            }
                        }
                    }
                }
                if (showDisconnectConfirm) {
                    AlertDialog(
                        onDismissRequest = { showDisconnectConfirm = false },
                        title = { Text("Disconnect", fontFamily = piMono, fontSize = 14.sp) },
                        text = {
                            Text(
                                "Disconnect from host?",
                                fontFamily = piMono, fontSize = 12.sp
                            )
                        },
                        confirmButton = {
                            TextButton(
                                onClick = { showDisconnectConfirm = false; vm.disconnect() }
                            ) {
                                Text("Disconnect", color = error, fontFamily = piMono)
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showDisconnectConfirm = false }) {
                                Text("Cancel", color = textMuted, fontFamily = piMono)
                            }
                        }
                    )
                }

                // Reconnect on resume if we have a URL but lost the connection.
                // Uses DisposableEffect so the observer is removed when the
                // composable leaves — prevents stacking stale observers and
                // firing with outdated `status`/`url` snapshots.
                DisposableEffect(lifecycle) {
                    val observer = LifecycleEventObserver { _, event ->
                        when (event) {
                            Lifecycle.Event.ON_RESUME -> {
                                if (status == ConnectionStatus.Disconnected && url.isNotEmpty()) {
                                    vm.connect()
                                }
                            }
                            // DO NOT disconnect on ON_STOP — foreground service owns the connection.
                            else -> {}
                        }
                    }
                    lifecycle.addObserver(observer)
                    onDispose { lifecycle.removeObserver(observer) }
                }

                // -- Extension UI helpers
                fun uiRespond(id: String, value: String) = ws.sendUIResponse(id, value = value)
                fun uiCancelled(id: String) = ws.sendUIResponse(id, cancelled = true)
                fun renderInput(id: String, value: String) = ws.sendInput(id, value)

                // Dialog requests
                val selReq = uiRequests.firstOrNull { it.method in listOf("select", "confirm") }
                val inpReq = uiRequests.firstOrNull { it.method in listOf("input", "editor") }

                when {
                    status is ConnectionStatus.Connected && isTablet ->
                        TabletLayout(
                            vm = vm,
                            st = st,
                            status = status,
                            busy = busy,
                            sessions = sessions,
                            selectedSession = selectedSession,
                            compacting = compacting,
                            notifyBanners = notifyBanners,
                            uiTitle = uiTitle,
                            clientCount = clientCount,
                            savedSessions = savedSessions,
                            renderFrame = renderFrame,
                        )
                    isTablet -> {
                        val inp by st.inputText.collectAsState()
                        val ms by st.messages.collectAsState()
                        val assist by st.streamingText.collectAsState()
                        TabletConnectScreen(
                            vm = vm,
                            url = url,
                            input = inp,
                            messages = ms,
                            assist = assist,
                            status = status,
                            urlHistory = urlHistory,
                            sessions = sessions,
                        )
                    }
                    // ── Phone layout (unchanged) ─────────────────
                    status == ConnectionStatus.Connected && currentScreen == ConnectedScreen.Chat ->
                        ChatScreen(vm, status, busy, sessions, selectedSession,
                            notifyBanners = notifyBanners,
                            uiTitle = uiTitle,
                            clientCount = clientCount
                        )
                    status == ConnectionStatus.Connected ->
                        SessionsScreen(vm, sessions, selectedSession, compacting, clientCount, savedSessions)
                    else -> {
                        val inp by st.inputText.collectAsState()
                        val ms by st.messages.collectAsState()
                        val assist by st.streamingText.collectAsState()
                        ConnectScreen(vm, url, inp, ms, assist, status, urlHistory, sessions)
                    }
                }

                // Host-pushed file downloads (both form factors)
                val fileDownload by vm.fileDownload.collectAsState()
                fileDownload?.let { FileDownloadDialog(it) { vm.clearFileDownload() } }

                // Overlay dialogs (phone only — tablet owns its own in TabletLayout)
                if (!isTablet) {
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
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Do NOT disconnect here — the WS singleton survives config changes
        // (orientation) and the foreground service keeps it alive in the
        // background. Let the ViewModel or explicit user action own disconnect.
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_BACKGROUND) clearImageCaches()
    }
}
