# Isolated Android fixtures

The ordinary debug application remains `com.example.chatbar`. Tests that save real editor drafts or entities must use a separate application/data directory. In an ordinary build, `CharacterCardFieldsPersistenceTest` reports three explicit JUnit assumptions rather than modifying that application's repositories.

On a clean emulator, run all device tests with the repository's isolated configuration (from `app/`):

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest -PCHATBAR_ISOLATED_ANDROID_TEST=true
```

This builds `com.example.chatbar.instrumented` with the same production code. To run only the new card editor tests:

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest -PCHATBAR_ISOLATED_ANDROID_TEST=true "-Pandroid.testInstrumentationRunnerArguments.class=com.example.chatbar.ui.character.CharacterCardFieldControlsTest,com.example.chatbar.ui.character.CharacterCardFieldsPersistenceTest"
```

`CHATBAR_ISOLATED_ANDROID_TEST_APP_ID` optionally selects another `com.example.chatbar.*` test ID when the switch is enabled. The production ID and malformed IDs are rejected. `BuildConfig.ISOLATED_ANDROID_TEST` and the runtime application ID guard both protect the repository-writing fixtures. No external source patch or real API credentials are required. Only the isolated run's behavioral results count as executed persistence regressions; the ordinary build's assumptions do not count as passes.
