---
name: chatbar-emulator-test
description: Run and test ChatBar on Android emulator or connected Android device. Use for emulator launch, APK install, release redeploy, unit tests, instrumented tests, adb interaction, or CI-equivalent verification.
---

# ChatBar Android Testing

## Prerequisites

- JDK 17 (`temurin-17`), set `JAVA_HOME`
- Android SDK at `%LOCALAPPDATA%\Android\Sdk`
- AVD named `chatbar_avd` (Pixel 6, API 36, x86_64, Google APIs)
- AEHD hypervisor driver installed (AMD) or WHPX/Hyper-V enabled (Intel)

## Default Redeploy Flow

For any user-owned phone or physical device, use the release-signed redeploy script. This is the same release package line used for GitHub release builds and keeps `com.example.chatbar` on one signing identity:

```powershell
.\redeploy.bat --no-pause
```

`redeploy.bat` reads `secrets\chatbar-release-signing.txt`, builds `assembleRelease`, installs `app/app/build/outputs/apk/release/app-release.apk` with `adb install --no-streaming -r`, refreshes the launcher cache, and launches `com.example.chatbar/.MainActivity`.

If Android reports `INSTALL_FAILED_VERSION_DOWNGRADE`, `redeploy.bat` first retries `adb install --no-streaming -r -d`. If that still fails, it reads the installed `versionCode`/`versionName`, rebuilds release with matching `CHATBAR_VERSION_CODE` and `CHATBAR_VERSION_NAME`, then retries a normal data-preserving `adb install --no-streaming -r`.

Never build or install `app-debug.apk` on a physical phone. Do not run `assembleDebug` as a phone redeploy substitute.

## Device Data Safety

Real-device install is data-risky. Before installing on a user-owned device:

- Confirm target device with `adb devices -l`.
- Check installed package with `adb shell dumpsys package com.example.chatbar`.
- Treat `INSTALL_FAILED_UPDATE_INCOMPATIBLE` as a hard stop. Do not uninstall, do not clear data, and do not try a different signing key to "fix" it.
- Do not run `adb uninstall`, `adb shell pm clear`, or any install path that requires uninstalling unless the user explicitly confirms data loss risk.
- Use `redeploy.bat --no-pause` for physical-device updates.
- If `dumpsys package com.example.chatbar` shows `DEBUGGABLE` on a physical phone, stop and report that debug signing currently owns the package. Do not continue installing either debug or release until data backup/export and uninstall risk are decided.
- Use debug APK installs only on emulator or disposable devices with non-user data.

If a real device has important data and `run-as com.example.chatbar` works, pull a copy of `files/` before any risky install attempt.

## Post-Change Auto Deploy

After completing a feature or fix, if `adb devices -l` shows a connected physical phone, use the release redeploy path so local installs match GitHub release signing. Do not wait for another prompt.

Use only data-preserving install paths:

- Physical phone: run `.\redeploy.bat --no-pause` from project root.
- Emulator or disposable test device: debug install is allowed only when it cannot replace a real-user `com.example.chatbar` install.

Stop on `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. Do not uninstall, clear data, or switch signing keys unless the user explicitly confirms the data risk.

## Emulator Quick Start

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

Release build/install from project root:

```powershell
.\redeploy.bat --build-only --no-pause
.\redeploy.bat --no-pause
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

If installing manually on emulator and Android returns `INSTALL_FAILED_VERSION_DOWNGRADE`, prefer `emu.cmd` for emulator debug or `redeploy.bat` for release. They keep the install data-preserving and rebuild once with the device's current `versionCode`/`versionName` when needed.

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

# Clear app data. Real-device destructive: ask user first.
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" shell pm clear com.example.chatbar

# View logcat (filtered)
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" logcat -s AndroidRuntime:E ChatBar:*

# Uninstall. Real-device destructive: ask user first.
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
| APK install fails | For physical phones, use `redeploy.bat --no-pause` only. For emulator debug, ensure APK exists at `app/app/build/outputs/apk/debug/app-debug.apk`; for `INSTALL_FAILED_VERSION_DOWNGRADE`, use `emu.cmd` or `redeploy.bat` so the data-preserving reinstall fallback runs |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | Signing mismatch. Stop. Do not uninstall or clear data. Use the same release signing key or ask user how to proceed |
| Gradle build fails | JDK 17 required: `java --version` must show 17.x |
| Keyboard not working | Set `hw.keyboard = yes` in config.ini and cold boot |
| Emulator exits with terminal | Launch via `cmd /c start "" ...` or double-click `emu.cmd` |
