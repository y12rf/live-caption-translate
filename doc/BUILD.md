# Build from source

For end-user install and API setup, see the [README](../README.md).

## Requirements

- JDK 17
- Android SDK 34
- Device or emulator on API 26+

## Assemble debug APK

```bat
gradlew.bat :app:assembleDebug
```

| Variant | Path |
|---------|------|
| arm64 | `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk` |
| x86 | `app/build/outputs/apk/debug/app-x86-debug.apk` |

Install the APK that matches your device/emulator ABI, then configure ASR/LLM in **Settings** (see [README → Configuration](../README.md#configuration)).

## Optional tests

```bat
gradlew.bat :app:testDebugUnitTest
```

## Notes

- **minSdk** 26 · **targetSdk** 34 · `applicationId` `com.example.livetranslate`
- ABI splits: `arm64-v8a`, `x86` (no universal fat APK)
- Release builds default to the debug keystore (sideload only) — replace for Play Store
- Fork tip: change `namespace` / `applicationId` under `app/build.gradle.kts` before publishing under your own package name

Architecture details: [ARCHITECTURE.md](ARCHITECTURE.md).
