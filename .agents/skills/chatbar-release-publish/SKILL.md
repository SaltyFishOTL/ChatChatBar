---
name: chatbar-release-publish
description: "Publish a complete ChatBar update from the current workspace. Use whenever the user says 更新, 发布更新, 发新版本, 打包发布, or otherwise asks to ship ChatBar changes by committing every workspace change, pushing the current branch, dispatching the release workflow, and verifying the GitHub Release APK."
---

# ChatBar Release Publish

Treat “更新” as authorization to publish every current ChatBar workspace change directly; do not open a PR or leave unrelated dirty files behind.

## Workflow

1. Read `chatbar-app-update` and `.github/workflows/release.yml`.
2. Inspect all tracked, staged, and untracked changes. Check for accidental secrets or generated junk; stop only for real data or credential risk.
3. Determine next unused patch version from remote stable tags/Releases. Never reuse a tag.
4. Write concise release notes entirely from user perspective:
   - State what users can now do or what feels better.
   - Avoid filenames, class names, prompts, RAG internals, test details, refactors, or implementation terminology.
   - Translate internal changes into outcomes. Example: write “现在 AI 遵循格式的能力大幅增强”, not “格式卡提示词后置”.
   - Omit developer-only changes unless they fix a user-visible failure.
5. Run `.\gradlew.bat test` and `powershell -ExecutionPolicy Bypass -File .\ci.ps1 -SkipAssemble` from `app/`. Fix in-scope failures before publishing.
6. Stage everything with `git add -A`. Review staged diff, then create one concise Chinese commit covering the update.
7. Push the current branch to `origin`.
8. Dispatch `.github/workflows/release.yml` with:
   - `versionName`: next unused version without `v`.
   - `releaseNotes`: user-facing notes from step 4.
9. Monitor workflow through completion. On failure, inspect the failed job, fix the cause, commit and push the fix, then dispatch an unused version if a tag was already created.
10. Confirm all release results:
    - workflow succeeded;
    - tag `v<version>` exists;
    - GitHub Release is published;
    - asset `ChatBar-<version>.apk` exists.
11. Pull the workflow-created version bump with `git pull --ff-only`, then confirm local workspace is clean and matches `origin`.

Report version, commit, workflow result, Release URL, APK asset, and verification commands.
