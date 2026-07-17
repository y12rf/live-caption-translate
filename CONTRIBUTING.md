# Contributing

Thanks for helping improve Live Translate.

## Development

1. Open the project root in Android Studio (Giraffe+ / AGP 8.x) or use the Gradle wrapper.
2. Copy nothing secret into the tree — API keys live only in the running app’s Settings.
3. Prefer:

   ```bat
   gradlew.bat :app:testDebugUnitTest :app:assembleDebug
   ```

## Code style

- Kotlin + Jetpack Compose for UI.
- UI strings go in `app/src/main/res/values/strings.xml` **and** `values-zh/`.
- Settings defaults live in `UserSettings`; keep `docs/DEFERRED_DECISIONS.md` roughly in sync when defaults change.
- Prefer small, focused PRs with tests for domain / controller logic when practical.

## Pull requests

- Describe **what** and **why**.
- Note any behavior change for live session, overlay, or history export.
- Do not include build outputs, APKs, or personal settings.

## License

By contributing, you agree your contributions are licensed under the MIT License (see `LICENSE`).
