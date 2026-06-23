# ChatBar - Agent Guidance

## Build & Verification

All Gradle commands run from `app/`, **not** the repo root:

```powershell
cd app
.\gradlew.bat assembleDebug          # Build debug APK
.\gradlew.bat :app:compileDebugKotlin # Compile only (faster for syntax checks)
.\gradlew.bat test                    # Unit tests (JVM, no device needed)
```

Full CI-equivalent verification (lint not configured):

```powershell
.\ci.ps1              # test + compileDebugAndroidTestKotlin + assembleDebug
.\ci.ps1 -SkipAssemble  # Skip APK packaging
```

- **JDK 17** required. CI uses `temurin-17`.
- The debug APK installs via `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
- Gradle wrapper is at `app/gradlew.bat`.

## Architecture

Single-module Android app, `com.example.chatbar`, minSdk 26, targetSdk 36. Jetpack Compose UI with Navigation3 (type-safe serializable routes).

```
UI (Compose screens + ViewModels)
  -> Data (Repositories: Character, Chat, Model, FormatCard, SaveSlot, Settings)
       -> JsonFileStorage  (JSON files, no SQL/DB)
  -> Domain (Chat: PromptAssembler, ContextWindowManager, StreamingChatService)
           (RAG: ChunkingEngine, EmbeddingService, VectorSearchEngine, RagManager)
```

- **No real database.** `ObjectBox` is listed as a dependency but is unused. All persistence is JSON files on disk via `JsonFileStorage` (`filesDir/entities/<type>/<id>.json`).
- `ChatBarApp` (Application class) initializes all repositories and domain services. Access globals via `ChatBarApp.instance`.
- `DebugConfig.SHOW_DEBUG_UI` toggles debug overlays (log dialog, etc.).
- Navigation routes defined in `NavigationKeys.kt`. `Navigation.kt` wires the `NavDisplay` with bottom nav + screen stack.

## Dependencies & Build

- Uses **Aliyun Maven mirrors** in `settings.gradle.kts` — required for artifact resolution inside China.
- Version catalog at `app/gradle/libs.versions.toml`.
- Compose BOM `2026.03.01`, Kotlin `2.3.20`, AGP `9.0.1`.
- `isMinifyEnabled = false` even in release builds.

## Testing

- **Unit tests** (`app/src/test/`): JVM, run with `.\gradlew.bat test`. No device/emulator needed.
- **Instrumented tests** (`app/src/androidTest/`): require device. Compile-only check via `compileDebugAndroidTestKotlin`.

## Device Seed Data

`device-entities/` contains pre-populated JSON files (characters, models, sessions, etc.) used for device seeding. Entity schemas match the `data.local.entity` Kotlin data classes.

## Key Constraints

- Portrait-only (`screenOrientation="portrait"` in manifest).
- Requires `INTERNET` + `ACCESS_NETWORK_STATE` permissions.
- No ProGuard/R8 enabled; no keystore configured for release signing.
