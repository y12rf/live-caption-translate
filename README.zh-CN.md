# Live Translate（实时翻译）

**v1.1.0** · Kotlin + Jetpack Compose · MIT

[English](README.md) · **中文**

[![CI & Release](https://github.com/y12rf/live-caption-translate/actions/workflows/ci-release.yml/badge.svg)](https://github.com/y12rf/live-caption-translate/actions/workflows/ci-release.yml)
[![GitHub release](https://img.shields.io/github/v/release/y12rf/live-caption-translate)](https://github.com/y12rf/live-caption-translate/releases)

近实时语音 → OpenAI 兼容 **ASR 流式识别** → **LLM 流式翻译** → 双语界面 / 悬浮字幕 → Room 历史 + 会话录音 WAV。

自备 ASR / LLM API Key。

适合上课听讲做笔记：填好自己的接口即可用。

---

## 功能

| 能力 | 说明 |
|------|------|
| 麦克风 / 内录 / 文件 | 麦克风；内录（API 29+）；**本地文件**经 FFmpeg → Silero VAD → 时间轴偏移 → ASR/LLM |
| VAD | Silero DNN：静音切句 + `maxUtteranceMs` 强制切句；过短静音合并进下一句 |
| ASR / LLM | SSE 流式；Bearer / ApiKey 鉴权头；完整 URL 或 OpenAI 路径拼接；可选 `thinking` 字段 |
| 仅识别 | 跳过 LLM，只做语音识别 |
| 管线 | ASR / LLM **双队列**（上一句还在翻译时，下一句可先跑 ASR） |
| 主页列表 | 始终 **整句** 双语对照 + 流式 partial |
| 悬浮字幕 | 双语 / 仅原文 / 仅译文；整句或 ScrollLine 跑马灯；**3 秒无新内容自动清空**；透明度 0–100 |
| 会话 | 暂停 / 同一会话继续；停止即保存；可选 LLM 在 N 轮后生成标题 |
| 历史 | 搜索、导出、进度跳转与 scrub；**重新识别/翻译**；失败软跳过仍可保存 |
| 术语表 | 系统提示中的 `{{glossary}}` |
| 恢复 | 冷启动发现孤儿 WAV：重跑 / 丢弃 / 稍后 |
| 保活 | 前台服务 + WakeLock / WifiLock + 电池优化提示 |

## 快速开始

**环境要求：** JDK 17、Android SDK 34、真机/模拟器 API 26+。

**预编译 APK：** 每次向 `master` 推送会跑 CI，并在 [Releases](https://github.com/y12rf/live-caption-translate/releases) 发布 arm64 / x86 安装包（debug 签名，仅适合旁加载）。

```bat
gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

1. 安装 `app/build/outputs/apk/debug/` 下的 debug APK，或从 Release 下载。
2. 打开 **设置** → 填写 ASR / LLM 的 URL、API Key、模型、鉴权方式。
3. 在 **实时** 页选择音源 → **开始**。可选悬浮字幕（通知栏锁定/解锁拖动）。顶栏全屏图标进入沉浸双语。

| 变体 | 典型路径 |
|------|----------|
| Debug · arm64 | `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk` |
| Debug · x86 | `app/build/outputs/apk/debug/app-x86-debug.apk` |

- **minSdk** 26 · **targetSdk** 34 · `applicationId` `com.example.livetranslate`
- ABI 分包：`arm64-v8a`、`x86`（无胖包 universal APK）
- Release 默认使用 debug 签名（仅适合旁加载）——上架 Play 需换成正式 keystore

> **Fork 提示：** 若要以自己的包名发布，请先在 `app/build.gradle.kts` 中修改 `namespace` / `applicationId`。

## 架构

```
ui/          Compose + ViewModel（实时 / 历史 / 设置）
domain/      SessionOrchestrator（实时 + 文件 VAD 管线）
             OfflineReprocessPipeline（历史 / 孤儿重跑）
data/
  audio/     AudioCapture · Silero · FFmpeg · 会话 WAV
  asr/       OpenAI transcriptions / chat-audio 流式
  llm/       Chat completions 流式 + 标题
  settings/  DataStore · UserSettings · 字幕枚举
  history/   Room
service/     RecordingService · SubtitleOverlayService
```

## 默认 VAD（设置项）

| 参数 | 默认 | 作用 |
|------|------|------|
| `silenceMs` | 260 | Silero 静音挂起（hangover） |
| `maxUtteranceMs` | 4500 | 应用层强制切句上限 |
| `minUtteranceMs` | 1500 | 过短静音切句并入下一句 |
| `sileroVadMode` | NORMAL | Silero 阈值模式 |
| 帧长 | 512 @ 16 kHz | 约 32 ms（Silero 固定） |

## 权限

| 权限 | 用途 |
|------|------|
| `RECORD_AUDIO` | 麦克风 / 内录采集 |
| `FOREGROUND_SERVICE` + mic / mediaProjection | 后台采集 |
| `POST_NOTIFICATIONS` | 录音通知（API 33+） |
| `SYSTEM_ALERT_WINDOW` | 悬浮字幕 |
| `WAKE_LOCK` | 长时间会话 |

## 稳定性与容错

| 机制 | 行为 |
|------|------|
| HTTP 超时 | 连接 20s · 读空闲 45s · 整次调用 120s |
| 重试 | 可配置（默认 3），指数退避 |
| 队列 + 磁盘溢出 | 实时语句先落盘排队，尽量不丢句 |
| ASR 优先 | 原文先落定，译文可稍后完成 |
| 悬浮 ScrollLine | 译文未就绪时 hold 空中文，直至 LLM 完成 / 失败戳时间戳；积压时加速追赶 |
| 空闲悬浮 | **3 秒**无新字幕内容则清空浮层文字 |
| 停止路径 | 先 `stopAndJoin` 采集，再收尾 WAV / 释放 MediaProjection |

## 已知限制

- 延迟受 VAD + 网络 ASR/LLM 共同约束
- 内录质量依赖各厂商 MediaProjection 实现
- Room 跨 schema 使用破坏性迁移（可能清空历史）
- API Key 以明文存 DataStore；为局域网网关允许 cleartext HTTP
- FFmpeg 原生库会增大 APK 体积

## 安全

见 [SECURITY.md](SECURITY.md)。请勿提交 API Key 或 keystore。

## 文档

- 第三方声明：[NOTICE](NOTICE)
- 安全说明：[SECURITY.md](SECURITY.md)
- 英文说明：[README.md](README.md)

## 参与贡献

见 [CONTRIBUTING.md](CONTRIBUTING.md)。

## 许可证

[MIT](LICENSE) — 捆绑的原生 / VAD 组件见 [NOTICE](NOTICE)。
