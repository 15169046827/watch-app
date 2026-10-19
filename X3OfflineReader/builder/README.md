# 听读工坊（Windows Audiobook Builder）

流水线为：`Vue/PrimeVue GUI → Tauri 本地桥接 → Java CLI/BuilderCore → SourceParser → TextNormalizer/SentenceSegmenter → TtsProvider → WAV Timeline → FFmpeg → OpbookWriter`。

桌面 GUI 必须用 `ui` 目录下的 `npm run desktop:build` 构建（Tauri CLI + `custom-protocol`），将生产页面内嵌到 EXE。普通 `cargo build --release` 缺少该选项会直接失败，不能把仅带开发页面地址的 EXE 部署给用户。两个打包脚本统一调用同一入口；验收必须在未启动 Vite 时确认实际制作页、存储页和更新状态正常，不能仅用进程存在/窗口响应判定通过。Release Rust 验证需带 `--features custom-protocol`。

便携升级验证使用 `builder/test-portable-upgrade.ps1 -OldApp '<旧版程序绝对路径>' -NewApp '<新版程序绝对路径>'`（PowerShell 7）。脚本仅在随机临时目录生成公开 tone 样本：旧版中途保存、新版续作，核对缓存哈希和修改时间均不变，并验证输出包；另直接执行预览打包脚本的原有提交事务，注入归档工具缺失和校验文件提交失败，确认旧程序、旧 ZIP 与校验文件均恢复。只测回退可使用 `-RollbackOnly`；不会读取、恢复或清理真实书籍任务。

打包复制会排除 Python 字节码、缓存目录、Git 元数据、任务工作目录、Conda 安装历史及日志/临时文件；保留运行所需 Python 源码、包许可元数据和离线模型。预览 ZIP 在提交前再次检查这些禁入路径，不会把本机运行生成的缓存当成发布内容。

已实现：

## EPUB 逻辑章节修复

- 根据正文中的独立编号标题拆分逻辑章节；一个 EPUB spine 文件可以包含多章，拆分文件开头的续文可继续归入上一章。同章的重复英文标题不会额外产生章节。
- 章节名优先使用正文标题与 NCX / EPUB 3 导航名称，拒绝把 HTML 页面的哈希或内部代码当成可朗读标题；保留卷首和简介内容。
- 完整规则、兼容边界及旧文件处理见 [EPUB 逻辑章节与标题](../specification/EPUB_CHAPTERS.md)。本机修复统一沿用原有“听读工坊”入口；测试结束后清理测试样本和临时入口。

## Edge 握手 403 修复

- 随包客户端基线为 edge-tts 7.3.1，使用上游修正后的 Sec-MS-GEC v2 协议。7.2.8 的旧握手在服务变更后可能持续返回 403，无法靠延长等待恢复。
- HTTP 401/403 立即保存并暂停，提示更新组件或检查服务，不再误报成“持续恢复 20 分钟仍未收到音频”。空音频、限流和临时网络错误仍按原策略重试。
- 诊断包含 HTTP 状态及客户端版本，握手错误不再输出带请求参数的完整 URL。原音色、句子缓存和 EPUB 章节修复保持兼容。

## 0.7.0 公共安装版数据与签名

- Edge 连接组件支持独立自动更新，不必为兼容的客户端更新重新编译工坊。“存储管理 → 在线语音组件”显示实际版本和检查结果，可关闭自动检查或手动检查；启动时每天最多检查一次，转换中不切换。
- 仅从官方 PyPI 下载同主版本正式 wheel，校验 SHA-256、路径/大小、Python 与现有依赖兼容性，再用固定公开中文短句合成并解码验证，成功才原子启用。不运行 pip，不改模型、缓存或断点；失败保留当前组件。
- 握手 401/403 保存并停止后，自动检查开启时会额外检查一次，但不自动恢复私人转换。跨主版本、依赖不兼容或未知协议仍可能需要更新工坊；详细边界见 [在线组件更新](../specification/EDGE_CLIENT_UPDATES.md)。

