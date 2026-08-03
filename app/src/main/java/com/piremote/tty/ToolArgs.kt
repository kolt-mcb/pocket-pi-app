package com.piremote.tty

import com.piremote.JP
import com.piremote.Js

/**
 * Shared parsers for pi tool-call argument JSON.
 */

/**
 * Parse pi's edit / multiEdit args (`{path, edits: [{oldText, newText}, ...]}`)
 * into pairs of (oldText, newText). Returns empty list on any parse failure or
 * non-edit-shaped args — caller falls back to the existing result rendering.
 */
fun parseEdits(argsJson: String): List<Pair<String, String>> {
    if (argsJson.isBlank()) return emptyList()
    val m = try { JP.p(argsJson) } catch (_: Throwable) { null } ?: return emptyList()
    val arr = m["edits"] as? List<*> ?: return emptyList()
    return arr.mapNotNull { e ->
        if (e !is Map<*, *>) return@mapNotNull null
        val oldT = Js.gets(e, "oldText") ?: Js.gets(e, "old_str") ?: return@mapNotNull null
        val newT = Js.gets(e, "newText") ?: Js.gets(e, "new_str") ?: ""
        oldT to newT
    }
}

/**
 * Parse pi's write args (`{path, content}`) and return the content string,
 * or null if absent.
 */
fun parseWriteContent(argsJson: String): String? {
    if (argsJson.isBlank()) return null
    val m = try { JP.p(argsJson) } catch (_: Throwable) { null } ?: return null
    return Js.gets(m, "content")
}

/** One-line human preview of tool args: path, command, or name — else raw prefix. */
fun parseToolArgs(json: String): String {
    return try {
        val j = JP.p(json)
        j?.let { m ->
            val path = Js.gets(m, "path") ?: Js.gets(m, "file") ?: ""
            val cmd = Js.gets(m, "cmd") ?: Js.gets(m, "command") ?: ""
            val name = Js.gets(m, "name") ?: ""
            if (path.isNotBlank()) path
            else if (cmd.isNotBlank()) cmd
            else if (name.isNotBlank()) name
            else json.take(60)
        } ?: json.take(60)
    } catch (_: Exception) {
        json.take(60)
    }
}
