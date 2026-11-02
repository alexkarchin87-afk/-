# Доказательства текущей проверки

- `regression-before.log`: production baseline + два новых regression tests; app 116/2 failures, core 27/0.
- `regression-after.log`: целевые MessengerBridgeTest + WorkflowCancellationTest после исправления.
- `notes-route.log`: 17 маршрутов реального AgentOrchestrator после выделения NotesActions.
- `compile-tests-lint.log`: финальный combined BUILD SUCCESSFUL, app 124 + core 27.
- `test-summary.json`: suite-level counts из актуальных XML результатов Gradle; 12 Python architecture tests также прошли отдельно.
- `lint-results-debug.xml`: 0 errors, 9 warnings, 5 information; предупреждения не подавлены.
- `assemble.log`: штатный assembleDebug остановлен verifyVoskModel; реальная модель отсутствует.
- `preservation.json`: все 489 файлов базы на месте, VanyaBridge/Windows-Portable неизменны.

Логи могут содержать абсолютные пути текущего исполнителя. Переносимые команды — в DEMO_GUIDE_RU.md. Отдельные пути/логи в прежних validation-каталогах относятся к историческим проверкам, не к этому проходу. Ни один mock не подтверждает реальную работу устройства или интеграции.
