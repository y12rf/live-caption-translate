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

Output (default ABI is arm64 only):

`app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`

Install, then set ASR/LLM in **Settings** ([README](../README.md)).

### Emulator (x86)

CI and the default project only build **arm64**. For an x86 emulator, temporarily add `"x86"` to `splits.abi.include` in `app/build.gradle.kts`, then assemble again.

## Optional tests

```bat
gradlew.bat :app:testDebugUnitTest
```

## Notes

- **minSdk** 26 · **targetSdk** 34 · `applicationId` `com.example.livetranslate`
- Release uses the debug keystore by default (sideload only)
- Fork tip: change `namespace` / `applicationId` in `app/build.gradle.kts` before publishing under your package name

Architecture details: [ARCHITECTURE.md](ARCHITECTURE.md).