- 首次启动明确说明本机断点、缓存、界面偏好和最近 50 项来源识别历史；在线 Edge 仍对每个新任务单独确认，离线 Provider 不发送正文。
- “存储管理”可清除界面设置与来源识别历史；清理超过 30 天的未完成任务新增明确确认。缓存、断点、源文件和输出文件的影响分别标示，完整边界见 [`../specification/DATA_LIFECYCLE.md`](../specification/DATA_LIFECYCLE.md)。
- 正式 Windows 打包在任何构建或包替换前检查 Authenticode 证书 SHA-256、HTTPS 时间戳和 `signtool.exe`。GUI 与 CLI 启动程序签名后必须独立验签；缺少正式材料时失败关闭，不覆盖现有包。
- WARS 1.1 个人 GitHub 预览使用独立 `package-windows-preview.ps1`：只接受干净的 X3OfflineReader 工作树，确认 GUI/CLI 均为未签名，写入源提交和未知发布者说明，输出带 `UNSIGNED-PREVIEW` 的便携 ZIP 与 SHA-256；不生成安装器，也不覆盖正式包。

- 多选、排序并合并 EPUB/TXT 为多卷 `.opbook`，或 `--separate` 分别导出。
- UTF-8/BOM、UTF-16LE、GB18030 TXT；安全 EPUB 2/3 spine 解析；允许常见 XHTML `DOCTYPE`，`&nbsp;` 等命名实体从 JDK 内置安全表本地转换，但禁止加载外部 DTD/外部实体并拒绝内部实体子集；明确拒绝加密/DRM 及超限 XML。
- 默认 MeloTTS（自适应本地长驻 worker 池），Windows `System.Speech` 保底，以及只供确定性测试的 tone provider。
- 句级 WAV 真实时长生成 Timeline，再拼接并编码 AAC-LC/M4A；章节超过固定 20 分钟时在句子边界自动拆 Track。该内部保护值不在 GUI/CLI 暴露。
- 512×512 WebP 封面、稳定 ID、完整 SHA-256 和确定顺序 ZIP。

## 0.6.4 独立任务数据目录

- 正式 GUI/CLI 的句子缓存、checkpoint、未完成 Track 和短期控制文件统一位于 `%LOCALAPPDATA%\Canace\X3AudiobookBuilder\work`，不再写入可被 Gradle `clean` 或程序重打包删除的 app-image。
- 首次启动或正式打包会迁移旧 `builder/.work`。每个文件先复制到随机临时名并对源文件和副本计算 SHA-256；已存在的相同文件直接复用，同名不同内容立即停止。只有整棵目录验证完成后才删除旧副本。
- 打包脚本在删除旧正式包之前迁移源码侧和旧包侧工作目录；迁移失败、工坊仍运行或目标冲突时拒绝替换。新正式包中明确禁止残留 `builder/.work`。
- 自定义数据磁盘可设置绝对路径环境变量 `X3_BUILDER_WORK_ROOT`；打包时也可传 `-WorkRoot`。相对路径会被拒绝，避免 GUI 和 Java 后端写入不同目录。

## 0.6.3 全 Provider 性能优化

- Windows `System.Speech` 改为可复用的 32 位长驻 worker 池，避免每句重启 PowerShell；本机 2 进程长篇端到端实测约 108.3 万字/小时，继续增加进程反而变慢。
- Kokoro 与 AISHELL-3 改为独立长驻模型进程池，并按 CPU 数限制每个模型的线程预算；本机 4 进程分别约 8.3 万与 46.5 万字/小时。
- Melo 保留已验证的 2 进程上限，长篇样本约 7.4 万字/小时；Edge 保留可靠性更好的 8 并发，约 83.3 万字/小时，12 并发无额外收益。
- 模型选择框直接显示“本机长篇实测”效率；初始 ETA 使用同一组端到端实测曲线，不再沿用理论速度因子。公开可复现脚本为 `benchmark-tts.ps1`，其输出和临时成书位于被忽略的工作目录并在每轮后清理。

