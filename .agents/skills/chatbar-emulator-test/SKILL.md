---
name: chatbar-emulator-test
description: Run and verify ChatBar on its configured Android emulator or connected device. Use for Gradle checks, CI-equivalent verification, APK builds, release redeploy, emulator startup, instrumented tests, update-dialog Release Notes retrieval, adb interaction, alternate resolution or density checks, screenshots, logs, and install troubleshooting.
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

## Alternate Resolution Verification

- Apply `wm size` / `wm density` overrides only to an explicitly targeted emulator serial, never a physical device.
- Read current physical and override values before changing them. For 1080×1920 compact-height coverage, use `1080x1920` and `480`, then confirm `dumpsys window` reports `w360dp h640dp`.
- Target every install, input, screenshot, test, and cleanup command with `-s emulator-5554` when a physical device is also connected.
- Once the user starts manual testing, leave the emulator, resolution, app, and test state untouched until the user explicitly says testing is finished or asks for a change.
- For agent-only verification, reset size/density and close only the emulator instance started by the agent after evidence is collected. Remove temporary screenshots and UI dumps.

## Update Note Verification

Server metadata checks do not prove the app renders release notes. For update-note changes:

1. Use only a disposable/debug emulator, never a release-signed physical install.
2. Build with `.\gradlew.bat :app:assembleDebug -PCHATBAR_VERSION_NAME=<previous-version>` so the current stable GitHub Release appears newer.
3. Install and launch the override build, then confirm the update dialog shows real note bullets rather than “此版本没有填写 release note”.
4. Inspect `AppUpdateChecker` logs to identify whether API or Atom supplied metadata.
5. Rebuild once without `CHATBAR_VERSION_NAME` override after verification so the normal debug APK is restored.

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
