# Live Translate — 近实时同声传译

**v1.0.0** · Kotlin + Jetpack Compose

音频采集 → Silero VAD 切句 → OpenAI 兼容 **ASR stream** → **LLM stream** 翻译 → 双语实时 UI / 悬浮字幕 → Room 历史 + 整场 WAV 存档。

适合上课听讲做笔记：自备 ASR / LLM API（不硬编码密钥）。

## 功能概览

| 能力 | 说明 |
|------|------|
| 麦克风 / 内录 / 文件 | 麦克风；内部音频（API 29+）；**本地文件**走录音管线（FFmpeg → Silero VAD → 带时间轴 offset 的 ASR/LLM） |
| 切句 | Silero DNN VAD：静音切句 + `maxUtteranceMs` 强制截断 |
| ASR / LLM | 流式 SSE；多鉴权；完整 URL 或 OpenAI 路径拼接；可选 `thinking` 字段（写在 messages 后）；响应剥离 thinking 内容 |
| 只识别 | 设置开启后跳过 LLM，仅 ASR |
| 管线 | ASR 与 LLM 分队列：下句 ASR 可与上句翻译并行，句序 FIFO |
| 主页展示 | **逐句双语对照**、居中；可调字号；**沉浸模式**仅显双语 |
| 字幕 | 内容：Both / SourceOnly / TranslationOnly；布局：整句居中 / 单行滚动；各区块可还原默认 |
| 会话 | Pause / Resume 同会话；Stop 落库；≥N 句可 LLM 生成标题 |
| 历史 | 搜索、导出、点句跳转 + scrub；**事后重跑** ASR/翻译（标点切句，`Re` 会话）；失败项**软跳过仍保存** |
| 术语表 | `{{glossary}}` 注入 system prompt |
| 恢复 | 冷启动 orphan WAV：事后识别 / 丢弃 / 稍后 |
| 缓存 / 保活 | 会话翻译缓存；FGS + WakeLock / WifiLock + 电池引导 |

## 快速开始

1. Android Studio 打开本目录，或：
   ```bat
   gradlew.bat :app:assembleDebug
   ```
2. **Settings** 配置 ASR / LLM（URL、Key、Model、鉴权）；各区块有 ℹ 说明与「还原默认」（**不清空 API Key**）。
3. 主界面选音源 → **Start**；可选悬浮字幕、沉浸模式。

### 构建产物

```bat
gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

| 变体 | APK |
|------|-----|
| Debug · arm64 | `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk` |
| Debug · x86 | `app/build/outputs/apk/debug/app-x86-debug.apk` |
| Release · arm64 / x86 | `app/build/outputs/apk/release/…` |

- **minSdk** 26 · **targetSdk** 34 · 包名 `com.example.livetranslate`
- ABI 分包（`arm64-v8a` / `x86`）；含 FFmpeg 时体积较大
- Release 默认 debug keystore，仅侧载

## 架构

```
ui/          Compose + ViewModel（Live / History / Settings）
domain/      SessionOrchestrator（实时 + 文件 VAD 管线）
             OfflineReprocessPipeline（历史/orphan 事后重跑，软失败兜底）
data/
  audio/     AudioCapture · SileroSpeechClassifier · EnergyVad · FFmpeg · FileAudioSegmenter · SessionAudioRecorder
  asr/       OpenAI transcriptions / chat-audio 流式
  llm/       Chat completions 流式翻译 + 标题；thinking 过滤
  settings/  DataStore · UserSettings · OverlayTextMode / OverlayLayoutMode
  history/   Room
service/     RecordingService · SubtitleOverlayService
```

## 默认 VAD（可在设置中改）

| 参数 | 默认 | 归属 |
|------|------|------|
| `silenceMs` | 300 | Silero `silenceDuration`（末段挂起） |
| `maxUtteranceMs` | 4500 | 应用层强制截断 |
| `minUtteranceMs` | 1700 | 应用层：静音切过短则**并入下一句**（Stop/最长仍刷出） |
| `sileroVadMode` | NORMAL | Silero Mode |
| 帧长 | 512 @ 16 kHz | Silero 固定（~32 ms） |
| speechDuration | 50 ms | Silero 推荐，写死防毛刺 |

## 权限

| 权限 | 用途 |
|------|------|
| `RECORD_AUDIO` | 麦克风 / 内录 |
| `FOREGROUND_SERVICE` + microphone / mediaProjection | 后台采集 |
| `POST_NOTIFICATIONS` | 录音通知（API 33+） |
| `SYSTEM_ALERT_WINDOW` | 悬浮字幕 |

## 弱网 / 重跑兜底

| 机制 | 行为 |
|------|------|
| HTTP 超时 | connect 20s · read 空闲 45s · call 120s |
| 自动重试 | 可配置次数（默认 3），指数退避 |
| 有界队列 + 磁盘溢出 | 实时句不轻易丢 |
| ASR 优先 | 原文先落库；译失败可只重试 LLM |
| 事后重跑 | ASR/译失败**软跳过**；有有效原文则仍保存；Toast 提示跳过数量 |
| 标点切句空 | 整段原文作一句兜底 |

## 命名约定（代码 / 资源）

| 类别 | 约定 |
|------|------|
| 包 | `com.example.livetranslate` |
| 设置键 / 字段 | camelCase（`overlayTextMode`, `asrOnlyMode`） |
| 枚举 | PascalCase（`OverlayTextMode.Both`, `OverlayLayoutMode.FullSentence`） |
| 字符串资源 | `settings_*` / `offline_*` / `overlay_*` 等前缀；**UI 文案进 `values` + `values-zh`** |
| 文档 | `docs/superpowers/specs|plans/` 历史设计；以代码 `UserSettings` 默认为准 |

## 已知限制

- 近实时取决于 VAD + ASR/LLM 延迟
- 内录依赖厂商 MediaProjection
- Room `fallbackToDestructiveMigration` 可能清空历史
- API Key 明文 DataStore；允许 cleartext 以便本地网关
- 文件导入依赖 FFmpeg native，APK 体积大

## 文档

- 设计：`docs/superpowers/specs/`
- 实现计划：`docs/superpowers/plans/`
- 默认决策快照：`docs/DEFERRED_DECISIONS.md`（数值以 `UserSettings` 为准）