## 0.6.2 界面收尾

- 转换进行中显示“已用时 / 预计总用时 / 预计剩余”；完成后切换为“任务总时长 / 本次执行总时长”。任务总时长累计各次实际执行时间，不把暂停等待计入；旧断点缺少累计字段时安全按 0 秒兼容。
- 当前转换会话的日志不再只展示最后八行；完整记录保留到下一次任务开始，并在固定高度区域内滚动、自动定位最新消息。
- 每个 Provider 音色卡使用统一高度；Kokoro、AISHELL-3 等大量音色在卡片内部滚动，不再撑长整个试听页面。
- “声音与节奏”的表单和 PrimeVue 输入组件允许随卡片收缩；朗读速度与句间停顿会在空间不足时自动改为上下排列，最小窗口不再裁掉第二个输入框。
- 修复 AISHELL-3/Kokoro 中文规范化时把字符数组对象标识传给模型的问题；离线转换与试听都恢复为自然中文，并用新版 Provider 缓存键隔离旧错误句子缓存。
- 本地试听重建为 286 条：Melo 1、Kokoro 中文 100、AISHELL-3 174、Edge 中文 11。
- 新任务的书名、作者和封面始终从空白开始，不再从上次任务偏好中残留；成功转换后只按完全相同的本机来源文件集合保留最近 50 组书名/作者，下次再次选择同一来源时才回填。成功页会立即清空来源与书籍信息，旧版偏好中的书名/作者/封面会被忽略。
- Windows 系统语音改用本机已验证的 32 位 `System.Speech`，可在试听页实时生成中文短句；其余 Provider 继续使用随包预生成试听。各页面右上角不再显示与当前页面无关的全局“在线音色/离线处理”标识。
- Edge 新任务默认 8 个并发请求，可在 GUI 或 `--edge-workers 1..12` 调整；旧断点缺少该字段时仍按 1 个请求恢复。并发只改变调度，不改变音频缓存键、Timeline、章节和包内顺序；重试加入小幅错峰，避免所有请求同时再次冲击在线服务。
- `builder/voice-previews/` 是由 `generate-voice-previews*.ps1` 生成的本机打包资源，Git 仅保留生成代码与说明；正式包仍嵌入完整索引和 WAV。
- 正式包继续使用 Windows GUI 子系统和隐藏 Java 子进程；`.opbook` v1、Provider、缓存键和可执行文件名保持兼容。0.6.4 会把旧工作目录自动迁移到独立用户数据目录。

## 0.6.1 回归修复

- 音色试听由单一播放器控制：点击新试听、切换 Provider/音色或关闭窗口时，立即暂停并释放上一段；异步返回的旧试听请求会被丢弃，避免叠音。
- ETA 初值继续来自文本长度、缓存、Provider、语速和章节数，并计入前期解析时间；运行中只在百分比实际前进时按 85% 原估算 + 15% 观察值平滑校准，停滞期间不再每秒放大总预计。
- Tauri 正式二进制明确编译为 Windows GUI 子系统；Java 后台仍使用 `CREATE_NO_WINDOW`，因此主界面和转换核心均不创建命令行窗口。
- 0.6.1 完整包恢复并验证 Melo、AISHELL-3/Kokoro、Edge 与 FFmpeg；重建过程保留 `builder/.work` 中的缓存与可恢复任务。

## 0.6.0 桌面界面

- `builder/ui` 使用 Vue 3.5、PrimeVue 4.5（Aura）和 Tauri 2.11；运行时不依赖远程网页或 CDN。
- Tauri 只负责原生文件选择、窗口生命周期和 Java 子进程事件转发。`BuilderBridge` 提供 JSON/JSON-lines 边界，`.opbook` 仍只由 Java 核心生成。
- 一级页面为“制作新书 / 音色试听 / 任务与续作 / 存储管理”。设置保存到本机 WebView 配置，输入书目列表不持久化；输入、输出和封面选择器保留上次目录。
- 点击“保存进度”或转换中关闭窗口时，程序创建安全控制文件并等待 Java 保存 checkpoint、关闭 TTS/FFmpeg 后再退出。
- 正式打包保留 `X3AudiobookBuilder.exe`、`X3BuilderCLI.exe` 和开始菜单路径；0.6.4 起工作数据独立于 app-image。完整设计与限制见 [`../specification/BUILDER_UI_VUE_TAURI.md`](../specification/BUILDER_UI_VUE_TAURI.md)。

