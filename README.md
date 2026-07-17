# Live Translate

**v1.1.0** · Kotlin + Jetpack Compose · MIT

**English** · [中文](README.zh-CN.md)

[![CI & Release](https://github.com/y12rf/live-caption-translate/actions/workflows/ci-release.yml/badge.svg)](https://github.com/y12rf/live-caption-translate/actions/workflows/ci-release.yml)
[![GitHub release](https://img.shields.io/github/v/release/y12rf/live-caption-translate)](https://github.com/y12rf/live-caption-translate/releases)

Near-real-time speech → OpenAI-compatible **ASR stream** → **LLM stream** translation → bilingual UI / floating captions → Room history + session WAV.

Bring your own ASR / LLM API keys .

适合上课听讲做笔记：自备 ASR / LLM API。

---

## Features

| Capability | Detail |
|------------|--------|
| Mic / internal / file | Microphone; internal audio (API 29+); **local file** via FFmpeg → Silero VAD → timeline offsets → ASR/LLM |
| VAD | Silero DNN: silence cut + `maxUtteranceMs` force cut; short silence merges into next utterance |
| ASR / LLM | Streaming SSE; Bearer / ApiKey headers; full URL or OpenAI path append; optional `thinking` field |
| ASR-only | Skip LLM; recognize only |
| Pipeline | Separate ASR / LLM queues (next ASR can run while previous sentence translates) |
| Home list | Always **full-sentence** bilingual pairs + streaming partials |
| Floating overlay | Both / SourceOnly / TranslationOnly; FullSentence or ScrollLine marquee; **auto-clear text after 3s** with no new caption; opacity 0–100 |
| Session | Pause / Resume same session; Stop saves; optional LLM title after N turns |
| History | Search, export, seek + scrub; **reprocess** ASR/translate; soft-skip failures and still save |
| Glossary | `{{glossary}}` in system prompt |
| Recovery | Cold-start orphan WAV: reprocess / discard / later |
| Keep-alive | FGS + WakeLock / WifiLock + battery optimization hints |

## Quick start

**Requirements:** JDK 17, Android SDK 34, device/emulator API 26+.

**Prebuilt APKs:** every push to `master` runs CI and publishes a [GitHub Release](https://github.com/y12rf/live-caption-translate/releases) with arm64 / x86 APKs (debug-keystore signed, sideload only).

```bat
gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

1. Install a debug APK from `app/build/outputs/apk/debug/`, or grab a Release build.
2. Open **Settings** → set ASR / LLM URL, API key, model, auth style.
3. On Live: pick source → **Start**. Optional floating overlay (notification lock/unlock). Full-screen bilingual: top-bar fullscreen icon.

| Variant | Typical path |
|---------|----------------|
| Debug · arm64 | `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk` |
| Debug · x86 | `app/build/outputs/apk/debug/app-x86-debug.apk` |

- **minSdk** 26 · **targetSdk** 34 · `applicationId` `com.example.livetranslate`
- ABI splits: `arm64-v8a`, `x86` (no fat APK)
- Release uses the debug keystore by default (sideload only) — replace for Play Store

> **Fork tip:** change `namespace` / `applicationId` under `app/build.gradle.kts` before publishing under your own package name.

## Architecture

```
ui/          Compose + ViewModel (Live / History / Settings)
domain/      SessionOrchestrator (live + file VAD pipeline)
             OfflineReprocessPipeline (history / orphan reprocess)
data/
  audio/     AudioCapture · Silero · FFmpeg · session WAV
  asr/       OpenAI transcriptions / chat-audio streaming
  llm/       Chat completions streaming + title
  settings/  DataStore · UserSettings · overlay enums
  history/   Room
service/     RecordingService · SubtitleOverlayService
```

## Default VAD (Settings)

| Parameter | Default | Role |
|-----------|---------|------|
| `silenceMs` | 260 | Silero silence hangover |
| `maxUtteranceMs` | 4500 | App-layer force cut |
| `minUtteranceMs` | 1500 | Short silence cuts merge into next speech |
| `sileroVadMode` | NORMAL | Silero threshold mode |
| Frame | 512 @ 16 kHz | ~32 ms (Silero fixed) |

## Permissions

| Permission | Why |
|------------|-----|
| `RECORD_AUDIO` | Mic / internal capture |
| `FOREGROUND_SERVICE` + mic / mediaProjection | Background capture |
| `POST_NOTIFICATIONS` | Recording notification (API 33+) |
| `SYSTEM_ALERT_WINDOW` | Floating captions |
| `WAKE_LOCK` | Long sessions |

## Resilience

| Mechanism | Behavior |
|-----------|----------|
| HTTP timeouts | connect 20s · read idle 45s · call 120s |
| Retries | Configurable (default 3), exponential backoff |
| Queues + disk overflow | Live utterances park offline instead of dropping |
| ASR-first | Source commits before translation finishes |
| Overlay ScrollLine | Holds incomplete empty ZH until LLM settle / fail stamp; catch-up speed on backlog |
| Idle overlay | Clears floating text after **3s** without new caption content |
| Stop path | `stopAndJoin` capture before WAV finalize / MediaProjection release |

## Known limitations

- Latency is VAD + network ASR/LLM bound
- Internal audio depends on OEM MediaProjection quality
- Room uses destructive migration on schema jump (can wipe history)
- API keys in plaintext DataStore; cleartext HTTP allowed for LAN gateways
- FFmpeg native libs increase APK size

## Security

See [SECURITY.md](SECURITY.md). Never commit API keys or keystores.

## Docs

- Third-party notices: [NOTICE](NOTICE)
- Security: [SECURITY.md](SECURITY.md)

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md).

## License

[MIT](LICENSE) — see also [NOTICE](NOTICE) for bundled native / VAD components.
