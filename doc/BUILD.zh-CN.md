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

| 变体 | 路径 |
|------|------|
| arm64 | `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk` |
| x86 | `app/build/outputs/apk/debug/app-x86-debug.apk` |

按设备 / 模拟器 ABI 安装对应 APK，然后在 **设置** 中配置 ASR / LLM（见 [README → 配置](../README.zh-CN.md#配置)）。

## 可选测试

```bat
gradlew.bat :app:testDebugUnitTest
```

## 备注

- **minSdk** 26 · **targetSdk** 34 · `applicationId` `com.example.livetranslate`
- ABI 分包：`arm64-v8a`、`x86`（无 universal 胖包）
- Release 默认使用 debug 签名（仅适合旁加载）——上架 Play 需换成正式 keystore
- Fork 时：若要以自己的包名发布，请先在 `app/build.gradle.kts` 中修改 `namespace` / `applicationId`

架构说明见 [ARCHITECTURE.zh-CN.md](ARCHITECTURE.zh-CN.md)。
