# 听读 / 听读工坊

项目包含用户可见名称为“听读”的 Watch App，以及“听读工坊”Windows 转换器。PC 将用户拥有或获授权的无 DRM EPUB/TXT 转为开放 `.opbook`；Watch 对用户选中的源文件建立安全索引后离线播放并逐句同步正文。内部目录、包名和兼容可执行文件名保持不变。

## 目录与边界

- `builder/`：Vue 3 + PrimeVue + Tauri 2 Windows GUI，Java 17 CLI/转换核心，EPUB/TXT 解析、多 Provider TTS、可恢复断点、受控缓存、FFmpeg M4A/WebP 和 `.opbook` 打包。
- `watch/`：包名 `com.canace.x3offlinereader`；Java + Android Views + Media3，目标 OPPO Watch X3（Android 11/API 30、armeabi-v7a、466×466 圆屏）。
- `specification/`：`.opbook` 1.0 开放格式、Draft 2020-12 JSON Schema 和安全/语义验证器。
- `samples/`：项目自写公开输入、Schema 正反例和本机生成但不纳入源码的 Golden 包。

小说与音乐在 UI、SQLite、队列、进度、设置和存储上隔离，只共享 `PlaybackService`、MediaSession、Audio Focus 与系统媒体控制。Watch 0.8.0 延续 0.7.6 的两个播放器均采用横向操作页与内容页：较短滑动即可切页，全部操作图标在音频准备前后保持固定视觉尺度，内容滚动视口覆盖完整 466×466 画布并使用 Android 圆屏原生弧形滚动条。小说书库按最近播放时间优先，点击书籍直接续听；章节目录自动定位当前章，已有原始章节号时直接沿用，仅为无编号标题补序号，避免序章导致目录与正文错位。操作页表冠调音量，正文页表冠按行滚动；小说倍速为显示倍率的 0.5×～5.0× 滑杆，每 0.1× 一档。书签功能已取消，书内搜索保留。音乐歌词页保留全部有效歌词并持续高亮、居中跟随当前句。

小说与音乐播放页的播放/暂停控件采用 74dp 主按钮，便于圆屏点击。OPPO Watch X3 的上方物理键短按由系统电源策略在普通 App 收到事件前消费，长按又与全局电源菜单冲突，因此听读不拦截该键；后台控制继续使用 MediaSession 提供的系统媒体入口。

## 构建与验证

存储管理中的缓存整理会立即显示持续活动进度条与已耗时（不显示估算百分比），清理期间禁止重复操作及开始转换，关闭窗口会提示等待。结束后就地显示删除数量、释放空间或失败原因，并明确可以关闭工坊；失败可能已部分清理，可重试。句子缓存清理不删除未完成任务断点、源书或输出文件。

Builder 测试需要 Python 3（安全策略夹具仅使用标准库，不加载模型或私人数据）；可用 `X3_BUILDER_TEST_PYTHON` 指定解释器。`:builder:test` 自动运行这组正反例，因此现有 CI 也会执行，无需改变仓库级工作流。

听读工坊的 Edge 连接组件可独立自动更新，无需为兼容的客户端变更重新编译。启动时每天最多检查一次；“存储管理 → 在线语音组件”可关闭自动检查、手动检查并查看版本。只有通过官方包哈希、兼容性和公开短句音频验证的组件才会在空闲时启用，失败保留原组件。详见 [安全更新边界](specification/EDGE_CLIENT_UPDATES.md)。

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
.\builder\package-windows-preview.ps1  # 个人 GitHub 未签名便携预览 ZIP；只接受干净的已审核源码
# 独立候选：-CandidateName security-test；不会覆盖默认预览目录或 ZIP。
# 可通过 -MeloEnvironmentPath 与 -EdgePackagesPath 指向分别验收过的运行时/依赖目录。
.\builder\package-windows.ps1          # 正式包；须配置 Authenticode 证书、HTTPS 时间戳和 signtool
.\builder\package-windows.ps1 -WithoutMelo -WithoutAdditionalVoices
.\builder\package-windows.ps1 -WorkRoot '<absolute-data-directory>' # 可选的绝对数据目录
```

未签名预览产物为 `builder\build\X3AudiobookBuilder-0.7.0-UNSIGNED-PREVIEW.zip` 及其 `.sha256`，只用于 WARS 1.1 LIMITED_PREVIEW，不生成安装器、不覆盖正式目录，并随包写入未知发布者提示和源提交。正式产物位于 `builder\build\windows-package\X3AudiobookBuilder`。兼容 GUI 文件名为 `X3AudiobookBuilder.exe`，CLI 为 `X3BuilderCLI.exe`。Debug APK 位于 `watch\build\outputs\apk\debug\watch-debug.apk`，固定使用工作区共享 Debug Key；不得生成替代密钥。

Edge 与 Sherpa 共用 `.tools\melo` 中的便携 Python；因此省略 Melo 运行时的精简包必须同时使用 `-WithoutAdditionalVoices`。脚本会拒绝只传 `-WithoutMelo` 的残缺组合，不会生成缺少 Python 的表面可用包。

为当前 Windows 用户创建开始菜单入口：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\builder\create-start-menu-shortcut.ps1
```

