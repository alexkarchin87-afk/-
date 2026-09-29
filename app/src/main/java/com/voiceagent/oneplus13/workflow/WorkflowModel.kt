package com.voiceagent.oneplus13.workflow

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Типы блоков — сознательно НЕ произвольный список: каждый один в один
 * соответствует действию, которое [com.voiceagent.oneplus13.agent.AgentOrchestrator]
 * уже умеет выполнять по голосу. Это значит, что исполнение сценария не
 * добавляет новый непроверенный код — оно просто вызывает то же самое,
 * что уже работает для обычных голосовых команд (см. [WorkflowRunner]).
 *
 * [paramKeys] — что именно нужно спросить/показать в редакторе для этого
 * блока; [paramLabels] — как подписать поле в UI.
 */
enum class BlockType(val displayName: String, val paramKeys: List<String>, val paramLabels: List<String>) {
    OPEN_APP("Открыть приложение", listOf("package"), listOf("Пакет или имя приложения")),
    OPEN_URL("Открыть сайт", listOf("url"), listOf("Ссылка")),
    SET_VOLUME("Громкость", listOf("percent"), listOf("Процент (0-100)")),
    TAP_TEXT("Нажать на текст на экране", listOf("label"), listOf("Текст кнопки/надписи")),
    SWIPE_UP("Пролистать вверх", emptyList(), emptyList()),
    SPEAK("Сказать голосом", listOf("text"), listOf("Что сказать")),
    DELAY("Пауза", listOf("seconds"), listOf("Секунд"));

    companion object {
        fun fromId(id: String): BlockType? = entries.firstOrNull { it.name == id }
    }
}

data class WorkflowBlock(
    val id: String = UUID.randomUUID().toString().take(8),
    val type: BlockType,
    val params: MutableMap<String, String> = mutableMapOf(),
    var x: Float = 0f,
    var y: Float = 0f
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("type", type.name)
        put("params", JSONObject(params as Map<*, *>))
        put("x", x)
        put("y", y)
    }

    companion object {
        fun fromJson(json: JSONObject): WorkflowBlock? {
            val type = BlockType.fromId(json.optString("type")) ?: return null
            val paramsJson = json.optJSONObject("params") ?: JSONObject()
            val params = paramsJson.keys().asSequence().associateWith { paramsJson.getString(it) }.toMutableMap()
            return WorkflowBlock(
                id = json.optString("id").ifBlank { UUID.randomUUID().toString().take(8) },
                type = type,
                params = params,
                x = json.optDouble("x", 0.0).toFloat(),
                y = json.optDouble("y", 0.0).toFloat()
            )
        }
    }
}

/** Связь "после блока [fromId] выполнить блок [toId]" — сценарий линейный
 *  (без ветвлений в этой версии): у каждого блока не более одной исходящей
 *  связи, порядок выполнения читается прямо по ним. */
data class WorkflowConnection(val fromId: String, val toId: String) {
    fun toJson(): JSONObject = JSONObject().put("from", fromId).put("to", toId)

    companion object {
        fun fromJson(json: JSONObject): WorkflowConnection =
            WorkflowConnection(json.getString("from"), json.getString("to"))
    }
}

data class Workflow(
    val id: String = UUID.randomUUID().toString().take(8),
    var name: String,
    /** Фраза, при которой сценарий запускается сам, без похода к LLM — см.
     *  AgentOrchestrator.handleTranscript. Может быть пустой — тогда только
     *  вручную из редактора или командой "запусти сценарий <имя>". */
    var triggerPhrase: String? = null,
    val blocks: MutableList<WorkflowBlock> = mutableListOf(),
    val connections: MutableList<WorkflowConnection> = mutableListOf()
) {
    /** Блок, с которого начинать: тот, на который никто не ссылается как на "to". */
    fun startBlock(): WorkflowBlock? {
        val targets = connections.map { it.toId }.toSet()
        return blocks.firstOrNull { it.id !in targets } ?: blocks.firstOrNull()
    }

    fun next(blockId: String): WorkflowBlock? {
        val nextId = connections.firstOrNull { it.fromId == blockId }?.toId ?: return null
        return blocks.firstOrNull { it.id == nextId }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("triggerPhrase", triggerPhrase ?: JSONObject.NULL)
        put("blocks", JSONArray(blocks.map { it.toJson() }))
        put("connections", JSONArray(connections.map { it.toJson() }))
    }

    companion object {
        fun fromJson(json: JSONObject): Workflow {
            val blocksJson = json.optJSONArray("blocks") ?: JSONArray()
            val blocks = (0 until blocksJson.length())
                .mapNotNull { WorkflowBlock.fromJson(blocksJson.getJSONObject(it)) }
                .toMutableList()
            val connectionsJson = json.optJSONArray("connections") ?: JSONArray()
            val connections = (0 until connectionsJson.length())
                .map { WorkflowConnection.fromJson(connectionsJson.getJSONObject(it)) }
                .toMutableList()
            return Workflow(
                id = json.optString("id").ifBlank { UUID.randomUUID().toString().take(8) },
                name = json.optString("name", "Сценарий"),
                triggerPhrase = json.optString("triggerPhrase").takeIf { it.isNotBlank() && it != "null" },
                blocks = blocks,
                connections = connections
            )
        }
    }
}
