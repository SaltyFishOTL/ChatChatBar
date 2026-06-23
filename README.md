# ChatBar

Android AI roleplay app with local character cards, chat sessions, multimodal messages, and RAG memory.

## Requirements

- Windows
- JDK 17
- Android SDK / platform tools
- Android 8.0+ device or emulator

## Build Debug APK

```powershell
cd D:\Projects\ChatBar\app
.\gradlew.bat assembleDebug
```

Output:

```text
D:\Projects\ChatBar\app\app\build\outputs\apk\debug\app-debug.apk
```

## Install Debug APK

Enable USB debugging, connect the device, then run:

```powershell
C:\Users\Administrator\AppData\Local\Android\Sdk\platform-tools\adb.exe install -r app\build\outputs\apk\debug\app-debug.apk
```

Launch:

```powershell
C:\Users\Administrator\AppData\Local\Android\Sdk\platform-tools\adb.exe shell monkey -p com.example.chatbar 1
```

## Verify Build

```powershell
cd D:\Projects\ChatBar\app
.\gradlew.bat :app:compileDebugKotlin
.\gradlew.bat assembleDebug
```

Both commands should end with `BUILD SUCCESSFUL`.

## Notes

- Minimum Android version: Android 8.0 (`minSdk 26`).
- The debug APK is unsigned for store distribution, but installable via `adb`.
- Local app data is stored on device private storage.
