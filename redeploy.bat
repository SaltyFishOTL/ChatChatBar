@echo off
setlocal
set "ROOT=%~dp0"
set "APP_ID=com.example.chatbar"
set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
set "SIGNING_FILE=%ROOT%secrets\chatbar-release-signing.txt"
set "BUILD_ONLY="
set "NO_PAUSE="
set "FAIL_MESSAGE="
set "FAIL_CODE=1"

:parse_args
if "%~1"=="" goto after_args
if /i "%~1"=="--build-only" set "BUILD_ONLY=1"
if /i "%~1"=="--no-pause" set "NO_PAUSE=1"
shift
goto parse_args

:after_args

if not exist "%SIGNING_FILE%" (
    set "FAIL_MESSAGE=Missing release signing file: %SIGNING_FILE%"
    set "FAIL_CODE=1"
    goto fail
)

for /f "usebackq tokens=1,* delims==" %%A in ("%SIGNING_FILE%") do (
    if /i "%%A"=="KeystorePath" set "ANDROID_KEYSTORE_PATH=%%B"
    if /i "%%A"=="KeystorePassword" set "ANDROID_KEYSTORE_PASSWORD=%%B"
    if /i "%%A"=="KeyAlias" set "ANDROID_KEY_ALIAS=%%B"
    if /i "%%A"=="KeyPassword" set "ANDROID_KEY_PASSWORD=%%B"
)

if not defined ANDROID_KEYSTORE_PATH (
    set "FAIL_MESSAGE=Missing KeystorePath in %SIGNING_FILE%"
    set "FAIL_CODE=1"
    goto fail
)
if not defined ANDROID_KEYSTORE_PASSWORD (
    set "FAIL_MESSAGE=Missing KeystorePassword in %SIGNING_FILE%"
    set "FAIL_CODE=1"
    goto fail
)
if not defined ANDROID_KEY_ALIAS (
    set "FAIL_MESSAGE=Missing KeyAlias in %SIGNING_FILE%"
    set "FAIL_CODE=1"
    goto fail
)
if not defined ANDROID_KEY_PASSWORD (
    set "FAIL_MESSAGE=Missing KeyPassword in %SIGNING_FILE%"
    set "FAIL_CODE=1"
    goto fail
)

if not defined BUILD_ONLY (
    if not exist "%ADB%" (
        set "FAIL_MESSAGE=ADB not found: %ADB%"
        set "FAIL_CODE=1"
        goto fail
    )

    "%ADB%" get-state >nul 2>nul
    if %ERRORLEVEL% neq 0 (
        set "FAIL_MESSAGE=No Android device found. Connect phone or start emulator, then run redeploy.bat again."
        set "FAIL_CODE=%ERRORLEVEL%"
        goto fail
    )
)

cd /d "%ROOT%app"
call .\gradlew.bat assembleRelease
if %ERRORLEVEL% neq 0 (
    set "FAIL_MESSAGE=Release build failed."
    set "FAIL_CODE=%ERRORLEVEL%"
    goto fail
)

if defined BUILD_ONLY (
    echo Done: release build
    exit /b 0
)

call :install_apk "%ROOT%app\app\build\outputs\apk\release\app-release.apk"
if %ERRORLEVEL% neq 0 (
    set "FAIL_MESSAGE=Install failed. Check ADB output above."
    set "FAIL_CODE=%ERRORLEVEL%"
    goto fail
)
call :refresh_launcher_cache
"%ADB%" shell am start -n com.example.chatbar/.MainActivity
if %ERRORLEVEL% neq 0 (
    set "FAIL_MESSAGE=Install succeeded, but launch failed."
    set "FAIL_CODE=%ERRORLEVEL%"
    goto fail
)
echo Done: release build + install + launch
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

echo Rebuilding release APK with device version %DEVICE_VERSION_NAME% ^(versionCode %DEVICE_VERSION_CODE%^).
set "CHATBAR_VERSION_CODE=%DEVICE_VERSION_CODE%"
set "CHATBAR_VERSION_NAME=%DEVICE_VERSION_NAME%"
pushd "%ROOT%app"
call .\gradlew.bat assembleRelease
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

:refresh_launcher_cache
rem ColorOS/OPPO launcher can keep a stale grey "installing" shortcut after adb reinstall.
"%ADB%" shell am force-stop com.android.launcher >nul 2>nul
"%ADB%" shell am force-stop com.android.launcher3 >nul 2>nul
exit /b 0

:fail
echo.
echo ERROR: %FAIL_MESSAGE%
echo.
if not defined NO_PAUSE pause
exit /b %FAIL_CODE%
