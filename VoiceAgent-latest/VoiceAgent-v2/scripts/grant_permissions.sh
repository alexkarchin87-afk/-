#!/system/bin/sh
# Запуск из Termux на рутованном телефоне:   su -c "sh /путь/к/scripts/grant_permissions.sh"
# Выдаёт VoiceAgent разрешения, которые Android 13+ иначе заставляет включать руками
# (и вообще блокирует для APK не из магазина — «ограниченные настройки»).
# Список фиксированный; ничего, кроме самого VoiceAgent, не затрагивается.
PKG=com.voiceagent.oneplus13
A11Y="$PKG/$PKG.system.AccessibilityAgent"
LISTENER="$PKG/$PKG.system.NotificationReader"

pm grant "$PKG" android.permission.RECORD_AUDIO
pm grant "$PKG" android.permission.POST_NOTIFICATIONS 2>/dev/null
appops set "$PKG" ACCESS_RESTRICTED_SETTINGS allow 2>/dev/null   # Android 13+
appops set "$PKG" SYSTEM_ALERT_WINDOW allow
cmd notification allow_listener "$LISTENER"
dumpsys deviceidle whitelist +"$PKG"

# Спец. возможности: добавляем наш сервис к уже включённым, а не затираем список.
CUR="$(settings get secure enabled_accessibility_services)"
case "$CUR" in
  *"$A11Y"*) ;;
  ""|null) settings put secure enabled_accessibility_services "$A11Y" ;;
  *) settings put secure enabled_accessibility_services "$CUR:$A11Y" ;;
esac
settings put secure accessibility_enabled 1

echo "Готово. Перезапусти VoiceAgent. Запись экрана Android спрашивает согласие при каждом запуске — это нельзя обойти."
