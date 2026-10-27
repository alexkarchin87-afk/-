# Проверка на OnePlus 13

Новый APK в этом архиве отсутствует: сначала нужна полная Vosk-модель. Все команды ниже — для локального выполнения пользователем; в этой среде устройство не подключалось. Команды shell рассчитаны на bash/Linux/macOS/WSL с Android SDK platform-tools.

## Подготовка APK

Из корня проекта:

```sh
sh scripts/download_vosk_model.sh
sh gradlew :app:verifyVoskModel :app:assembleDebug
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Если официальный download недоступен, загрузите `vosk-model-small-ru-0.22.zip` с https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip на своей машине и поместите ВСЁ содержимое каталога модели непосредственно в `app/src/main/assets/model-ru-small/`. Не используйте тестовые fixtures. При ручной замене существующей модели удалите прежний asset `uuid` перед сборкой: genVoskUuid создаст новый marker для обновления кэша. Полноту и native-совместимость нужно подтвердить запуском; preflight проверяет лишь основные файлы. Не удаляйте данные установленного приложения ради диагностики.

## Проверка именно установленного APK

```sh
adb shell pm path com.voiceagent.oneplus13
```

Возьмите путь base.apk из вывода (без префикса package:) и скопируйте локально:

```sh
adb pull '<путь base.apk из вывода>' installed-voiceagent.apk
python - <<'CHECK_APK'
import zipfile
with zipfile.ZipFile('installed-voiceagent.apk') as z:
    for name in ('uuid', 'am/final.mdl', 'conf/mfcc.conf', 'graph/HCLG.fst', 'graph/HCLr.fst', 'graph/Gr.fst'):
        path = 'assets/model-ru-small/' + name
        print(name, 'present' if path in z.namelist() and z.getinfo(path).file_size > 0 else 'absent')
    print('arm64_vosk', 'lib/arm64-v8a/libvosk.so' in z.namelist())
CHECK_APK
```

Для graph допустим HCLG.fst либо пара HCLr.fst + Gr.fst. Если установка split APK, pm path выдаст несколько путей: проверяйте также split с ресурсами/native-библиотеками. Не публикуйте APK с персональными встроенными ключами; достаточно результатов этой проверки.

Проверка микрофона (локально):

```sh
adb shell dumpsys package com.voiceagent.oneplus13 | grep -E 'android.permission.RECORD_AUDIO|android.permission.POST_NOTIFICATIONS'
adb shell appops get com.voiceagent.oneplus13 RECORD_AUDIO
```

Vosk распаковывает модель в app-specific external storage, обычно `/sdcard/Android/data/com.voiceagent.oneplus13/files/model/model-ru-small`, а не во внутренний files/model. Для debug-сборки можно проверить только имена основных файлов, без чтения данных приложения:

```sh
adb shell run-as com.voiceagent.oneplus13 ls -l /sdcard/Android/data/com.voiceagent.oneplus13/files/model/model-ru-small/uuid /sdcard/Android/data/com.voiceagent.oneplus13/files/model/model-ru-small/am/final.mdl
```

На устройстве с другим пользователем/томом путь может отличаться; ошибка run-as/доступа сама по себе не доказывает отсутствие модели.

## Сценарий и безопасный лог

В отдельном терминале, перед воспроизведением (Ctrl+C после теста):

```sh
adb logcat -v threadtime -T 1 'VA_STT:I' 'VA_UI:I' 'VA_TELEGRAM:D' 'VA_ROUTE:I' '*:S' > voiceagent-safe.log
```

1. В настройках Android разрешите микрофон; проверьте системный переключатель доступа к микрофону и уведомления. Остальные разрешения включайте для используемых действий (Accessibility — для чтения/нажатий; overlay — для пузыря).
2. При выключенном агенте введите короткую несекретную фразу и нажмите отправку: появится объяснение неготовности, введённый текст должен остаться.
3. Включите агента. В логе ожидаются service=connected и model=ready; model=failed stage=assets/storage/native указывает этап отказа. Отсутствие ошибки не заменяет фактическое распознавание.
4. Отправьте текст: ожидается text=accepted, затем reply_displayed либо error_displayed. Проверьте визуально наличие пользовательского сообщения и результата; не отправляйте повторно из-за долгого ответа без проверки состояния.
5. Произнесите «Ваня, открой Telegram», проверьте фактическое открытие установленного Telegram. Повторите команду, проверьте отсутствие двойного выполнения. Смотрите phase и open_app result; лог dispatch сам по себе не доказывает видимое открытие.
6. Прервите голосовой ответ, отправьте новую команду; убедитесь, что старый ответ/действие не появилось после отмены.
7. Отключите сеть, отправьте обычный запрос, дождитесь понятной ошибки; включите сеть и повторите. Оффлайн Vosk не требует сети после успешной загрузки модели, LLM/Telegram могут требовать её.
8. Сверните/верните приложение, остановите/запустите агента и повторите ввод. Запишите наблюдаемое поведение и примерное время; передайте только отфильтрованный лог и результаты проверки assets/разрешений.

Новые теги не содержат команд, ответов, chatId, имён, токенов, request URL или raw exception. Не собирайте полный bugreport/нефильтрованный logcat для этого теста: другие компоненты/приложения могут содержать личные данные.

## Telegram /start

Встроенная версия НЕ реализует автоматический ответ на /start и НЕ запускает TelegramBotService из UI. Отсутствие VA_TELEGRAM при обычном запуске поэтому ожидаемо. Не пытайтесь запускать неэкспортированный сервис через adb как исправление. При реально работающем экземпляре polling диагностические события различают 401/403/409/429 и transport_or_parse. Повторный вызов startPolling в одном экземпляре не создаёт второй цикл; внешние экземпляры остаются отдельной проверкой.

Если речь об отдельном развёрнутом боте, нужны его исходники и журналы обработчика (с удалёнными текстами сообщений, именами, chatId, токенами/URL). В этом проекте нет известного процесса/имени systemd/Docker для такого бота, поэтому команда получения его логов не выдумана.

Следующая необязательная локальная read-only проверка Bot API запрашивает токен через скрытый ввод. Она не запускает polling, не отправляет сообщений и не меняет webhook. Выполните её только для своего бота. Не включайте debug HTTP и не вставляйте токен в командную строку:

```sh
python - <<'CHECK_BOT'
import getpass, json, urllib.request, urllib.error
token = getpass.getpass('Bot token (hidden): ').strip()
for method in ('getMe', 'getWebhookInfo'):
    try:
        request = urllib.request.Request('https://api.telegram.org/bot' + token + '/' + method)
        with urllib.request.urlopen(request, timeout=15) as response:
            data = json.load(response)
        print(method, 'ok=', data.get('ok') is True)
        result = data.get('result') or {}
        if method == 'getWebhookInfo':
            print('webhook_configured=', bool(result.get('url')))
            print('pending_update_count=', int(result.get('pending_update_count', 0)))
            print('webhook_error_present=', bool(result.get('last_error_date')))
    except urllib.error.HTTPError as error:
        print(method, 'http_status=', error.code)
    except Exception:
        print(method, 'transport_or_parse_error')
CHECK_BOT
```

Не вызывайте getUpdates вручную параллельно работающему боту. Если webhook настроен, polling того же токена конфликтует; при 409 также возможен другой poller. Удалять webhook/останавливать неизвестный сервис эта диагностика не предлагает. Даже getMe=ok не доказывает, что обработчик /start существует и запущен.
