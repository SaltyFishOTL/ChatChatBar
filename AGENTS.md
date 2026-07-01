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

## Coding Style & Naming Conventions

Use Kotlin with standard 4-space indentation. Keep package names under `com.example.chatbar`. Name Compose screens `*Screen`, ViewModels `*ViewModel`, factories `*ViewModelFactory`, repositories `*Repository`, and tests `*Test`. Prefer existing `ui/kit` components and app theme primitives before adding new UI styles. Keep domain logic out of Composables; place chat, RAG, model, card, and image behavior in `domain/`.

## Testing Guidelines

Add JVM tests in `app/app/src/test` for domain and repository behavior. Add instrumented tests in `app/app/src/androidTest` for Compose UI or device-only behavior. Match existing test names such as `ContextWindowManagerTest` or `TutorialScreenTest`. Run `.\gradlew.bat test` for normal changes; run `.\ci.ps1 -SkipAssemble` when touching UI, navigation, Android APIs, or shared build config.

## Commit & Pull Request Guidelines

Git history uses short, scope-focused summaries, often Chinese imperatives such as `优化RAG` or `美化界面`. Keep commits concise and focused on one change. PRs should include problem, solution, verification commands, and screenshots or recordings for visible UI changes. Link related issues when available and call out data migration or seed-data changes.

## Architecture & Configuration Notes

`ChatBarApp` wires repositories and services; access app-level instances through `ChatBarApp.instance`. Navigation routes live in `NavigationKeys.kt`, with wiring in `Navigation.kt`. The app is portrait-only and uses `INTERNET` plus `ACCESS_NETWORK_STATE`. `DebugConfig.SHOW_DEBUG_UI` controls debug overlays.
