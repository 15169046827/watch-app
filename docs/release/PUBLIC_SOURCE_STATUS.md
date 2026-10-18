# Watch App 公开源码状态

- 结论：`PASS — PUBLIC SOURCE READY`
- 范围：GitHub 默认分支 `main` 的源码、规范、测试和构建配置
- 不包含：签名材料、凭据、本机配置、设备标识、私人 Wolai 页面、原始设备日志、用户数据、构建输出和本地任务协调文件
- 发布主体：个人 `Ym_Li`

## 结论边界

本结论只表示当前源码树适合公开浏览，不等同于每个二进制产品已经达到 WARS 正式公共生产门禁。GitHub Releases 中标记为 `LIMITED_PREVIEW` 的 Android APK 和 Windows 便携 ZIP 继续按各自发布说明使用。

- 日程 0.7.0：批准范围内代码与实机候选验收通过，已提供双端 Android 个人预览包；手表本地准时提醒不在发布范围内。
- 记账、笔记和听读：统一个人预览批次使用 Android Release 证书；笔记个人 Token 模式及自动记账限制以产品 README 和发布说明为准。
- 听读工坊：个人预览仅提供明确标记的未签名便携 ZIP；没有 Authenticode 时不提供安装器，也不宣称正式生产签名通过。
- X3AccessibilityProbe：仅为内部诊断工具，不是公共产品安装包。

## 自动门禁

公开源码批次必须通过：

- WATCH-APP-UPARS 审计 profile 正例与负例；
- WARS 公开发布标准正例与负例；
- 设备全局网络变更禁令正例与负例；
- 公开源码边界正例与负例；
- 各 Android 项目的 Checkstyle、单元测试、Lint、Debug 构建与统一 Debug 证书验证；
- 听读工坊 Java、Vue/TypeScript、Rust、Python 和 PowerShell 检查；
- `npm audit --audit-level=high`。

公开源码边界门禁只输出违规规则和文件名，不输出疑似秘密内容。发布前还应对远端可达 Git 历史执行敏感路径和凭据模式复核。

## 许可与贡献

当前仓库没有统一开源许可证，原创内容保留所有权利；源码公开可见不自动授予复制、修改或再分发许可。明确选择许可证前不接收外部代码贡献。第三方组件继续适用其各自声明的许可证。
