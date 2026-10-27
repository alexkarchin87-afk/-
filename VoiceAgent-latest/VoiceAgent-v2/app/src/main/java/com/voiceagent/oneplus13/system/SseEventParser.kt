package com.voiceagent.oneplus13.system

import org.json.JSONObject
import java.io.IOException

/** Backend wire protocol: one JSON data line per event and a required final [DONE]. */
internal class SseEventParser {
    var completed: Boolean = false
        private set
    private var chars = 0
    fun line(line: String): String? {
        if (completed || !line.startsWith("data:")) return null
        val data = line.removePrefix("data:").trim()
        if (data == "[DONE]") { completed = true; return null }
        val json = JSONObject(data)
        if (json.has("error")) throw IOException("SSE server reported an error")
        val text = json.optString("text")
        chars += text.length
        if (chars > 128 * 1024) throw IOException("SSE response too large")
        return text.takeIf { it.isNotEmpty() }
    }
    fun endOfInput() { if (!completed) throw IOException("SSE ended without [DONE]") }
}
