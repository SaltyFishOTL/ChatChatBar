@echo off
setlocal
set "ROOT=%~dp0"
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

if not exist "%ADB%" (
    set "FAIL_MESSAGE=ADB not found: %ADB%"
    set "FAIL_CODE=1"
    goto fail
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

"%ADB%" get-state >nul 2>nul
if %ERRORLEVEL% neq 0 (
    set "FAIL_MESSAGE=No Android device found. Connect phone or start emulator, then run redeploy.bat again."
    set "FAIL_CODE=%ERRORLEVEL%"
    goto fail
)

"%ADB%" install -r "%ROOT%app\app\build\outputs\apk\release\app-release.apk"
if %ERRORLEVEL% neq 0 (
    set "FAIL_MESSAGE=Install failed. Possible causes: different signing key, installed versionCode is newer, or device rejected APK."
    set "FAIL_CODE=%ERRORLEVEL%"
    goto fail
)
"%ADB%" shell am start -n com.example.chatbar/.MainActivity
if %ERRORLEVEL% neq 0 (
    set "FAIL_MESSAGE=Install succeeded, but launch failed."
    set "FAIL_CODE=%ERRORLEVEL%"
    goto fail
)
echo Done: release build + install + launch
exit /b 0

:fail
echo.
echo ERROR: %FAIL_MESSAGE%
echo.
if not defined NO_PAUSE pause
exit /b %FAIL_CODE%
