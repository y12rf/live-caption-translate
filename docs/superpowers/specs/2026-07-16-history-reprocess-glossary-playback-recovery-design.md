# 历史重跑 · 术语表 · 点句播放 · 杀后台恢复

**日期**: 2026-07-16  
**状态**: 已批准（待实现）  
**架构**: 扩展现有编排层（方案 A）

## 1. 背景

Live Translate 已支持：

- 会话整场 WAV（`files/recordings/session_*.wav`）与 Room 历史（`offsetMs` 分段）
- 文件导入：FFmpeg → 能量 VAD → ASR/LLM 双队列
- 可配置 LLM system prompt（`{{to}}` / `{{from}}`）
- 历史导出 / 分享录音；无应用内播放、无术语表、无事后重跑、无进程被杀后的恢复提示

本规格补齐四项能力，并复用现有 `AsrClient` / `LlmClient` / `HistoryRepository` / 设置层。

## 2. 目标

| # | 能力 | 成功标准 |
|---|------|----------|
| 1 | 历史整场 WAV 事后重跑 ASR/翻译 | 有 WAV 的会话可触发；产出**新**会话，共享同一 `audioPath`，标题 `Re`+原标题；文稿由标点切句，**无时间轴**（`offsetMs = 0`） |
| 2 | 术语表注入 system prompt | 设置页维护源→译列表；`{{glossary}}` 渲染进 LLM system prompt；实时与重跑均生效 |
| 3 | 点句跳录音 + 时间轴 scrub | 仅当会话存在有效 `offsetMs` 且 WAV 可读：底部播放条 + 点句 seek + 当前句高亮 |
| 4 | 杀后台恢复提示 | 冷启动检测未入库 orphan WAV；对话框：事后识别 / 丢弃 / 稍后 |

## 3. 非目标

- 覆盖原会话文稿
- 重跑会话的点句跳转 / 伪时间轴估算
- 整文件一次 ASR 且永不切块（超长音频按固定时长分块）
- 会话级术语覆盖、云端托管、WorkManager 独立重跑服务
- 修改 Room 使用 `fallbackToDestructiveMigration` 的策略（本特性尽量不改 sessions/segments schema；若仅需字段则可加，但默认不强制 migration）

## 4. 架构总览

```
Settings (glossary + prompt)
        │
        ▼
UserSettings.renderLlmSystemPrompt()  ← {{to}} {{from}} {{glossary}}
        │
        ├──────────────────► SessionOrchestrator（实时 / 文件导入）
        │
        └──────────────────► OfflineReprocessPipeline（历史重跑 / orphan 恢复）
                                    │
                    WavPcmReader → 分块 ASR → PunctuationSegmenter
                                    → 逐句 LLM → HistoryRepository.saveSession
                                    （audioPath 复用，title = "Re" + base）

HistoryDetail UI
  ├── SessionAudioPlayer（MediaPlayer：play/pause/seek/scrub）
  └── 点句 seek（仅 hasTimeline）

MainActivity / Live 冷启动
  └── OrphanRecordingDetector → 恢复对话框 → 同上 Pipeline 或删除文件
```

原则：

- **不**把重跑强行塞进 `startFromFile`（VAD 路径）；独立 pipeline，翻译侧复用 LLM 配置与上下文窗口逻辑。
- 播放仅在 History 详情生命周期内；离开详情 release player。
- Orphan 与现有 `CacheCleaner.clearOrphanRecordings` 语义对齐（路径不在任何 `session.audioPath`）。

## 5. 功能设计

### 5.1 术语表 + `{{glossary}}`

**数据**

```kotlin
data class GlossaryEntry(
    val source: String,
    val target: String
)

// UserSettings 新增
val glossaryTerms: List<GlossaryEntry> = emptyList()
```

- DataStore：JSON 数组字符串键 `glossary_terms`（纯 Kotlin 序列化/反序列化即可，避免新依赖）。
- 条目：两侧 trim；空 `source` 不持久化；条数建议软上限 100（UI 提示，防 prompt 爆炸）。

**渲染**

```kotlin
fun renderLlmSystemPrompt(): String {
    val glossaryBlock = formatGlossary(glossaryTerms) // 每行 "source → target"，空则 ""
    return llmSystemPrompt
        .replace("{{to}}", outputLanguage)
        .replace("{{from}}", inputLanguage)
        .replace("{{glossary}}", glossaryBlock)
}
```

- 默认 system prompt 文案**追加**术语说明段，例如：  
  `术语表（须优先遵守，可为空）：\n{{glossary}}`  
  用户可删掉 `{{glossary}}`；不强制迁移已保存的自定义 prompt（已有自定义 prompt 无占位符则术语不注入——设置页 helper 文案说明「加入 {{glossary}} 以启用」）。
