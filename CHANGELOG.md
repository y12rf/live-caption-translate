# Changelog

## 1.1.0

### Captions / overlay
- ScrollLine: hold incomplete empty translation until LLM settles; release on ready text or `timestampMs` bump (including failed LLM)
- Mid-scroll translation fill-in without restarting the source marquee
- Catch-up marquee speed when backlog builds
- Floating overlay **auto-clears text after 3s** without new caption content (no re-show of same FullSentence line until content changes)
- Background opacity can be **0** (no forced border/divider alpha floor)
- Home bilingual list always **FullSentence** (independent of overlay layout)
- Removed duplicate immersive button on the home panel (top-bar fullscreen only)
- Layout mode switch resets overlay queue only on real mode changes

### Session / capture
- `AudioCapture.stopAndJoin` before WAV finalize and MediaProjection release (reduces OEM native crashes on internal audio)
- Safer RecordingService teardown (projection clear order, stopForeground, exception isolation)
- `markSegmentFailed` bumps segment timestamp so overlay hold can release

### Robustness / platform
- App-scope `CoroutineExceptionHandler`
- Overlay settings/caption collectors log failures instead of dying silently
- Empty ScrollLine captions advance without deep recursion
- `allowBackup=false` to reduce API key leakage via backup

### Project
- MIT license, SECURITY / CONTRIBUTING / NOTICE, refreshed README
- versionName **1.1.0** (versionCode 4)

## 1.0.0

Initial public MVP: Silero VAD, streaming ASR/LLM, bilingual UI, floating overlay, history, reprocess, file import, glossary, orphan recovery.
