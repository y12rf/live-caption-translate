# Changelog

## 1.2.1-beta

- **Reprocess (scheme C′)**: replace `OfflineReprocessPipeline` with `ReprocessEngine`
  - Cut WAV by history timeline offsets (VAD when no axis / orphan)
  - Multi-sentence ASR pack default **20** / max **30** + **90s** duration valve
  - Block ASR failure → per-block VAD fallback; **fail-closed** (save only if fully successful)
  - Batch translate + few-shot `|||`; new session `offsetMs = 0`, `Re` title, shared audio
- History multi-select: toolbar **Select** button; swipes only select/deselect **inside** multi-select (no scroll misfires); long-press still edits a line
- Offline VAD packer: duration hard cap ~30s; default batch size 15 (live file/old offline helpers)
- ASR stream merger: suffix/prefix overlap dedupe (reduces repeated tail phrases)
- versionName **1.2.1-beta** (versionCode 6)

## 1.2.0

- Default UI language applies English on first launch (no longer follows system Chinese while Settings shows English)
- Overlay defaults: background opacity **0**, max height **80**, border **off**
- Docs: slim README; architecture / build / usage under `doc/`
- CI Release: arm64 only; shorter release notes
- versionName **1.2.0** (versionCode 5)

## 1.1.0

### Captions / overlay
- ScrollLine: hold incomplete empty translation until LLM settles; release on ready text or `timestampMs` bump (including failed LLM)
- Mid-scroll translation fill-in without restarting the source marquee
- Catch-up marquee speed when backlog builds
- Floating overlay **auto-clears text after 3s** without new caption content (no re-show of same FullSentence line until content changes)
- Background opacity can be **0** (no forced border/divider alpha floor)
- Home bilingual list always **FullSentence** (independent of overlay layout)
- Removed duplicate immersive button on the home panel (top-bar fullscreen only)
- Layout mode switch resets overlay queue only on real mode changes; **catch-up** seeds `seenIds` so history is not re-marquee’d
- Long sessions: prune `seenIds` safely (no re-enqueue of old segments every tick)
- Stack/order chrome changes cancel marquee finish counters so the queue cannot stall
- Empty dual-blank captions (e.g. TranslationOnly + failed ZH) are not enqueued

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
