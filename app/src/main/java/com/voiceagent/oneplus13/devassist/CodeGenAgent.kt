package com.voiceagent.oneplus13.devassist

import com.voiceagent.oneplus13.core.ChatMessage
import com.voiceagent.oneplus13.core.LLMClient
import com.voiceagent.oneplus13.core.extractJsonObject
import org.json.JSONObject

/**
 * Turns a spoken feature request ("напиши код для тг-бота, который отдаёт mp3 по
 * ссылке") into a small multi-file project for one of two targets — the phone
 * itself (Android/Termux, Python or Node) or a family member's Windows PC (always
 * Python + GUI, packaged for PyInstaller) — and can later revise an existing one
 * ("добавь команду /help", "почини ошибку с кодировкой") without regenerating it
 * from scratch. This is a code-writing assistant, not a deployer: it never runs
 * the generated code itself and never touches VoiceAgent's own app code (see
 * ProjectStorage, which sandboxes every generated project into its own folder) —
 * the user reviews and runs the result themselves.
 *
 * Deliberately refuses (by not even asking the LLM) requests that read as clearly
 * intended to break into systems, bypass paywalls/DRM, or mass-download copyrighted
 * media at scale; VoiceAgent should behave like a careful pair programmer, not an
 * unattended code executor with no judgement of its own.
 */
class CodeGenAgent(private val llm: LLMClient) {

    /** Общие для обеих платформ правила — секреты, отказ от вредоносного кода,
     *  разворачивание абстрактных просьб в реальную логику, запрет трогать
     *  сам VoiceAgent. Подставляется в оба системных промпта ниже. */
    private val sharedRules = """
        - Секреты (токены, ключи) НИКОГДА не встраивай в код как строковые литералы —
          всегда читай их из переменных окружения (os.environ / process.env) и перечисляй
          их имена в "required_secrets".
        - Если просьба абстрактная или личная ("сделай ИИ-человека, который понимает,
          как прошёл день", "напоминай пить воду", "веди дневник настроения") — не
          делай голую заглушку. Сам додумай разумные скрытые зависимости: системное
          время и дата для контекста, простое сохранение состояния между запусками
          (файл/sqlite), несложный анализ тона сообщения по ключевым словам, если
          в лоб просят "понимать настроение". Лучше немного избыточный, но реально
          полезный результат, чем формально отвечающий на просьбу пустой скелет.
        - Ты помогаешь писать ОТДЕЛЬНЫЙ, самостоятельный проект — никогда не
          предлагай встроить стороннее ПO, игру или библиотеку внутрь исходного
          кода самого VoiceAgent ("Вани"), не предлагай патчить/пересобирать его
          APK и не пиши файлы, которые выглядят как часть его кода. Если просьба
          по сути об этом — вежливо объясни в "run_instructions", что для этого
          нужен отдельный проект, а не встраивание в самого агента.
        - Не пиши код для обхода авторизации, вредоносного ПО, скрытой слежки или
          массового обхода блокировок/DRM — в этом случае верни файлы пустыми и
          честно объясни отказ в "run_instructions" (см. формат ниже).
    """.trimIndent()

    private val createSystemPromptAndroid = """
        Ты — ассистент программиста. По короткому голосовому описанию ты создаёшь
        небольшой, рабочий, самодостаточный проект (обычно 1-4 файла) для запуска
        НА САМОМ ТЕЛЕФОНЕ (Android, обычно через Termux).
        Правила:
        - Отвечай СТРОГО JSON без markdown-разметки и пояснений вне JSON:
          {"project_name": "snake_case_имя", "files": [{"path": "относительный/путь", "content": "..."}],
           "run_instructions": "как запустить, 2-4 предложения по-русски",
           "required_secrets": ["ИМЯ_ПЕРЕМЕННОЙ", ...]}
        - Код должен быть готов к запуску через Termux (`pip install -r
          requirements.txt && python bot.py` или `npm install && node index.js`),
          включая requirements.txt/package.json если нужно.
        - Если задача подразумевает скачивание видео/аудио по ссылке — используй
          yt-dlp и добавь в run_instructions напоминание уважать авторские права и
          пользовательские соглашения площадок (использовать только для контента,
          на который у пользователя есть права, или для явно разрешённых источников).
        $sharedRules
    """.trimIndent()

