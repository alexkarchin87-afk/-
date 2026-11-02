# Запись лекций и восстановление резюме

Владелец: `lecture`.

## Основные интерфейсы

LectureCommands, LectureRepository, LectureSummaryRecovery.

## Зависимости

Точные входящие и исходящие связи, файлы и тесты: [ARCHITECTURE_MAP](../../ARCHITECTURE_MAP.md#lecture). Разрешённые направления: `architecture/components.json`.

## Жизненный цикл

Сессия имеет UUID; recorder job принадлежит сервису; checkpoint на каждую фразу; summary после durable transcript.

## Возможные ошибки

Прерывание записи, IO, ошибка LLM; транскрипт остаётся локально.

## Тесты

- [ LectureRepositoryTest.kt ](../../app/src/test/java/com/voiceagent/oneplus13/lecture/LectureRepositoryTest.kt)
- [ LectureSummaryRecoveryTest.kt ](../../app/src/test/java/com/voiceagent/oneplus13/lecture/LectureSummaryRecoveryTest.kt)

## Правила расширения

Сохранять expectedSessionId при stop; summary retry не должен перезаписывать сохранённый результат.
