# Редактор сценариев

Владелец: `workflow-ui`.

## Основные интерфейсы

WorkflowEditorActivity.

## Зависимости

Точные входящие и исходящие связи, файлы и тесты: [ARCHITECTURE_MAP](../../ARCHITECTURE_MAP.md#workflow-ui). Разрешённые направления: `architecture/components.json`.

## Жизненный цикл

Activity/Compose state; хранение через WorkflowStorage.

## Возможные ошибки

Некорректный граф и ошибки AI builder.

## Тесты

Автономных JVM-тестов нет; нужна проверка Android lifecycle/device.

## Правила расширения

Не дублировать валидацию executionOrder; бизнес-логику держать в workflow.