- **决策（明确）**：若用户自定义 prompt 不含 `{{glossary}}`，不自动拼接术语，避免破坏用户全文；Settings 显示开关文案 +「插入 {{glossary}}」快捷按钮。

**UI（Settings）**

- 区块「术语表」：Lazy 列表 + 源/译两个输入 + 添加/删除。
- System prompt 编辑区 helper：`{{to}}` `{{from}}` `{{glossary}}`。

### 5.2 事后重跑 `OfflineReprocessPipeline`

**入口**

1. 历史详情菜单：「重新识别翻译」（需 WAV 存在）。
2. Orphan 恢复对话框：「事后识别」。

**流程**

1. 校验 Idle（无进行中的直播会话与重跑）、网络、ASR/LLM Key。
2. 读取 WAV（`WavPcmReader`）；按时长分块，默认 **30_000 ms** PCM（常量可调）。
3. 每块编码为临时短 WAV（或复用现有 multipart 字节路径）→ `AsrClient` 识别；拼接全文（块间空格/换行规范化）。
4. `PunctuationSegmenter.split(text)` → 句子列表。
5. 顺序调用 `LlmClient` 翻译（context window = settings.contextWindowSize；system prompt = `renderLlmSystemPrompt()`）。
6. **全部成功**后 `saveSession`：
   - `audioPath` = 源会话路径（orphan 则为该文件绝对路径）
   - `title` = `"Re" + baseTitle`，其中 `baseTitle` = 原 `previewZh`（trim）；若 blank → `"未命名会话"`；orphan → `"未保存录音"` 或文件名时间戳派生，最终仍加 `Re` 前缀（即 `Re未保存录音`）
   - 每句 `offsetMs = 0`，`incomplete = false`（单句译失败见错误策略）
7. 取消：协作取消；**不写库**；清理临时块文件。

**进度状态（StateFlow，History 或共享 ReprocessState）**

```text
phase: Idle | Running | Cancelling
asrChunkIndex / asrChunkTotal
translateIndex / translateTotal
message: 人类可读一行
error: 可空
```

UI：详情顶栏或 Modal 进度；禁止同一 `audioPath` 并行第二次重跑。

**错误策略（明确）**

- 自动重试：与现有 ASR/LLM 次数/退避对齐（最多 3 次类策略，复用或抽取公共 retry helper）。
- **任一块 ASR 或任一句翻译在耗尽重试后仍失败 → 整次失败，不入库**；展示错误，用户可再试。
- 无网络 / 无 Key：启动前失败，不进入 Running。

**标点切句 `PunctuationSegmenter`**

- 句末/切分信号：`。！？；…` 与 `.!?;` 以及换行 `\n`。
- 规则：
  - 在切分符**之后**断开（标点保留在前句末尾）。
  - 英文小数：`\d\.\d` 不切（简单 lookbehind/lookahead）。
  - 连续标点合并到同一句尾。
  - trim 后长度 < 2 的片段丢弃（可测常量）。
  - 全文无任何切分符：整段作为一句。
  - 标题每次重跑固定前缀 `Re`，不剥已有 `Re`（对 `Re…` 再跑会得到 `ReRe…`，可接受）。

**与 `SessionOrchestrator` 关系**

- 重跑**不**占用麦克风 FGS；若直播 `Recording`/`Processing` 中，禁用重跑入口并提示「请先结束当前会话」。
- 翻译实现可抽取小型 `TranslationRunner`（window + cache 可选），避免复制粘贴；MVP 允许 pipeline 内直接调 `LlmClient` + 本地 window 列表。

### 5.3 点句跳转 + scrub

**启用条件 `hasTimeline`**

```text
audioFile exists AND segments.any { offsetMs > 0 }
```

重跑会话（全 0）→ 仅文稿列表，无播放条（或仅「分享录音」保留）。

**`SessionAudioPlayer`**

- 封装 `MediaPlayer` 或 `android.media.MediaPlayer` setDataSource(path)。
- API：`prepare(file)` / `play()` / `pause()` / `seekTo(ms)` / `positionMs` / `durationMs` / `release()`。
- Compose：底部 `Slider` + 时间标签 + 播放/暂停；定时刷新 position（200–500ms）。
- 点击 segment：`seekTo(seg.offsetMs)` 并 play；高亮「当前时间 ∈ [offset_i, offset_{i+1})」的句子（末句到 duration）。
- 离开 `HistoryDetailScreen`：`DisposableEffect` release。

### 5.4 杀后台恢复（orphan）

**检测 `OrphanRecordingDetector`**

```text
files/recordings/*.wav
  minus paths referenced by SessionDao.allAudioPaths()
  = orphans
```

