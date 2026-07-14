# 近实时英文同声传译 Android App — Streaming MVP 设计文档

**日期**: 2026-07-14  
**状态**: 待用户审阅  
**范围**: 能跑通的 streaming MVP（分块录音 + 能量 VAD 切句 + ASR stream + LLM stream + 实时 UI + 历史）

---

## 1. 目标与成功标准

### 1.1 产品目标

开发 Android App，实现**近实时英文同声传译**（语音 → 中文翻译），用于上课听讲做笔记。用户打开麦克风后，能比较流畅地看到实时翻译结果。

### 1.2 核心工作流（必须严格遵循）

1. 用户点击「开始」后，App 通过麦克风持续采集音频。
2. 能量 VAD 将语音切成完整句（静音结束或最长时长强制截断）。
3. 把该句音频发送给 **OpenAI 兼容的 ASR API**，**必须 `stream=true`**，实时获取英文转录增量。
4. 把 ASR 文本（完整句）发送给 **OpenAI 兼容的 LLM API**，**必须 `stream=true`**，并带滑动窗口上下文，实时获取中文翻译增量。
5. 双语结果**实时累积更新**到界面。
6. 用户点击「停止」后，保存本次完整会话的翻译记录。

### 1.3 验收标准

1. 开始后稳定采集；**静音切句**与 **`maxUtteranceMs` 强制截断**均可触发 ASR。
2. ASR / LLM 均为 streaming；当前句 UI 呈打字机式增量更新（不等待完整响应再显示）。
3. 暂停 / 停止行为符合本设计；停止后历史可查看、可导出。
4. API Key / Base URL 全部由用户在设置中配置，不硬编码。
5. 单句失败可重试，不破坏已完成累积文本；界面无明显卡顿或乱序闪烁。

### 1.4 明确不在 MVP 范围

- 说话过程中的 partial ASR / 半成品展示
- TTS 播放译文、双向同传
- ML-VAD / WebRTC VAD / 说话人分离
- 云端账号、多设备同步
- Hilt 等重型 DI（MVP 用手动 `AppContainer`）

---

## 2. 已确认决策

| 项 | 决策 |
|----|------|
| 切句策略 | 能量阈值 VAD；静音 ≥ `silenceMs` 切句 |
| 长语音 | **采样过长强制截断**：持续 speaking ≥ `maxUtteranceMs` 强制产出 utterance |
| 展示策略 | **仅完整句**：句末 → ASR stream → LLM stream |
| 翻译上下文 | **滑动窗口**：最近 N 句原文/译文 + 当前句 |
| 整体架构 | **轻量分层 + 单句串行队列** |
| VAD 实现 | 能量阈值（RMS），参数可配置 |
| 网络 | OkHttp 读 SSE body，边解析边 emit |
| 配置存储 | DataStore |
| 历史存储 | Room |
| minSdk | 26；targetSdk 34 或 35 |

---

## 3. 整体架构

### 3.1 分层

```
ui/          Compose + ViewModel（StateFlow）
domain/      模型 + SessionOrchestrator（串行编排）
data/
  audio/     AudioRecord + 分块 + EnergyVad + WavEncoder
  asr/       OpenAI 兼容 ASR streaming
  llm/       OpenAI 兼容 LLM streaming
  network/   共用 SSE 解析
  settings/  DataStore
  history/   Room
```

### 3.2 运行时数据流

```
[开始]
  → AudioRecord 持续 PCM（16kHz / mono / 16-bit）
  → 按 frame（约 20–40ms）计算 RMS
  → EnergyVad：
       · RMS ≥ threshold → speaking，写入当前缓冲
       · speaking 且静音 ≥ silenceMs → 正常切句
       · speaking 且时长 ≥ maxUtteranceMs → 强制截断切句
       · 缓冲 < minUtteranceMs → 丢弃
  → UtteranceQueue（串行，一次一句）
       → AsrClient.transcribeStream(pcm→wav)  // stream=true
       → 更新 partialEn
       → LlmClient.translateStream(en + window) // stream=true
       → 更新 partialZh
       → 句完成：并入 cumulative，推进 context window
[暂停] 停采集；进行中请求可跑完；不清空会话文本
[停止] 停采集；cancel 进行中请求（或可选 flush 尾段）；Room 落库；重置运行时
```

### 3.3 为何串行队列

