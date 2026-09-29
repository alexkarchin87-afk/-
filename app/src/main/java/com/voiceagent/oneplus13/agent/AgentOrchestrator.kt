package com.voiceagent.oneplus13.agent

import android.content.Context
import com.voiceagent.oneplus13.core.AgentEvent
import com.voiceagent.oneplus13.core.AgentEventBus
import com.voiceagent.oneplus13.core.ChatMessage
import com.voiceagent.oneplus13.core.FeatureRequests
import com.voiceagent.oneplus13.core.LLMClient
import com.voiceagent.oneplus13.core.TTSEngine
import com.voiceagent.oneplus13.core.UserMemory
import com.voiceagent.oneplus13.core.MemoryTier
import com.voiceagent.oneplus13.core.extractJsonObject
import com.voiceagent.oneplus13.core.security.SecretsStore
import com.voiceagent.oneplus13.devassist.CodeGenAgent
import com.voiceagent.oneplus13.devassist.ProjectStorage
import com.voiceagent.oneplus13.integrations.messenger.TokenCapture
import com.voiceagent.oneplus13.lecture.LectureCommands
import com.voiceagent.oneplus13.notes.NotesBuilder
import com.voiceagent.oneplus13.notes.NotesStorage
import com.voiceagent.oneplus13.system.ChatsDigest
import com.voiceagent.oneplus13.system.PendingProject
import com.voiceagent.oneplus13.system.SystemController
import com.voiceagent.oneplus13.workflow.WorkflowAiBuilder
import com.voiceagent.oneplus13.workflow.WorkflowRunner
import com.voiceagent.oneplus13.workflow.WorkflowStorage
import com.voiceagent.oneplus13.workflow.Workflow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.CoroutineScope
import org.json.JSONObject

private data class PendingConfirmation(val action: String, val params: Map<String, String>, val question: String)
private data class PendingCodeChoice(val projectName: String, val platform: String)

/**
 * Central dispatcher: takes a raw transcript, decides (via the LLM, with a small
 * fixed set of fast-path commands checked first) whether it's a lecture command,
 * a system action, a code-generation request, or just something to answer out
 * loud, and executes it.
 *
 * The LLM is instructed to respond either with plain text (spoken back to the user)
 * or with a JSON action envelope: {"action": "...", "params": {...}}.
 */
