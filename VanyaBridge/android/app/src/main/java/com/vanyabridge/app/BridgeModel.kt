package com.vanyabridge.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.WebSocket
import org.json.JSONObject
import java.util.UUID

data class BridgeState(
    val status: String = "Не подключено", val paired: Boolean = false, val connected: Boolean = false,
    val busy: Boolean = false, val snapshot: JSONObject = JSONObject(), val error: String = "",
    val address: String = "", val sentVersion: Int = 0, val sentText: String = "",
    val activeChatId: String? = null, val newChatKind: String? = null
)

class BridgeModel(application: Application) : AndroidViewModel(application) {
    private val secrets = Secrets(application)
    private val mutable = MutableStateFlow(BridgeState())
    val state = mutable.asStateFlow()
    private var client: BridgeClient? = null
    private var socket: WebSocket? = null
    private var reconnect: Job? = null
    private var pairingJob: Job? = null
    private var foreground = false
    private var generation = 0
    private var pendingCommand: Triple<String, String, String>? = null
    private var pendingChatId: String? = null

    init {
        try {
            secrets.load()?.let { raw ->
                val value = JSONObject(raw)
                client = BridgeClient(value.getString("address"), value.getString("fingerprint"), value.getString("token"))
                mutable.value = mutable.value.copy(paired = true, address = value.getString("address"))
            }
        } catch (_: Exception) {
            mutable.value = mutable.value.copy(error = "Сохранённое подключение не читается; выполните сопряжение заново")
        }
    }

    fun foreground(enabled: Boolean) {
        foreground = enabled
        if (enabled && client != null) connect()
        if (!enabled) {
            generation++
            reconnect?.cancel(); socket?.cancel(); socket = null
            pairingJob?.cancel()
            mutable.value = mutable.value.copy(connected = false, status = "Соединение приостановлено")
        }
    }

    fun pair(address: String, fingerprint: String, code: String) {
        if (mutable.value.busy) return
        mutable.value = mutable.value.copy(busy = true, error = "", status = "Ожидаю подтверждения на ПК")
        pairingJob = viewModelScope.launch {
            var temporary: BridgeClient? = null
            try {
                val url = ServerIdentity.address(address)
                val pin = ServerIdentity.normalizeFingerprint(fingerprint)
                require(code.matches(Regex("[0-9]{6}"))) { "Введите шестизначный код" }
                val pairing = BridgeClient(url, pin)
                temporary = pairing
                val request = pairing.post("/pair/request", JSONObject().put("code", code).put("name", "Android VanyaBridge"))
                val credentials = withTimeout(120_000) {
                    var claim: JSONObject
                    do {
                        delay(1000)
                        claim = pairing.post("/pair/claim", request)
                    } while (claim.optString("status") != "paired")
                    claim
                }
                val saved = JSONObject().put("address", url).put("fingerprint", pin).put("token", credentials.getString("token"))
                secrets.save(saved.toString())
                client?.close()
                client = BridgeClient(url, pin, credentials.getString("token"))
                mutable.value = mutable.value.copy(paired = true, address = url)
                if (foreground) connect()
            } catch (error: CancellationException) {
                mutable.value = mutable.value.copy(error = "Сопряжение прервано или истекло; создайте новый код на ПК")
                throw error
            } catch (error: Exception) {
                mutable.value = mutable.value.copy(error = error.message ?: "Сопряжение не удалось", status = "Не подключено")
            } finally {
                temporary?.close()
                mutable.value = mutable.value.copy(busy = false)
            }
        }
    }

    private fun connect() {
        reconnect?.cancel(); socket?.cancel()
        val session = ++generation
        val active = client ?: return
        reconnect = viewModelScope.launch {
            var backoff = 1000L
            while (isActive && foreground && session == generation) {
                try {
                    val first = active.get("/snapshot")
                    ensureActive()
                    if (session != generation) return@launch
                    mutable.value = mutable.value.copy(connected = true, status = "ПК подключён", snapshot = first, error = "")
                    val closed = CompletableDeferred<Unit>()
                    socket = active.events({ snapshot ->
                        viewModelScope.launch {
                            if (session == generation && foreground) mutable.value = mutable.value.copy(snapshot = snapshot)
                        }
                    }, { closed.complete(Unit) })
                    backoff = 1000
                    closed.await()
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* safe status only; no body/token logging */ }
                if (session == generation) mutable.value = mutable.value.copy(connected = false,
                    status = "Нет связи. Повторное подключение…", error = "Проверьте ПК/Wi-Fi; при отзыве доступа выполните сопряжение заново")
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(30_000)
            }
        }
    }

    fun selectChat(chatId: String?) {
        mutable.value = mutable.value.copy(activeChatId = chatId, newChatKind = null)
    }

    fun createChat(kind: String) = action { active ->
        val chat = active.post("/chats", JSONObject().put("kind", kind))
        mutable.value = mutable.value.copy(activeChatId = chat.getString("id"), newChatKind = kind,
            snapshot = active.get("/snapshot"))
    }

    fun clearChatDraftSignal() {
        mutable.value = mutable.value.copy(newChatKind = null)
    }

    fun send(text: String, mode: String) {
        if (mutable.value.busy || !mutable.value.connected || text.isBlank()) return
        val chatId = mutable.value.activeChatId
        val previous = pendingCommand
        val request = if (previous?.first == text && previous.second == mode && pendingChatId == chatId) previous
            else Triple(text, mode, UUID.randomUUID().toString()).also { pendingCommand = it; pendingChatId = chatId }
        action {
            it.post("/tasks", JSONObject().put("text", text).put("mode", mode)
                .put("request_id", request.third).apply { if (chatId != null) put("chat_id", chatId) })
            pendingCommand = null
            pendingChatId = null
            mutable.value = mutable.value.copy(sentVersion = mutable.value.sentVersion + 1, sentText = text)
        }
    }

    fun approve(id: String, approved: Boolean) = action {
        it.post("/tasks/$id/approval", JSONObject().put("approve", approved))
    }
    fun cancel(id: String) = action { it.post("/tasks/$id/cancel", JSONObject()) }

    private fun action(block: suspend (BridgeClient) -> Unit) {
        val active = client ?: return
        if (mutable.value.busy || !mutable.value.connected) return
        mutable.value = mutable.value.copy(busy = true, error = "")
        viewModelScope.launch {
            try { block(active) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { mutable.value = mutable.value.copy(error = error.message ?: "Операция не подтверждена") }
            finally { mutable.value = mutable.value.copy(busy = false) }
        }
    }

    fun forget() {
        generation++; reconnect?.cancel(); pairingJob?.cancel(); socket?.cancel(); client?.close(); client = null
        secrets.clear(); pendingCommand = null; pendingChatId = null
        mutable.value = BridgeState()
    }

    override fun onCleared() {
        socket?.cancel(); client?.close()
    }
}