- 避免多句 ASR/LLM 交叉导致 UI 乱序、上下文窗口错乱。
- 课堂语速下串行足够；**稳定性优先于吞吐**。
- 失败按「当前句」重试，不影响已完成句。

---

## 4. 核心模块设计

### 4.1 音频采集与能量 VAD（`data/audio`）

#### 职责

- `AudioRecord` 稳定分块采集 PCM。
- Frame 级 RMS 能量检测。
- 按规则产出完整句 `UtteranceAudio`。

#### 切句规则

| 条件 | 行为 |
|------|------|
| RMS ≥ `energyThreshold` | 进入/保持 speaking，PCM 写入当前缓冲 |
| speaking 且静音持续 ≥ `silenceMs` | **正常切句**，`CutReason.Silence` |
| speaking 且缓冲时长 ≥ `maxUtteranceMs` | **强制截断**，`CutReason.MaxDuration`；下一 frame 开新缓冲 |
| 缓冲时长 &lt; `minUtteranceMs` | 丢弃，不送 ASR |
| 暂停 | 停止写入；不强制 flush（避免半句噪音） |
| 停止 | 停止写入；可选对尾段若 ≥ min 则 flush（`CutReason.StopFlush`），否则丢弃 |

#### 默认参数（可配置）

| 参数 | 默认建议 | 说明 |
|------|----------|------|
| sampleRate | 16000 | 与常见 ASR 兼容 |
| frameMs | 20–40 | 能量计算粒度 |
| silenceMs | 300 | 静音判句末（更积极切句） |
| maxUtteranceMs | 6000 | 强制截断（更积极） |
| minUtteranceMs | 300 | 过滤误触发 |
| energyThreshold | 可标定 | 设置页滑条或高级项 |

#### 接口

```kotlin
interface AudioCapture {
    fun start()
    fun pause()
    fun stop()
    val utterances: Flow<UtteranceAudio>
}

data class UtteranceAudio(
    val pcm: ByteArray,
    val sampleRate: Int,
    val reason: CutReason
)

enum class CutReason { Silence, MaxDuration, StopFlush }
```

#### 实现要点

- 录音循环在单后台线程 / `Dispatchers.IO` + 专用 scope，避免阻塞主线程。
- PCM 缓冲用 `ByteArrayOutputStream` 或可复用缓冲，注意强制截断时的内存上限（与 maxUtteranceMs 绑定）。
- `WavEncoder`：给 PCM 加 44 字节 WAV 头再上传，兼容 OpenAI 兼容 multipart 接口。

### 4.2 ASR Streaming（`data/asr`）

#### 协议

- `POST {baseUrl}/v1/audio/transcriptions`
- `multipart/form-data`：`file`（wav）、`model`、`language`、`stream=true`
- 响应：SSE（`data: {...}`）

#### 接口

```kotlin
interface AsrClient {
    fun transcribeStream(
        audio: UtteranceAudio,
        config: AsrConfig
    ): Flow<AsrStreamEvent>
}

sealed class AsrStreamEvent {
    data class Delta(val text: String) : AsrStreamEvent()
    data class Completed(val fullText: String) : AsrStreamEvent()
    data class Error(val throwable: Throwable, val retryable: Boolean) : AsrStreamEvent()
}

data class AsrConfig(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val language: String // 默认 en
)
```

#### Streaming 处理

- OkHttp 执行请求，对 `ResponseBody.source()` **按行**读取。
- 识别 `data:` 前缀，跳过空行与 `[DONE]`。
- JSON 中兼容多种增量字段（实现时探测）：如 `text`、`delta`、嵌套字段；统一对外为 `Delta`。
- **两种 provider 形态需自适应**：
  - **Append 型**：每个 chunk 是新片段 → UI append。
  - **Snapshot 型**：每个 chunk 是到目前为止的全文 → UI replace `partialEn`。
- 自动探测：若新 text 以旧 partial 为前缀，视为 snapshot；否则 append。
- 禁止缓冲完整 body 再解析。

### 4.3 LLM Streaming（`data/llm`）

#### 协议

- `POST {baseUrl}/v1/chat/completions`，body JSON，`stream=true`
- SSE：`choices[0].delta.content` **仅 append**

#### 滑动窗口上下文

