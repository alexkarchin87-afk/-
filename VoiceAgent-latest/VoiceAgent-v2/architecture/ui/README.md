# Чат, статус, адаптивный интерфейс и тема

Владелец: `ui`.

## Основные интерфейсы

MainScreen, AgentControls, TextSubmission, UiPreferences.

## Зависимости

Точные входящие и исходящие связи, файлы и тесты: [ARCHITECTURE_MAP](../../ARCHITECTURE_MAP.md#ui). Разрешённые направления: `architecture/components.json`.

## Жизненный цикл

Compose/Activity lifecycle; черновик rememberSaveable, статус из VoiceRuntime.

## Возможные ошибки

Binder не готов, разрыв binding, IME/размер окна.

## Тесты

- [ TextSubmissionTest.kt ](../../app/src/test/java/com/voiceagent/oneplus13/ui/TextSubmissionTest.kt)

## Правила расширения

В UI только отображение/ввод; не дублировать диспетчеризацию команд.
