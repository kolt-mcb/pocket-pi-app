package com.piremote.test

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.piremote.AppState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.piremote.dataStore

private const val TAG = "PiTest"

/**
 * Debug-build-only ADB hook: drive the app's shared PiWebSocket via
 * broadcasts (see src/debug/AndroidManifest.xml for the intent filter and
 * the DUMP-permission guard). Not compiled into release builds.
 */
class TestReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Log.d(TAG, "RID:$intent.action received")
        val ws = AppState.ws
        when (intent.action) {
            "com.piremote.test.CONNECT" -> {
                val url = intent.getStringExtra("url") ?: "ws://10.0.2.2:8765"
                Log.d(TAG, "Connecting to: $url")
                ws.connect(url)
            }
            "com.piremote.test.SEND_PROMPT" -> {
                val msg = intent.getStringExtra("message") ?: "Hello from ADB test"
                Log.d(TAG, "Send prompt: $msg")
                ws.sendPrompt(msg)
            }
            "com.piremote.test.SEND_STEER" -> {
                val msg = intent.getStringExtra("message") ?: "Steer here"
                Log.d(TAG, "Send steer: $msg")
                ws.sendSteer(msg)
            }
            "com.piremote.test.SEND_FOLLOWUP" -> {
                val msg = intent.getStringExtra("message") ?: "Follow up"
                Log.d(TAG, "Send followup: $msg")
                ws.sendFollowUp(msg)
            }
            "com.piremote.test.DISCONNECT" -> {
                Log.d(TAG, "Disconnecting")
                ws.disconnect()
            }
            "com.piremote.test.CLEAR_HISTORY" -> {
                Log.d(TAG, "Clear history")
                // Off the main thread; goAsync keeps the receiver alive until done.
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        ctx.dataStore.edit { it[stringSetPreferencesKey("url_history")] = emptySet() }
                    } finally {
                        pending.finish()
                    }
                }
            }
            else -> Log.d(TAG, "Unknown action: ${intent.action}")
        }
    }
}
