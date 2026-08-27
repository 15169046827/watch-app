# 听读 / 听读工坊

项目包含用户可见名称为“听读”的 Watch App，以及“听读工坊”Windows 转换器。PC 将用户拥有或获授权的无 DRM EPUB/TXT 转为开放 `.opbook`；Watch 安全导入后离线播放并逐句同步正文。内部目录、包名和兼容可执行文件名保持不变。

## 目录与边界

- `builder/`：Vue 3 + PrimeVue + Tauri 2 Windows GUI，Java 17 CLI/转换核心，EPUB/TXT 解析、多 Provider TTS、可恢复断点、受控缓存、FFmpeg M4A/WebP 和 `.opbook` 打包。
- `watch/`：包名 `com.canace.x3offlinereader`；Java + Android Views + Media3，目标 OWW261（Android 11/API 30、armeabi-v7a、466×466 圆屏）。
- `specification/`：`.opbook` 1.0 开放格式、Draft 2020-12 JSON Schema 和安全/语义验证器。
- `samples/`：项目自写公开输入、Schema 正反例和本机生成但不纳入源码的 Golden 包。

小说与音乐在 UI、SQLite、队列、进度、收藏、设置和存储上隔离，只共享 `PlaybackService`、MediaSession、Audio Focus 与系统媒体控制。Watch 不直接播放 ZIP：先在 staging 中限制和验证路径、大小、压缩比、JSON、资源关系、SHA-256、M4A/WebP，再原子提交解压目录。

## 构建与验证

```powershell
.\gradlew.bat :builder:test :builder:installDist :watch:testDebugUnitTest :watch:assembleDebug :watch:lintDebug

& '..\scripts\verify-debug-signatures.ps1'
```

格式验证（可在命令末尾追加一个或多个 `.opbook`）：

```powershell
& '<python.exe>' .\specification\tools\validate_fixtures.py .\samples\generated\golden-two-volume.opbook
```

Windows 便携应用镜像：

```powershell
.\builder\package-windows.ps1          # 内含 JRE、FFmpeg、离线 TTS 运行环境与模型
.\builder\package-windows.ps1 -WithoutMelo
```

产物位于 `builder\build\windows-package\X3AudiobookBuilder`，用户可见名称为“听读工坊”，兼容 GUI 文件名为 `X3AudiobookBuilder.exe`，CLI 为 `X3BuilderCLI.exe`。Debug APK 位于 `watch\build\outputs\apk\debug\watch-debug.apk`，用户可见名称为“听读”，固定使用工作区共享 Debug Key；不得生成替代密钥。

为当前 Windows 用户创建开始菜单入口：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\builder\create-start-menu-shortcut.ps1
```

## 当前状态

听读工坊 0.6.0 将 Windows 展示层升级为 Vue 3.5 + PrimeVue 4.5 + Tauri 2.11，Java 继续作为唯一转换核心。新界面按“制作新书 / 音色试听 / 任务与续作 / 存储管理”组织；转换中关闭窗口会先安全保存断点。进度按本次未缓存字符推进，并在 Java/桥接两层保持单调；完整缓存命中会明确提示跳过合成。正式 EXE、CLI 名称、开始菜单路径和 `builder/.work` 数据位置保持兼容。详见 [`specification/BUILDER_UI_VUE_TAURI.md`](specification/BUILDER_UI_VUE_TAURI.md)。
0.4.1 延续已通过 OWW261 验收的阅读、续听、逐句高亮、逻辑章节和音乐功能。Watch 图标保持已验收的 SPEC v2.1：全画布 adaptive background 与 0.72 居中 foreground，本轮不改动 Watch。Windows“听读工坊”按 SPEC v2.2 改为透明 512×512 外画布、居中的 420×420（82.03%）`#D8A142` 圆角底板与原有窗口/书/波形前景；ICO 包含 16/24/32/48/64/128/256 七个原生尺寸，四角透明、中心不透明，避免 Windows 将完整色块显示成方形图标。

正式 Windows app-image 与“听读工坊”开始菜单快捷方式已按 v2.2 安全刷新；句子语音缓存保留，Watch 工程、安装与数据未改动。Windows Shell 最终同屏证据位于 `builder/build/icon-v22-validation/windows-shell-formal-shortcut.png`。

听读工坊 0.4.1 清晰标注网络边界，并随包提供 223 条可即时播放的自然语音试听：Melo 1、Kokoro 81、AISHELL-3 130、Microsoft Edge 中文 11。试听使用模型 1× 自然速率，只通过选择约 5–10 秒可读完的短句控制时长，不做后期变速；超时或过短且调整文本后仍不合格的模型音色不进入清单。Windows SAPI 仍是离线保底，但本机 Huihui 注册异常，未生成其试听。在线正式转换前必须确认正文会发送给第三方服务；`.opbook` 格式仍为 v1。详见 [`specification/TTS_PROVIDERS.md`](specification/TTS_PROVIDERS.md)。

听读工坊 0.5.0 增加“保存进度并关闭 / 恢复未完成”、原子句子缓存、已编码 Track 复用、20 GiB/180 天自动缓存整理和可视化缓存管理；成功任务会立即删除重复 PCM/Track 工作文件。AISHELL-3、Kokoro 的 ETA 已改用本机端到端实测速率，并在每句完成时动态校准。详见 [`specification/BUILDER_RESUME_CACHE.md`](specification/BUILDER_RESUME_CACHE.md)。
