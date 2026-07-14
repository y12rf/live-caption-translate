# 醒来后请确认的决策（已用合理默认落地）

实现时为不阻塞进度，下列项已按默认实现。若要改，直接说编号即可。

| # | 项 | 当前默认 | 可选 |
|---|----|----------|------|
| 1 | 应用包名 | `com.example.livetranslate` | 改为你的域名包名 |
| 2 | targetSdk | 34（buildTools 35.0.0） | 升到 35 |
| 3 | 自动重试 | 最多 3 次尝试，退避 500ms×n² | 改次数/间隔 |
| 4 | 暂停后点 Start | **同一会话 Resume**，不清空累积文本 | 暂停=结束会话 |
| 5 | Stop 时 flush 尾段 | 开启（≥ minUtterance 才送 ASR） | 关闭 flush |
| 6 | 默认 ASR model | `whisper-1` | 如 `gpt-4o-mini-transcribe` |
| 7 | 默认 LLM model | `gpt-4o-mini` | 任意兼容模型 |
| 8 | maxUtteranceMs | 10000 | 更短/更长强制截断 |
| 9 | silenceMs | 700 | 课堂语速可调 |
| 10 | 允许 HTTP cleartext | 是（本地兼容网关） | 仅 HTTPS |
| 11 | App 图标 | 系统默认图标 | 自定义品牌图标 |
| 12 | 合并到 master | 仍在 `feature/live-translate-mvp` | 合并/开 PR |

## 真机验收清单

1. Android Studio 打开本项目，Sync，装到真机/模拟器  
2. 设置页填入 ASR / LLM 的 Base URL 与 API Key  
3. Start → 说英语 → 停顿 → 应先流式出 EN，再流式出 ZH  
4. 连续说超过约 10s 不换气 → 应强制截断并开始下一段  
5. Pause / Resume / Stop → 历史页可看会话并分享导出  
