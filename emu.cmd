@echo off
set EMULATOR=%LOCALAPPDATA%\Android\Sdk\emulator\emulator.exe
set ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe

echo Starting emulator...
start "" "%EMULATOR%" -avd chatbar_avd

echo Waiting for device...
"%ADB%" wait-for-device

echo Installing ChatBar APK...
"%ADB%" install -r "%~dp0app\app\build\outputs\apk\debug\app-debug.apk"

echo Launching ChatBar...
"%ADB%" shell am start -n com.example.chatbar/.MainActivity

echo Done.
pause
