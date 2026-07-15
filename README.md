# Live Translate — 近实时同声传译

**v1.0.0** · Kotlin + Jetpack Compose

音频采集 → 能量 VAD 切句 → OpenAI 兼容 **ASR stream** → **LLM stream** 翻译 → 双语实时 UI / 悬浮字幕 → Room 历史 + 整场 WAV 存档。

适合上课听讲做笔记：自备 ASR / LLM API（不硬编码密钥）。

## 功能概览

| 能力 | 说明 |
|------|------|
| 麦克风 / 内录 / 文件 | 麦克风；内部音频（API 29+，MediaProjection + 多采样率回退）；**本地文件**（FFmpeg 转 16k WAV → VAD 切句后每 70 句一批 ASR + 标点切句翻译；≥10 句生成 LLM 标题） |
| 切句 | 能量 VAD：静音切句 + `maxUtteranceMs` 强制截断 |
| ASR / LLM | 流式 SSE；多鉴权风格；完整 URL 或 OpenAI 路径拼接 |
| 管线 | ASR 与 LLM 分队列：下句 ASR 可与上句翻译并行，句序仍保证 FIFO |
| 实时展示 | 主界面双语打字机；可拖动 / 锁定悬浮字幕 |
| 会话 | Pause / Resume 同会话；Stop 落库；≥10 句可 LLM 生成标题 |
| 历史 | 搜索、分段浏览、Markdown / SRT / 纯文本导出、录音分享；点句跳转 + 时间轴 scrub；整场 WAV **事后重跑** ASR/翻译（标点切句，另存 `Re` 会话） |
| 术语表 | 设置页源→译列表，经 system prompt 的 `{{glossary}}` 注入（实时与重跑） |
| 恢复 | 冷启动检测未入库 orphan WAV，提示事后识别 / 丢弃 / 稍后 |
| 缓存 | 会话内翻译缓存；孤立录音与历史清理 |
| 保活 | 前台服务通知、WakeLock / WifiLock、电池优化引导 |

## 快速开始

1. Android Studio 打开本目录，或命令行构建：
   ```bat
   gradlew.bat :app:assembleDebug
   ```
2. 安装运行 → **Settings** 配置：
   - **ASR**：Base URL / API Key / Model；风格（transcriptions / chat-audio）、鉴权（Bearer / api-key 头等）
   - **LLM**：Base URL / API Key / Model；system prompt（支持 `{{to}}` / `{{from}}`）
   - 输入语言默认 `en`，输出默认 `zh`
   - VAD：`silenceMs`、`maxUtteranceMs`、`minUtteranceMs`、`energyThreshold`
   - 悬浮窗尺寸 / 颜色 / 透明度；保活与缓存清理
3. 主界面选 **麦克风** / **内部音频** / **文件** → **Start**
   - 麦克风：`RECORD_AUDIO`（Android 13+ 另需通知权限）
   - 内部音频：系统投屏授权 + 前台服务
   - 文件：系统文件选择器（mp3/m4a/wav/…）→ FFmpeg 转码 → **事后重跑通道**（分块 ASR / 标点切句 / 翻译 / 可选 LLM 标题，无需麦克风）
   - 悬浮字幕：需「显示在其他应用上层」

### 构建产物

```bat
gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

| 变体 | APK |
|------|-----|
| Debug · 真机 arm64 | `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk` |
| Debug · 模拟器 x86 | `app/build/outputs/apk/debug/app-x86-debug.apk` |
| Release · arm64 | `app/build/outputs/apk/release/app-arm64-v8a-release.apk` |
| Release · x86 | `app/build/outputs/apk/release/app-x86-release.apk` |

```bat
gradlew.bat :app:assembleRelease
```

- **minSdk** 26 · **targetSdk** 34 · 包名 `com.example.livetranslate`
- 按 ABI 分包（`arm64-v8a` / `x86`），不打 universal；含 FFmpeg 时单包约数十 MB 而非百兆
- Release 默认用 debug keystore，仅适合侧载；上架需自备签名与正式包名

## 架构

```
ui/          Compose + ViewModel（StateFlow）
domain/      SessionOrchestrator（ASR / LLM 双队列 + 上下文窗口 + 翻译缓存）
data/
  audio/     AudioCapture + EnergyVad + WavEncoder + SessionAudioRecorder + FFmpeg 文件导入
  asr/       OpenAI 兼容 transcriptions / MIMO chat-audio 流式
  llm/       Chat completions 流式翻译 + 会话标题
  network/   SSE、URL 解析、延迟探测
  settings/  DataStore
  history/   Room（会话 / 分段 / 录音路径）
