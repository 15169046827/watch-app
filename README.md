# Watch Apps

面向 OPPO Watch X3 的应用集合。每个产品位于独立子目录，共享本仓库的通用设计与验证工具。

## 应用

- [`X3AccessibilityProbe`](./X3AccessibilityProbe/)：OPPO Watch X3 统一硬件与系统能力探针（Accessibility、BLE 等）
- [`X3VoiceLedger`](./X3VoiceLedger/)：OPPO Watch X3 端本地语音记账工具
- [`X3WolaiNotes`](./X3WolaiNotes/)：OPPO Watch X3 端 Wolai 速记与离线重试工具
- [`X3OfflineReader`](./X3OfflineReader/)：手表端离线听读 App 与 Windows 内容转换器
- [`X3Schedule`](./X3Schedule/)：手机编辑、手表查看与双端同步的本地日程管理工具

Android Studio 应打开具体 Android 项目的子目录。各项目的构建、配置和功能边界见其 README。

## 审计标准

本仓库全部项目采用 UPARS 1.0.0，并叠加[手表应用审计补充规则](./docs/audit/WATCH_APP_AUDIT_PROFILE.md)。UPARS 负责跨项目通用要求；本仓库维护圆屏、Wear OS、离线、设备网络、签名和配套构建器等项目专属规则。[WARS 1.1 公共版发布标准](./docs/release/WARS_1_0.md)记录面向普通用户的安装、授权、数据与签名要求；[生产发布验收清单](./docs/release/PRODUCTION_ACCEPTANCE_CHECKLIST.md)用于逐批次留证和最终放行。代码审计与发布标准化并行执行并分别给出结论。

`X3VoiceLedger`、`X3WolaiNotes`、`X3OfflineReader`、`X3Schedule`、配套手机端和听读工坊按公共产品管理；`X3AccessibilityProbe` 保持内部诊断工具定位。

## 发布状态

`main` 是公开源码基线。GitHub Releases 中标记为 `LIMITED_PREVIEW` 的文件属于个人预览轨道，不等同于全部生产门禁通过：Android 预览包使用统一 Release 证书；Wolai 笔记由用户自行导入个人 Token；未取得 Authenticode 证书时，Windows 听读工坊只提供带 `UNSIGNED-PREVIEW` 标识、来源提交和 SHA-256 的便携 ZIP，不提供安装器。

已发布的日程和听读工坊预览版本见仓库的 [Releases](https://github.com/15169046827/watch-app/releases)。每个产品的功能、数据边界、已知限制和构建方法以其子目录 README 为准。

代码公开与产品生产发布是两个独立结论；当前边界见[公开源码状态](./docs/release/PUBLIC_SOURCE_STATUS.md)。

机器可读的项目规则位于 [`config/audit/watch-app-audit-profile.json`](./config/audit/watch-app-audit-profile.json)。现有 Java 质量、签名、网络安全及 CI 文件是该 profile 的仓库级实现；项目规则可以加严，但不得静默降低 UPARS 门槛。

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

仓库不包含任何签名密钥。所有 Android 应用的 Debug 构建要求使用仓库外的既有共享 Debug Key；配置阶段会校验其私钥条目和固定证书摘要，缺失或不符时停止构建。

维护者可在仓库根目录验证内部 Debug APK 的证书：

```powershell
.\scripts\verify-debug-signatures.ps1
```

正式 Android 产品共用一把独立、安全备份且不进入仓库的 Release Key，不能使用 Debug Key。Release 构建所需的密钥位置、类型、别名、口令和证书 SHA-256 只从环境变量或受保护的 CI Secret 提供；发布产物还需运行独立签名核验。

Windows 听读工坊的正式生产 EXE/MSI 必须通过 Authenticode 验证，并核对预先登记的签名证书 SHA-256；个人预览轨道只允许明确标记的未签名便携 ZIP。密钥准备、构建配置和验收要求详见 WARS 1.1。

## 参与和安全

- 提交改动前请阅读 [`CONTRIBUTING.md`](./CONTRIBUTING.md)。
- 自动化开发 Agent 应先阅读 [`AI Agent 维护指南`](./docs/maintenance/AI_AGENT_MAINTENANCE.md)；本机专属路径、任务和凭据入口由未纳入 Git 的根 `AGENTS.md` 提供。
- 跨设备、跨进程、HTTP、系统回调与开放文件契约由 [`Apifox API 维护入口`](./docs/api/README.md) 统一盘点和同步。
- 安全问题请按 [`SECURITY.md`](./SECURITY.md) 私下报告，不要在公开 Issue 中附带 Token、页面 ID、设备日志或个人数据。

## 许可说明

当前仓库尚未授予统一的开源许可证；公开可见不等于获得复制、修改或再分发许可，原创内容保留所有权利。随仓库固定的第三方组件继续适用其自身许可证；例如记账手机端的 JExcelAPI 声明见 [`X3VoiceLedger/phone/THIRD_PARTY_NOTICES.md`](./X3VoiceLedger/phone/THIRD_PARTY_NOTICES.md)。
