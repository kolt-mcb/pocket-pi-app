package com.piremote

/**
 * App-wide connection singleton: the one PiWebSocket every screen (and the
 * debug-build TestReceiver) shares. Kept outside any ViewModel so the socket
 * survives configuration changes and stays reachable from broadcast receivers.
 */
object AppState {
    val ws = PiWebSocket()
}
