# 默认决策快照

实现时为不阻塞进度的默认项。数值以 `UserSettings` / 设置为准；此处仅作对照。

| # | 项 | 当前默认 | 说明 |
|---|----|----------|------|
| 1 | 应用包名 | `com.example.livetranslate` | 可改为域名包名 |
| 2 | targetSdk | 34（buildTools 35.0.0） | 可升 35 |
| 3 | 自动重试 | 默认 3 次（可设 1–10），退避 500ms×n² | 设置「网络重试次数」 |
| 4 | 暂停后 Start | **同一会话 Resume** | — |
| 5 | Stop flush 尾段 | 开启（≥ minUtterance） | — |
| 6 | 默认 ASR model | `whisper-1` | — |
| 7 | 默认 LLM model | `gpt-4o-mini` | — |
| 8 | maxUtteranceMs | **4500**（代码默认） | 设置可改 |
| 9 | silenceMs | **500** | — |
| 10 | minUtteranceMs | **1700** | — |
| 11 | energyThreshold | **400** | — |
| 12 | 文件导入 | 录音管线（VAD + 时间轴） | 非离线标点通道 |
| 13 | 事后重跑失败 | **软跳过 + 仍保存** | 非整次作废 |
| 14 | 字幕默认 | Both + FullSentence + 16sp | — |
| 15 | HTTP cleartext | 是 | 本地网关 |
| 16 | 合并到 master | 已合并 | — |

## 真机验收清单

1. Sync 并安装 → Settings 填 ASR / LLM，点 ℹ 与「还原默认」确认文案  
2. 麦克风 Start → 停顿 → 主页**逐句双语对照**；沉浸模式仅双语  
3. 只识别模式 → 无译文、不请求 LLM  
4. 悬浮字幕：内容/布局/字号；通知锁定拖动  
5. 文件导入 → 进度走 VAD 管线；历史有 offset 可点句  
6. 历史「重新识别翻译」→ 故意断网/坏 Key 时仍应部分保存并提示跳过数  
7. Pause / Resume / Stop → 历史导出 SRT / 录音  