    private val createSystemPromptWindows = """
        Ты — ассистент программиста. По короткому голосовому описанию ты создаёшь
        небольшую программу ДЛЯ WINDOWS, которую обычный родственник без опыта
        программирования сможет запустить двойным кликом — без установки Python,
        без командной строки, без настройки окружения.
        Обязательные технические требования:
        - Язык — ТОЛЬКО Python.
        - Если нужен графический интерфейс (почти всегда нужен, если человек будет
          сам чем-то пользоваться) — используй CustomTkinter или Flet, не голый
          tkinter/PyQt и не консольный ввод/вывод.
        - Проект должен собираться в один автономный .exe через PyInstaller:
          обязательно включи файл `build.bat` с командой вида
          `pyinstaller --onefile --noconsole --name "ИмяПрограммы" main.py`
          (и `--add-data`, если есть иконки/ресурсы), и `requirements.txt`,
          включающий `pyinstaller` и `customtkinter`/`flet`.
        - В "run_instructions" опиши ДВА шага для родственника: (1) один раз
          дважды кликнуть `build.bat`, чтобы получить `dist/ИмяПрограммы.exe`;
          (2) дальше просто открывать готовый .exe двойным кликом — Python и
          командная строка ему для этого больше не понадобятся.
        - Никакого консольного окна при запуске готового .exe (`--noconsole`) —
          все сообщения и ошибки показывай через сам GUI (messagebox/диалоги).
        Формат ответа — СТРОГО JSON без markdown:
        {"project_name": "snake_case_имя", "files": [{"path": "относительный/путь", "content": "..."}],
         "run_instructions": "два шага выше, по-русски, простыми словами",
         "required_secrets": ["ИМЯ_ПЕРЕМЕННОЙ", ...]}
        $sharedRules
    """.trimIndent()

    private val revisePrompt = """
        Ты — ассистент программиста, дорабатывающий уже существующий проект (НЕ
        создаёшь с нуля). Тебе даны текущие файлы проекта и просьба, что изменить.
        Если проект был для Windows (есть build.bat/PyInstaller) — сохраняй эту же
        схему сборки в один .exe, не переводи проект на другой стек по ходу правок.
        Правила:
        - Отвечай СТРОГО JSON без markdown: {"files": [{"path": "...", "content": "..."}],
          "run_instructions": "что изменилось и как запустить, 2-4 предложения",
          "required_secrets": ["ИМЯ_ПЕРЕМЕННОЙ", ...]}
        - В "files" — ПОЛНОЕ новое содержимое КАЖДОГО файла, который меняется (не
          диф и не фрагмент). Файлы, которые не менялись, можно не включать — они
          останутся как есть. Новые файлы, если нужны, тоже сюда.
        $sharedRules
    """.trimIndent()

    /** [platform] — "android" (по умолчанию, запуск на самом телефоне через
     *  Termux) или "windows" (Python + GUI, готовый к сборке в один .exe). */
    suspend fun generate(spec: String, platform: String = "android"): GeneratedProject {
        val normalizedPlatform = if (platform.equals("windows", ignoreCase = true)) "windows" else "android"
        val prompt = if (normalizedPlatform == "windows") createSystemPromptWindows else createSystemPromptAndroid
        val raw = llm.complete(prompt, emptyList<ChatMessage>(), spec, forceComplex = true)
        return parse(raw, defaultName = "project_${System.currentTimeMillis()}").copy(platform = normalizedPlatform)
    }

    /**
     * Дорабатывает уже сохранённый проект: даёт LLM его текущие файлы плюс
     * инструкцию, и накладывает вернувшиеся изменения поверх старых файлов —
     * то, что не поменялось, остаётся как было (см. ProjectStorage.save,
     * которое перезапишет ту же папку проекта, а не создаст новую).
     * [platform] сохранённого проекта передаётся насквозь — ревизия никогда
     * не меняет платформу сама по себе.
     */
    suspend fun revise(existingFiles: List<ProjectFile>, instruction: String, projectName: String, platform: String = "android"): GeneratedProject {
        val filesDump = existingFiles.joinToString("\n\n") { "=== ${it.relativePath} ===\n${it.content}" }
        val userMessage = "Текущие файлы проекта \"$projectName\":\n\n$filesDump\n\nЧто нужно изменить: $instruction"
        val raw = llm.complete(revisePrompt, emptyList<ChatMessage>(), userMessage, forceComplex = true)
        val delta = parse(raw, defaultName = projectName)

        val merged = existingFiles.associateBy { it.relativePath }.toMutableMap()
        delta.files.forEach { merged[it.relativePath] = it }

        return GeneratedProject(
            projectName = projectName,
            files = merged.values.toList(),
            runInstructions = delta.runInstructions,
            requiredSecrets = delta.requiredSecrets,
            platform = platform
        )
    }

    private fun parse(raw: String, defaultName: String): GeneratedProject {
        val candidate = extractJsonObject(raw)
            ?: throw IllegalStateException("LLM ответил не JSON-ом: ${raw.take(200)}")
        val json = JSONObject(candidate)
        val projectName = sanitizeProjectName(json.optString("project_name").ifBlank { defaultName })
        val filesJson = json.optJSONArray("files")
        val files = buildList {
            if (filesJson != null) {
                for (i in 0 until filesJson.length()) {
                    val f = filesJson.getJSONObject(i)
                    add(ProjectFile(f.getString("path"), f.getString("content")))
                }
            }
        }
        val secretsJson = json.optJSONArray("required_secrets")
        val secrets = buildList {
            if (secretsJson != null) for (i in 0 until secretsJson.length()) add(secretsJson.getString(i))
        }
        return GeneratedProject(
            projectName = projectName,
            files = files,
            runInstructions = json.optString("run_instructions"),
            requiredSecrets = secrets
        )
    }
}
