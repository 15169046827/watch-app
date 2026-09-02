# 听读工坊 0.6.x：Vue / Tauri 桌面界面

## 目标与边界

0.6.0 只替换 Windows 展示与桌面集成层，不改 `.opbook` v1、正文/Timeline/Manifest 权威关系、TTS Provider、FFmpeg 编码、安全解析、缓存键或 Watch App。正式入口仍为兼容路径 `X3AudiobookBuilder.exe`，CLI 仍为 `X3BuilderCLI.exe`。

## 架构

- `builder/ui`：Vue 3.5 + TypeScript + PrimeVue 4.5（Aura）单页界面；提供制作新书、音色试听、任务续作和存储管理四个一级页面。
- `builder/ui/src-tauri`：Tauri 2.11 Windows 外壳；负责原生文件夹/文件选择、启动本地 Java CLI、转发进度事件和窗口关闭生命周期。
- `BuilderBridge`：Java 转换核心的 JSON/JSON-lines 边界。状态、预生成试听、缓存整理为一次性请求；转换任务持续输出 `estimate/progress/paused/failed/complete` 事件。
- `AudiobookBuilder`：唯一转换实现。Vue/Rust 不解析书籍、不生成语音、不编码媒体，也不写 `.opbook` 内容。

正式打包先用 jpackage 生成 Java 运行时与 `X3BuilderCLI.exe`，再用 Tauri EXE 替换同路径主 GUI。这样保持开始菜单和脚本兼容；0.6.4 起缓存、断点与未完成任务迁移到独立用户数据目录，不再依附可替换的 app-image。

## 断点与进程生命周期

每个 Tauri 转换任务只在独立工作根目录的 `ui-requests` 创建短期请求 JSON 和控制文件。Tauri 把同一个绝对工作根目录通过 `X3_BUILDER_WORK_ROOT` 传给 Java，后端拒绝工作目录外的请求/控制文件。点击“保存进度”、点击“保存并关闭”或在转换中关闭窗口时，Rust 只创建控制文件；Java 在安全检查点停止 TTS/FFmpeg、保存 checkpoint 并关闭 Provider 后，窗口才退出。请求与控制文件结束后删除，私人正文不写入日志。

恢复任务从 Java checkpoint 读取原始选项。界面只展示最新未完成任务；现有句子 WAV、已编码 Track 与任务目录继续使用 0.5.0 的原子缓存和 20 GiB/180 天整理规则。

## 进度与 ETA

- 分析完成后以 10% 为流水线基线；语音按全书已完成字符数贡献 68%，章节编码按已完成章节贡献 12%，两条可并行工作量合计最高 90%。
- 已有句子缓存属于真实完成工作，完整缓存命中会显示 78% 并明确提示“跳过语音生成”；新任务没有缓存时保持约 10%，不会仅因进入第一章等待阶段跳到 79%。
- 恢复任务先把旧 checkpoint 显示值归零，再按有效句子缓存和可复用 Track 重算，避免旧版错误百分比形成不可回退的下限；同一次执行内 Java、桥接层与 Vue 仍保证百分比单调不回退。
- 界面显示已执行时间、动态总预计与剩余时间；观察值只在进度真正前进时平滑修正。

## 用户偏好与隐私

界面本地保存作者、标题、Provider、音色、语速、句间停顿、导出方式、输出目录，以及输入/封面选择器上次目录；不会保存输入书目列表。在线 Edge 仍在每次新任务开始前显示正文传输确认。CSP 默认仅允许本地资源和 `data:` 音频；预生成试听不加载模型、不联网。无 API Key、账号、签名密钥或私人正文进入源码和文档。

## 依赖与运行条件

前端锁定 Vue 3.5.41、PrimeVue 4.5.5、PrimeIcons 7.0.0、PrimeUIX Themes 1.2.5、Vite 8.2.2；桌面层为 Tauri 2.11 系列。PrimeVue 4 用于稳定的 MIT 组件线，避免把本次迁移与新主版本风险捆绑。Windows 需要系统 WebView2；转换核心仍随包携带 Java 17 和已验证的 TTS/FFmpeg 环境。

## 0.6.2 完成态与长列表收尾（2026-08-27）

