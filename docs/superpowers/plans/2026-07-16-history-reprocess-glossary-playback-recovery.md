# History Reprocess · Glossary · Playback · Recovery — Implementation Plan

> **For agentic workers:** Execute task-by-task. Steps use checkbox syntax.

**Goal:** Offline full-WAV ASR re-run (punctuation segments, Re-titled new sessions), glossary via `{{glossary}}`, timeline scrub on history detail, orphan WAV cold-start recovery.

**Architecture:** Extend existing layer — `OfflineReprocessPipeline` + `PunctuationSegmenter` + `SessionAudioPlayer` + settings glossary + `OrphanRecordingDetector`; reuse `AsrClient` / `LlmClient` / `HistoryRepository`.

**Tech Stack:** Kotlin, Jetpack Compose, Room, DataStore, MediaPlayer, OkHttp SSE, JUnit

## Global Constraints

- Re-run creates **new** session; shared `audioPath`; title `Re` + base; `offsetMs = 0`
- Whole WAV chunk ASR (~30s) → punctuation split → per-sentence LLM
- Fail any step after retries → **no** partial save
- Timeline play only if any `offsetMs > 0` and WAV exists
- Glossary: global list; inject only when prompt contains `{{glossary}}`

## File map

| Create | Responsibility |
|--------|----------------|
| `data/settings/GlossaryEntry.kt` | Entry + JSON codec |
| `domain/PunctuationSegmenter.kt` | Split text by CN/EN punctuation |
| `data/audio/WavChunker.kt` | PCM chunks by duration |
| `domain/OfflineReprocessPipeline.kt` | ASR → split → LLM → save |
| `data/audio/SessionAudioPlayer.kt` | MediaPlayer wrapper |
| `data/OrphanRecordingDetector.kt` | List orphan WAVs |
| Tests for segmenter, glossary, title helper, orphan, chunker | |

| Modify | |
|--------|--|
| `UserSettings.kt`, `SettingsRepository.kt`, `SettingsScreen.kt` | Glossary |
| `HistoryViewModel.kt`, `HistoryScreen.kt` | Reprocess + player |
| `AppContainer.kt`, `LiveTranslateScreen.kt` / VM | Orphan dialog |
| `strings.xml` | Copy |

### Task order

1. Glossary model + prompt render + Settings persistence/UI + tests  
2. PunctuationSegmenter + tests  
3. WavChunker + OfflineReprocessPipeline + History entry  
4. SessionAudioPlayer + click/scrub UI  
5. Orphan detector + Live cold-start dialog  
6. Build/test green  

---
