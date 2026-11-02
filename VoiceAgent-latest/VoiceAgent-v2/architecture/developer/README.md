# Генерация и сохранение пользовательских проектов

Владелец: `developer`.

## Основные интерфейсы

CodeGenAgent, ProjectStorage, ProjectReader.

## Зависимости

Точные входящие и исходящие связи, файлы и тесты: [ARCHITECTURE_MAP](../../ARCHITECTURE_MAP.md#developer). Разрешённые направления: `architecture/components.json`.

## Жизненный цикл

Генерация на запрос; результаты изолированы в каталогах; код автоматически не запускается.

## Возможные ошибки

Traversal, symlink, неверные файлы/имя, LLM и IO.

## Тесты

- [ ProjectReaderTest.kt ](../../app/src/test/java/com/voiceagent/oneplus13/devassist/ProjectReaderTest.kt)

## Правила расширения

Сохранять sandbox и ручной запуск; не менять исходники самого VoiceAgent.