## 当前状态

依赖安全整改将 Melo 安装基线调整为 Python 3.11、Transformers 5.10.1 和 PyTorch 2.13.0；旧环境不会被原地修改，打包会检查实际运行库并拒绝携带旧依赖。Tauri 的 glib 告警仍受上游 GTK 约束，但不进入当前 Windows 目标依赖树；其他平台构建暂时阻止。源码修复不代表旧预览 ZIP 已被替换，兼容性、可达性和复验命令见 [依赖安全边界](specification/DEPENDENCY_SECURITY.md)。

EPUB 转换已补充正文编号章节识别与跨 spine 续文合并，防止合并型 EPUB 仅按 HTML 文件数量生成章节；内部代码型 HTML 标题不再进入 TTS。规则与旧文件重新转换说明见 [EPUB 逻辑章节与标题](specification/EPUB_CHAPTERS.md)。

听读 0.8.0 增加首次离线数据说明与“数据和迁移”，可导出/恢复进度、收藏与播放设置并分类清理 App 本机数据；源 `.opbook`、音乐和 LRC 不进入备份，App 级清理也不删除这些源文件。听读工坊 0.7.0 补充首次数据说明、界面设置/最近来源历史清理、过期任务删除确认、独立的未签名 GitHub 便携预览轨道，以及 GUI/CLI 双启动程序的正式 Authenticode 签名和独立验签。完整边界见 [`specification/DATA_LIFECYCLE.md`](specification/DATA_LIFECYCLE.md)。

听读 0.7.6 的播放器分页、全屏原生弧形滚动条、章节目录、正文定位、倍速滑杆与完整歌词契约见 [`specification/WATCH_PLAYER_PAGING.md`](specification/WATCH_PLAYER_PAGING.md)。0.6.2 的播放器音量滑杆与表冠接入契约见 [`specification/WATCH_VOLUME_CONTROLS.md`](specification/WATCH_VOLUME_CONTROLS.md)。书内搜索详见 [`specification/WATCH_BOOK_SEARCH.md`](specification/WATCH_BOOK_SEARCH.md)。

听读工坊 0.6.4 将句子缓存、断点、未完成 Track 和 GUI 控制文件从可替换的 app-image/构建目录迁移到当前 Windows 用户的独立本机数据目录（默认 `%LOCALAPPDATA%\Canace\X3AudiobookBuilder\work`）。正式 GUI、CLI 和打包脚本使用同一位置；旧源码或正式包内的 `builder/.work` 会逐文件复制并校验 SHA-256，目标冲突或验证失败会中止替换并保留旧数据，全部通过后才移除旧副本。新正式包内不再创建 `.work`，因此 Gradle 清理、程序重打包和升级不会删除转换缓存或断点；需要自定义磁盘时可使用绝对路径环境变量 `X3_BUILDER_WORK_ROOT` 或打包参数 `-WorkRoot`。详见 [`specification/BUILDER_RESUME_CACHE.md`](specification/BUILDER_RESUME_CACHE.md)。

听读 0.5.0 将“导入”改为索引式添加：用户可从任意目录多选 `.opbook`，App 持久保存文件授权并原地完成路径、大小、压缩比、格式、资源关系、元数据 SHA-256 与媒体头校验；音频内容 SHA-256 延迟到首次播放提取时核对。页面切换不会丢失任务状态或解锁重复提交。书籍按 `bookId` 去重，源文件缺失时给出明确提示；书籍和音乐均支持长按管理，书籍可选择仅移出书库或连同源文件删除。播放只缓存前一/当前/下一章节，优先对可寻址 SAF 文件随机读取 ZIP 条目，回退到流式读取。详见 [`specification/WATCH_INDEXED_LIBRARY.md`](specification/WATCH_INDEXED_LIBRARY.md)。