class AgentOrchestrator(
    private val context: Context,
    private val llm: LLMClient,
    private val tts: TTSEngine,
    scope: CoroutineScope
) {
    private val systemController = SystemController(context)
    private val lectureCommands = LectureCommands(context)
    private val secrets = SecretsStore(context)
    private val codeGenAgent = CodeGenAgent(llm)
    private val projectStorage = ProjectStorage(context)
    private val tokenCapture = TokenCapture(secrets)
    private val userMemory = UserMemory(context)
    private val notesBuilder = NotesBuilder(llm)
    private val notesStorage = NotesStorage(context)
    private val chatsDigest = ChatsDigest(context, llm)
    private val featureRequests = FeatureRequests(context)
    private val workflowStorage = WorkflowStorage(context)
    private val workflowRunner = WorkflowRunner(systemController, tts)
    private val workflowAiBuilder = WorkflowAiBuilder(llm)
    private val history = ArrayDeque<ChatMessage>(MAX_HISTORY)

    private var lastGeneratedProjectName: String? = null
    private var lastNotesTopic: String? = null
    private var pendingConfirmation: PendingConfirmation? = null
    private var pendingCodeChoice: PendingCodeChoice? = null

    init {
        // Listen for events raised outside a direct voice command (e.g. TokenCapture
        // spotting a BotFather message) and turn them into a spoken confirmation.
        AgentEventBus.events.onEach(::handleExternalEvent).launchIn(scope)
    }

    private fun buildSystemPrompt(): String {
        val base = """
            Ты — голосовой ассистент по имени Ваня на телефоне пользователя: дружелюбный,
            поддерживающий, по делу, без занудства и без "воды". Отвечай кратко
            (обычно 1-3 предложения) и по-русски: ответ будет озвучен вслух, а не прочитан,
            поэтому не используй маркдаун, списки, звёздочки или другое форматирование —
            только обычный связный текст, как в живой речи.
            Если запрос двусмысленный или не хватает важной детали — задай один короткий
            уточняющий вопрос вместо того, чтобы гадать и действовать наугад.
            Если нужно выполнить действие на телефоне, ответь СТРОГО JSON вида
            {"action": "<имя>", "params": {...}} без обрамления в тройные обратные
            кавычки (markdown code fence) и без какого-либо текста до или после JSON.
            Доступные действия:
            - open_app {package} — package можно указать и точным именем пакета
              ("com.spotify.music"), и обычным названием приложения ("Spotify") —
              если не уверен в точном пакете, укажи название, система сама найдёт
              среди установленных.
            - open_url {url}, set_volume {percent}, tap_text {label}, swipe_up {}
            - lecture_start {title}, lecture_stop {}, lecture_summary {}
            - generate_code {spec, project_name, platform} — используй, когда пользователь
              просит "напиши код", "сделай бота/скрипт/программу" и т.п. В "spec" перепиши
              просьбу пользователя как чёткое техническое задание для программиста
              (на русском или английском — как удобнее), не обязательно дословно. Если
              просьба абстрактная ("сделай ИИ-человека, который понимает, как прошёл
              день") — сформулируй "spec" так, будто сам додумал реальные скрытые
              требования (время/дата, сохранение состояния между запусками, простой
              анализ настроения по словам и т.п.), а не голый пересказ фразы.
              "platform" — "windows", если явно сказано "для компьютера/ноутбука/ПК",
              речь о родственнике без телефона-Android под рукой, или контекст явно про
              Windows-программу; иначе (по умолчанию) "android" — код выполняется на
              этом же телефоне через Termux.
            - continue_code {instruction, project_name} — используй, когда пользователь
              просит поправить/доработать/починить уже созданный ранее код ("добавь
              команду /help", "почини ошибку", "сделай ещё и..."), а не написать с нуля.
              "project_name" можно не указывать — тогда возьмётся последний
              сгенерированный проект. Платформа не указывается — она уже зафиксирована
              в самом проекте.
            - describe_screen {question} — РЕЖИМ "ЧТО МНЕ ДЕЛАТЬ": пользователь запутался
              в приложении/на сайте (регистрация, ошибка, куда нажать), а также любое
              продолжение вроде "и что дальше" в рамках такого диалога — экран мог
              измениться, читай заново, не отвечай по памяти.
            - identify_on_screen {question} — РЕЖИМ "ЧТО ЭТО": пользователь спрашивает
              "что это за картина/памятник/штука на экране", "как это называется" —
              нужна не инструкция по интерфейсу, а короткая справка (что/кто/когда).
            - now_playing {} — когда спрашивает "что за музыка играет", "что сейчас играет".
            - like_current_track {} — когда просит добавить то, что сейчас играет, в
              избранное/"мне нравится" в Яндекс Музыке.
            - chats_digest {} — когда спрашивает "что нового", "прочитай уведомления",
              "че там в чатах/почте/гитхабе" — пересказать, что накопилось в шторке
              уведомлений с прошлого раза.
            - run_diagnostics {} — когда спрашивает "всё работает?", "проверь себя",
              "почему не слышишь" и т.п. — самопроверка разрешений и ключей.
            - reply_notification {name, text} — "ответь Х текст" — быстрый ответ в
              фоне через уведомление, не открывая сам мессенджер. "name" — как
              пользователь назвал собеседника (можно неполным именем).
            - toggle_wifi {enabled}, toggle_bluetooth {enabled}, toggle_mobile_data
              {enabled} — "enabled": "true"/"false". Только рутованный телефон —
              если root недоступен, агент честно об этом скажет.
            - install_app {query} — "скачай/установи Х": имя приложения → откроет
              Play Маркет; прямая ссылка на .apk → скачает и откроет системный
              экран подтверждения установки (тихой установки без подтверждения
              пользователя НЕТ и не будет).
            - notes_build {topic, sources} — когда просит собрать конспект/конспект урока
              из присланного текста лекции/слайдов/учебника. "sources" — все переданные
              материалы одним текстом (раздели явными пометками, если источников
              несколько). Если среди материалов было описание прикреплённой пользователем
              схемы/картинки, включи в "sources" строку вида "Изображение схемы:
              [имя_файла — краткое описание]".
            - notes_extend {addition, topic} — когда просит дополнить/расширить уже
              собранный конспект ("добавь слова учителя про...", "распиши подробнее
              пункт про..."). "topic" можно не указывать — возьмётся последний конспект.
            - run_workflow {name} — когда просит запустить уже собранный сценарий
              ("запусти сценарий утро", "включи режим за рулём"), а не выполнить
              одно разовое действие.
            - create_workflow {description} — когда явно просит СОБРАТЬ/СОЗДАТЬ новый
              многошаговый сценарий из нескольких действий подряд ("сделай сценарий,
              который включает громкость на 50 и открывает Спотифай", "собери
              автоматизацию для..."). Не путай с разовой командой на одно действие —
              для одного действия используй open_app/set_volume/... напрямую, а не
              create_workflow.
            - remember_fact {fact, tier} — когда пользователь сообщает о себе что-то
              durable или явно просит "запомни, что ...". Сформулируй "fact" одной
              короткой фразой от третьего лица ("зовут Лёша", "учится в 8 классе").
              "tier": "P1" — только если пользователь явно сказал "запомни навсегда"/
              "сохрани железно" (такой факт нельзя будет стереть автосжатием, только
              явным "забудь"); иначе не указывай tier — по умолчанию факт долгосрочный,
              но заменяемый (например, класс в школе легко поменяется в будущем).
            - forget_fact {fact} — когда просит забыть что-то конкретное; "fact" — часть
              текста, по которой искать (например "возраст"). Работает для любого факта,
              включая "навсегда" — если пользователь сам явно попросил забыть.
            Если действие не требуется — просто ответь текстом, который будет озвучен.
        """.trimIndent()

        val facts = userMemory.factsBlock()
        return if (facts.isBlank()) base else "$base\n\n$facts"
    }

    suspend fun handleTranscript(text: String) {
        pendingCodeChoice?.let { pending ->
            when (classifyCodeChoice(text)) {
                CodeChoice.RUN_NOW -> {
                    pendingCodeChoice = null
                    handleRunNowChoice(pending)
                    return
                }
                CodeChoice.PIN_AS_COMMAND -> {
                    pendingCodeChoice = null
                    featureRequests.add(pending.projectName, "Сделать системной командой: \"${pending.projectName}\" (${pending.platform})")
                    tts.speak(
                        "Записал. Сам вшить это в себя я не могу — я всего лишь запущенное " +
                            "приложение, а не то, что его собирает, — но при следующем обновлении " +
                            "кода этот проект превратится в настоящую команду. Список ожидающих " +
                            "запросов — в Настройках."
                    )
                    return
                }
                CodeChoice.NONE -> { /* не похоже на ответ по коду — обрабатываем как новую команду */ }
            }
        }

        pendingConfirmation?.let { pending ->
            when (classifyYesNo(text)) {
                true -> {
                    pendingConfirmation = null
                    AgentEventBus.publish(AgentEvent.ActionRequested(pending.action, pending.params))
                    dispatch(pending.action, pending.params)
                    return
                }
                false -> {
                    pendingConfirmation = null
                    tts.speak("Хорошо, не буду.")
                    return
                }
                null -> { /* not a clear yes/no — fall through and treat as a new command */ }
            }
        }

        workflowStorage.findByTriggerIn(text)?.let { workflow ->
            AgentEventBus.publish(AgentEvent.ActionRequested("run_workflow", mapOf("name" to workflow.name)))
            runWorkflow(workflow)
            return
        }

        // "Спроси Клода ..." — мгновенно в обход классификатора и черновика
        // DeepSeek, прямо к Claude. Один сетевой вызов вместо потенциальных
        // двух — дешевле по токенам, а не дороже, ровно как просили.
        extractAskClaudeQuery(text)?.let { question ->
            AgentEventBus.publish(AgentEvent.ThinkingStarted(question))
            try {
                val reply = llm.askClaudeDirect(buildSystemPrompt(), history.toList(), question)
                remember(ChatMessage("user", question))
                remember(ChatMessage("assistant", reply))
                AgentEventBus.publish(AgentEvent.Reply(reply))
                tts.speak(reply)
            } catch (t: Throwable) {
                AgentEventBus.publish(AgentEvent.Error("Не удалось спросить Claude", t))
                tts.speak("Не получилось спросить Claude — проверь ключ в Настройках.")
            }
            return
        }

        AgentEventBus.publish(AgentEvent.ThinkingStarted(text))
        try {
            val reply = llm.complete(buildSystemPrompt(), history.toList(), text)
            remember(ChatMessage("user", text))
            remember(ChatMessage("assistant", reply))

            val action = tryParseAction(reply)
            if (action != null) {
                AgentEventBus.publish(AgentEvent.ActionRequested(action.first, action.second))
                dispatch(action.first, action.second)
            } else {
                AgentEventBus.publish(AgentEvent.Reply(reply))
                tts.speak(reply)
            }
        } catch (t: Throwable) {
            AgentEventBus.publish(AgentEvent.Error("Не удалось обработать команду", t))
            tts.speak("Что-то пошло не так, попробуй ещё раз.")
        }
    }

    /** Handles events that didn't come from a direct voice command, e.g. TokenCapture
     *  spotting a fresh BotFather token — these always require a spoken confirmation
     *  before VoiceAgent acts on a captured secret. */
    private suspend fun handleExternalEvent(event: AgentEvent) {
        if (event !is AgentEvent.ActionRequested) return
        if (event.action != "botfather_token_found") return

        val project = event.params["project"] ?: return
        val masked = event.params["masked_token"] ?: "токен"
        pendingConfirmation = PendingConfirmation(
            action = "apply_captured_token",
            params = mapOf("project" to project),
            question = "Похоже, BotFather прислал токен ($masked) для проекта \"$project\". Использовать его?"
        )
        tts.speak(pendingConfirmation!!.question)
    }

    private fun classifyYesNo(text: String): Boolean? {
        val t = text.trim().lowercase()
        val yes = setOf("да", "ага", "угу", "давай", "используй", "конечно", "yes")
        val no = setOf("нет", "не надо", "отмена", "no")
        return when {
            yes.any { t.contains(it) } -> true
            no.any { t.contains(it) } -> false
            else -> null
        }
    }

    /** "Спроси Клода, ..." / "спроси у Клода ..." — возвращает саму суть вопроса
     *  без этого префикса, либо null, если фраза не про это. */
    private fun extractAskClaudeQuery(text: String): String? {
        val trigger = Regex("(?i)^спроси\\s+(у\\s+)?клод[аеу]\\s*[,:]?\\s*")
        val match = trigger.find(text) ?: return null
        return text.substring(match.range.last + 1).trim().ifBlank { null }
    }

    private enum class CodeChoice { RUN_NOW, PIN_AS_COMMAND, NONE }

    /** Отвечает на закрывающий вопрос после генерации кода — строго то же
     *  меню, что и в самом вопросе (см. handleGenerateCode/handleContinueCode):
     *  "скомпилировать и запустить" или "пришить как системную команду". */
    private fun classifyCodeChoice(text: String): CodeChoice {
        val t = text.trim().lowercase()
        val runNow = setOf("скомпилир", "запусти", "запуск", "прямо сейчас", "готовое приложение", "новый экран")
        val pin = setOf("пришей", "пришить", "системн", "команд", "встрой", "добавь как команду")
        return when {
            pin.any { t.contains(it) } -> CodeChoice.PIN_AS_COMMAND
            runNow.any { t.contains(it) } -> CodeChoice.RUN_NOW
            else -> CodeChoice.NONE
        }
    }

    /** "Запустить сейчас" — честно, без обещаний того, чего телефон физически
     *  не может: для Android можно реально открыть Termux, где лежит код
     *  (сам код за пользователя не запускаем — Termux всё равно спросит
     *  разрешения на первый запуск); для Windows зип нужно донести до ПК,
     *  агент на телефоне не может собрать/выполнить .exe за него. */
    private suspend fun handleRunNowChoice(pending: PendingCodeChoice) {
        if (pending.platform.equals("windows", ignoreCase = true)) {
            tts.speak(
                "На этом телефоне .exe не соберётся — Windows-часть нужно донести до " +
                    "компьютера. Зип уже в Загрузках, оттуда можно переслать на ПК и один раз " +
                    "дважды кликнуть build.bat."
            )
            return
        }
        val opened = systemController.openApp("com.termux")
        tts.speak(
            if (opened) {
                "Открыл Termux — сама программу оттуда запусти командой из инструкции, " +
                    "я не выполняю код за тебя без подтверждения на каждый шаг."
            } else {
                "Не нашёл Termux на этом телефоне — установи его, чтобы запускать " +
                    "сгенерированный код прямо на OnePlus."
            }
        )
    }

    private fun tryParseAction(reply: String): Pair<String, Map<String, String>>? {
        val candidate = extractJsonObject(reply) ?: return null
        return try {
            val json = JSONObject(candidate)
            val action = json.optString("action").takeIf { it.isNotBlank() } ?: return null
            val paramsJson = json.optJSONObject("params") ?: JSONObject()
            val params = paramsJson.keys().asSequence().associateWith { paramsJson.getString(it) }
            action to params
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun dispatch(action: String, params: Map<String, String>) {
        when (action) {
            "open_app" -> params["package"]?.let { systemController.openApp(it) }
            "open_url" -> params["url"]?.let { systemController.openUrl(it) }
            "set_volume" -> params["percent"]?.toIntOrNull()?.let { systemController.setMediaVolume(it) }
            "tap_text" -> params["label"]?.let { systemController.tapByText(it) }
            "swipe_up" -> systemController.swipeUp()
            "lecture_start" -> lectureCommands.start(params["title"] ?: "Лекция")
            "lecture_stop" -> lectureCommands.stop()
            "lecture_summary" -> tts.speak(lectureCommands.latestSummary() ?: "Пока нет сохранённых лекций")
            "generate_code" -> handleGenerateCode(params)
            "continue_code" -> handleContinueCode(params)
            "describe_screen" -> handleDescribeScreen(params)
            "identify_on_screen" -> handleIdentifyOnScreen(params)
            "now_playing" -> handleNowPlaying()
            "like_current_track" -> handleLikeCurrentTrack()
            "chats_digest" -> handleChatsDigest()
            "run_diagnostics" -> handleRunDiagnostics()
            "reply_notification" -> handleReplyNotification(params)
            "toggle_wifi" -> handleRootToggle(RootActions.Toggle.WIFI, params)
            "toggle_bluetooth" -> handleRootToggle(RootActions.Toggle.BLUETOOTH, params)
            "toggle_mobile_data" -> handleRootToggle(RootActions.Toggle.MOBILE_DATA, params)
            "install_app" -> handleInstallApp(params)
            "notes_build" -> handleNotesBuild(params)
            "notes_extend" -> handleNotesExtend(params)
            "run_workflow" -> handleRunWorkflowByName(params)
            "create_workflow" -> handleCreateWorkflow(params)
            "remember_fact" -> params["fact"]?.let { fact ->
                val tier = if (params["tier"]?.uppercase() == "P1") MemoryTier.P1 else MemoryTier.P2
                userMemory.remember(fact, tier)
                tts.speak("Запомнил.")
            }
            "forget_fact" -> params["fact"]?.let {
                val removed = userMemory.forget(it)
                tts.speak(if (removed > 0) "Забыл." else "Не нашёл такого среди того, что помню.")
            }
            "apply_captured_token" -> handleApplyCapturedToken(params)
            else -> AgentEventBus.publish(AgentEvent.Error("Неизвестное действие: $action"))
        }
    }

    private suspend fun handleGenerateCode(params: Map<String, String>) {
        val spec = params["spec"] ?: return
        val platform = params["platform"]?.takeIf { it.isNotBlank() } ?: "android"
        tts.speak(
            if (platform.equals("windows", ignoreCase = true)) "Пишу программу для Windows, секунду."
            else "Пишу код, секунду."
        )
        val project = codeGenAgent.generate(spec, platform)

        if (project.files.isEmpty()) {
            tts.speak(project.runInstructions.ifBlank { "Не могу сгенерировать этот код." })
            return
        }

        val zip = projectStorage.save(project)
        lastGeneratedProjectName = project.projectName
        AgentEventBus.publish(AgentEvent.ProjectReady(project.projectName, zip.absolutePath))

        val savedWhere = if (platform.equals("windows", ignoreCase = true)) {
            "сохранён в Загрузки — перекинь на компьютер"
        } else {
            "сохранён в Загрузки"
        }
        var message = "Готово. Проект \"${project.projectName}\" $savedWhere. ${project.runInstructions}"
        if (project.requiredSecrets.isNotEmpty()) {
            message += " Понадобится: ${project.requiredSecrets.joinToString(", ")}."
            // If the project needs a Telegram bot token, start listening for one
            // from BotFather so it can be offered automatically once it arrives.
            if (project.requiredSecrets.any { it.contains("TELEGRAM", ignoreCase = true) }) {
                PendingProject.awaitingTokenFor = project.projectName
                message += " Если попросишь новый токен у BotFather в Telegram, я предложу подставить его сюда."
            }
        }
        pendingCodeChoice = PendingCodeChoice(project.projectName, platform)
        tts.speak("$message $CODE_READY_PROMPT")
    }

    /** "Продолжить проект" — доработка уже сгенерированного кода вместо создания
     *  нового с нуля: подгружаем сохранённые файлы, просим LLM внести только
     *  нужные изменения (CodeGenAgent.revise), перезаписываем ту же папку/zip. */
    private suspend fun handleContinueCode(params: Map<String, String>) {
        val instruction = params["instruction"] ?: return
        val projectName = params["project_name"]?.takeIf { it.isNotBlank() } ?: lastGeneratedProjectName
        if (projectName == null) {
            tts.speak("Не помню, какой проект дорабатывать — скажи его имя.")
            return
        }

        val existingFiles = projectStorage.loadProjectFiles(projectName)
        if (existingFiles.isNullOrEmpty()) {
            tts.speak("Не нашёл сохранённый проект \"$projectName\".")
            return
        }
        val platform = projectStorage.loadProjectPlatform(projectName)

        tts.speak("Дорабатываю проект, секунду.")
        val revised = codeGenAgent.revise(existingFiles, instruction, projectName, platform)
        val zip = projectStorage.save(revised)
        lastGeneratedProjectName = revised.projectName
        AgentEventBus.publish(AgentEvent.ProjectReady(revised.projectName, zip.absolutePath))

        var continueMessage = "Готово. Обновил \"$projectName\". ${revised.runInstructions}"
        if (revised.requiredSecrets.isNotEmpty()) {
            continueMessage += " Понадобится: ${revised.requiredSecrets.joinToString(", ")}."
        }
        pendingCodeChoice = PendingCodeChoice(revised.projectName, platform)
        tts.speak("$continueMessage $CODE_READY_PROMPT")
    }

    /**
     * "Что на экране и что делать" — читает видимые тексты/подписи элементов через
     * [SystemController.readScreen] (Accessibility) и просит LLM превратить их в
     * короткую пошаговую голосовую инструкцию. Экран читается заново при каждом
     * вызове (в том числе на "а что дальше?"), а не переиспользуется из прошлого
     * ответа — он вполне мог измениться между шагами.
     */
    private suspend fun handleDescribeScreen(params: Map<String, String>) {
        val question = params["question"].orEmpty()
        val screenText = systemController.readScreen()
        if (screenText.isEmpty()) {
            tts.speak(
                "Не вижу содержимого экрана — проверь, что для VoiceAgent включён " +
                    "специальный доступ (Accessibility) в настройках."
            )
            return
        }

        val dump = screenText.joinToString(" · ").take(MAX_SCREEN_DUMP_CHARS)
        val screenPrompt = """
            Тебе дан список видимых надписей и подписей элементов на экране телефона
            пользователя (порядок обхода интерфейса, не обязательно порядок на экране).
            Объясни коротко, по шагам, что пользователь видит и что нажать, чтобы
            добиться того, что он просит. Каждый шаг — отдельное короткое предложение,
            начинающееся с глагола действия ("нажми…", "введи…", "пролистай…").
            Не придумывай элементы, которых нет в списке ниже — если непонятно, что
            нажать, честно скажи, что не уверен, и предложи пролистать экран.

            Видимые элементы экрана: $dump
        """.trimIndent()

        val reply = llm.complete(screenPrompt, emptyList(), question.ifBlank { "Что на экране и что делать?" })
        AgentEventBus.publish(AgentEvent.Reply(reply))
        tts.speak(reply)
    }

    /**
     * РЕЖИМ "ЧТО ЭТО" (энциклопедия): в отличие от [handleDescribeScreen], здесь не
     * нужна инструкция по интерфейсу — только распознать объект и дать короткую
     * справку. Ограничение: агент видит не картинку, а только видимые
     * тексты/подписи элементов экрана (Accessibility) — если на экране нет
     * подписи с названием объекта, честно скажет, что не может определить по
     * одному только тексту.
     */
    private suspend fun handleIdentifyOnScreen(params: Map<String, String>) {
        val question = params["question"].orEmpty()
        val screenText = systemController.readScreen()
        if (screenText.isEmpty()) {
            tts.speak(
                "Не вижу содержимого экрана — проверь, что для VoiceAgent включён " +
                    "специальный доступ (Accessibility) в настройках."
            )
            return
        }

        val dump = screenText.joinToString(" · ").take(MAX_SCREEN_DUMP_CHARS)
        val identifyPrompt = """
            Тебе дан список видимых надписей и подписей элементов на экране телефона.
            Пользователь спрашивает "что это" про что-то на экране (картина,
            достопримечательность, устройство и т.п.). Если по подписям на экране
            понятно, что это — назови точное имя/автора/год (если применимо) и дай
            интересную справку в 2-3 предложениях, БЕЗ инструкций, что нажимать.
            Если по этим подписям невозможно понять, что именно на экране — честно
            скажи, что не можешь определить только по видимому тексту.

            Видимые элементы экрана: $dump
        """.trimIndent()

        val reply = llm.complete(identifyPrompt, emptyList(), question.ifBlank { "Что это?" })
        AgentEventBus.publish(AgentEvent.Reply(reply))
        tts.speak(reply)
    }

    /** "Что нового / прочитай уведомления" — пересказ того, что накопилось в
     *  [com.voiceagent.oneplus13.system.NotificationBuffer] с прошлого раза. */
    private suspend fun handleChatsDigest() {
        val digest = chatsDigest.summarizeSinceLastDigest()
        AgentEventBus.publish(AgentEvent.Reply(digest))
        tts.speak(digest)
    }

    /** "Всё работает?" / "проверь себя" — самопроверка системных разрешений и
     *  ключей без похода в LLM: за секунду понятно, что именно не настроено,
     *  вместо непонятного "не отвечает". Полный список с кнопками — в
     *  SettingsActivity → «Диагностика». */
    private suspend fun handleRunDiagnostics() {
        val checks = com.voiceagent.oneplus13.system.Diagnostics.runAll(context)
        val failed = checks.filter { it.status == com.voiceagent.oneplus13.system.CheckStatus.FAIL }
        val warned = checks.filter { it.status == com.voiceagent.oneplus13.system.CheckStatus.WARNING }

        val summary = when {
            failed.isEmpty() && warned.isEmpty() -> "Всё в порядке, все проверки пройдены."
            failed.isNotEmpty() -> "Есть серьёзная проблема: ${failed.first().title.lowercase()} — " +
                "${failed.first().detail}. Полный список — в настройках, раздел «Диагностика»."
            else -> "В целом всё работает, но стоит проверить: ${warned.first().title.lowercase()}. " +
                "Подробности — в настройках, раздел «Диагностика»."
        }
        AgentEventBus.publish(AgentEvent.Reply(summary))
        tts.speak(summary)
    }

    /** "Ответь Вике: буду через 10 минут" — быстрый ответ в фоне через
     *  собственное действие уведомления мессенджера (RemoteInput), без
     *  открытия самого приложения. См. [com.voiceagent.oneplus13.system.NotificationReader.replyTo]. */
    private suspend fun handleReplyNotification(params: Map<String, String>) {
        val name = params["name"]
        val text = params["text"]
        if (name.isNullOrBlank() || text.isNullOrBlank()) {
            tts.speak("Скажи, кому и что ответить.")
            return
        }
        val reader = com.voiceagent.oneplus13.system.NotificationReader.instance
        if (reader == null) {
            tts.speak("Доступ к уведомлениям не включён — не могу ответить в фоне.")
            return
        }
        val sent = reader.replyTo(name, text)
        tts.speak(if (sent) "Отправил $name." else "Не нашёл свежее уведомление от $name с быстрым ответом.")
    }

    /** "Выключи вайфай" / "включи блютус" — root-переключатель из фиксированного
     *  списка (см. [com.voiceagent.oneplus13.system.RootActions] — LLM выбирает
     *  ТОЛЬКО имя действия, не пишет shell-команду сама). */
    private suspend fun handleRootToggle(toggle: com.voiceagent.oneplus13.system.RootActions.Toggle, params: Map<String, String>) {
        val enabled = params["enabled"]?.equals("true", ignoreCase = true) ?: true
        val result = com.voiceagent.oneplus13.system.RootActions.setToggle(toggle, enabled)
        val label = when (toggle) {
            com.voiceagent.oneplus13.system.RootActions.Toggle.WIFI -> "Wi-Fi"
            com.voiceagent.oneplus13.system.RootActions.Toggle.BLUETOOTH -> "Bluetooth"
            com.voiceagent.oneplus13.system.RootActions.Toggle.MOBILE_DATA -> "мобильный интернет"
        }
        val state = if (enabled) "включён" else "выключен"
        val message = when (result) {
            is com.voiceagent.oneplus13.system.RootActions.Result.Success -> "$label $state."
            is com.voiceagent.oneplus13.system.RootActions.Result.NoRoot ->
                "Нет root-доступа — либо телефон не рутован, либо приложению не выдали разрешение в KernelSU/Magisk."
            is com.voiceagent.oneplus13.system.RootActions.Result.Error ->
                "Не получилось: ${result.message}"
        }
        tts.speak(message)
    }

    /** "Скачай Х" — см. подробное объяснение в [SystemController.installApp]: без
     *  root-обхода экрана подтверждения установки. */
    private suspend fun handleInstallApp(params: Map<String, String>) {
        val target = params["query"] ?: return
        when (val outcome = systemController.installApp(target)) {
            is SystemController.InstallOutcome.OpenedPlayStore -> tts.speak("Открыл Play Маркет — там нажми «Установить».")
            is SystemController.InstallOutcome.OpenedUrl -> tts.speak("Открыл ссылку в браузере.")
            is SystemController.InstallOutcome.PromptedInstall -> tts.speak("Скачал — подтверди установку на экране.")
            is SystemController.InstallOutcome.Failed -> tts.speak("Не получилось скачать: ${outcome.reason}")
        }
    }

    /** "Собери конспект по..." — из присланных источников (текст лекции, слайды,
     *  учебник, описания схем) собирает один структурированный конспект и
     *  сохраняет его под именем темы, чтобы потом можно было дополнить. */
    private suspend fun handleNotesBuild(params: Map<String, String>) {
        val topic = params["topic"]?.takeIf { it.isNotBlank() } ?: "Конспект"
        val sources = params["sources"]
        if (sources.isNullOrBlank()) {
            tts.speak("Не вижу материалов для конспекта — пришли текст, слайды или фото учебника.")
            return
        }
        tts.speak("Собираю конспект, секунду.")
        val notes = notesBuilder.build(topic, listOf(sources))
        notesStorage.save(topic, notes)
        lastNotesTopic = topic
        AgentEventBus.publish(AgentEvent.Reply(notes))
        tts.speak("Готово, конспект \"$topic\" сохранён. Можешь попросить дополнить его позже.")
    }

    /** "Дополни конспект..." — находит нужное место в уже собранном конспекте и
     *  вписывает туда новую информацию, не переписывая всё с нуля. */
    private suspend fun handleNotesExtend(params: Map<String, String>) {
        val addition = params["addition"]
        if (addition.isNullOrBlank()) return
        val topic = params["topic"]?.takeIf { it.isNotBlank() } ?: lastNotesTopic ?: notesStorage.latestTopic()
        if (topic == null) {
            tts.speak("Не помню, какой конспект дополнять — скажи его тему.")
            return
        }
        val existing = notesStorage.load(topic)
        if (existing == null) {
            tts.speak("Не нашёл конспект \"$topic\".")
            return
        }
        tts.speak("Дополняю конспект, секунду.")
        val updated = notesBuilder.extend(existing, addition)
        notesStorage.save(topic, updated)
        lastNotesTopic = topic
        AgentEventBus.publish(AgentEvent.Reply(updated))
        tts.speak("Дополнил конспект \"$topic\".")
    }

    /** "Запусти сценарий Х" — ищет сохранённый сценарий по имени и выполняет. */
    private suspend fun handleRunWorkflowByName(params: Map<String, String>) {
        val name = params["name"]
        if (name.isNullOrBlank()) {
            tts.speak("Какой сценарий запустить?")
            return
        }
        val workflow = workflowStorage.findByName(name)
        if (workflow == null) {
            tts.speak("Не нашёл сценарий \"$name\". Собери его сначала в Мастерской сценариев.")
            return
        }
        runWorkflow(workflow)
    }

    private suspend fun runWorkflow(workflow: Workflow) {
        if (workflow.blocks.isEmpty()) {
            tts.speak("Сценарий \"${workflow.name}\" пустой — там нет ни одного блока.")
            return
        }
        tts.speak("Запускаю сценарий \"${workflow.name}\".")
        workflowRunner.run(workflow) { step ->
            if (step is WorkflowRunner.StepResult.Failed) {
                AgentEventBus.publish(AgentEvent.Error("Сценарий \"${workflow.name}\" остановился", null))
            }
        }
    }

    /** "Собери сценарий, который..." — то же самое, что делает режим ИИ в
     *  редакторе, но по голосу; итог сразу сохраняется и доступен по имени. */
    private suspend fun handleCreateWorkflow(params: Map<String, String>) {
        val description = params["description"]
        if (description.isNullOrBlank()) {
            tts.speak("Опиши, что должен делать сценарий.")
            return
        }
        tts.speak("Собираю сценарий, секунду.")
        val workflow = try {
            workflowAiBuilder.build(description)
        } catch (t: Throwable) {
            tts.speak("Не получилось собрать сценарий — попробуй описать проще или собери руками в Мастерской.")
            return
        }
        workflowStorage.save(workflow)
        AgentEventBus.publish(AgentEvent.Reply("Сценарий \"${workflow.name}\" готов — ${workflow.blocks.size} шагов."))
        tts.speak(
            "Готово, сценарий \"${workflow.name}\" из ${workflow.blocks.size} шагов сохранён. " +
                "Скажи \"запусти сценарий ${workflow.name}\", когда понадобится, или открой его в Мастерской, чтобы поправить."
        )
    }

    /** "Что за музыка играет" — берёт метаданные из активной медиасессии, какое бы
     *  приложение сейчас ни играло (не только Яндекс Музыка). */
    private fun handleNowPlaying() {
        val track = systemController.nowPlaying()
        if (track == null) {
            tts.speak(
                "Не вижу, что сейчас играет — либо ничего не воспроизводится, либо ещё " +
                    "не выдан доступ к уведомлениям (нужен для чтения медиасессий)."
            )
            return
        }
        val artistPart = track.artist?.let { " — $it" }.orEmpty()
        tts.speak("Сейчас играет: ${track.title}$artistPart.")
    }

    /** "Добавь в избранное в Яндекс Музыке" — открывает Яндекс Музыку (если там что-то
     *  играет) и тапает по кнопке "Мне нравится" через Accessibility. См.
     *  [SystemController.likeCurrentTrackInYandexMusic] о том, почему это UI-автоматизация,
     *  а не вызов API Яндекс Музыки. */
    private suspend fun handleLikeCurrentTrack() {
        val track = systemController.nowPlaying()
        if (track == null) {
            tts.speak("Не вижу, что сейчас играет в Яндекс Музыке.")
            return
        }
        val liked = systemController.likeCurrentTrackInYandexMusic()
        tts.speak(
            if (liked) "Добавил «${track.title}» в избранное в Яндекс Музыке."
            else "Не получилось — либо сейчас играет не Яндекс Музыка, либо не нашёл " +
                "кнопку «Мне нравится» на экране (нужен включённый специальный доступ)."
        )
    }

    private suspend fun handleApplyCapturedToken(params: Map<String, String>) {
        val project = params["project"] ?: return
        val token = tokenCapture.consumePendingToken(project)
        if (token == null) {
            tts.speak("Не нашёл сохранённый токен для этого проекта.")
            return
        }
        secrets.put("project_secret:$project:TELEGRAM_BOT_TOKEN", token)
        PendingProject.awaitingTokenFor = null
        tts.speak(
            "Токен сохранён. Перед запуском проекта \"$project\" через Termux экспортируй его: " +
                "export TELEGRAM_BOT_TOKEN=<значение из настроек VoiceAgent, раздел секреты>."
        )
    }

    private fun remember(message: ChatMessage) {
        if (history.size >= MAX_HISTORY) history.removeFirst()
        history.addLast(message)
    }

    companion object {
        private const val MAX_HISTORY = 12
        private const val CODE_READY_PROMPT = "Код готов! Что мне с ним сделать? Скомпилировать и " +
            "запустить прямо сейчас как готовое полноценное приложение (.exe на Windows или новый " +
            "экран на OnePlus 13)? Пришить эту утилиту к Ване как новую системную команду (НЕ игру!)?"
        private const val MAX_SCREEN_DUMP_CHARS = 3000
    }
}
