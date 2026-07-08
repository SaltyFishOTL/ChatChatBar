@echo off
setlocal
set "ROOT=%~dp0"
set "APP_ID=com.example.chatbar"
set "EMULATOR=%LOCALAPPDATA%\Android\Sdk\emulator\emulator.exe"
set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
set "FAIL_MESSAGE="
set "FAIL_CODE=1"

if not exist "%EMULATOR%" (
    set "FAIL_MESSAGE=Emulator not found: %EMULATOR%"
    goto fail
)
if not exist "%ADB%" (
    set "FAIL_MESSAGE=ADB not found: %ADB%"
    goto fail
)

echo Starting emulator...
start "" "%EMULATOR%" -avd chatbar_avd

echo Waiting for device...
"%ADB%" wait-for-device
if %ERRORLEVEL% neq 0 (
    set "FAIL_MESSAGE=No Android device found."
    set "FAIL_CODE=%ERRORLEVEL%"
    goto fail
)

echo Building ChatBar APK...
pushd "%ROOT%app"
call .\gradlew.bat assembleDebug
if %ERRORLEVEL% neq 0 (
    set "FAIL_MESSAGE=Debug build failed."
    set "FAIL_CODE=%ERRORLEVEL%"
    popd
    goto fail
)
popd

echo Installing ChatBar APK...
call :install_apk "%ROOT%app\app\build\outputs\apk\debug\app-debug.apk"
if %ERRORLEVEL% neq 0 (
    set "FAIL_MESSAGE=Install failed. Check ADB output above."
    set "FAIL_CODE=%ERRORLEVEL%"
    goto fail
)

echo Launching ChatBar...
"%ADB%" shell am start -n com.example.chatbar/.MainActivity
if %ERRORLEVEL% neq 0 (
    set "FAIL_MESSAGE=Install succeeded, but launch failed."
    set "FAIL_CODE=%ERRORLEVEL%"
    goto fail
)

echo Done.
pause
exit /b 0

:install_apk
set "APK_PATH=%~1"
set "INSTALL_LOG=%TEMP%\chatbar-install-%RANDOM%.log"
"%ADB%" get-state >nul 2>nul
if errorlevel 1 (
    echo No Android device found during install.
    exit /b 1
)
"%ADB%" install --no-streaming -r "%APK_PATH%" > "%INSTALL_LOG%" 2>&1
set "INSTALL_CODE=%ERRORLEVEL%"
type "%INSTALL_LOG%"
if "%INSTALL_CODE%"=="0" (
    del "%INSTALL_LOG%" >nul 2>nul
    exit /b 0
)

findstr /c:"INSTALL_FAILED_VERSION_DOWNGRADE" "%INSTALL_LOG%" >nul 2>nul
if errorlevel 1 (
    del "%INSTALL_LOG%" >nul 2>nul
    exit /b 1
)

echo Downgrade detected. Trying data-preserving recovery for %APP_ID%.
echo Trying adb install -r -d first.
"%ADB%" install --no-streaming -r -d "%APK_PATH%"
set "INSTALL_CODE=%ERRORLEVEL%"
if "%INSTALL_CODE%"=="0" (
    del "%INSTALL_LOG%" >nul 2>nul
    exit /b 0
)

call :get_device_version
if errorlevel 1 (
    del "%INSTALL_LOG%" >nul 2>nul
    exit /b 1
)

echo Rebuilding debug APK with device version %DEVICE_VERSION_NAME% ^(versionCode %DEVICE_VERSION_CODE%^).
set "CHATBAR_VERSION_CODE=%DEVICE_VERSION_CODE%"
set "CHATBAR_VERSION_NAME=%DEVICE_VERSION_NAME%"
pushd "%ROOT%app"
call .\gradlew.bat assembleDebug
set "REBUILD_CODE=%ERRORLEVEL%"
popd
set "CHATBAR_VERSION_CODE="
set "CHATBAR_VERSION_NAME="
if not "%REBUILD_CODE%"=="0" (
    del "%INSTALL_LOG%" >nul 2>nul
    exit /b 1
)

"%ADB%" install --no-streaming -r "%APK_PATH%"
set "INSTALL_CODE=%ERRORLEVEL%"
del "%INSTALL_LOG%" >nul 2>nul
exit /b %INSTALL_CODE%

:get_device_version
set "DEVICE_VERSION_CODE="
set "DEVICE_VERSION_NAME="
set "VERSION_LOG=%TEMP%\chatbar-package-%RANDOM%.log"
"%ADB%" shell dumpsys package %APP_ID% > "%VERSION_LOG%" 2>&1
if errorlevel 1 (
    del "%VERSION_LOG%" >nul 2>nul
    exit /b 1
)
for /f "tokens=2 delims==" %%V in ('findstr /c:"versionCode=" "%VERSION_LOG%"') do (
    for /f "tokens=1" %%C in ("%%V") do set "DEVICE_VERSION_CODE=%%C"
)
for /f "tokens=2 delims==" %%V in ('findstr /c:"versionName=" "%VERSION_LOG%"') do (
    set "DEVICE_VERSION_NAME=%%V"
)
del "%VERSION_LOG%" >nul 2>nul
if not defined DEVICE_VERSION_CODE exit /b 1
if not defined DEVICE_VERSION_NAME exit /b 1
echo Device version detected: %DEVICE_VERSION_NAME% ^(versionCode %DEVICE_VERSION_CODE%^).
exit /b 0

:fail
echo.
echo ERROR: %FAIL_MESSAGE%
echo.
pause
exit /b %FAIL_CODE%
