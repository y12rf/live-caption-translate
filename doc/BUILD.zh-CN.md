# 自行编译

安装与 API 配置请看 [README 中文版](../README.zh-CN.md)。

## 环境要求

- JDK 17
- Android SDK 34
- 真机或模拟器 API 26+

## 编译 Debug 安装包

```bat
gradlew.bat :app:assembleDebug
```

输出（默认仅 arm64）：

`app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`

安装后在 **设置** 里填 ASR / LLM（见 [README](../README.zh-CN.md)）。

### 模拟器（x86）

CI 与默认工程只编 **arm64**。若要用 x86 模拟器，在 `app/build.gradle.kts` 的 `splits.abi.include` 里临时加上 `"x86"` 再编译。

## 可选测试

```bat
gradlew.bat :app:testDebugUnitTest
```

## 备注

- **minSdk** 26 · **targetSdk** 34 · `applicationId` `com.example.livetranslate`
- Release 默认 debug 签名（仅旁加载）
- Fork：以自己的包名发布前，先改 `app/build.gradle.kts` 里的 `namespace` / `applicationId`

架构说明见 [ARCHITECTURE.zh-CN.md](ARCHITECTURE.zh-CN.md)。
