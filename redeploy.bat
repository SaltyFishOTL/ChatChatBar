@echo off
set ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe

cd /d "%~dp0app"
call .\gradlew.bat assembleDebug
if %ERRORLEVEL% neq 0 exit /b %ERRORLEVEL%

"%ADB%" install -r "%~dp0app\app\build\outputs\apk\debug\app-debug.apk"
"%ADB%" shell am start -n com.example.chatbar/.MainActivity
echo Done: build + install + launch
