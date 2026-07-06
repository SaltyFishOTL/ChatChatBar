@echo off
setlocal EnableExtensions
set "MODE=off"
set "NO_PAUSE="
set "SUPABASE_URL=%CHATBAR_SUPABASE_URL%"
set "SERVICE_ROLE_KEY=%CHATBAR_SUPABASE_SERVICE_ROLE_KEY%"
set "CONFIG_FILE=%~dp0secrets\chatbar-community-admin.txt"
set "PROJECT_REF=rrlxofrrgfnhjfyrqvry"
set "FAIL_MESSAGE="
set "FAIL_CODE=1"

if not defined SERVICE_ROLE_KEY if defined SUPABASE_SERVICE_ROLE_KEY set "SERVICE_ROLE_KEY=%SUPABASE_SERVICE_ROLE_KEY%"
if not defined SUPABASE_URL if defined CHATBAR_SUPABASE_PROJECT_URL set "SUPABASE_URL=%CHATBAR_SUPABASE_PROJECT_URL%"

if exist "%CONFIG_FILE%" (
    for /f "usebackq tokens=1,* delims==" %%A in ("%CONFIG_FILE%") do (
        if /i "%%A"=="SupabaseUrl" set "SUPABASE_URL=%%B"
        if /i "%%A"=="CHATBAR_SUPABASE_URL" set "SUPABASE_URL=%%B"
        if /i "%%A"=="ServiceRoleKey" set "SERVICE_ROLE_KEY=%%B"
        if /i "%%A"=="CHATBAR_SUPABASE_SERVICE_ROLE_KEY" set "SERVICE_ROLE_KEY=%%B"
        if /i "%%A"=="SUPABASE_SERVICE_ROLE_KEY" set "SERVICE_ROLE_KEY=%%B"
    )
)

:parse_args
if "%~1"=="" goto after_args
if /i "%~1"=="--no-pause" (
    set "NO_PAUSE=1"
) else (
    set "MODE=%~1"
)
shift
goto parse_args

:after_args

if /i "%MODE%"=="help" goto help
if /i "%MODE%"=="--help" goto help
if /i "%MODE%"=="/?" goto help

set "ENABLED="
set "MESSAGE=Community disabled by emergency switch"
if /i "%MODE%"=="off" set "ENABLED=false"
if /i "%MODE%"=="disable" set "ENABLED=false"
if /i "%MODE%"=="on" set "ENABLED=true"
if /i "%MODE%"=="enable" set "ENABLED=true"
if /i "%MODE%"=="status" goto status

if not defined ENABLED (
    set "FAIL_MESSAGE=Unknown mode: %MODE%"
    set "FAIL_CODE=2"
    goto fail
)
if /i "%ENABLED%"=="true" set "MESSAGE="

if not defined SUPABASE_URL goto cli_update
if not defined SERVICE_ROLE_KEY goto cli_update

set "TEMP_JSON=%TEMP%\chatbar-community-%RANDOM%-%RANDOM%.json"
> "%TEMP_JSON%" echo {"next_enabled":%ENABLED%,"next_message":"%MESSAGE%"}

echo Setting global Community enabled=%ENABLED%...
curl.exe --fail-with-body -sS -X POST "%SUPABASE_URL%/rest/v1/rpc/set_community_enabled" ^
    -H "apikey: %SERVICE_ROLE_KEY%" ^
    -H "Authorization: Bearer %SERVICE_ROLE_KEY%" ^
    -H "Content-Type: application/json" ^
    --data-binary "@%TEMP_JSON%"
set "CURL_CODE=%ERRORLEVEL%"
del "%TEMP_JSON%" >nul 2>nul
if %CURL_CODE% neq 0 (
    set "FAIL_MESSAGE=Supabase update failed."
    set "FAIL_CODE=%CURL_CODE%"
    goto fail
)

echo.
echo Done. Apps will hide/show Community after next backend status poll.
if not defined NO_PAUSE pause
exit /b 0

:cli_update
where npx.cmd >nul 2>nul
if %ERRORLEVEL% neq 0 (
    set "FAIL_MESSAGE=Missing service-role config and npx.cmd. Set CHATBAR_SUPABASE_SERVICE_ROLE_KEY or install Node/Supabase CLI."
    set "FAIL_CODE=1"
    goto fail
)

echo Setting global Community enabled=%ENABLED% via Supabase CLI...
npx.cmd --yes supabase db query --linked --workdir . "select enabled, message from public.set_community_enabled(%ENABLED%, '%MESSAGE%')"
if %ERRORLEVEL% neq 0 (
    set "FAIL_MESSAGE=Supabase CLI update failed. Run supabase login/link or set CHATBAR_SUPABASE_SERVICE_ROLE_KEY."
    set "FAIL_CODE=%ERRORLEVEL%"
    goto fail
)

echo.
echo Done. Apps will hide/show Community after next backend status poll.
if not defined NO_PAUSE pause
exit /b 0

:status
if not defined SUPABASE_URL goto cli_status
if not defined SERVICE_ROLE_KEY goto cli_status
curl.exe --fail-with-body -sS "%SUPABASE_URL%/rest/v1/community_runtime_config?id=eq.community&select=enabled,message,updated_at" ^
    -H "apikey: %SERVICE_ROLE_KEY%" ^
    -H "Authorization: Bearer %SERVICE_ROLE_KEY%"
if %ERRORLEVEL% neq 0 (
    set "FAIL_MESSAGE=Supabase status request failed."
    set "FAIL_CODE=%ERRORLEVEL%"
    goto fail
)
echo.
if not defined NO_PAUSE pause
exit /b 0

:cli_status
where npx.cmd >nul 2>nul
if %ERRORLEVEL% neq 0 (
    set "FAIL_MESSAGE=Missing service-role config and npx.cmd. Set CHATBAR_SUPABASE_SERVICE_ROLE_KEY or install Node/Supabase CLI."
    set "FAIL_CODE=1"
    goto fail
)
npx.cmd --yes supabase db query --linked --workdir . "select enabled, message, updated_at from public.community_runtime_config where id = 'community'"
if %ERRORLEVEL% neq 0 (
    set "FAIL_MESSAGE=Supabase CLI status request failed. Run supabase login/link or set CHATBAR_SUPABASE_SERVICE_ROLE_KEY."
    set "FAIL_CODE=%ERRORLEVEL%"
    goto fail
)
echo.
if not defined NO_PAUSE pause
exit /b 0

:help
echo Usage: toggle-community.bat [off^|on^|status] [--no-pause]
echo Default: off
echo Uses service-role REST when configured, otherwise Supabase CLI linked project auth.
echo Config env: CHATBAR_SUPABASE_URL and CHATBAR_SUPABASE_SERVICE_ROLE_KEY
echo Config file: secrets\chatbar-community-admin.txt
echo   SupabaseUrl=https://PROJECT.supabase.co
echo   ServiceRoleKey=SERVICE_ROLE_KEY
echo CLI fallback: npx.cmd --yes supabase db query --linked --workdir .
exit /b 0

:fail
echo.
echo ERROR: %FAIL_MESSAGE%
echo.
if not defined NO_PAUSE pause
exit /b %FAIL_CODE%
