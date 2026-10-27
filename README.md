# Ваня: VoiceAgent-v2 и VanyaBridge

Актуальные исходники и состояние работы для продолжения в другом диалоге. Два проекта сохранены отдельно; VanyaBridge не заменяет VoiceAgent-v2.

**Начните с [SESSION_STATE_RU.md](SESSION_STATE_RU.md)**: выполненная работа, результаты проверок, известные ограничения и следующие шаги.

- [VanyaBridge](VanyaBridge/) — Android, desktop и Python backend; [запуск](VanyaBridge/README.md).
- [VoiceAgent-v2](VoiceAgent-latest/) — последний полный исправленный проект, отчёты и патчи.
- [Полный ZIP для переноса](handoff/Vanya-FULL-HANDOFF-2026-10-27.zip) — оба проекта, Android APK, Windows EXE со всеми runtime-библиотеками, отчёты и состояние работы (74,3 МиБ). На странице файла нажмите **Download raw file**.
- [Android APK VanyaBridge](VanyaBridge/releases/VanyaBridge-debug.apk).
- [Проверки Windows-сборки](windows-validation/).

Windows-Portable находится внутри полного ZIP; в отдельное дерево Git его DLL не дублируются. Это не удаление файлов: весь готовый onedir-пакет сохранён в ZIP.

Windows EXE собран и окно открыто под Wine 10. Реальный Windows 11 и OnePlus 13 пока не тестировались. Windows-Python тесты под Wine: 43 passed, 1 failed (особенность lstat symlink в Wine), 1 skipped; подробности в файле состояния. Ранее Linux backend: 45 passed; Android JVM: 4 passed, APK собран.

Vosk-модель VoiceAgent не была получена в рабочей среде и в архиве отсутствует. Новый голосовой APK VoiceAgent из-за этого не объявлялся собранным. Не заменяйте его старым APK.

Кеши, виртуальные окружения, Wine/Docker, реальные токены и приватные ключи не включены. Контрольная сумма полного ZIP: [handoff/SHA256SUMS.txt](handoff/SHA256SUMS.txt).

Для следующего диалога: укажите этот репозиторий и попросите сначала прочитать SESSION_STATE_RU.md, сохранить оба проекта и продолжить проверку Windows EXE, не начиная заново.