service/     RecordingService（FGS）+ SubtitleOverlayService
```

运行时数据流（简述）：

1. PCM 分帧 → VAD 产出完整句（或最长强制截断）
2. 句入 ASR 队列 → 流式英文 → 入翻译队列
3. LLM 流式译文（滑动窗口上下文；缓存命中可跳过请求）
4. UI / 悬浮窗增量更新；整场 PCM 同步写入会话 WAV
5. Stop → 可选等标题 → Room 保存文稿 + 录音路径

## 默认 VAD（可在设置中改）

| 参数 | 默认 |
|------|------|
| `silenceMs` | 500 |
| `maxUtteranceMs` | 4500 |
| `minUtteranceMs` | 1700 |
| `energyThreshold` | 400 |

嘈杂教室可能需要提高阈值或拉长静音；过短句会被 `minUtteranceMs` 丢弃。

## 权限说明

| 权限 | 用途 |
|------|------|
| `RECORD_AUDIO` | 麦克风 / 内录捕获 |
| `FOREGROUND_SERVICE` + microphone / mediaProjection | 后台持续采集 |
| `POST_NOTIFICATIONS` | 录音状态通知（API 33+） |
| `SYSTEM_ALERT_WINDOW` | 悬浮字幕 |
| `WAKE_LOCK` / 电池优化相关 | 长会话保活（可选） |

## 弱网兜底

| 机制 | 行为 |
|------|------|
| HTTP 超时 | connect 20s · read 空闲 45s（防 SSE 假死）· write 90s · call 120s |
| 自动重试 | ASR/LLM 最多 3 次，指数退避；408/429/5xx 与传输错误可重试 |
| 有界队列 | 内存 ASR/译各 12；溢出写入 `files/pending_utterances` 磁盘队列 |
| 离线 | 继续录音；worker 等待网络；恢复后磁盘泵入 + 失败列表自动重试 |
| ASR 优先落句 | 识别成功立即写入原文（incomplete）；翻译失败可**只重试 LLM** |
| 失败列表 | 多句失败可「重试 1 / 全部 / 忽略」；非仅 lastFailed |
| Partial 采纳 | 流中断时若译文已 ≥4 字则保留并标 incomplete |
| Stop | 可选「等待排空再保存」（默认，最长约 45s）或「立即结束」 |
| 保存失败 | 文稿留在内存，提供「重试保存」 |

## 已知限制

- 近实时上限取决于 VAD 切句 + ASR/LLM 延迟；非端到端语音模型
- 内部音频依赖厂商 MediaProjection 行为，ROM 差异较大
- Room 使用 `fallbackToDestructiveMigration`：DB schema 升级可能清空历史
- API Key 存 DataStore 明文；`usesCleartextTraffic` 开启以便本地网关
- 当前定位为侧载 / 自用；非正式商店 1.0 发布包
- 文件导入依赖 FFmpeg native（`ffmpeg-kit-audio`），APK 体积显著增大
- 文件模式为离线整段切句，非实时语速回放；超长文件受 API 配额与处理时间限制

## 文档

- 设计：`docs/superpowers/specs/2026-07-14-live-translate-streaming-mvp-design.md`
- 实现计划：`docs/superpowers/plans/2026-07-14-live-translate-streaming-mvp.md`
- 历史默认决策：`docs/DEFERRED_DECISIONS.md`（部分数值以代码 `UserSettings` 为准）
