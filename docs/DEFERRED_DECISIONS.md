# 默认决策快照

实现时为不阻塞进度的默认项。数值以 `UserSettings` / 设置为准；此处仅作对照。

| # | 项 | 当前默认 | 说明 |
|---|----|----------|------|
| 1 | 应用包名 | `com.example.livetranslate` | 开源可 fork 后改为域名包名 |
| 2 | targetSdk | 34（buildTools 35.0.0） | 可升 35 |
| 3 | 版本 | **1.1.0** (versionCode 4) | `app/build.gradle.kts` |
| 4 | 自动重试 | 默认 3 次（可设 1–10），退避 500ms×n² | 设置「网络重试次数」 |
| 5 | 暂停后 Start | **同一会话 Resume** | — |
| 6 | Stop flush 尾段 | 开启（≥ minUtterance） | `stopAndJoin` 后再落 WAV |
| 7 | 默认 ASR model | `whisper-1` | — |
| 8 | 默认 LLM model | `gpt-4o-mini` | — |
| 9 | maxUtteranceMs | **4500** | 设置可改 |
| 10 | silenceMs | **260** | Silero hangover |
| 11 | minUtteranceMs | **1500** | 过短静音切句并入下一句 |
| 12 | sileroVadMode | **NORMAL** | Silero |
| 13 | 文件导入 | 录音管线（VAD + 时间轴） | 非离线标点通道 |
| 14 | 事后重跑失败 | **软跳过 + 仍保存** | 非整次作废 |
| 15 | 字幕默认 | Both + FullSentence + 16sp | 主页始终整句；悬浮可 ScrollLine |
| 16 | 悬浮空闲清空 | **3s** | `SubtitleOverlayService.IDLE_BLANK_MS` |
| 17 | HTTP cleartext | 是 | 本地网关 |
| 18 | allowBackup | **false** | 减少 API Key 进备份 |

## 真机验收清单

1. Sync 并安装 → Settings 填 ASR / LLM，点 ℹ 与「还原默认」确认文案  
2. 麦克风 Start → 停顿 → 主页**逐句双语对照**；顶栏全屏进入沉浸双语  
3. 只识别模式 → 无译文、不请求 LLM  
4. 悬浮字幕：内容/布局/字号；3s 无新句清空；透明度 0；通知锁定拖动  
5. ScrollLine + 慢翻译 / 翻译失败 → 仍应显示（hold 后 stamp 放行）  
6. 文件导入 → 进度走 VAD 管线；历史有 offset 可点句  
7. 历史「重新识别翻译」→ 故意断网/坏 Key 时仍应部分保存并提示跳过数  
8. Pause / Resume / Stop → 历史导出 SRT / 录音；内录 Stop 不崩溃  
