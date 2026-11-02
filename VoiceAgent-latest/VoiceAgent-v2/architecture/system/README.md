# Android API, уведомления, root и системные действия

Владелец: `system`.

## Основные интерфейсы

SystemController, NotificationReader, AccessibilityAgent, RootActions, RootPermissions, ChatsDigest.

## Зависимости

Точные входящие и исходящие связи, файлы и тесты: [ARCHITECTURE_MAP](../../ARCHITECTURE_MAP.md#system). Разрешённые направления: `architecture/components.json`.

## Жизненный цикл

Android управляет listener/service; notification buffer в памяти, digest сериализован Mutex; root процессы ограничены временем.

## Возможные ошибки

Нет разрешений/root, неоднозначный получатель, background launch denied, устаревшее уведомление.

## Тесты

- [ AppLabelResolverTest.kt ](../../app/src/test/java/com/voiceagent/oneplus13/system/AppLabelResolverTest.kt)
- [ ChatsDigestTest.kt ](../../app/src/test/java/com/voiceagent/oneplus13/system/ChatsDigestTest.kt)
- [ DigestBatchTest.kt ](../../app/src/test/java/com/voiceagent/oneplus13/system/DigestBatchTest.kt)
- [ NotificationBufferTest.kt ](../../app/src/test/java/com/voiceagent/oneplus13/system/NotificationBufferTest.kt)
- [ SystemControllerLaunchTest.kt ](../../app/src/test/java/com/voiceagent/oneplus13/system/SystemControllerLaunchTest.kt)

## Правила расширения

Root только фиксированный allowlist; чувствительные действия через подтверждение, не произвольный shell из LLM.