## GUI 使用习惯

- TTS、各 Provider 的声音 ID、语速和导出模式会保存到当前 Windows 用户设置；书名、作者和封面不属于全局偏好，每个新来源默认留空。成功转换过的完全相同来源可从最近本机记录回填书名/作者；作者仍留空时包内写为“未署名”。
- 输入、输出和封面选择器分别记住上次目录；首次使用从“下载”或用户主目录开始，不再固定打开“文档”。
- 勾选“输出到源目录”时，合并导出写到第一个源文件目录，分别导出则让每个 `.opbook` 回到对应源文件目录；取消勾选会恢复此前手动选择的文件或目录。
- 转换区显示 0–100% 进度条和带耗时的里程碑日志，包括解析统计、语音句数/缓存命中、逐章 M4A 编码、封面和最终打包。
- 进度条下方动态显示预计总用时和预计剩余时间。初值来自 TTS 类型、语速、未缓存文本字符数、缓存句数和章节数；流水线进度由语音字符完成度 68% 与章节编码完成度 12% 共同组成，已有缓存属于真实已完成工作。恢复任务会丢弃旧版错误百分比并按缓存与 Track 重新计算，只有进度真正前进时才平滑校准 ETA。
- “Melo 性能”提供“加速（推荐，最多 2 进程）”与“节省内存（1 进程）”。程序按 CPU、总内存和转换开始时的可用内存自动降级；每个进程只持有一个模型，并限制 PyTorch/OMP 线程，避免盲目超卖 CPU。i7-1360P / 32 GB 的同工作量未缓存端到端基准为单进程 78.78 秒、双进程每个 8 线程 63.65 秒；三进程 5 线程为 68.16 秒、三进程 8 线程为 71.32 秒，均慢于双进程，因此生产界面不提供三进程。双进程至少需要 10 GiB 可用内存，条件不足时安全回退。实际工作进程数写入里程碑日志。
- 0.3.0 会预取后续章节的句级语音，在当前章节编码 M4A 时继续使用 Melo worker，减少章节边界的空闲时间；缓存键、Timeline 顺序和包内顺序不变。
- 随包提供 286 条版本化试听：Melo 1、Kokoro 中文 100、AISHELL-3 174、Edge 中文 11。以上样本点击后直接播放 16 kHz 单声道 PCM WAV；Windows 系统语音不随包复制音频，而是在本机实时生成短句。所有样本保持 Provider 的 1× 自然速率；不会把长句强行加速。
- 在线 Edge 转换会把待朗读正文发送给 Microsoft 服务。GUI 会在开始前再次确认，CLI 必须显式传入 `--allow-online-tts`；Builder 不保存 API Key、账号或正文日志。安装额外组件运行 `builder\install-tts-voices.ps1`，许可与数据来源见 [`THIRD_PARTY_TTS.md`](THIRD_PARTY_TTS.md)。
- Edge online conversion pins edge-tts 7.3.1 and uses a Windows-compatible event loop. 本次真实 RCA 证明 `NoAudioReceived` 不一定是上游瞬时故障：旧切句器会在句号处截断，把随后的右引号/右括号拆成纯标点“句子”，这类确定性不可发音请求重试 25 次仍必然失败。切句器现在把闭合标点并回前句、过滤纯标点片段；Edge Provider 还会在联网前快速拒绝不含文字或数字的请求，并正确识别引号/括号内的句末标点。只有包含可发音内容却收到空音频的请求才进入可中断的 25 次恢复窗口；缓存、音色和正文均不会被静默替换。
- 声音使用选择框；存在预生成索引时仅显示通过时长门槛的可试听音色。当前 Melo 中文仅有 `ZH`，Windows `System.Speech` 动态列出本机中文声音并支持实时试听，tone 仅用于测试且不会出现在 GUI。
- 进度区同时显示开始时间、总耗时和当前阶段耗时；超过一小时明确写“小时/分”。连续 10 分钟无真实进度会提示观察，Melo 单句、FFmpeg 单 Track 超过 12 分钟会只重启该步骤并自动重试一次，打包失败只重建 staging，已完成缓存保留。
- 合并输出默认命名为 `书名 [作者].opbook`；标题为空时使用首个源文件名，作者为空时使用“未署名”。Windows 文件名非法字符会安全替换，用户手动选定的名称仍会保留。
- Melo 会把超长句安全拆成短片段后无间隔拼回同一句 WAV；较短片段失败时会进一步拆分或重启 worker 重试。失败信息现在包含异常类型和有限的诊断尾部，不再只显示空白 `ERROR`，且不会记录正文。
- 中文 Melo 的朗读副本会先做 NFKC 规范化，并把下划线、Emoji、扩展汉字及其他前端不支持字符替换为停顿；基本汉字若仍触发 G2P 断言，会逐字检查并只替换无法注音的字符。正文与 Timeline 保持原文不变。

