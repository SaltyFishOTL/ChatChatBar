---
name: chatbar-emulator-test
description: Run and verify ChatBar on its configured Android emulator or connected device. Use for Gradle checks, CI-equivalent verification, APK builds, release redeploy, emulator startup, instrumented tests, adb interaction, screenshots, logs, and install troubleshooting.
---

# ChatBar Android Testing

Use repository scripts as primary entry points. Follow AGENTS.md for verification scope and device-data safety.

## Project Facts

- Run Gradle from app/.
- Package/activity: com.example.chatbar/.MainActivity.
- Local AVD: chatbar_avd, Pixel 6, API 36, x86_64, Google APIs.
- Android SDK: %LOCALAPPDATA%\Android\Sdk.
- JDK: 17.
- Emulator debug APK: app/app/build/outputs/apk/debug/app-debug.apk.
- Physical-device release APK: app/app/build/outputs/apk/release/app-release.apk.

## Primary Entry Points

- Physical phone release build/install/launch from repository root:

  .\redeploy.bat --no-pause

- Release build without install:

  .\redeploy.bat --build-only --no-pause

- Emulator start/build/install/launch from repository root:

  .\emu.cmd

- JVM and CI checks from app/:

  .\gradlew.bat test
  .\gradlew.bat :app:compileDebugKotlin
  .\ci.ps1 -SkipAssemble
  .\ci.ps1

- Instrumented tests from app/ with emulator running:

  .\gradlew.bat :app:connectedDebugAndroidTest

## Script Behavior

- redeploy.bat uses project release signing and data-preserving adb install.
- On INSTALL_FAILED_VERSION_DOWNGRADE it retries with downgrade allowance, then can rebuild with installed versionCode/versionName before retrying.
- emu.cmd targets disposable/emulator debug installs and can rebuild against installed version metadata on downgrade.
- Physical phones stay on release signing; emulator/disposable devices may use debug signing.

## Workflow

1. Run the smallest relevant JVM/compile check.
2. Run ci.ps1 -SkipAssemble for UI, navigation, Android API, or shared behavior changes.
3. Query adb devices -l and dumpsys package before choosing install path.
4. Use redeploy.bat for a physical phone; use emu.cmd or debug install for the configured emulator.
5. Launch MainActivity and verify process/activity state.
6. Use logcat, screenshots, or instrumented tests only for the behavior under test.

## Useful ADB Checks

- Device list: adb devices -l
- Package state: adb shell dumpsys package com.example.chatbar
- Launch: adb shell am start -n com.example.chatbar/.MainActivity
- Filtered log: adb logcat -s AndroidRuntime:E ChatBar:*
- Screenshot: adb exec-out screencap -p

Use the SDK platform-tools adb path when adb is not on PATH.

## Troubleshooting

- INSTALL_FAILED_UPDATE_INCOMPATIBLE: signing mismatch; stop install flow.
- INSTALL_FAILED_VERSION_DOWNGRADE: use repository scripts so version matching remains data-preserving.
- ADB missing: use %LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe.
- Gradle/JDK failure: verify JDK 17 and run from app/.
- Emulator not listed: start chatbar_avd through emu.cmd, then verify boot state.
- Keyboard unavailable: ensure hw.keyboard = yes in the AVD config and cold boot.
- Emulator exits with terminal: launch detached through emu.cmd or cmd /c start.
