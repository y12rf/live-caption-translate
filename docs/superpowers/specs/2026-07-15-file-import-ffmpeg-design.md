# 自定义音频文件导入（FFmpeg → 原管线）

**日期**: 2026-07-15  
**状态**: 已实现  
**范围**: 选文件 → FFmpeg 转 16k 单声道 WAV → 能量 VAD 切句 → 现有 ASR/LLM 双队列 → 历史落库

## 目标

用户可上传/选择本地录音（mp3/m4a/wav/…），离线整段处理，自动翻译，体验与麦克风会话一致（文稿、SRT、会话 WAV）。

## 流程

1. UI：音源 **文件** → Start → `OpenDocument`（`audio/*` / `video/*`）
2. 复制 Content URI → `cache/import_audio/`
3. FFmpeg（`com.antonkarpenko:ffmpeg-kit-audio`）：`-vn -ac 1 -ar 16000 -c:a pcm_s16le` → WAV
4. 安装到 `files/recordings/session_*_import.wav` 作为会话存档
5. `FileAudioSegmenter`：按设置 VAD 参数切句，带 `offsetMs`
6. `enqueueUtterance` → 现有 ASR/LLM/弱网逻辑
7. 切句结束后等待管线排空 → `stop(drain=true)` 自动保存

## 模块

| 模块 | 职责 |
|------|------|
| `FfmpegAudioConverter` | FFmpeg 异步转码 + 进度 |
| `WavPcmReader` | 读标准/带 data 块的 PCM WAV |
| `FileAudioSegmenter` | 离线 VAD → `Flow<UtteranceAudio>` |
| `SessionOrchestrator.startFromFile` | 编排导入会话 |
| `AudioCapture.installImportedSessionWav` | 会话 WAV 路径 |

## 边界

- 文件模式无 Pause、不启麦克风 FGS
- 用户 Stop 可取消转码/切句
- APK 因 ffmpeg-kit-audio 明显增大（多 ABI native）
- 官方 arthenica 包已下架，使用 Maven Central 社区重建版 2.2.1

## 非目标

- 实时语速回放喂入
- 整文件单次 ASR（不切句）
- 云端上传托管
