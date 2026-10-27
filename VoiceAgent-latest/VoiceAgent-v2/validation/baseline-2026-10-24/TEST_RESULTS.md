# Фактически выполненные проверки

Дата: 24 октября 2026, Europe/Moscow. Среда: Linux, Temurin JDK 17.0.16+8, Gradle 8.9, Android SDK 35 / build-tools 34.0.0. Android-устройство, эмулятор и adb отсутствуют. Не выполнялись реальные запросы к LLM/Telegram и платные API.

## Gradle

Рабочий каталог: `/workspace/voiceagent-fix/VoiceAgent-v2`.

```sh
python /workspace/voiceagent-tools/run_gradle.py :app:compileDebugKotlin :app:testDebugUnitTest :core:ai-engines:testDebugUnitTest :app:lintDebug
python /workspace/voiceagent-tools/run_gradle.py :app:assembleDebug
```

run_gradle.py — локальный launcher toolchain/proxy/CA этой среды; конфигурация проекта используется существующая. На машине с настроенными JDK/SDK эквивалент: `sh gradlew` с теми же задачами. Окружение launcher не требуется переносить в приложение.

- compileDebugKotlin: PASS.
- app:testDebugUnitTest: 89 тестов, 0 failures/errors/skipped.
- core:ai-engines:testDebugUnitTest: 27 тестов, 0 failures/errors/skipped.
- Всего JVM: 116. Это полный существующий набор, включая ранее созданные проверки сессий/отмены/устаревших ответов; не только новые тесты.
- lintDebug: PASS, 0 errors, 9 warnings, 2 informational findings. Предупреждения сохранены в lint-отчёте, они не означают полный runtime-аудит.
- assembleDebug: FAIL на :app:verifyVoskModel, недостающие uuid, am/final.mdl, conf/mfcc.conf, graph/FST. Проверка намеренно не выпускает voice APK без основных ресурсов. В этой среде нет полной модели, запрос официального ZIP завершился curl exit 22 / HTTP 403 (proxy; write-out HTTP 000, 0 bytes). Новый APK отсутствует. Старый APK, оставшийся в build/, не считается результатом и в архив не включён.

После последней правки порядка UI-сообщений compile/tests/lint повторены; окончательный лог validation-final-delivery.log. Отдельный assemble-debug.log получен до этой последней UI-правки; отсутствие модели с тех пор не изменилось, APK не создавался.

Промежуточные прогоны обнаружили ошибки новых Mockito-тестов (checked-exception stub, suspend Boolean verify/null-unboxing). Исправлены именно тестовые заглушки и проверка числа вызовов; тесты не удалялись. Промежуточные логи сохранены для прозрачности. Они не являются финальным результатом.

## Python и файловые тесты

```sh
# Из корня проекта:
python -m unittest discover -s scripts/tests -v
# Из backend/ с установленными backend/requirements.txt:
/workspace/voiceagent-tools/backend-venv/bin/python -m unittest discover -s tests -v
```

- Скрипт установки Vosk: 3 PASS. Используются файловые фикстуры, подставной curl; фикстуры не являются работающей речевой моделью.
- Backend: 14 PASS. Реальные ASGI routes, подставной upstream. Backend не изменялся.
- Итого: 133 автоматических теста (116 JVM + 3 installer + 14 backend), 0 финальных провалов. Эти тесты не доказывают работу микрофона, native Vosk, визуального UI или внешнего Telegram-бота.

## Новые проверки

- VoskModelFilesTest: 5 — uuid-only, варианты graph, неполный/полный cache, реальный путь Vosk.
- TextSubmissionTest: 4 — отказ без сервиса сохраняет черновик, успешная однократная отправка, динамическая готовность, пустой ввод.
- MessengerBridgeTest: 5 — отменённая публикация не подтверждает batch, успешный offset/repeated start/stop, backoff 409, безопасное логирование, /start без автоматического ответа.
- test_vosk_download.py: 3 — HTTP error/неполный архив сохраняют старую модель, структурно полный fixture устанавливается без лишней вложенности.

## Дополнительные проверки и ограничения

Исследован байткод StorageService из реально используемого Vosk AAR; подтверждены externalFilesDir/model/model-ru-small, обязательный asset uuid и short-circuit по равному uuid. У предыдущего APK проверено отсутствие assets модели и наличие native libvosk (APK_INSPECTION.json).

При упаковке проверяются: все исходные файлы базового архива сохранены, diff применяется к базе и воспроизводит все 20 изменённых файлов, ZIP проходит CRC, ключи/локальные конфиги/build-cache/APK не включаются. SHA256SUMS.txt внутри архива описывает файлы поставки. Итог — PACKAGING_CHECKS.json.

НЕ выполнено: Vosk Model(real files), захват/освобождение AudioRecord на устройстве, Android runtime permissions/lifecycle, UI instrumentation, end-to-end Telegram, открытие Telegram на OnePlus 13, recovery после потери сети на телефоне. Команды и сценарий — DEVICE_DIAGNOSTICS.md.
