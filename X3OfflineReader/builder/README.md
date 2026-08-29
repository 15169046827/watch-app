# 听读工坊（Windows Audiobook Builder）

流水线为：`Vue/PrimeVue GUI → Tauri 本地桥接 → Java CLI/BuilderCore → SourceParser → TextNormalizer/SentenceSegmenter → TtsProvider → WAV Timeline → FFmpeg → OpbookWriter`。

已实现：

- 多选、排序并合并 EPUB/TXT 为多卷 `.opbook`，或 `--separate` 分别导出。
- UTF-8/BOM、UTF-16LE、GB18030 TXT；安全 EPUB 2/3 spine 解析；允许常见 XHTML `DOCTYPE`，`&nbsp;` 等命名实体从 JDK 内置安全表本地转换，但禁止加载外部 DTD/外部实体并拒绝内部实体子集；明确拒绝加密/DRM 及超限 XML。
- 默认 MeloTTS（自适应本地长驻 worker 池），Windows SAPI 保底，以及只供确定性测试的 tone provider。
- 句级 WAV 真实时长生成 Timeline，再拼接并编码 AAC-LC/M4A；章节超过固定 20 分钟时在句子边界自动拆 Track。该内部保护值不在 GUI/CLI 暴露。
- 512×512 WebP 封面、稳定 ID、完整 SHA-256 和确定顺序 ZIP。

## 0.6.2 界面收尾

- 转换进行中显示“已用时 / 预计总用时 / 预计剩余”；完成后切换为“任务总时长 / 本次执行总时长”。任务总时长累计各次实际执行时间，不把暂停等待计入；旧断点缺少累计字段时安全按 0 秒兼容。
- 当前转换会话的日志不再只展示最后八行；完整记录保留到下一次任务开始，并在固定高度区域内滚动、自动定位最新消息。
- 每个 Provider 音色卡使用统一高度；Kokoro、AISHELL-3 等大量音色在卡片内部滚动，不再撑长整个试听页面。
- 修复 AISHELL-3/Kokoro 中文规范化时把字符数组对象标识传给模型的问题；离线转换与试听都恢复为自然中文，并用新版 Provider 缓存键隔离旧错误句子缓存。
- 本地试听重建为 286 条：Melo 1、Kokoro 中文 100、AISHELL-3 174、Edge 中文 11。
- `builder/voice-previews/` 是由 `generate-voice-previews*.ps1` 生成的本机打包资源，Git 仅保留生成代码与说明；正式包仍嵌入完整索引和 WAV。
- 正式包继续使用 Windows GUI 子系统和隐藏 Java 子进程；`.opbook` v1、Provider、缓存键、可执行文件名和工作目录保持兼容。

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
- 正式打包保留 `X3AudiobookBuilder.exe`、`X3BuilderCLI.exe`、开始菜单路径和 `builder/.work`。完整设计与限制见 [`../specification/BUILDER_UI_VUE_TAURI.md`](../specification/BUILDER_UI_VUE_TAURI.md)。

## GUI 使用习惯

