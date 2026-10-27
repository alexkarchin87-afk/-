# Как собрать APK из этого проекта

Это полный проект VoiceAgent-v2 со всеми ранее внесёнными исправлениями, включая поле ввода и плавающее окно. ZIP открывают как проект в Android Studio; сам файл ZIP не является APK и не переименовывается в APK.

Единственный известный блокер упаковки в проверенной облачной среде — отсутствие файлов модели Vosk. У вас уже есть нужный архив `vosk-model-small-ru-0.22 (1).zip`. Его не удалось перенести в эту среду из-за ошибки лимита инструмента, поэтому модель в этом ZIP НЕ включена.

## 1. Распакуйте проект

Распакуйте этот ZIP в обычную папку. Рабочий корень — вложенная папка `VoiceAgent-v2`, где лежат `settings.gradle.kts`, `gradlew.bat`, `app` и `core`.

## 2. Добавьте свою модель — скачивать её заново не нужно

Распакуйте ваш архив модели. Скопируйте ВСЁ содержимое папки `vosk-model-small-ru-0.22` в:

```
VoiceAgent-v2/app/src/main/assets/model-ru-small/
```

Создайте папки assets и model-ru-small, если их ещё нет. В результате должны существовать непосредственно такие пути:

```
app/src/main/assets/model-ru-small/am/final.mdl
app/src/main/assets/model-ru-small/conf/mfcc.conf
app/src/main/assets/model-ru-small/conf/model.conf
app/src/main/assets/model-ru-small/graph/HCLr.fst
app/src/main/assets/model-ru-small/graph/Gr.fst
```

Скопируйте также остальные файлы модели: этот список служит для проверки вложенности, а не для отбора файлов. Дополнительной папки `vosk-model-small-ru-0.22` внутри `model-ru-small` быть не должно. Сам ZIP модели внутрь assets не кладите.

Файл `uuid` создаст существующая Gradle-задача. Если заменяете ранее добавленную модель, удалите только старый `app/src/main/assets/model-ru-small/uuid` перед сборкой, чтобы обновился маркер распаковки.

Ручное копирование выше остаётся способом без дополнительных скриптов. Windows-установщик исправлен и также принимает локальный ZIP:

```powershell
powershell -File scripts\download_vosk_model.ps1 -ModelZip "C:\Downloads\vosk-model-small-ru-0.22 (1).zip"
```

Он сохраняет прежнюю модель в .model-backups, вне APK. Этот PowerShell-скрипт в облачной Linux-среде НЕ запускался; для него добавлены локальные проверки scripts/tests/test_vosk_model.ps1.

## 3. Откройте проект в Android Studio

Выберите Open и папку `VoiceAgent-v2`. Дождитесь Gradle Sync. Нужны JDK 17, Android SDK Platform 35 и Build-Tools 34.0.0. Gradle Wrapper 8.9 уже включён в ZIP. Android Studio создаст локальный путь к SDK; чужой local.properties из облачной среды не переносится.

Для первой синхронизации требуется доступ к репозиториям Gradle/Google/Maven. Не вводите API-ключи для самой сборки — они задаются в приложении позднее для используемых сетевых функций.

## 4. Соберите APK

В Terminal Android Studio, из корня проекта:

Windows / PowerShell:

```powershell
.\gradlew.bat :app:assembleDebug
```

Linux/macOS/WSL:

```sh
sh gradlew :app:assembleDebug
```

На Windows после настройки SDK/JDK можно также открыть BUILD_APK.bat. Это оболочка над той же Gradle-командой; в Linux-среде Windows-оболочка отдельно не выполнялась.

Если verifyVoskModel сообщает об отсутствующих файлах, проверьте вложенность папок в шаге 2. Не отключайте проверку и не подставляйте тестовые файлы вместо модели.

Готовый APK после BUILD SUCCESSFUL:

```
VoiceAgent-v2/app/build/outputs/apk/debug/app-debug.apk
```

## 5. Проверьте на телефоне

Установите именно APK из последней успешной сборки. Проверьте микрофон, загрузку STT, ввод с открытой клавиатурой и оверлей по KEYBOARD_TEST.md. Сценарии остальных функций — DEVICE_DIAGNOSTICS.md и NEXT_DEVICE_TEST.md.

Если Android отказывается обновлять приложение из-за несовпадения подписи, используйте свой прежний signing key. Удаление установленного приложения может удалить его данные, поэтому не используйте удаление как первый способ исправления.

## Что уже проверено

Для текущих исходников прошли Kotlin, 141 JVM-тест и Lint (0 errors, 9 warnings). Модель в этой среде отсутствует, verifyVoskModel останавливает assembleDebug. PowerShell-скрипты/BUILD_APK.bat и OnePlus 13 здесь не запускались. Нового APK в ZIP нет. Детали — TEST_RESULTS.md.
