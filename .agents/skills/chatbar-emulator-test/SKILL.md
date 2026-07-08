---
name: chatbar-emulator-test
description: Run and test ChatBar on Android emulator without a physical device. Use for emulator launch, APK install, unit tests, instrumented tests, adb interaction, or CI-equivalent verification on emulator.
---

# ChatBar Emulator Testing

## Prerequisites

- JDK 17 (`temurin-17`), set `JAVA_HOME`
- Android SDK at `%LOCALAPPDATA%\Android\Sdk`
- AVD named `chatbar_avd` (Pixel 6, API 36, x86_64, Google APIs)
- AEHD hypervisor driver installed (AMD) or WHPX/Hyper-V enabled (Intel)

## Quick Start

Double-click `emu.cmd` at project root. It starts the emulator, builds the debug APK, installs it, and falls back to a device-version-matching rebuild if Android reports `INSTALL_FAILED_VERSION_DOWNGRADE`.

Manual equivalent:

```powershell
# Start emulator (detached, survives terminal close)
cmd /c start "" "%LOCALAPPDATA%\Android\Sdk\emulator\emulator.exe" -avd chatbar_avd

# Wait for boot, then install + launch
& "%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe" wait-for-device
cd app
.\gradlew.bat assembleDebug
cd ..
& "%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe" install -r app\app\build\outputs\apk\debug\app-debug.apk
& "%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe" shell am start -n com.example.chatbar/.MainActivity
```

## Build Commands

All commands run from `app/`:

```powershell
cd app
.\gradlew.bat assembleDebug           # Build debug APK
.\gradlew.bat test                    # Unit tests (JVM, no emulator needed)
.\gradlew.bat :app:compileDebugKotlin # Compile-only for syntax checks
```

## CI-Equivalent Verification

Run the full check pipeline (no emulator needed):

```powershell
.\ci.ps1                 # test + compileDebugAndroidTestKotlin + assembleDebug
.\ci.ps1 -SkipAssemble   # Skip APK packaging
```

## Emulator Testing Workflow

### 1. Verify JVM Unit Tests First

Always run unit tests before involving the emulator:

```powershell
cd app
.\gradlew.bat test
```

Check results at `app/app/build/test-results/test/`.

### 2. Build and Install on Emulator

Run from project root:

```powershell
.\emu.cmd
```

If installing manually and Android returns `INSTALL_FAILED_VERSION_DOWNGRADE`, prefer `emu.cmd` or `redeploy.bat`. They first try `adb install -r -d`; if the device still rejects, they read the device's current `versionCode` and `versionName`, then rebuild the APK once with `CHATBAR_VERSION_CODE` and `CHATBAR_VERSION_NAME` matching the device.

### 3. Run Instrumented Tests

Instrumented tests require the emulator to be running:

```powershell
cd app
.\gradlew.bat :app:connectedDebugAndroidTest
```

If the emulator is not detected, verify with:

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" devices
```

Should show `emulator-5554   device`.

### 4. Manual Verification via ADB

```powershell
# Launch app
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" shell am start -n com.example.chatbar/.MainActivity

# Force-stop app
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" shell am force-stop com.example.chatbar

# Clear app data
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" shell pm clear com.example.chatbar

# View logcat (filtered)
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" logcat -s AndroidRuntime:E ChatBar:*

# Uninstall
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" uninstall com.example.chatbar

# Take screenshot
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" exec-out screencap -p > screenshot.png
```

## AVD Management

### Check if AVD exists

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe" -list-avds
```

### Create AVD (if missing)

```powershell
# Install prerequisites first
& "$env:LOCALAPPDATA\Android\Sdk\cmdline-tools\latest\bin\sdkmanager.bat" --sdk_root="$env:LOCALAPPDATA\Android\Sdk" "platforms;android-36" "system-images;android-36;google_apis;x86_64"

# Create AVD
& "$env:LOCALAPPDATA\Android\Sdk\cmdline-tools\latest\bin\avdmanager.bat" create avd -n chatbar_avd -k "system-images;android-36;google_apis;x86_64" -d pixel_6 -f
```

### Wipe emulator data and cold boot

```powershell
cmd /c start "" "%LOCALAPPDATA%\Android\Sdk\emulator\emulator.exe" -avd chatbar_avd -wipe-data
```

### Kill emulator

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" -s emulator-5554 emu kill
```

## Keyboard Input

The AVD config must include `hw.keyboard = yes` for physical keyboard support. Verify in `~/.android/avd/chatbar_avd/config.ini`. If keyboard is unresponsive, cold boot the emulator after fixing this setting.

## App-Specific Notes

- Package name: `com.example.chatbar`
- App minSdk 26, targetSdk/compileSdk 36, matches API 36 emulator
- App enables `DebugConfig.SHOW_DEBUG_UI` for debug overlays
- Seed data in `device-entities/` directory

## Troubleshooting

| Symptom | Check |
|---------|-------|
| Emulator slow | Verify AEHD: `& "$env:LOCALAPPDATA\Android\Sdk\emulator\emulator-check.exe" accel` should return 0 |
| ADB no device | `adb kill-server` then `adb start-server`, or restart emulator |
| APK install fails | Ensure APK exists at `app/app/build/outputs/apk/debug/app-debug.apk`; for `INSTALL_FAILED_VERSION_DOWNGRADE`, use `emu.cmd` or `redeploy.bat` so the data-preserving reinstall fallback runs |
| Gradle build fails | JDK 17 required: `java --version` must show 17.x |
| Keyboard not working | Set `hw.keyboard = yes` in config.ini and cold boot |
| Emulator exits with terminal | Launch via `cmd /c start "" ...` or double-click `emu.cmd` |
