# Результаты проверок UI — 26 октября 2026

Среда: Linux, JDK 17, Gradle 8.9, SDK 35. Устройства, эмулятора и adb не было. Реальные LLM/Telegram API не вызывались; пользовательские данные внешним сервисам не отправлялись.

Из /workspace/voiceagent-fix/VoiceAgent-v2 выполнено:

```sh
python /workspace/voiceagent-tools/run_gradle.py :app:compileDebugKotlin :app:testDebugUnitTest :core:ai-engines:testDebugUnitTest :app:lintDebug
python /workspace/voiceagent-tools/run_gradle.py :app:assembleDebug
```

run_gradle.py — локальная настройка установленного toolchain/proxy. В обычной среде с SDK/JDK эквивалентны задачи через `sh gradlew`.

- Kotlin: PASS. Есть предупреждение deprecated SOFT_INPUT_ADJUST_RESIZE; режим используется для существующего WindowManager overlay, включая поддержку старых Android.
- app unit: 104 теста, 0 failures/errors/skipped.
- core unit: 27 тестов, 0 failures/errors/skipped, неизменённый модуль использует актуальные Gradle-результаты.
- Итого: 131 JVM-тест; 6 новых OverlayPositionTest. Проверяются размещение над клавиатурой без дополнительного зазора, восстановление желаемой позиции, отсутствие ненужного смещения уже видимой панели, ширина раскрытого окна, временно чрезмерный размер, перетаскивание за границы.
- lintDebug: 0 errors, 9 warnings. BUILD SUCCESSFUL in 1m 2s.
- Merged manifest: у com.voiceagent.oneplus13.MainActivity подтверждён windowSoftInputMode=adjustResize.
- assembleDebug: FAIL на :app:verifyVoskModel — отсутствуют uuid, am/final.mdl, conf/mfcc.conf, graph/FST. Новый APK не создан; старые артефакты build/ не включены в ZIP.

Приложенный файл попытались перенести tools.download_file, file_id=file_00000000fd54821194e9599625e35156: ошибка `file exceeds the executor transfer limit of 32 MiB`. Фактический размер вложения не измерен и содержимое не проверено. Повтор загрузки официальной модели через curl --fail --location --connect-timeout 15 --max-time 45: exit 22, HTTP 403. Подстановка тестовых данных вместо модели не выполнялась.

Backend/installer не изменены и заново не тестировались. Нативная загрузка Vosk, анимация клавиатуры, настоящий Compose layout, жесты и lifecycle OnePlus не тестировались. Unit-тесты геометрии не заменяют эти проверки.

Упаковка: проверены CRC ZIP, SHA-256 файлов, сохранность всех файлов предыдущей поставки, применение KEYBOARD_FIX.patch к отдельной копии базы и побайтовое совпадение 8 изменённых файлов. Журналы/XML/lint — validation/keyboard-fix. Конфиги с ключами, .env, keystore, build-cache и APK не включаются.