- 点击“开始转换”不会重置表单。作者、TTS、各 Provider 的声音 ID、语速和导出模式会保存到当前 Windows 用户设置；作者留空时包内写为“未署名”，不会再拼接 `Unknown`。
- 输入、输出和封面选择器分别记住上次目录；首次使用从“下载”或用户主目录开始，不再固定打开“文档”。
- 勾选“输出到源目录”时，合并导出写到第一个源文件目录，分别导出则让每个 `.opbook` 回到对应源文件目录；取消勾选会恢复此前手动选择的文件或目录。
- 转换区显示 0–100% 进度条和带耗时的里程碑日志，包括解析统计、语音句数/缓存命中、逐章 M4A 编码、封面和最终打包。
- 进度条下方动态显示预计总用时和预计剩余时间。初值来自 TTS 类型、语速、未缓存文本字符数、缓存句数和章节数；流水线进度由语音字符完成度 68% 与章节编码完成度 12% 共同组成，已有缓存属于真实已完成工作。恢复任务会丢弃旧版错误百分比并按缓存与 Track 重新计算，只有进度真正前进时才平滑校准 ETA。
- “Melo 性能”提供“加速（推荐，最多 2 进程）”与“节省内存（1 进程）”。程序按 CPU、总内存和转换开始时的可用内存自动降级；每个进程只持有一个模型，并限制 PyTorch/OMP 线程，避免盲目超卖 CPU。i7-1360P / 32 GB 的同工作量未缓存端到端基准为单进程 78.78 秒、双进程每个 8 线程 63.65 秒；三进程 5 线程为 68.16 秒、三进程 8 线程为 71.32 秒，均慢于双进程，因此生产界面不提供三进程。双进程至少需要 10 GiB 可用内存，条件不足时安全回退。实际工作进程数写入里程碑日志。
- 0.3.0 会预取后续章节的句级语音，在当前章节编码 M4A 时继续使用 Melo worker，减少章节边界的空闲时间；缓存键、Timeline 顺序和包内顺序不变。
- 随包提供 286 条版本化本地试听：Melo 1、Kokoro 中文 100、AISHELL-3 174、Edge 中文 11。点击“试听”只播放 16 kHz 单声道 PCM WAV，不加载模型、不临时合成、不联网。所有样本保持 Provider 的 1× 自然速率；不会把长句强行加速。
- 在线 Edge 转换会把待朗读正文发送给 Microsoft 服务。GUI 会在开始前再次确认，CLI 必须显式传入 `--allow-online-tts`；Builder 不保存 API Key、账号或正文日志。安装额外组件运行 `builder\install-tts-voices.ps1`，许可与数据来源见 [`THIRD_PARTY_TTS.md`](THIRD_PARTY_TTS.md)。
- Edge online conversion pins edge-tts 7.2.8 and uses a Windows-compatible event loop. Unpunctuated fragments receive a sentence-final mark in the spoken copy; transient no-audio retries use six semantically equivalent punctuation/quotation payloads with bounded 2/5/10/20/40-second backoff instead of resending an identical upstream key. If all variants fail, the task preserves its sentence/Track cache and automatically changes to paused; invalid parameters still fail immediately, and the Builder never silently switches voices.
- 声音改为选择框；存在预生成索引时仅显示通过时长门槛的可试听音色。当前 Melo 中文仅有 `ZH`，Windows SAPI 列出本机声音，tone 仅用于测试且不会出现在 GUI。
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

`--melo-workers 1|2` 设置允许的最大工作进程数，仍受资源保护规则限制。`--tts windows` 使用 SAPI；先运行 `voices` 查看可用声音。`--tts tone` 仅用于 Golden/回归测试。GUI 使用同一转换核心，不另写转换逻辑。

## Melo 与便携包

首次准备源码工作区运行：

```powershell
.\builder\melo\setup-melo.ps1
```

脚本把 Python 3.9、官方 MeloTTS、中文模型、Hugging Face 与 NLTK 缓存固定在被忽略的 `.tools\melo`。安装/预取需要网络，之后转换不需要网络、API Key 或账号。详情见 [`melo/README.md`](melo/README.md)。

```powershell
.\builder\package-windows.ps1
```

便携镜像约 4.14GB，内含 Java、FFmpeg 和 Melo 模型；`-WithoutMelo` 可生成较小的 SAPI/tone 版本。构建缓存位于 `builder\.work`，键包含 provider、声音、语速和规范化句子；不得把私人正文、日志或生成包提交到源码。

重建便携镜像时脚本会临时备份并恢复旧镜像的句级 WAV 缓存，避免修复升级后从头合成；若打包中途失败，备份会保留在 `builder\build\windows-cache-preserve-*`，不会静默删除。
