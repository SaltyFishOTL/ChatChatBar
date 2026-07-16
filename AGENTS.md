# Repository Guidelines

## Project Structure & Module Organization

ChatBar is a single-module Android project under `app/`. Run Gradle from that directory, not repository root.

- `app/app/src/main/java/com/example/chatbar/`: Kotlin source. Main layers are `ui/`, `data/`, and `domain/`.
- `app/app/src/test/`: JVM unit tests.
- `app/app/src/androidTest/`: instrumented Compose/device tests.
- `device-entities/`: seed JSON for characters, models, sessions, and related local data.
- `app/gradle/libs.versions.toml`: dependency versions.

Persistence is JSON-file based through `JsonFileStorage`; there is no active SQL database.

## Build, Test, and Development Commands

Use PowerShell from `app/`:

```powershell
.\gradlew.bat :app:compileDebugKotlin
.\gradlew.bat test
.\gradlew.bat assembleDebug
.\ci.ps1 -SkipAssemble
.\ci.ps1
```

- `compileDebugKotlin`: fast Kotlin syntax/type check.
- `test`: JVM unit tests, no emulator required.
- `assembleDebug`: builds debug APK at `app/app/build/outputs/apk/debug/app-debug.apk`.
- `ci.ps1 -SkipAssemble`: tests plus Android test compilation.
- `ci.ps1`: full local verification. JDK 17 required.

## Post-Change Device Testing

After completing a feature or fix, if `adb devices -l` shows a connected Android device, automatically rebuild, reinstall, and launch ChatBar for manual testing. Use data-preserving install only:

- Release-owned installs: run `.\redeploy.bat --no-pause` from the project root.
- Current debug/test install state: run `.\gradlew.bat assembleDebug` from `app/`, then `adb install --no-streaming -r -d app/app/build/outputs/apk/debug/app-debug.apk`, then `adb shell am start -n com.example.chatbar/.MainActivity`.

Stop on `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. Do not uninstall, clear data, or switch signing keys unless the user explicitly confirms the data risk.

## Coding Style & Naming Conventions

Use Kotlin with standard 4-space indentation. Keep package names under `com.example.chatbar`. Name Compose screens `*Screen`, ViewModels `*ViewModel`, factories `*ViewModelFactory`, repositories `*Repository`, and tests `*Test`. Prefer existing `ui/kit` components and app theme primitives before adding new UI styles. Keep domain logic out of Composables; place chat, RAG, model, card, and image behavior in `domain/`.

## Testing Guidelines

Add JVM tests in `app/app/src/test` for domain and repository behavior. Add instrumented tests in `app/app/src/androidTest` for Compose UI or device-only behavior. Match existing test names such as `ContextWindowManagerTest` or `TutorialScreenTest`. Run `.\gradlew.bat test` for normal changes; run `.\ci.ps1 -SkipAssemble` when touching UI, navigation, Android APIs, or shared build config.

Prompt tests should assert behavior, parameter inclusion, ordering, omission, and required machine-protocol tokens. Do not assert editable natural-language wording or section titles exactly unless that literal text is itself a required external protocol.

## Prompt Ownership

Do not change user-facing prompt text, system prompt text, or prompt template text without explicit user approval. `PromptTemplates.kt` contains both prompt text and normal Kotlin code; helper functions, parameter plumbing, length handling, serialization, and other non-prompt code in that file may be changed as normal code. If a prompt text change seems necessary, first explain the exact problem, the proposed prompt diff, and the expected behavior change; wait for user confirmation before editing. After the user confirms the prompt text change, implement it directly as part of the current task. Non-prompt code fixes must not opportunistically rewrite prompts. Prompt text should describe the AI's task, input, output, and quality criteria; do not include irrelevant implementation details such as backend limits, API behavior, UI plumbing, storage, or execution flow unless the user explicitly wants those exposed to the AI.

All newly added hardcoded AI prompts, prompt templates, and prompt-builder text must live in `app/app/src/main/java/com/example/chatbar/domain/prompt/PromptTemplates.kt`. Feature code should call `PromptTemplates` constants/functions instead of embedding prompt text inline, so prompts remain easy for the user to review and edit in one place. New prompt text must be written in Chinese by default; use English only for required protocol tokens, JSON field names, model-facing tag formats such as NovelAI/Danbooru tags, or quoted external identifiers.

## Fallback Policy

Fallback paths are failure signals, not success paths. When fallback is triggered, first investigate and fix the upstream failure reason, and make the failure visible in status/debug output. Do not optimize fallback behavior to hide broken primary functionality unless the user explicitly asks for graceful degradation.

## Skill Maintenance

Before changing an area covered by a project skill under `.agents/skills`, read the relevant `SKILL.md`. After any change, update related skills in the same turn only when the change makes their existing content stale, incomplete, or misleading. Keep skills compact: replace stale facts, avoid logs, and do not add generic knowledge that Codex should already know.

Skills must not repeat global rules already present in this AGENTS.md, including prompt ownership, fallback policy, shell safety, coding style, verification defaults, or device install safety. Put only feature-specific file maps, workflows, exceptions, and project facts in skills. If a skill needs to reference a globally governed area, name the feature-specific entry point without restating the global rule.

## Advanced Tutorial Maintenance

Every user-visible long-press action must be documented in the advanced tutorial in `ui/tutorial/TutorialScreen.kt`. When adding, removing, renaming, or changing a long-press gesture or its menu actions, update the advanced tutorial in the same change.

## Commit & Pull Request Guidelines

Git history uses short, scope-focused summaries, often Chinese imperatives such as `优化RAG` or `美化界面`. Keep commits concise and focused on one change. PRs should include problem, solution, verification commands, and screenshots or recordings for visible UI changes. Link related issues when available and call out data migration or seed-data changes.

## Architecture & Configuration Notes

`ChatBarApp` wires repositories and services; access app-level instances through `ChatBarApp.instance`. Navigation routes live in `NavigationKeys.kt`, with wiring in `Navigation.kt`. The app is portrait-only and uses `INTERNET` plus `ACCESS_NETWORK_STATE`. `DebugConfig.SHOW_DEBUG_UI` controls debug overlays.