听读工坊 0.6.3 已完成五种语音引擎的并行优化与公开长样本端到端复测。模型选择框会显示本机长篇参考效率：Windows 系统语音 2 进程约 108.3 万字/小时、Edge 8 并发约 83.3 万、AISHELL-3 4 进程约 46.5 万、Kokoro 4 进程约 8.3 万、Melo 2 进程约 7.4 万；效率包含解析、模型启动、合成、编码与打包。Windows 每句重启 PowerShell 的瓶颈已由长驻 worker 池消除，Kokoro/AISHELL-3 使用受控模型进程池，ETA 同步改用实测曲线。结果会随硬件、文本、音色、网络和在线服务状态变化。详见 [`specification/TTS_PROVIDERS.md`](specification/TTS_PROVIDERS.md)。

听读工坊 0.6.2 延续 Vue 3.5 + PrimeVue 4.5 + Tauri 2.11 界面：转换完成后将运行中的三项预计时间替换为“任务总时长 / 本次执行总时长”，日志完整滚动，多音色卡固定高度。Edge 故障的真实 RCA 是旧切句器在句号后把右引号拆成纯标点独立句，导致 Microsoft 服务对确定性不可发音请求连续返回 `NoAudioReceived`；单纯延长到 25 次重试无法修复。现在闭合引号/括号会归入前句，纯标点片段被过滤，Provider 联网前再次校验文字/数字并正确识别闭合标点后的真正句末；只有有效文本的上游空响应才进入恢复窗口。公开引号文本已由源码和正式 CLI 真实在线转换并通过全部 `.opbook` 校验。Java 继续作为唯一转换核心，缓存、断点、正式路径和开始菜单入口保持兼容。
0.5.0 延续已通过 OPPO Watch X3 验收的阅读、续听、逐句高亮、逻辑章节和音乐功能。Watch 图标保持已验收的 SPEC v2.1：全画布 adaptive background 与 0.72 居中 foreground。Windows“听读工坊”按 SPEC v2.2 使用透明 512×512 外画布、居中的 420×420（82.03%）`#D8A142` 圆角底板与原有窗口/书/波形前景；ICO 包含 16/24/32/48/64/128/256 七个原生尺寸，四角透明、中心不透明，避免 Windows 将完整色块显示成方形图标。

正式 Windows app-image 与“听读工坊”开始菜单快捷方式已按 v2.2 安全刷新；句子语音缓存保留，Watch 工程、安装与数据未改动。Windows Shell 验收确认透明圆角、视觉尺度与同系列应用协调。

听读工坊 0.6.2 修复了 AISHELL-3/Kokoro 中文预处理把 Unicode 字符数组当作对象文本传入模型的问题，并升级缓存命名空间以隔离旧错误句子缓存。随包试听已重建为 286 条：Melo 1、Kokoro 中文 100、AISHELL-3 174、Microsoft Edge 中文 11；离线模型转换与试听现在使用同一条正确的中文文本路径。试听保持模型 1× 自然速率，不做后期变速。Windows 系统语音继续作为离线保底；在线正式转换前必须确认正文会发送给第三方服务。详见 [`specification/TTS_PROVIDERS.md`](specification/TTS_PROVIDERS.md)。

听读工坊的新任务不再继承上次书名、作者或封面；仅当用户再次选择成功转换过的完全相同来源文件集合时，才从最近 50 条本机记录回填书名/作者。Windows 系统语音已改为实测可用的 `System.Speech` 实时试听与合成；误留在所有页面顶栏的全局“在线音色/离线处理”标识已移除。Edge 支持 1–12 个并发请求（新任务默认 8、旧断点保持 1），缓存和输出顺序不变；0.6.3 公开基准确认 8 并发比 12 并发更快且压力更低。

听读工坊 0.5.0 增加“保存进度并关闭 / 恢复未完成”、原子句子缓存、已编码 Track 复用、20 GiB/180 天自动缓存整理和可视化缓存管理；成功任务会立即删除重复 PCM/Track 工作文件。AISHELL-3、Kokoro 的 ETA 已改用本机端到端实测速率，并在每句完成时动态校准。详见 [`specification/BUILDER_RESUME_CACHE.md`](specification/BUILDER_RESUME_CACHE.md)。