- 与 `CacheCleaner` 共用列举逻辑，避免两套规则。
- 排序：按 `lastModified` 降序；对话框可只处理**最新一个**，其余在「清理缓存」或列表中二次提示（MVP：**每次弹窗处理最新 orphan 一条**；丢弃后若还有，下次 resume 再提示）。

**触发**

- `MainActivity` / Live 路由 `LaunchedEffect` 或 `Lifecycle.ON_RESUME`：`detector.findOrphans()` 非空且非 skip → 对话框。

**对话框**

| 操作 | 行为 |
|------|------|
| 事后识别 | 关闭对话框 → 走 `OfflineReprocessPipeline`（无原标题 → `Re未保存录音`）→ 成功后可导航到新 session 详情 |
| 丢弃 | 删除该 WAV 文件 |
| 稍后 | 本进程内 session skip 集合记录 path；**下次冷启动仍提示**（符合「稍后」非永久忽略） |

**进程被杀场景说明**

- 直播中杀进程：可能已有部分 PCM 的 WAV（header 未 finish 时文件可能损坏）。
- 检测：打开失败 / dataSize=0 → 提示损坏并建议丢弃，不进入 ASR。
- 可选增强（非 MVP 必须）：`SessionAudioRecorder` 周期性 patch header，提高可恢复性——**本规格列为建议 follow-up**，不阻塞四项主功能。

## 6. UI 改动摘要

| 界面 | 改动 |
|------|------|
| Settings | 术语表列表；prompt helper / 插入 `{{glossary}}` |
| History detail | 菜单「重新识别翻译」；进度 UI；条件播放条；可点击 segment |
| Live / Main | orphan 恢复对话框；重跑进行中全局禁用 Start 或提示 |

## 7. 模块与文件（预期）

| 模块 | 路径（预期） |
|------|----------------|
| Glossary 模型 + 序列化 | `data/settings/GlossaryEntry.kt` 或并入 `UserSettings.kt` |
| Settings 读写 | `SettingsRepository.kt`、`SettingsScreen.kt` |
| 标点切句 | `domain/PunctuationSegmenter.kt` + 单测 |
| 分块 ASR 辅助 | `data/audio/WavChunker.kt` 或 pipeline 内私有 |
| 重跑管线 | `domain/OfflineReprocessPipeline.kt` |
| Orphan | `data/OrphanRecordingDetector.kt`（或扩展 `CacheCleaner`） |
| 播放器 | `data/audio/SessionAudioPlayer.kt` |
| History UI/VM | `HistoryScreen.kt`、`HistoryViewModel.kt` |
| DI | `AppContainer.kt` |
| 字符串 | `res/values/strings.xml` |

## 8. 测试计划

| 用例 | 类型 |
|------|------|
| 中英混排、无标点、连续标点、小数不切 | 单元 `PunctuationSegmenter` |
| `{{glossary}}` 空/非空；无占位符不注入 | 单元 `UserSettings` |
| orphan：库内路径 vs 孤立文件 | 单元（临时目录） |
| 标题 `Re` + blank fallback | 单元 helper |
| 分块字节连续、无重叠无空洞 | 单元 `WavChunker` |
| 回归：现有文件导入 / 导出 / 直播 | 现有单测 + 手工 |

## 9. 风险与缓解

| 风险 | 缓解 |
|------|------|
| 长音频 ASR 费用/耗时 | 30s 分块 + 进度 + 可取消 |
| 标点切句与口语无标点 | 整段一句；用户可改 VAD 参数再走文件导入（非本路径） |
| 双会话共享 WAV，清理一端误删 | 删除会话若将来支持：仅当无其他 session 引用该 path 才删文件（当前无删单条会话则暂无问题；`CacheCleaner` 已按 path 集合判断） |
| MediaPlayer 与 Compose 生命周期 | `DisposableEffect` + 单一 player 实例 |
| 自定义 prompt 无 `{{glossary}}` | UI 明确说明 + 一键插入 |

## 10. 实现优先级

1. 术语表 DataStore + `renderLlmSystemPrompt` + Settings UI + 单测  
2. `PunctuationSegmenter` + 单测  
3. `OfflineReprocessPipeline` + History 入口 + 进度  
4. `SessionAudioPlayer` + 点句 / scrub  
5. `OrphanRecordingDetector` + 冷启动对话框串重跑  
6. 文案 / README 简短更新（可选）

## 11. 变更记录

| 日期 | 说明 |
|------|------|
| 2026-07-16 | 初稿：方案 A；新会话 Re 标题；标点切句无时间轴；全局术语 `{{glossary}}`；仅有 timeline 可播放；orphan 冷启动恢复 |