## CLI

```powershell
.\builder\build\install\builder\bin\builder.bat --help
.\builder\build\install\builder\bin\builder.bat build `
  --input .\book-1.epub --input .\book-2.txt `
  --output .\merged.opbook --title '合并书名' `
  --tts melo --voice ZH --melo-workers 2
```

`--melo-workers 1|2`、`--sherpa-workers 1..4`、`--windows-workers 1..12` 与 `--edge-workers 1..12` 设置最大并发，仍受资源保护规则限制；实测默认分别为 2、4、2、8。`--tts windows` 使用 SAPI；先运行 `voices` 查看可用声音。`--tts tone` 仅用于 Golden/回归测试。GUI 使用同一转换核心，不另写转换逻辑。

## Melo 与便携包

首次准备源码工作区运行：

```powershell
.\builder\melo\setup-melo.ps1
```

脚本把 Python 3.11、固定提交且经 SHA-256 校验的官方 MeloTTS、中文模型、Hugging Face 与 NLTK 缓存放在被忽略的 `.tools\melo`。安装/预取需要网络，之后转换不需要网络、API Key 或账号。详情见 [`melo/README.md`](melo/README.md)。

```powershell
.\builder\package-windows-preview.ps1
.\builder\package-windows.ps1
```

便携镜像内含 Java、FFmpeg 和所选离线模型。Edge 与 Sherpa 共用 Melo 目录中的便携 Python，因此较小版本必须同时指定 `-WithoutMelo -WithoutAdditionalVoices`；脚本拒绝会留下缺失运行时组件的组合。预览脚本输出 `build\X3AudiobookBuilder-0.7.0-UNSIGNED-PREVIEW.zip` 及 `.sha256`，结论只能写 LIMITED_PREVIEW。正式程序的持久数据默认位于 `%LOCALAPPDATA%\Canace\X3AudiobookBuilder\work`，缓存键包含 provider、声音、语速和规范化句子；不得把私人正文、日志或生成包提交到源码。

重建便携镜像前，脚本会先验证 `X3_WINDOWS_SIGNING_CERT_SHA256`、`X3_WINDOWS_TIMESTAMP_URL`（HTTPS）和 `X3_WINDOWS_SIGNTOOL_PATH`，并确认当前用户证书库内有匹配 SHA-256 且含私钥的证书；条件不足时在构建和删除旧包前停止。成功构建后会签署并独立验证 `X3AudiobookBuilder.exe` 与 `X3BuilderCLI.exe`。

脚本随后把旧源码/正式包中的工作数据校验迁移到独立目录；失败会在删除正式包之前终止。迁移后 app-image 只含可重建的程序和模型文件。
