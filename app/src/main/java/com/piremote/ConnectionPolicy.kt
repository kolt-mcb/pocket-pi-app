package com.piremote

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch

/** How long the app can sit in the background with pi idle before we let the
 *  socket go. Long enough to cover app-switching and glancing at a
 *  notification; short enough that a phone in a pocket overnight isn't holding
 *  a pinged socket open. */
private const val IDLE_GRACE_MS = 2 * 60 * 1000L

/**
 * Owns *when* the websocket and the foreground service should be alive.
 *
 * The rules, in one place:
 *
 *  - **Foreground** → connected, and the FGS is up. We don't strictly need a
 *    foreground service while the app is visible, but starting it here is what
 *    makes the background transition legal: Android 12+ forbids *starting* an
 *    FGS from the background, so it has to already be running by the time we
 *    go away.
 *  - **Background + pi busy** → hold everything. A turn is in flight and the
 *    process must survive to deliver the "Pi is ready" notification.
 *  - **Background + pi idle for [IDLE_GRACE_MS]** → close the socket and stop
 *    the FGS. This is the battery fix: no more 15s keepalive ping forever, and
 *    it stops burning the Android 14+ `dataSync` 6h/24h quota on a connection
 *    that isn't doing anything, so the quota is still there when a turn
 *    actually needs it.
 *  - **Network appears / disappears** → retry at once, or stop retrying. Blind
 *    backoff against a dead radio was what exhausted the retry budget and left
 *    the app stuck in [ConnectionStatus.Error] until the user tapped connect.
 *
 * Coming back is meant to be invisible rather than literally uninterrupted: a
 * LAN reconnect lands in well under a second and the chat is already on screen
 * from the Room cache, so the user never sees the connect screen.
 */
