---
name: chatbar-app-update
description: Maintain ChatBar update discovery, GitHub Release APK selection, in-app download state, APK validation, unknown-source permission, FileProvider installer handoff, and release publishing. Use when changing update checks, download progress or retry, install behavior, release asset naming, Android install permissions, or .github/workflows/release.yml.
---

# ChatBar App Update

## Entry Points

- Release lookup and asset selection: `domain/update/AppUpdateChecker.kt`.
- Download, APK validation, and install handoff: `domain/update/AppUpdateManager.kt`.
- Dialog states: `ui/components/AppUpdateDialog.kt`.
- Startup and manual-check callers: `MainActivity.kt` and `ui/manage/ManageScreen.kt`.
- Android access: `AndroidManifest.xml` and `res/xml/file_paths.xml`.
- Publishing: `.github/workflows/release.yml`.
- Selection/version tests: `AppUpdateCheckerTest.kt`.

All Kotlin paths are relative to `app/app/src/main/java/com/example/chatbar/` unless stated otherwise.

## Invariants

- Keep normal update flow inside ChatBar: select stable Release APK, download it, then open system installer. Open Release page only when no usable APK exists.
- Prefer `browser_download_url` from GitHub asset metadata. Keep deterministic fallback name aligned with workflow output `ChatBar-<version>.apk`.
- Store packages under app-private `files/updates/`; expose only that directory through existing FileProvider.
- Before install, reject incomplete files, invalid APKs, foreign package names, mismatched version names, and non-increasing `versionCode`.
- Keep one active download. Scope UI state to asset URL so stale state cannot control another release.
- Treat unknown-source approval and installer confirmation as required Android user actions. Never uninstall, clear data, or bypass signature checks.
- Preserve failure detail in dialog. Do not silently redirect to browser after download or validation failure.

## Change Workflow

1. Read all entry points above before changing contracts.
2. Keep `AppUpdateInfo`, serialized GitHub models, downloader state, and both dialog callers synchronized.
3. When changing asset naming or release metadata, update checker fallback, release workflow, and tests together.
4. When changing download storage, update FileProvider paths and verify URI grants remain narrow.
5. Represent idle, downloading, ready, failed, permission-required, and installer-launch behavior explicitly.
6. Verify with update unit tests, debug Kotlin compile, and `ci.ps1 -SkipAssemble`.
7. If a release-signed device is connected, use data-preserving release deployment. Debug signing cannot validate production self-update compatibility.

## Publishing

Use `.github/workflows/release.yml` through `workflow_dispatch` after all intended commits reach its target branch. Supply:

- `versionName`: next unused version without relying on local build overrides.
- `releaseNotes`: short user-facing changes since previous Release; exclude implementation details.

Workflow increments Android `versionCode`, commits version metadata, tags `v<version>`, builds signed APK, and creates GitHub Release. Confirm workflow success, Release tag, and attached `ChatBar-<version>.apk` before reporting completion.
