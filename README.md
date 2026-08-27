# Watch Apps

面向 OPPO Watch X3 的应用集合。每个产品位于独立子目录，共享本仓库的通用设计与验证工具。

## 应用

- [`X3AccessibilityProbe`](./X3AccessibilityProbe/)：OPPO Watch X3 统一硬件与系统能力探针（Accessibility、BLE 等）
- [`X3VoiceLedger`](./X3VoiceLedger/)：OPPO Watch X3 端本地语音记账工具
- [`X3WolaiNotes`](./X3WolaiNotes/)：OPPO Watch X3 端 Wolai 速记与离线重试工具
- [`X3OfflineReader`](./X3OfflineReader/)：手表端离线听读 App 与 Windows 内容转换器

Android Studio 应打开具体 Android 项目的子目录。各项目的构建、配置和功能边界见其 README。

## 开发环境

- JDK 17
- Android SDK（通过 `ANDROID_SDK_ROOT`、`ANDROID_HOME` 或项目中未纳入版本控制的 `local.properties` 配置）
- Windows PowerShell 5.1 或 PowerShell 7

构建示例：

```powershell
Set-Location .\X3AccessibilityProbe
.\gradlew.bat assembleDebug
```

## 本地配置与安全

- 不提交访问令牌、设备序列号、签名密钥、本机绝对路径或私有页面 ID。
- 可提交的配置模板使用 `.example` 后缀；真实值放在被忽略的 `local.properties`、环境变量或外部凭据存储中。
- 构建产物、缓存、下载依赖和本机开发工具不进入版本控制。
- 示例命令使用仓库相对路径，可在任意克隆目录中执行。

## 签名

仓库不包含任何签名密钥。普通克隆使用 Android 标准 Debug 签名即可构建；维护者可在仓库根目录的 `.signing/x3-debug.keystore` 放置既有团队 Debug Key，以便覆盖安装内部测试版本。

维护者可在仓库根目录验证内部 Debug APK 的证书：

```powershell
.\scripts\verify-debug-signatures.ps1
```

正式发布必须使用独立、安全备份且不进入仓库的 Release Key，不能使用 Debug Key。