```kotlin
data class ContextTurn(val source: String, val translation: String)

// 默认 N = 4
// System：你是同声传译助手，将用户输入的英文译为中文；只输出译文，不要解释。
// 历史：成对提供最近 N 句 source/translation，仅作术语与指代参考
// User：当前待译英文句
// 约束：不要重译历史，只译当前句
```

#### 接口

```kotlin
interface LlmClient {
    fun translateStream(
        sourceText: String,
        context: List<ContextTurn>,
        config: LlmConfig
    ): Flow<LlmStreamEvent>
}

sealed class LlmStreamEvent {
    data class Delta(val text: String) : LlmStreamEvent()
    data class Completed(val fullText: String) : LlmStreamEvent()
    data class Error(val throwable: Throwable, val retryable: Boolean) : LlmStreamEvent()
}

data class LlmConfig(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val targetLanguage: String // 默认 Chinese / zh
)
```

### 4.4 会话编排（`domain/SessionOrchestrator`）

串行处理队列中的每个 `UtteranceAudio`：

1. UI phase → Processing（可同时 Recording）。
2. `collect` ASR stream → 更新 `partialEn`。
3. ASR 完成后若文本非空 → `collect` LLM stream → 更新 `partialZh`。
4. 句完成：`partial` 并入 `cumulative`，追加 `ContextTurn`，裁剪窗口至 N。
5. 失败：标记当前句错误，支持重试；队列可继续（重试策略见 §5）。

**暂停**：停止 `AudioCapture`；不取消已在跑的当前句（可选，MVP 建议跑完更简单）。  
**停止**：停止采集；`cancel` 编排 Job / OkHttp Call；已完成 segments 写入 Room。

### 4.5 实时 UI 状态

```kotlin
data class LiveSessionUiState(
    val phase: Phase, // Idle | Recording | Paused | Processing
    val cumulativeEn: String,
    val cumulativeZh: String,
    val partialEn: String,
    val partialZh: String,
    val lastCutReason: CutReason?,
    val error: String?
)
```

#### UI 更新原则

- 仅 `StateFlow` → `collectAsStateWithLifecycle` 驱动重组。
- 流式中只改 `partial*`；完成后并入 `cumulative*` 并清空 partial。
- 稳定布局：上下分区（上英文、下中文）或同列双语；避免列表 key 抖动。
- 自动滚到底部；重组时避免重置滚动位置（记住用户是否在底部）。

主界面控件：开始 / 暂停 / 停止；可选显示「强制截断」等轻量状态指示。

### 4.6 设置（`data/settings`）

DataStore 字段：

- ASR：`baseUrl`、`apiKey`、`model`
- LLM：`baseUrl`、`apiKey`、`model`
- 语言：`inputLanguage`（默认 English/en）、`outputLanguage`（默认 Chinese/zh）
- VAD：`silenceMs`、`maxUtteranceMs`、`minUtteranceMs`、`energyThreshold`
- 上下文：`contextWindowSize`（默认 4）

API Key **仅存本机 DataStore**，不写死在源码，不进版本库。

### 4.7 历史（`data/history`）

- `Session`：id、createdAt、endedAt、title/preview
- `Segment`：sessionId、sourceText、translationText、createdAt、cutReason 可选
- 列表、详情、导出：`Intent.ACTION_SEND` 文本（Markdown 逐句对照）

---

## 5. 错误处理与重试

| 场景 | 策略 |
|------|------|
| 麦克风权限拒绝 | 引导开启权限；不启动录音 |
| AudioRecord 初始化失败 | 错误文案 + 可再次点开始 |
| 网络超时 / 5xx | 当前句指数退避最多 2 次；仍失败则标记该句，不回滚全文 |
| 401 / 错误 Key | **不重试**；提示检查设置 |
| ASR 空文本 | 跳过 LLM；不写 segment |
| LLM 流中断 | 保留已有 partialZh，可标「[不完整]」；支持单句重试 |
| 强制截断后仍在说话 | 立即开新缓冲，音频不丢（新句继续录） |
| 停止时有进行中请求 | cancel Call；已完成句落库 |

**原则**：失败粒度 = 当前句；已展示累积文本不回滚。

---

## 6. 目录结构