class ConnectionPolicy(
    private val ctx: Context,
    private val ws: PiWebSocket,
    private val host: String,
    private val idleGraceMs: Long = IDLE_GRACE_MS,
) {

    @OptIn(kotlinx.coroutines.FlowPreview::class) // Flow.sample, for notification throttling
    fun run(scope: CoroutineScope) {
        // 1. Connectivity → socket. PiWebSocket owns what to do with it.
        scope.launch {
            onlineFlow().collect { up -> ws.setNetworkAvailable(up) }
        }

        // 2. Socket lifetime. Deliberately does NOT depend on messageFlow:
        //    that re-emits per streamed token, and collectLatest would cancel
        //    and restart the idle timer thousands of times a turn.
        scope.launch {
            combine(foregroundFlow(), ws.busyFlow, ws.statusFlow) { fg, busy, st ->
                Triple(fg, busy, st == ConnectionStatus.Connected)
            }
                .distinctUntilChanged()
                .collectLatest { (fg, busy, connected) ->
                    if (fg) {
                        ws.resumeFromIdle()   // no-op unless we released
                        ws.retryNow()         // no-op unless the link is really down
                        return@collectLatest
                    }
                    if (ws.isIdleSuspended) return@collectLatest   // already released
                    // Hold the link only for a turn that is genuinely in flight.
                    // `connected` matters here: busy can be left set by a drop
                    // mid-turn (onFailure doesn't clear it, only onClosed does),
                    // and without this a failed background link would retry all
                    // night behind a stuck busy flag.
                    if (busy && connected) return@collectLatest
                    // collectLatest cancels this delay if we come back to the
                    // foreground or pi starts a turn, so we only release if we
                    // stayed backgrounded and idle for the whole window.
                    delay(idleGraceMs)
                    ws.suspendForIdle()
                    PiService.stop(ctx)
                }
        }

        // 3a. Foreground-service presence. Unsampled and driven only by
        //     (foreground, connected), so the service is up the instant we
        //     connect while visible. That matters: this is the only moment we
        //     are allowed to *start* an FGS, and a sampled 1s lag was long
        //     enough for a connect-then-immediately-background to miss it and
        //     leave us with no service at all for the turn.
        //
        //     The asymmetry is deliberate: while backgrounded we update the
        //     service but never stop it. Android 12+ forbids starting an FGS
        //     from the background, so stopping it on a transient reconnect blip
        //     would throw away the only window we have to run one, and the
        //     process could then be killed mid-turn. The single background stop
        //     is the deliberate idle release in (2).
        scope.launch {
            combine(foregroundFlow(), ws.statusFlow) { fg, st ->
                fg to (st == ConnectionStatus.Connected)
            }
                .distinctUntilChanged()
                .collect { (fg, connected) ->
                    when {
                        connected && !ws.isIdleSuspended -> startService(ws.busyFlow.value)
                        fg -> PiService.stop(ctx)
                    }
                }
        }

        // 3b. Notification content ("Thinking…" / message count). Separate from
        //     presence because messageFlow re-emits per streamed token and each
        //     update is a binder round-trip; sample() caps that at 1/s, which is
        //     also roughly Android's own notification rate limit.
        scope.launch {
            combine(ws.statusFlow, ws.busyFlow, ws.messageFlow) { st, busy, msgs ->
                ServiceState(st == ConnectionStatus.Connected, busy, msgs.size)
            }
                .distinctUntilChanged()
                .sample(1_000)
                .collect { s ->
                    if (!s.connected || ws.isIdleSuspended) return@collect
                    startService(s.busy, s.messageCount)
                }
        }
    }

    private data class ServiceState(
        val connected: Boolean,
        val busy: Boolean,
        val messageCount: Int,
    )

    private fun startService(busy: Boolean, count: Int = ws.messageFlow.value.size) {
        try {
            PiService.start(ctx, host, busy, count)
        } catch (e: Exception) {
            Log.w("ConnectionPolicy", "FGS start failed: ${e.message}")
        }
    }

    /** App-wide foreground/background, from ProcessLifecycleOwner. Observers
     *  must be added and removed on the main thread, hence the flowOn. */
    private fun foregroundFlow(): Flow<Boolean> = callbackFlow {
        val lifecycle = ProcessLifecycleOwner.get().lifecycle
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> trySend(true)
                Lifecycle.Event.ON_STOP -> trySend(false)
                else -> {}
            }
        }
        trySend(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        lifecycle.addObserver(observer)
        awaitClose { lifecycle.removeObserver(observer) }
    }.flowOn(Dispatchers.Main.immediate).distinctUntilChanged()

    /** Is there any usable network? */
    private fun onlineFlow(): Flow<Boolean> = callbackFlow {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (cm == null) {
            // No ConnectivityManager (shouldn't happen): assume online so we
            // fall back to the plain backoff rather than never retrying.
            trySend(true)
            awaitClose { }
            return@callbackFlow
        }
        // Track the *set* of live networks, not a bare boolean. During a
        // Wi-Fi↔cell handoff Android delivers onAvailable for the new network
        // before onLost for the old one, so a boolean would flap false on a
        // handoff that never actually lost connectivity.
        val live = mutableSetOf<Network>()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                synchronized(live) { live.add(network); trySend(true) }
            }
            override fun onLost(network: Network) {
                synchronized(live) { live.remove(network); trySend(live.isNotEmpty()) }
            }
        }
        // NET_CAPABILITY_INTERNET, deliberately *not* NET_CAPABILITY_VALIDATED:
        // the pi is on the LAN, and a Wi-Fi network with no working uplink
        // (which drops VALIDATED) still reaches it perfectly well.
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        try {
            cm.registerNetworkCallback(request, callback)
        } catch (e: Exception) {
            Log.w("ConnectionPolicy", "registerNetworkCallback failed: ${e.message}")
            trySend(true)
            awaitClose { }
            return@callbackFlow
        }
        awaitClose {
            try { cm.unregisterNetworkCallback(callback) } catch (_: Exception) {}
        }
    }.distinctUntilChanged()
}
