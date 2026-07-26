---
name: chatbar-release-publish
description: "Publish or repair a complete ChatBar GitHub update. Use whenever the user says 更新, 发布更新, 发新版本, 打包发布, reports missing Release Notes, or otherwise asks to ship or correct a ChatBar Release by committing every workspace change, pushing the current branch, publishing the APK, and verifying user-visible and app-consumable release notes."
---

# ChatBar Release Publish

Treat “更新” as authorization to publish every current ChatBar workspace change directly; do not open a PR or leave unrelated dirty files behind.

## Workflow

1. Read `chatbar-app-update` and `.github/workflows/release.yml`.
2. Inspect all tracked, staged, and untracked changes. Check for accidental secrets or generated junk; stop only for real data or credential risk.
3. Determine next unused patch version from remote stable tags/Releases. Never reuse a tag.
4. Write concise release notes entirely from user perspective:
   - Start with the exact heading `## 更新内容`, followed by at least one Markdown bullet.
   - State what users can now do or what feels better.
   - Avoid filenames, class names, prompts, RAG internals, test details, refactors, or implementation terminology.
   - Translate internal changes into outcomes. Example: write “现在 AI 遵循格式的能力大幅增强”, not “格式卡提示词后置”.
   - Omit developer-only changes unless they fix a user-visible failure.
   - Save multiline notes in a temporary UTF-8 Markdown file; do not rely on a complex inline shell argument.
5. Run `.\gradlew.bat test` and `powershell -ExecutionPolicy Bypass -File .\ci.ps1 -SkipAssemble` from `app/`. Fix in-scope failures before publishing.
6. Stage everything with `git add -A`. Review staged diff, then create one concise Chinese commit covering the update.
7. Push the current branch to `origin`.
8. Dispatch `.github/workflows/release.yml` with:
   - `versionName`: next unused version without `v`.
   - `releaseNotes`: load the exact Markdown file from step 4.
9. Monitor workflow through completion. On failure, inspect the failed job, fix the cause, commit and push the fix, then dispatch an unused version if a tag was already created.
10. Run `python scripts/verify_release.py --repo SaltyFishOTL/ChatChatBar --version <version>`. Completion requires all gates:
    - workflow succeeded and stable tag exists;
    - published Release body is non-empty, starts with `## 更新内容`, and contains user-facing bullets;
    - GitHub API returns that body;
    - `github.com/<owner>/<repo>/releases.atom` contains the same note text for the tag, proving the app fallback can consume it;
    - asset `ChatBar-<version>.apk` exists.
11. If verification reports missing or mismatched notes, repair the Release with `gh release edit v<version> --notes-file <file>`, then rerun the script. Never report success from workflow inputs, dispatch output, or asset presence alone.
12. Pull the workflow-created version bump with `git pull --ff-only`, remove temporary notes files, then confirm local workspace is clean and matches `origin`.

Report version, commit, workflow result, Release URL, APK asset, exact user-facing notes, and verification result.

## Client Reality Check

GitHub Release body existence does not prove the app can display it. When the app reports empty notes:

1. Read `chatbar-app-update`.
2. Verify API and Atom output separately with `python scripts/verify_release.py`.
3. Inspect the installed app version and its `AppUpdateChecker` primary/fallback paths.
4. State clearly when a fix requires publishing and installing a newer client; editing server-side notes cannot change retrieval code already installed.
