# Live Translate — 近实时英文同声传译 (Streaming MVP)

Kotlin + Jetpack Compose。麦克风 → 能量 VAD 切句（含最长强制截断）→ OpenAI 兼容 **ASR stream** → **LLM stream** 中文翻译 → 双语实时 UI → Room 历史。

## 快速开始

1. 用 Android Studio 打开本目录（或命令行 `gradlew.bat :app:assembleDebug`）
2. 运行 App → **Settings** 配置：
   - ASR Base URL / API Key / Model
   - LLM Base URL / API Key / Model
   - 输入语言默认 `en`，输出默认 `zh`
   - VAD：`silenceMs`、`maxUtteranceMs`（强制截断）等
3. 回到主界面 → **Start**（授予麦克风权限）

## 架构要点

- `data/audio`：`AudioRecord` + `EnergyVad` + `WavEncoder`
- `data/asr`、`data/llm`：OkHttp + SSE（`stream=true`），增量更新 UI
- `domain/SessionOrchestrator`：单句串行队列 + 滑动窗口上下文
- `data/settings`：DataStore；`data/history`：Room + 导出

## 验证

```bat
gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

APK：`app/build/outputs/apk/debug/app-debug.apk`

## 文档

- 设计：`docs/superpowers/specs/2026-07-14-live-translate-streaming-mvp-design.md`
- 计划：`docs/superpowers/plans/2026-07-14-live-translate-streaming-mvp.md`
- 待确认默认：`docs/DEFERRED_DECISIONS.md`
