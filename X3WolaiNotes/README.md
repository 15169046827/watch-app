# X3 Wolai Notes

OPPO Watch X3（OWW261）端的轻量 Wolai 速记应用。项目独立于同目录下的其他手表应用。

## MVP

- 创建短文本笔记和待办（待办以 `☐` 前缀写入，避免依赖未稳定开放的专用块类型）
- 本地 SQLite 先保存，失败时保留队列并支持重试
- 写入前持久化“待确认”状态；网络结果不明确或进程中断后，先在线核对近期同内容块，至少等待 2 分钟再重试，降低重复写入风险
- 在线读取“手表收藏”中的页面引用，支持子页面导航和常用文字块展示
- 在已加载的当前页面内按关键词筛选收藏页、文字块和数据库行；支持空格分隔的多关键词，筛选不发起额外请求且不落盘
- 收藏页顶部显示最多 10 个最近访问页面；仅保存页面标题、页面 ID 和访问时间，可筛选或一键清除，不缓存正文、表格单元格或媒体
- 使用 Wolai 官方 GET /databases/{id} 在线读取嵌入式数据表格；按行卡片展示列名与单元格，并可进入行页面
- 阅读内容只保存在当前界面内存中，退出后不落盘；图片、视频和附件只显示占位提示
- 针对圆屏预留安全边距
- 从左向右滑动退出
- 仅使用 HTTPS；Token 不写入源码、Gradle 配置或 APK
- Debug 文件注入仅用于开发验证；个人 GitHub 预览版由用户在受保护的临时导入窗口输入个人 Token，正式公共生产版仍要求 Wolai 正式 OAuth

目标页面和收藏目录的页面 ID 由用户在“数据与授权”的临时配置窗口保存到应用私有配置。配置完成后只显示两项配置状态和配置时间，原始 ID 不回填、不允许直接修改；更换必须先删除再重新配置。`local.properties` 和环境变量只作为 Debug 测试回退；Release 包会忽略并清空这些编译时值。

未配置页面 ID 时，速记仍可保存到本地但不会发起写入；收藏页显示明确的配置提示。授权失效、无权限、页面不存在、限流、服务异常和断网均转换为适合圆屏阅读的提示，不展示服务端原始响应。

## 版本

- 0.6.1（versionCode 10）：修复“数据与授权”页明文回填同步页和收藏页 ID 的隐私问题。主页面不再保留 Token 或页面 ID 输入框；已配置状态只显示用途、配置/导入时间和删除入口，原始值不回显且不可直接修改，更换必须先删除后重新导入/配置。旧版本配置无法准确还原时间时明确显示“时间未知”。
- 0.6.0（versionCode 9）：按 WARS 1.1 增加个人 GitHub 预览授权。个人 Token 默认未配置，保存前明确提示其不是 OAuth；Token 不回显，经 Android Keystore 加密后仅保存在应用私有存储，不进入 APK、日志、导出或备份，并可随时清除。Release 同步与在线阅读可使用用户自行配置的 Token；Debug 文件注入仍不进入 Release。
- 0.5.0（versionCode 8）：执行 WARS 标准化；统一 Release 签名门禁，隔离 Debug 旧令牌和私人页面 ID，新增首次数据说明、本机页面配置、授权清除、JSON 导入导出及分类二次确认清除。正式 OAuth 尚缺 Wolai 官方参数，Release 在线能力保持停用。
- 0.4.0（versionCode 7）：增加最近访问页面，SQLite v4 无损迁移并将元数据限制为最多 10 条；2026-09-03 已完成保数据覆盖安装、自动回归和圆屏人工验收。
- 0.3.3（versionCode 6）：增加仅 Debug 构建可启用的确定性断网界面与不确定写入故障注入回归；Release 构建无法启用测试故障。
- 0.3.2（versionCode 5）：增加当前在线页面的内存筛选，覆盖收藏页、正文块和数据库行。
- 0.3.1（versionCode 4）：增加不确定写入对账、配置校验和分类错误提示。
- 对账是基于官方开放 API 返回的近期文本块做的本地保护，并非服务端幂等保证；相同文本在非常接近的时间内重复提交时仍应进行人工核对。

## 构建

```powershell
.\gradlew.bat qualityCheck :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleAndroidTest
```

`qualityCheck` 会执行主源码、单元测试、Android 仪器测试与 `tools` 自测源码的编码规范检查，并实际运行三组独立 Java 自测；单元测试、Lint 和 APK 构建由同一条验收命令继续执行。

## 设备测试

安装脚本只从进程或 Windows 用户环境变量读取 `WOLAI_OPENAPI_TOKEN`，不会打印 Token：

```powershell
.\scripts\check_device.ps1
.\scripts\install_and_provision.ps1
```

安装 Debug 主 APK 和 Android 测试 APK 后，可运行不输出私人内容的自动回归：

```powershell
adb shell am instrument -w -e mode offline-ui com.canace.x3wolainotes.test/com.canace.x3wolainotes.MvpTestRunner
adb shell am instrument -w -e mode backup-synthetic com.canace.x3wolainotes.test/com.canace.x3wolainotes.MvpTestRunner
adb shell am instrument -w -e mode personal-token-ui com.canace.x3wolainotes.test/com.canace.x3wolainotes.MvpTestRunner
adb shell am instrument -w com.canace.x3wolainotes.test/com.canace.x3wolainotes.MvpTestRunner
```