- 运行中保留“已用时 / 预计总用时 / 预计剩余”。`complete` 事件提供 `taskElapsedSeconds` 与 `runElapsedSeconds`，完成态改为“任务总时长 / 本次执行总时长”。
- checkpoint 新增累计实际执行秒数；每次暂停、失败或进度里程碑原子更新。恢复后继续累计，暂停期间不计入；旧 checkpoint 未包含字段时按 0 读取。Edge 的临时 `NoAudioReceived` 已按“WebSocket 正常结束但上游未下发音频”处理：保持所选音色和朗读文本不变，在约 20 分钟内最多自动恢复 25 次，前五次为 2/5/10/20/40 秒、之后封顶 60 秒。用户保存/关闭时等待及 Python 子进程可中断；持续失败才通过同一暂停事件保存断点，无效参数仍进入失败态。
- 日志保留当前会话的全部消息，不再在模板层裁剪最后八条；固定 240px 高度、内部滚动并自动定位最新消息。
- Provider 卡统一限制在 420–560px 之间，音色按钮在各卡内部独立纵向滚动，避免大量音色撑高页面。
- Java 40 项测试、Vue 类型检查/生产构建与 Rust release 测试/构建通过；正式入口仍为 Windows GUI 子系统。

## 0.6.4 数据目录隔离（2026-09-01）

- 默认工作根目录改为 `%LOCALAPPDATA%\Canace\X3AudiobookBuilder\work`；正式包内不得存在 `.work`。
- Java 统一解析缓存、checkpoint、书籍工作目录、Windows 实时试听和 UI 请求路径；Tauri 使用相同绝对路径启动后端。
- 旧数据迁移采用临时副本、长度与 SHA-256 双重校验、原子提交和冲突即停。打包脚本在删除旧 app-image 前完成迁移，因此失败不会破坏旧缓存或断点。
- 制作页表单移除输入组件的固有最小宽度；“声音与节奏”的双数字字段使用可自适应网格，卡片宽度不足时自动切换为单列。
- 正式 0.6.4 验证覆盖 53 项 Java 测试、Vue 生产构建、Rust release 测试/构建、1,105 个既有文件迁移、CLI/bridge、GUI 启停、PE GUI 子系统、源码/正式 JAR 一致性、开始菜单目标和公开 tone `.opbook` 全套格式/攻击校验。

## 0.6.1 回归修复（2026-08-27）

- 试听播放器是单实例资源。新试听在请求前停止旧音频；Provider/音色变化和窗口销毁也会停止；请求序号阻止较慢的旧请求在新请求之后播放。
- ETA 不再用静止的总进度每秒外推。文本/缓存估算仍是初值并包含解析耗时，只有收到更大的进度值才采样实际吞吐，观察值在合理边界内以 15% 权重平滑合入。
- 正式入口的 Windows PE Subsystem 为 2（GUI）；后台 Java 子进程保留 `CREATE_NO_WINDOW`。
- 正式包版本与桥接状态均为 0.6.1；Java 28 项测试、Vue 构建、Rust release 测试/构建全部通过。
- 完整包已验证 Melo、AISHELL-3/Kokoro、Edge 和 FFmpeg；重建过程保留工作缓存与可恢复任务。

## 0.6.0 验收证据

- Java：28 tests，0 failures/errors/skips；包括进度 10/44/78 字符权重回归。
- Vue：`vue-tsc -b --noEmit` 与 Vite production build 通过；npm audit 为 0 vulnerabilities。
- Tauri：Windows release profile 构建通过，正式入口使用 GUI 子系统。
- 公开样例桥接转换成功；21 个事件完成，百分比序列 `0,3,7,8,9,10,78,79,79,85,85,85,85,90,90,91,95,99,100` 单调不回退。
- 正式 UI 人工检查：主制作页、预生成音色入口、任务续作、存储统计与中文编码通过；可恢复任务能够呈现且不会被验证流程自动启动。
- 正式升级验证确认工作数据完整迁移到独立目录、app-image 内无 `.work`，开始菜单目标路径不变。

## 已知限制

任务页第一版只展示最新 checkpoint，不做多任务队列；转换仍受 CPU TTS 的真实吞吐限制，UI 迁移本身不提高模型推理速度。WebView2 缺失或损坏时需要修复 Windows WebView2 Runtime。Watch App 与外部设备不在本里程碑范围。
