---
name: chatbar-emulator-test
description: Manage ChatBar post-change release handoff and opt-in Android diagnostics. Use after a feature or fix to follow the repository's ADB-aware release build/install/launch policy and provide manual test steps; also use when the user explicitly asks for emulator/device testing, logs, screenshots, or install troubleshooting.
---

# ChatBar Release Handoff and Android Testing

Follow AGENTS.md for target selection, automatic release handoff, test permissions, and device-data safety.

## Project Facts

- Run Gradle from app/.
- Package/activity: com.example.chatbar/.MainActivity.
- Local AVD: chatbar_avd, Pixel 6, API 36, x86_64, Google APIs.
- Android SDK: %LOCALAPPDATA%\Android\Sdk.
- JDK: 17.
- Emulator debug APK: app/app/build/outputs/apk/debug/app-debug.apk.
- Physical-device release APK: app/app/build/outputs/apk/release/app-release.apk.

## Release Handoff

- Build/install/launch release from repository root:

  .\redeploy.bat --no-pause

- Build release without install:

  .\redeploy.bat --build-only --no-pause

- `redeploy.bat` uses release signing, data-preserving install, downgrade recovery, ColorOS launcher refresh, and MainActivity launch.
- Because the script uses ADB's default target, invoke its install mode only after AGENTS.md target-count gate passes.
- Report `app/app/build/outputs/apk/release/app-release.apk` plus feature-specific manual test steps after handoff.

## Explicit Test Entry Points

- Emulator start/build/install/launch from repository root:

  .\emu.cmd

- JVM and CI checks from app/:

  .\gradlew.bat test
  .\gradlew.bat :app:compileDebugKotlin
  .\ci.ps1 -SkipAssemble
  .\ci.ps1

- Instrumented tests from app/ with emulator running:

  .\gradlew.bat :app:connectedDebugAndroidTest

## Explicit Test Workflow

1. Confirm requested test scope: automated checks, emulator, physical device, or specific diagnostics.
2. Run only checks needed for that scope.
3. Query adb devices -l and dumpsys package before choosing an install path.
4. Use redeploy.bat for a physical phone; use emu.cmd or targeted debug install for the configured emulator.
5. Launch MainActivity only when requested test needs runtime verification.
6. Use logcat, screenshots, or instrumented tests only for requested behavior.

## Alternate Resolution Verification

- Apply `wm size` / `wm density` overrides only to an explicitly targeted emulator serial, never a physical device.
- Read current physical and override values before changing them. For 1080×1920 compact-height coverage, use `1080x1920` and `480`, then confirm `dumpsys window` reports `w360dp h640dp`.
- Target every install, input, screenshot, test, and cleanup command with `-s emulator-5554` when a physical device is also connected.
- Once the user starts manual testing, leave the emulator, resolution, app, and test state untouched until the user explicitly says testing is finished or asks for a change.
- Reset size/density and close only the emulator instance started for the explicit request after evidence is collected. Remove temporary screenshots and UI dumps.

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
