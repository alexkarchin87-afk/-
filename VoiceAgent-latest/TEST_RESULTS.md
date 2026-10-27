# Выполненные проверки

Дата: 26 октября 2026. Linux, JDK 17, Gradle 8.9, SDK 35; реального Android-устройства и PowerShell нет. Реальные платные API, Telegram и пользовательские уведомления в тестах не использовались.

Из /workspace/voiceagent-fix/VoiceAgent-v2 выполнено:

```sh
python /workspace/voiceagent-tools/run_gradle.py :app:testDebugUnitTest --tests com.voiceagent.oneplus13.system.ChatsDigestTest
python /workspace/voiceagent-tools/run_gradle.py :app:compileDebugKotlin :app:testDebugUnitTest :core:ai-engines:testDebugUnitTest :app:lintDebug
python /workspace/voiceagent-tools/run_gradle.py :app:assembleDebug
```

Комбинированный прогон compile/tests/lint выполнен после исправлений и повторён после добавления проверок совместимости NotificationBuffer. Локальный launcher задаёт установленный toolchain/proxy; в настроенном Android Studio эквивалент — gradlew/gradlew.bat с теми же задачами.

- До изменений: ChatsDigestTest — 7 тестов, 5 FAIL (4 новых и 1 усиленный прежний). Лог/XML сохранены как regression-before.
- После: app — 114 тестов, 0 failures/errors/skipped; core — 27, 0 failures/errors/skipped (неизменённый модуль использует актуальные Gradle-результаты).
- Всего: 141 JVM-тест. Добавлено 10: 4 в ChatsDigestTest, 4 DigestBatchTest, 2 NotificationBufferTest. Ещё один существующий тест усилен.
- compileDebugKotlin: PASS. lintDebug: PASS, 0 errors, 9 warnings. Финальный combined BUILD SUCCESSFUL in 32s.
- assembleDebug: FAIL на :app:verifyVoskModel; отсутствуют uuid, am/final.mdl, conf/mfcc.conf, graph/FST. Нового APK нет, старые файлы build/ в поставку не включены.

НЕ выполнены: 3 PowerShell-проверки scripts/tests/test_vosk_model.ps1 (нет powershell/pwsh). На Windows запуск: `powershell -File scripts\tests\test_vosk_model.ps1`. Проверки используют синтетические файловые fixtures, не действующую модель. Обычный shell installer/backend не менялись и повторно не тестировались.

Сборочные тесты не подтверждают работу приложения на OnePlus. Не выполнены native Model, микрофон/TTS, Windows-установка, реальные сети/уведомления и UI instrumentation.

Проверки упаковки: все прежние пути проекта сохранены, группа 32 UI/overlay/engine/backend файлов побайтово неизменна, patch применяется к базе и воспроизводит изменения, ZIP проходит CRC, файлы перечислены в SHA256SUMS.txt. Изменения исходников — PRESERVATION_FIX.patch, архив сравнения — VoiceAgent-v2-PROJECT-FOR-APK.zip. Доказательства — validation/preserve-fix.