`backup-synthetic` 只在专用测试数据库中验证旧库迁移、JSON 导出、无损合并与重复导入，结束时删除测试库；不读取真实笔记或令牌，也不发起 Wolai 请求。`personal-token-ui` 只检查 Token、同步页 ID、收藏页 ID 均无常驻输入框或明文回填，并验证状态/时间和删除后重配入口。默认整套测试含在线写入，不可用于仅限合成数据的验收。

脚本从 `ANDROID_SERIAL` 读取设备序列号，并从 `ANDROID_SDK_ROOT`、`ANDROID_HOME` 或 `PATH` 查找 ADB；也可显式传入 `-Serial` 和 `-AndroidSdk`。仓库不保存具体设备编号。

## 离线测试安全规则

离线回归只允许使用上述 Debug 进程内故障注入。永久禁止使用全局代理、飞行模式、Wi-Fi、蓝牙或移动数据开关制造本项目的自动断网条件。提交前运行 scripts\verify_network_test_policy.ps1。

2026-09-05 事故更正：早期人工断网测试曾将系统全局代理指向本机无效端口，恢复时只删除组合键并只检查该键为 null，未清除拆分代理字段或刷新 ConnectivityService 的 ProxyTracker，导致遵循系统代理的其他 App 长期无法联网。此前“代理已恢复”的结论错误。系统认可的直接代理清除入口是 settings put global http_proxy :0；若未来其他诊断任务确需临时修改系统状态，必须完整快照、在 finally 恢复，并核验所有代理字段、ProxyTracker 更新和独立第三方 HTTPS，不能用本 App 自身联网作为恢复证据。

## 安全边界

- 不 Root、不解锁 Bootloader、不修改系统分区。
- Debug APK 不包含 Wolai App Secret 或 Token。
- Release APK 必须使用工作区统一 Android Release Key；缺少密钥路径、别名、密码或证书摘要时构建会失败关闭，且绝不允许使用共享 Debug Key。
- 卸载应用会删除设备端加密 Token 和本地缓存。
- 个人 GitHub 预览版只允许用户在临时导入窗口输入自己持有的个人 Token，并明确标记为 LIMITED PREVIEW；导入后只显示时间和状态，Token 不回显、不可直接修改，只能删除后重导。不得把 Token 或 app secret 编译进 APK，也不得在 Release 使用 ADB 文件注入。正式公共生产版仍以正式 OAuth 为放行条件。
- 数据表格要求自建应用具备所需的数据表格权限；接口异常通过 Wolai 官方开发者文档底部的问题反馈入口提交。
- 离线测试不得修改设备级网络状态；本项目只使用 Release 无法启用的 Debug 进程内故障注入。
- 页面、数据表格和行页面 ID 在进入官方 API 路径前均执行格式与长度校验；客户端拒绝 HTTP 重定向，并限制响应体、行列数量、单元格长度及 JSON 递归深度。
- 速记和阅读界面均禁止系统截图/录屏，减少本地笔记与在线私有内容泄露风险。
- 安装配网脚本会先验证 Debug APK 使用共享开发证书；应用启动后还会确认明文临时 Token 已被导入并删除，失败时主动清理。

2026-09-09 完成 WACAS 1.1.0 全量静态审计与整改复验：Checkstyle、JUnit、Android Lint、Debug/AndroidTest/Release 构建、两个独立 Java 自测、联网测试策略和 Debug 签名校验均通过。本轮未连接或操作外部设备，真机仪器测试与账号写入测试未执行。

## 数据迁移与清除

主界面的“数据与授权”支持：

- 导出本地速记、待办、同步状态和最近访问为未加密 JSON；
- 导入前完整校验，按记录 ID 无损合并，同 ID 保留设备现有记录；
- 分别清除本地笔记、最近访问、本机授权，或清除全部本机数据；
- 页面配置和授权令牌不进入备份，换机后重新配置、重新授权；
- 在线阅读正文继续只在内存中存在，退出后不落盘。

完整边界见 `docs/DATA_LIFECYCLE.md`；正式授权的官方能力缺口、安全基线和放行测试见 `docs/OAUTH_RELEASE_READINESS.md`。

## 当前发布结论

当前代码支持 WARS 1.1 的个人 GitHub 预览轨道。0.6.1 Release APK 已从审核提交 `86bae2d` 构建，使用预登记正式证书并完成独立验签；目标 OPPO Watch X3 已完成保数据覆盖、合成迁移、无常驻敏感配置输入框和圆屏人工显示验收。该 APK 已包含在 [`Watch App LIMITED_PREVIEW 2026-09-21`](https://github.com/15169046827/watch-app/releases/tag/watch-app-preview-2026-09-21-86bae2d) 中。正式公共生产版仍因 Wolai 官方 OAuth 和完整生产验收而保持 FAIL / NOT READY；Debug 文件注入路径仅用于受控开发验证。
