@echo off
setlocal
pushd "%~dp0"
call gradlew.bat :app:assembleDebug
set "va_build_result=%errorlevel%"
if "%va_build_result%"=="0" (
  echo APK: %CD%\app\build\outputs\apk\debug\app-debug.apk
) else (
  echo Build failed. See Gradle output and BUILD_GUIDE_RU.md.
)
popd
pause
exit /b %va_build_result%