```
app/src/main/java/com/example/livetranslate/
├── MainActivity.kt
├── LiveTranslateApp.kt
├── di/
│   └── AppContainer.kt
├── ui/
│   ├── theme/
│   ├── navigation/AppNav.kt
│   ├── live/
│   │   ├── LiveTranslateScreen.kt
│   │   └── LiveTranslateViewModel.kt
│   ├── settings/
│   │   ├── SettingsScreen.kt
│   │   └── SettingsViewModel.kt
│   └── history/
│       ├── HistoryScreen.kt
│       ├── HistoryDetailScreen.kt
│       └── HistoryViewModel.kt
├── domain/
│   ├── model/
│   │   ├── Utterance.kt
│   │   ├── Session.kt
│   │   └── StreamEvents.kt
│   └── SessionOrchestrator.kt
└── data/
    ├── audio/
    │   ├── AudioCapture.kt
    │   ├── PcmChunker.kt
    │   ├── EnergyVad.kt
    │   └── WavEncoder.kt
    ├── asr/
    │   ├── AsrClient.kt
    │   └── AsrConfig.kt
    ├── llm/
    │   ├── LlmClient.kt
    │   └── LlmConfig.kt
    ├── network/
    │   └── SseReader.kt
    ├── settings/
    │   ├── UserSettings.kt
    │   └── SettingsRepository.kt
    └── history/
        ├── SessionEntity.kt
        ├── SegmentEntity.kt
        ├── AppDatabase.kt
        └── HistoryRepository.kt
```

包名 `com.example.livetranslate` 可在实现时按发布需求调整。

---

## 7. 技术栈

| 项 | 选择 |
|----|------|
| 语言 / UI | Kotlin, Jetpack Compose, Material 3 |
| 异步 | Coroutines + Flow |
| 网络 | OkHttp + SSE 行解析；JSON 用 Kotlinx Serialization 或等价 |
| 配置 | DataStore Preferences |
| 历史 | Room |
| 架构组件 | ViewModel, Navigation Compose, lifecycle-runtime-compose |
| DI | 手动 `AppContainer` |
| 权限 | `RECORD_AUDIO` |

---

## 8. Streaming 增量数据约定（实现必读）

| 环节 | 增量语义 | UI |
|------|----------|-----|
| ASR SSE | Append 或 Snapshot，客户端自动探测 | `partialEn` 打字机 |
| LLM SSE | `delta.content` 只 append | `partialZh` 打字机 |
| 句完成 | `Completed` 与累积对齐 | 并入 cumulative，清空 partial |

### SSE 解析注意

- 忽略空行与 `data: [DONE]`。
- 单行 JSON 失败：记日志并跳过该 chunk，不中断整条流。
- 取消：Flow 取消时 `Call.cancel()`。
- 主线程：解析与 IO 在后台；只把字符串状态 post 到 Main 更新 StateFlow。

### 降低卡顿

- StateFlow 更新可对极高频 ASR delta 做轻量合并（如 32–50ms 节流），但 **不得** 等整句结束再发第一次更新。
- Compose `Text` 显示完整字符串即可，避免每个字符独立 Composable。

---

## 9. 实现优先级（MVP）

1. 工程骨架 + 导航（Live / Settings / History）
2. Settings DataStore + 权限
3. AudioCapture + EnergyVad（含 max 截断）+ WavEncoder
4. SseReader + AsrClient streaming
5. LlmClient streaming + 上下文窗口
6. SessionOrchestrator 串行串联 + Live UI
7. Room 历史 + 导出
8. 错误提示与当前句重试

---

## 10. 风险与后续迭代

| 风险 | 缓解 |
|------|------|
| 教室嘈杂导致误切句 | 可调 threshold / silenceMs；后续可换 WebRTC/ML VAD |
| 强制截断切断单词 | MVP 可接受；后续重叠窗口或与下一句拼接 |
| 不同厂商 ASR SSE 字段不一致 | 多字段兼容 + append/snapshot 探测 |
| Base URL 尾斜杠 / 路径差异 | 规范化 `baseUrl`（去尾 `/`，固定拼 `/v1/...`） |
| 厂商不支持 transcription stream | 文档说明；可降级为非 stream 但仍用 LLM stream（非 MVP 默认路径） |

后续迭代方向：说话中 partial、更好 VAD、术语表、蓝牙麦、更低延迟的并行预取（在乱序可控前提下）。

---

## 11. 变更记录

| 日期 | 说明 |
|------|------|
| 2026-07-14 | 初稿：VAD 分句、仅完整句、滑动窗口、能量 VAD、轻量串行架构；用户要求增加最长采样强制截断 |
