# Фактические результаты проверки VanyaBridge 0.1

26 октября 2026. Среда: Linux x86_64, Python 3.12.14, Temurin JDK 17, Gradle 8.9, AGP 8.7.3, Kotlin 2.0.21, Android SDK 35 / Build Tools 34.0.0.
Windows, Wine, Android-устройство, adb и графический DISPLAY в среде отсутствуют.

## Выполненные команды

Из `/workspace/VanyaBridge`:

```bash
/workspace/vanyabridge-work/venv/bin/python -m pytest -q --junitxml=validation/backend-tests.xml
/workspace/vanyabridge-work/venv/bin/python -m compileall -q backend desktop tests desktop_entry.py
/workspace/vanyabridge-work/venv/bin/python -m pip check
python /workspace/vanyabridge-work/run_gradle.py :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Последняя команда использует технический launcher среды: он задаёт JAVA_HOME, ANDROID_HOME, расположение Gradle/cache и вызывает Gradle 8.9 в `android/`. Исходный проект не зависит от этого launcher; команды обычной сборки для пользователя есть в README.

| Проверка | Результат |
|---|---|
| Python unit/integration | **45 passed**, 0 failed, 0 skipped; 8 предупреждений deprecation зависимостей WebSocket. |
| Python compileall | Успешно, код возврата 0. |
| pip check | No broken requirements found. Это проверка совместимости, не аудит CVE. |
| Kotlin и debug APK | **BUILD SUCCESSFUL**, финальный запуск 36 с, 51 task: 15 executed / 36 up-to-date. |
| Android JVM-тесты | **4 passed**, 0 failed/error/skipped. Финальный Gradle повторно использовал актуальные результаты неизменённых тестов. |
| Android Lint | **0 errors, 1 warning** — CustomX509TrustManager; не скрыто, обоснование в SECURITY.md. |
| APK подпись | `apksigner verify --verbose` — код 0, проверка успешна. |
| APK метаданные | aapt: com.vanyabridge.app, 0.1.0-mvp, minSdk 26, targetSdk 35. |

APK: `android/app/build/outputs/apk/debug/app-debug.apk`, копия в поставке `releases/VanyaBridge-debug.apk`.
Прямая команда проверки подписи выполнена с JDK 17:

```bash
/workspace/voiceagent-tools/android-sdk/build-tools/34.0.0/apksigner verify --verbose /workspace/VanyaBridge/android/app/build/outputs/apk/debug/app-debug.apk
```

## Что подтверждено тестами

- Настоящий TLS-сервер на loopback: стандартное доверие CA отклоняет самоподписанный сертификат; клиент с явно доверенным сертификатом подключается.
- REST-сопряжение с одноразовым кодом и локальным подтверждением, получение token, авторизованный запрос задачи.
- Настоящее чтение созданного тестового файла, выполнение задачи, выдача результата по HTTPS.
- WebSocket-снимок задачи, закрытие и новое соединение с сохранённой идентичностью.
- Отзыв устройства: последующие запросы получают HTTP 401; задачи отозванного устройства отменяются.
- Грамматика команд, режим без LLM, неизвестные запросы и Standard не вызывают инструменты/LLM.
- Отдельное согласие для действий; отказ, отзыв разрешения перед выполнением, попытка чужого подтверждения.
- Тайм-ауты выполнения и согласования; отмена до старта/в работе; поздний результат/ошибка не переписывает CANCELLED.
- Повторная отправка request_id не вызывает инструмент заново, в том числе при чтении из сохранённой истории.
- После рестарта незаконченная задача не продолжается автоматически.
- Абсолютные/выходящие пути, Windows ADS и symlink отвергаются; исполняемые файлы не открываются.
- Проверка формата изображения; очистка невалидной загрузки.
- Структурный разбор Wikimedia с тестовыми HTTP-ответами, отбрасывание постороннего хоста.
- Windows boundary tests: фиксированные аргументы Popen без shell; параметры SystemParametersInfoW; проверка false/true результата API. **Это моки, не вызовы настоящей Windows.**
- Android JVM: запрет публичного/HTTP/содержащего credentials адреса, нормализация полного fingerprint, принятие совпадающего сертификата и отказ при неправильном pin.

## Пять сценариев: уровень доказательств

| Сценарий | Что реально проверено | Что ещё нужно |
|---|---|---|
| YouTube | План/согласие/отмена, mocked webbrowser API; возвращается REQUESTED | Реальный браузер и видимый сайт на Windows |
| Список файлов | Реальная папка/файл Linux, весь REST/TLS/WebSocket маршрут backend | GUI и Windows ACL/reparse points на целевом ПК |
| VS Code | План/согласие, mocked Popen с фиксированным путём | Стандартная установка и запуск Code.exe на Windows |
| Поиск изображений | Разбор ответов HTTP в тестах; живой запрос выполнен, но **ConnectError** | Доступный Wikimedia API из сети пользователя; реальные результаты |
| Обои | Согласие, реальный Pillow, mocked WinAPI, обработка ошибки | Windows должна реально изменить рабочий стол; формат/драйвер/мониторы |

Ни один mocked результат не используется в production-коде для имитации успешной работы.

## Попытки, которые ограничила среда

**Desktop GUI:**

```bash
/workspace/vanyabridge-work/venv/bin/python -m desktop.main --data /workspace/vanyabridge-work/desktop-smoke
```

Код 1: `TclError: no display name and no $DISPLAY environment variable`.
Модуль компилируется, но окно не запустилось. Windows EXE не собирался: в Linux нельзя подтвердить Windows-сборку этим запуском. BUILD_WINDOWS.bat — инструкция, а не проверенный EXE.

**Playwright Chromium:**

```bash
PLAYWRIGHT_BROWSERS_PATH=/workspace/vanyabridge-work/browsers /workspace/vanyabridge-work/venv/bin/python -m playwright install chromium
```

Код 1: серверы загрузки возвращают **403 Domain forbidden**. Первоначальная попытка без заданной writable-папки также не удалась из-за недоступного домашнего cache; повторная попытка исправила путь, но выявила сетевую блокировку.
Реальный Chromium/read_page не запущен. Ошибки инструмента/тайм-ауты проверены отдельно.

**Wikimedia:** настоящий вызов `BrowserAgent.execute('search_images', 'mountains')` завершился `ConnectError`. Отчёт не содержит выдуманных изображений.

**Официальные документы ChatGPT/Codex:** HTTPS CONNECT блокируется прокси 403. Поддерживаемость подписки именно для этого MVP не установлена, интеграция не реализована.

**OnePlus 13:** не подключён. Установка/запуск APK, Keystore на устройстве, IME и связь Android ↔ Windows на двух устройствах **не проверены**. BUILD SUCCESSFUL этого не доказывает.

Свидетельства: `validation/backend-tests.log`, `backend-tests.xml`, `android-tests.xml`, `gradle-final.log`, `lint-results-debug.txt`, `apk-signature.log`, `desktop-launch.log`, `live-source-check.log`, `official-docs-check.log`, `playwright-install.log`.
Требуется пройти [docs/MANUAL_TEST.md](docs/MANUAL_TEST.md) до заявления о стабильной работе на целевых устройствах.
