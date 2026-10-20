const { mkdirSync, writeFileSync } = require('node:fs')
const { join } = require('node:path')

const root = join(__dirname, '..')
const output = join(root, 'docs', 'api')
mkdirSync(output, { recursive: true })

const sourceCommit = 'e04548fe0553e6e6d9769fd1c9dc0c4db1eb84d7'
const folders = [
  { key: 'root', name: 'Watch-App API' },
  { key: 'ble', name: '01 跨设备 BLE', parent: 'root' },
  { key: 'ble.ledger', name: '记账', parent: 'ble' },
  { key: 'ble.schedule', name: '日程', parent: 'ble' },
  { key: 'ble.probe', name: '能力探针', parent: 'ble' },
  { key: 'desktop', name: '02 Windows IPC 与 Worker', parent: 'root' },
  { key: 'desktop.cli', name: '听读工坊 CLI', parent: 'desktop' },
  { key: 'desktop.tauri', name: '听读工坊 Tauri IPC', parent: 'desktop' },
  { key: 'desktop.bridge', name: '听读工坊 Java Bridge', parent: 'desktop' },
  { key: 'desktop.worker', name: '听读工坊 TTS Worker', parent: 'desktop' },
  { key: 'http', name: '03 HTTP 外部集成', parent: 'root' },
  { key: 'http.wolai', name: 'Wolai 笔记', parent: 'http' },
  { key: 'http.wolai.api', name: 'Wolai Open API', parent: 'http.wolai' },
  { key: 'http.reader', name: '听读工坊组件更新', parent: 'http' },
  { key: 'file', name: '04 文件与备份协议', parent: 'root' },
  { key: 'file.ledger', name: '记账', parent: 'file' },
  { key: 'file.notes', name: '笔记', parent: 'file' },
  { key: 'file.reader', name: '听读', parent: 'file' },
  { key: 'file.schedule', name: '日程', parent: 'file' },
  { key: 'android', name: '05 Android 系统回调', parent: 'root' },
  { key: 'android.ledger', name: '记账', parent: 'android' },
  { key: 'android.schedule', name: '日程', parent: 'android' },
  { key: 'android.probe', name: '能力探针', parent: 'android' },
  { key: 'guide', name: '90 规范与模型', parent: 'root' }
]

function entry(key, name, protocol, path, group, summary, request, response, errors, source) {
  return {
    key, name, protocol, path, group,
    content: `# ${name}\n\n${summary}\n\n## 请求 / 输入\n\n${request}\n\n## 返回 / 输出\n\n${response}\n\n## 失败与约束\n\n${errors}\n\n## 权威来源\n\n${source}\n\n源码基线：\`${sourceCommit}\`。该条目描述真实通信边界，不代表存在同名 HTTP 服务。`
  }
}

const interfaces = [
  entry('ledger.ble.data', '读取待同步账目', 'BLE GATT', 'voiceledger.ble.data.read', 'ble.ledger',
    '手表作为 GATT Server，手机作为 Central；服务 `7bc02101-9d38-4f90-83a2-4f77f3f0a100`，DATA 特征 `...2102`，使用已配对设备的加密 ATT。',
    '手机读取 DATA；无额外请求体。',
    'UTF-8 JSON。`type=ledger,schema=2` 时包含 UUID、创建/修改/交易时间、分币金额、备注、分类、账户、账本、标签、来源及可选位置；无待同步记录时为 `{"type":"empty","schema":2}`。',
    '只向当前授权设备提供；字段、长度和坐标均受模型校验。读取不等于提交，必须等待 ACK。',
    '`X3VoiceLedger/docs/BLE_SYNC.md`、`SyncProtocol.java`、`LedgerPayloadCodec.java`'),
  entry('ledger.ble.ack', '确认账目已接收', 'BLE GATT', 'voiceledger.ble.ack.write', 'ble.ledger',
    'ACK 特征 `7bc02103-9d38-4f90-83a2-4f77f3f0a100` 的 write-with-response。',
    'UTF-8 账目 UUID，必须与当前 DATA 暴露的记录一致。',
    'GATT 成功后手表将该记录标记为已同步，手机可继续读取下一条。',
    '空值、未知 UUID、越权设备或未加密连接拒绝；重复 ACK 保持幂等。',
    '`X3VoiceLedger/docs/BLE_SYNC.md`、`BleSyncService.java`'),
  entry('ledger.ble.config', '下发记账配置', 'BLE GATT', 'voiceledger.ble.config.write', 'ble.ledger',
    'CONFIG 特征 `7bc02104-9d38-4f90-83a2-4f77f3f0a100` 接收按序二进制分块。',
    '头部：magic `0x43`、version `1`、大端 u16 index/count、u32 total，随后是 JSON 片段；总长不超过 64 KiB。JSON 为 `type=ledgerProfile,schema=2`，含 revision、账户、账本、标签和父子分类。',
    '全部分块完成并通过校验后原子替换手表配置，GATT write 返回成功。',
    '乱序、重复冲突、长度不符、超限、错误 schema 或无效默认账户均拒绝，不部分应用。',
    '`SyncProtocol.java`、`ProfilePayloadCodec.java`、`BleSyncService.java`'),
  entry('schedule.ble.batch', '传输日程批次页', 'BLE GATT', 'schedule.ble.batch', 'ble.schedule',
    '服务 `bcb71eb0-7600-46ea-8cf0-4838947ab001`、DATA 特征 `...b002`；外层帧 kind=3，4 字节大端正文长度。',
    '正文依次为 protocol=4、batchId/source/deviceId、revision、count、archiveBytes、archiveSha256、pageIndex、pageBytes。每页最大 128 KiB，整份归档最大 32 MiB、最多 256 页。',
    '接收端暂存页并通过 ACK 帧返回页级摘要和 committed 状态；最终页验证整份归档后才事务提交。',
    '旧 kind 1/2 在生产拒绝。批次元数据、页摘要、严格 UTF-8/JSON、来源和业务语义任一不符都拒绝。',
    '`X3Schedule/specification/PROTOCOL.md`'),
  entry('schedule.ble.ack', '确认日程批次页', 'BLE GATT', 'schedule.ble.ack', 'ble.schedule',
    '外层帧 kind=4，用于日程批次页级确认与整批提交确认。',
    'ACK 正文：protocol=4、batchId、pageIndex、该页正文 SHA-256、committed 字节 0/1。',
    '`committed=0` 仅确认该页持久暂存；`committed=1` 表示整批已验证并提交，发送端才可推进已确认 revision。',
    '未知批次/页、摘要不匹配、超长 ACK 或提前 committed 拒绝；不得把页级 ACK 当作业务提交。',
    '`X3Schedule/specification/PROTOCOL.md`'),
  entry('schedule.ble.pull', '请求下一待发批次', 'BLE GATT', 'schedule.ble.pull-next', 'ble.schedule',
    '外层帧 kind=5，由连接端提示对端准备下一份待发送批次。',
    '正文必须为空。',
    '若存在待发送批次，对端准备 kind=3 数据；无数据时保持空闲。',
    '带正文、未授权连接或不支持协议版本时拒绝。',
    '`X3Schedule/specification/PROTOCOL.md`'),
  entry('probe.ble.read', '读取 BLE 能力探针值', 'BLE GATT', 'probe.ble.value.read', 'ble.probe',
    '仅用于硬件能力验证。服务 `7a2f1000-5d2b-4c93-9b65-6f8c2cf03001`，只读特征 `7a2f1001-...`。',
    'Central 读取特征，无请求体。',
    '静态 UTF-8 文本 `x3-ble-probe`。',
    '诊断接口不传业务数据、不代表生产同步安全模型；探针前台服务有时间边界。',
    '`X3AccessibilityProbe/BleProbeService.java`'),

  entry('reader.cli.build', '听读工坊命令行构建', 'CLI', 'build', 'desktop.cli',
    '公开控制台入口将一个或多个 EPUB/TXT 来源转换为 `.opbook`；可选择合并或逐文件输出。',
    '命令 `build --input PATH [--input PATH ...] --output PATH`；可选 title、author、language、tts、voice、rate、pause、cover、ffmpeg、Provider 并发、created-at、separate 与 `--allow-online-tts`。',
    '每个成功产物输出 `Built PATH`；失败以非零退出码和受限诊断结束。',
    '来源/输出、Provider、并发和速率必须通过校验；Edge 在线语音必须显式传 `--allow-online-tts`，表示正文会发送到外部服务。', '`BuilderMain.java`、`BuilderOptions.java`'),
  entry('reader.cli.voices', '列出 Windows 系统音色', 'CLI', 'voices', 'desktop.cli',
    '公开控制台入口通过本机 SAPI 枚举已安装音色。',
    '命令 `voices`，无其他参数。',
    'stdout 每行一个 SAPI 音色描述；成功退出码为 0。',
    'PowerShell/SAPI 不可用或枚举失败时非零退出；不访问网络、不输出私人正文。', '`BuilderMain.java`'),
  entry('reader.cli.migrate-work', '迁移听读工坊旧工作目录', 'CLI', 'migrate-work', 'desktop.cli',
    '一次性把旧版工作目录迁移到当前受管目录。',
    '命令 `migrate-work --from LEGACY_DIRECTORY`。',
    'stdout 返回迁移文件数、字节数及源目录是否已移除。',
    '拒绝源/目标重叠、越界或冲突；先复制校验再清理，失败时保留可恢复状态。', '`BuilderMain.java`、`BuilderWorkDirectory.java`'),

  entry('reader.tauri.bridge-state', '读取听读工坊桥接状态', 'Tauri IPC', 'bridge_state', 'desktop.tauri',
    'Vue 调用本地 Tauri command，后者执行 Java CLI `bridge-state`。', '无参数。',
    'JSON：version、providers（含音色与在线标记）、cache 统计及可选 checkpoint。',
    '无法定位程序/工作目录、CLI 启动失败或末行不是 JSON 时 invoke reject。', '`X3OfflineReader/builder/ui/src-tauri/src/lib.rs`、`App.vue`'),
  entry('reader.tauri.edge-update', '检查或更新 Edge 组件', 'Tauri IPC', 'edge_update', 'desktop.tauri',
    '管理可选 Microsoft Edge 在线语音组件。', '`action` 仅允许 `status`、`auto`、`force`。',
    'EdgeUpdate JSON：state、version、message、可选 lastChecked。',
    '转换任务或另一次更新进行中时拒绝；正文不参与组件自检。', '`lib.rs`、`EDGE_CLIENT_UPDATES.md`'),
  entry('reader.tauri.preview-audio', '生成或读取音色试听', 'Tauri IPC', 'preview_audio', 'desktop.tauri',
    '请求预生成或本机实时试听。', '`provider` 与 `voice` 字符串。',
    '`{mimeType,audioBase64}`；前端以 data URL 播放。',
    '未知 Provider/音色、试听不可用或后端失败时 reject；只使用公开固定试听短句。', '`lib.rs`、`App.vue`'),
  entry('reader.tauri.cache-action', '执行工坊缓存操作', 'Tauri IPC', 'cache_action', 'desktop.tauri',
    '请求 Java 核心整理缓存。', '`action` 仅允许 `prune`、`stale`、`clear`。',
    '后端 JSON 结果，界面随后刷新缓存统计。',
    '未知动作拒绝；具体动作不删除源文件或已输出 `.opbook`，影响断点时必须由界面确认。', '`lib.rs`、`DATA_LIFECYCLE.md`'),
  entry('reader.tauri.start-build', '开始听读转换', 'Tauri IPC', 'start_build', 'desktop.tauri',
    '创建短期请求文件并启动 Java `bridge-build`，进度通过 `builder-event` 事件流返回。',
    'request：inputs、output/outputToSource、separate、title、author、language、tts、voice、cover、各 Provider worker 数、pauseMs、maxTrackMinutes、rate、allowOnlineTts。',
    '命令本身成功返回 void；事件类型包括 estimate/progress/paused/failed/complete/idle/backend-exit，complete 可带 outputs 与运行时长。',
    '并发任务、非法路径/参数或在线 TTS 未逐任务授权时拒绝；正文不写日志。', '`lib.rs`、`App.vue`、`BUILDER_UI_VUE_TAURI.md`'),
  entry('reader.tauri.resume-build', '续接听读转换', 'Tauri IPC', 'resume_build', 'desktop.tauri',
    '从 Java checkpoint 恢复上次未完成任务。', '无参数；实际构建选项从 checkpoint 读取。',
    '成功返回 void，后续复用 `builder-event` 事件流。',
    '无合法 checkpoint、路径已变化或已有任务时拒绝，不创建第二份任务。', '`lib.rs`、`BUILDER_RESUME_CACHE.md`'),
  entry('reader.tauri.pause-build', '保存听读转换进度', 'Tauri IPC', 'pause_build', 'desktop.tauri',
    '通过控制文件请求 Java 在安全检查点暂停。', '`closeAfter:boolean`，为 true 时安全保存完成后关闭窗口。',
    '写入控制文件成功后返回 void；最终状态通过事件流报告。',
    '无活动任务或控制文件不可写时拒绝；不得直接终止子进程替代安全暂停。', '`lib.rs`、`BUILDER_UI_VUE_TAURI.md`'),

  entry('reader.bridge.state', 'Java Bridge 状态查询', 'CLI JSON', 'bridge-state', 'desktop.bridge',
    'Tauri 后端启动 Java 核心进程并通过 stdout 单行 JSON 读取工坊状态。',
    '命令行首参数 `bridge-state`，无其他参数。',
    '单行 JSON：version、providers、cache 与可选 checkpoint；成功退出码为 0。',
    '工作目录或依赖不可用、输出为空/非 JSON 时调用失败；stderr 不承载业务正文。', '`BuilderBridge.java`、`builder/ui/src-tauri/src/lib.rs`'),
  entry('reader.bridge.edge-update', 'Java Bridge Edge 组件更新', 'CLI JSON', 'bridge-edge-update', 'desktop.bridge',
    'Tauri 后端通过 Java 核心调用独立 Python 更新器。',
    '命令行 `bridge-edge-update ACTION`；ACTION 仅允许 `status`、`auto`、`force`。',
    '单行 EdgeUpdate JSON：state、version、message、可选 lastChecked；成功退出码为 0。',
    '并发转换/更新由上层拒绝；更新超时、来源验证或自检失败时保留原组件。', '`BuilderBridge.java`、`EdgeClientUpdater.java`、`builder/edge/updater.py`'),
  entry('reader.bridge.preview', 'Java Bridge 音色试听', 'CLI JSON', 'bridge-preview', 'desktop.bridge',
    'Tauri 后端请求 Java 核心读取预生成试听或生成公开固定短句试听。',
    '命令行 `bridge-preview PROVIDER VOICE`。',
    '单行 JSON：`mimeType=audio/wav` 与 `audioBase64`；成功退出码为 0。',
    '未知 Provider/音色、在线授权或合成失败时命令失败；不接收任意私人试听正文。', '`BuilderBridge.java`、`VoicePreviewLibrary.java`'),
  entry('reader.bridge.cache', 'Java Bridge 缓存维护', 'CLI JSON', 'bridge-cache', 'desktop.bridge',
    'Tauri 后端请求 Java 核心执行受限缓存维护。',
    '命令行 `bridge-cache ACTION`；ACTION 仅允许 `prune`、`stale`、`clear`。',
    '单行 JSON：deletedFiles、deletedBytes 与 cache 统计；成功退出码为 0。',
    '未知动作不执行；只能处理工坊受管缓存和过期工作目录，不删除来源或已输出书籍。', '`BuilderBridge.java`、`BuilderCacheManager.java`'),
  entry('reader.bridge.build', 'Java Bridge 转换任务', 'CLI JSONL', 'bridge-build', 'desktop.bridge',
    'Tauri 后端用短期 JSON 请求文件启动 Java 转换任务，并持续读取 stdout JSON-lines 事件。',
    '命令行 `bridge-build REQUEST_JSON_PATH`；请求文件位于受管 ui-requests 目录，含 inputs/output、元数据、Provider/音色、并发、速率、封面、控制文件、在线授权与 resume。读取后立即删除。',
    'JSON-lines 事件：estimate、progress、paused、failed、complete；成功/暂停退出 0，业务失败退出 2。',
    '请求/控制文件必须位于受管目录；非法参数、无断点、未授权在线 TTS 或构建失败不产生伪完成事件。', '`BuilderBridge.java`、`BuildCheckpointStore.java`、`builder/ui/src-tauri/src/lib.rs`'),

  entry('reader.worker.edge', 'Edge TTS 单次 Worker', 'Worker IPC', 'reader.worker.edge', 'desktop.worker',
    '可选在线 TTS 子进程，一次启动处理一个 JSON 请求。',
    '`action=voices`；或合成请求含 action、text、voice、output、可选 rate(0.5–2.0)。',
    'stdout JSON：voices 返回 `{voices:[...]}`，合成返回 `{ok:true}`；失败在 stderr 输出脱敏 JSON 并以非零码退出。',
    '私人正文仅在用户对当前任务授权后传入；错误不得记录服务请求 URL 或正文。', '`builder/edge/worker.py`'),
  entry('reader.service.edge', 'Microsoft Edge 在线语音服务依赖', 'WebSocket', 'reader.edge-tts.service', 'desktop.worker',
    '可选 Edge Worker 通过固定版本 `edge-tts` 客户端访问 Microsoft 在线语音服务；具体握手与服务地址由受检客户端版本维护，不在本仓库伪造 REST 契约。',
    '查询音色，或提交用户逐任务授权的 text、voice 与 rate 合成请求。',
    '音色列表或音频流由 `edge-tts` 客户端处理，Worker 最终只向本地调用方返回脱敏 JSON/文件结果。',
    '每个转换任务默认关闭在线 TTS；正文会离开本机。服务/网络失败只返回脱敏状态，不持久记录正文或完整服务 URL。', '`builder/edge/worker.py`、`EdgeTtsProvider.java`、`EDGE_CLIENT_UPDATES.md`'),
  entry('reader.worker.melo', 'Melo TTS 常驻 Worker', 'Worker IPC', 'reader.worker.melo', 'desktop.worker',
    '本地常驻语音进程。启动后 stdout 首行 `READY`。',
    '每行 `output<TAB>voice<TAB>speed<TAB>base64(UTF-8 text)`；`QUIT` 退出。另有 `--prefetch LANGUAGE` 和 `--self-test` 启动模式。',
    '`OK` 或 `OK NORMALIZED n`；失败 `ERROR Type: detail`，诊断写 stderr。',
    '打包运行时仅支持 ZH 模型；路径与正文不进入持久日志。', '`builder/melo/worker.py`'),
  entry('reader.worker.sherpa', 'Sherpa TTS 常驻 Worker', 'Worker IPC', 'reader.worker.sherpa', 'desktop.worker',
    '本地 sherpa-onnx 常驻语音进程，启动后 stdout 首行 `READY`。',
    '启动参数 `--model kokoro|aishell3 --model-dir PATH [--rate 0.25..5]`；每行 `output<TAB>speakerId<TAB>base64(UTF-8 text)`；`QUIT` 退出。',
    '`OK`；失败 `ERROR Type: detail`，WAV 以 PCM_16 写入指定路径。',
    '模型目录必须通过既有依赖安全门禁；空音频或非法 speaker 拒绝。', '`builder/sherpa/worker.py`'),
  entry('reader.worker.windows', 'Windows Speech 常驻 Worker', 'Worker IPC', 'reader.worker.windows-speech', 'desktop.worker',
    '本机 System.Speech 常驻进程，启动后 stdout 首行 `READY`。',
    '`base64(output)<TAB>base64(voice)<TAB>rate(-10..10)<TAB>base64(text)`；`QUIT` 退出。',
    '`OK` 或 `ERROR Type: detail`；输出系统语音 WAV。',
    '音色必须与已安装音色名称或描述精确匹配；错误文本最多 400 字符。', '`builder/windows-speech/worker.ps1`'),

  entry('ledger.file.backup', '记账 JSON 备份 v1', 'File JSON', 'voiceledger.backup.v1', 'file.ledger',
    '手机端用户主动导出的未加密财务备份。',
    'UTF-8 JSON，format=`x3voiceledger-backup`、schema=1，最大 16 MiB、最多 50,000 条记录。',
    '包含手机账目、Yimu 归档状态、账户/分类/账本/标签配置、分类规则和确认模式。',
    '不含权限、蓝牙配对、截图、凭据或源导入文件；恢复按 UUID/updatedAt 无损合并，旧数据不得覆盖新数据。', '`X3VoiceLedger/docs/DATA_LIFECYCLE.md`'),
  entry('notes.file.backup', 'Wolai 笔记 JSON 备份 v1', 'File JSON', 'wolai-notes.backup.v1', 'file.notes',
    '手表本地笔记与最近访问的未加密备份。',
    'UTF-8 JSON，schema=1，最大 8 MiB、最多 50,000 条本地记录。',
    '包含本地速记/待办/同步状态与最近访问元数据；同 ID 保留设备现有记录。',
    'Token、页面配置和在线正文永不导出；重复 ID、未来 schema、非法枚举或越界字段在事务前拒绝。', '`X3WolaiNotes/docs/DATA_LIFECYCLE.md`'),
  entry('reader.file.opbook', '听读 .opbook 开放格式 v1', 'ZIP file', 'offline-reader.opbook.v1', 'file.reader',
    '扩展名为 `.opbook` 的受限 ZIP 容器，formatVersion=`1.0`。',
    '根目录仅允许 manifest.json、checksums.json、可选 cover.webp、content/*.json、timeline/*.json、audio/*.m4a；各 JSON 遵循仓库 schemas。',
    'manifest 定义资源关系，content 定义正文，timeline 定义毫秒与 Unicode code point 范围，音频为 AAC-LC/M4A。',
    '拒绝未知文件、危险路径/链接、压缩炸弹、未引用资源、校验和/Schema/跨文件语义错误；不含凭据。', '`X3OfflineReader/specification/OPEN_FORMAT.md`、`schemas/`'),
  entry('reader.file.backup', '听读手表 JSON 备份 v1', 'File JSON', 'offline-reader.watch-backup.v1', 'file.reader',
    '手表播放状态的未加密备份。',
    'UTF-8 JSON，product=`X3OfflineReaderWatch`、schema=1，最大 4 MiB、每类最多 10,000 项。',
    '小说/音乐进度、收藏、倍速与音乐播放模式。',
    '不含 `.opbook`、音频、歌词、正文、封面或缓存；恢复仅匹配已重新导入内容，较新本地进度优先。', '`X3OfflineReader/specification/DATA_LIFECYCLE.md`'),
  entry('schedule.file.backup', '日程分块备份 v1', 'Binary', 'schedule.x3backup.v1', 'file.schedule',
    '扩展名 `.x3backup`，当前内部 Snapshot protocol=2/schema=5。',
    '20 字节 `X3SCHEDULE-BACKUP/1\\n` 魔数；重复 `[u32be length][1..2MiB JSON]`；零长度结束；32 字节原始 SHA-256；立即 EOF。全文件最大 32 MiB、最多 4096 块。',
    '首块为空数据头，其余块携带计划、实例、事件与设置；恢复先完整验证，再单事务无损合并。',
    '跨块元数据不一致、重复 ID、摘要/尾随数据、超限或语义错误均拒绝；文件未加密、不是签名。', '`X3Schedule/specification/BACKUP.md`'),
  entry('schedule.file.ics', '日程 ICS 2.0 单次事件子集', 'iCalendar', 'schedule.ics.v2', 'file.schedule',
    '手机端导入/导出的 VCALENDAR 2.0 子集，仅交换 EXECUTION/REMINDER 单次 VEVENT。',
    '严格 UTF-8；完整 VCALENDAR 内每项须含 UID、SUMMARY、UTC DTSTART/DTEND，可选 `X-X3SCHEDULE-KIND`；最多 1000 项并受 Wire 最大字节数限制。',
    '导入生成稳定计划 ID；导出将未来 180 天实例展开为 UTC 单次事件，不含待办和完成记录。',
    '拒绝周期/全天/浮动时区、非零秒、超过 24 小时、取消/邀请、重复 UID、嵌套组件或不完整容器；完整数据使用 `.x3backup`。', '`X3Schedule/domain/Ics.java`、`IcsInput.java`、`specification/TYPES.md`'),

  entry('ledger.android.accessibility', '接收支付宝无障碍事件', 'Android', 'voiceledger.android.alipay-accessibility', 'android.ledger',
    'Android 系统在用户单独启用“支付自动记账”服务后回调。',
    '受系统过滤的 AccessibilityEvent；仅处理配置允许的支付宝支付结果界面和用户已授权字段。',
    '生成候选账目或忽略事件；原始节点树和页面全文不持久化。',
    '未授权、非目标包/窗口、字段不完整或重复支付事件忽略；服务不模拟点击。', '`AlipayPaymentAccessibilityService.java`、无障碍服务 XML'),
  entry('ledger.android.share-import', '接收易木账本导出文件', 'Intent', 'voiceledger.android.share-import', 'android.ledger',
    '手机主界面接收系统分享的用户选择文件。',
    '`ACTION_SEND` 单一 content URI；MIME 允许 xlsx/xls/zip/csv/tsv/json/octet-stream。',
    '进入预览与显式确认流程，支持的记录再导入本机数据库。',
    '无 URI、无临时读取授权、未知/伪装格式、越界内容或解析错误拒绝；不扫描任意存储。', '`X3VoiceLedger/phone/AndroidManifest.xml`、`PhoneMainActivity.java`'),
  entry('ledger.android.yimu-addbill', '向一木记账发起账单导入', 'Intent URI', 'yimu://api/addbill', 'android.ledger',
    '手机伴侣用显式包 `com.wangc.bill` 的 ACTION_VIEW URI 将用户确认的账目交给一木记账。',
    '必填 query：parentCategory、money（两位小数）、time（yyyy-MM-dd HH:mm:ss）、remark；可选 childCategory、asset、bookName、tags。',
    'Android 成功分派 Intent 即返回；一木记账内的最终保存结果不由本 App 回读。',
    '未安装兼容接收方时提示用户并保留本地账目；URI 仅由已校验本地字段编码，不广播给其他包。', '`X3VoiceLedger/phone/YimuImporter.java`'),
  entry('ledger.android.sync-wake', '接收记账同步唤醒广播', 'Android', 'voiceledger.android.sync-wake', 'android.ledger',
    '手表与手机接收系统开机完成和蓝牙状态变化，用于恢复同步调度。',
    '`BOOT_COMPLETED` 或 `android.bluetooth.adapter.action.STATE_CHANGED`。',
    '按权限、配对状态和本机队列安排内部服务，不携带业务正文。',
    '未知 action 忽略；广播本身不得绕过用户授权或已选设备绑定。', '`WatchSyncWakeReceiver.java`、`SyncWakeReceiver.java`'),
  entry('schedule.android.presence', '接收日程伴侣设备在线状态', 'Android', 'schedule.android.companion-presence', 'android.schedule',
    '手机端由系统绑定的 CompanionDeviceService 接收已关联手表出现/离开。',
    '`onDeviceAppeared(address)` / `onDeviceDisappeared(address)`，地址来自系统关联记录。',
    '更新连接状态并触发/停止有界同步服务。',
    '非已选择设备不得驱动同步；权限缺失或地址为空时安全忽略。', '`X3Schedule/shared/PresenceService.java`'),
  entry('schedule.android.reminder', '接收日程提醒与系统重调度事件', 'Android', 'schedule.android.reminder-lifecycle', 'android.schedule',
    '内部 ReminderReceiver/RefreshJob 处理闹钟、开机、时间/时区、覆盖升级及精确闹钟权限变化。',
    '系统 Intent 或 JobParameters；业务提醒 Intent 为应用内部显式目标。',
    '在静音、补发期限、实例时区和已发送键约束下投递或重排提醒。',
    '未知/过期/重复提醒不投递；公开系统回调不接受外部任务正文。', '`X3Schedule/shared/AndroidManifest.xml`、`ReminderReceiver.java`、`RefreshJob.java`'),
  entry('probe.android.accessibility', '接收无障碍能力探针事件', 'Android', 'probe.android.accessibility', 'android.probe',
    '诊断 App 由系统回调用于验证 OPPO Watch X3 的无障碍事件能力。',
    'AccessibilityEvent，经探针配置限定类型。',
    '仅在本机界面展示脱敏能力结果。',
    '不属于业务产品接口，不保存私人页面内容，不应扩展为常驻采集。', '`X3AccessibilityProbe/ProbeAccessibilityService.java`')
]

if (interfaces.length !== 40 || new Set(interfaces.map(item => item.path)).size !== interfaces.length) {
  throw new Error(`Interface inventory mismatch: ${interfaces.length}`)
}

const documents = [
  {
    key: 'overview', name: 'Watch-App API 总览与维护边界', group: 'guide',
    content: `# Watch-App API 总览与维护边界\n\n本目录记录 Watch-App 仓库中跨进程、跨设备、跨应用、系统回调和可移植文件的真实通信契约。内部普通 Java/Rust 方法、仅供启动的 Launcher Activity、数据库私有实现和 UI 事件不是 API，不单独建条目；构建依赖下载、开发服务器和用户选择的普通媒体/封面文件也不作为产品 API。\n\n- HTTP：3 个 Wolai Open API 与 2 个 Edge 组件更新端点，以真实 HTTP method/path 维护。\n- 原生协议：${interfaces.length} 个，覆盖 BLE、公开 CLI、Tauri IPC、Java Bridge、TTS Worker/外部服务、文件/备份及 Android 系统回调。\n- 安全边界：不保存个人 Token、密钥、设备序列号、私人正文、账目或日程数据。\n- 维护方式：本地清单为权威；默认只读计划，显式 Apply 才写入；发现远端人工修改或未受管同名项即停止。\n\n源码基线：\`${sourceCommit}\`。`
  },
  {
    key: 'models', name: '协议版本、容量与权限矩阵', group: 'guide',
    content: `# 协议版本、容量与权限矩阵\n\n| 产品 | 边界 | 当前版本/容量 | 安全要求 |\n|---|---|---|---|\n| 记账 | BLE | schema 2；配置最大 64 KiB | 已配对、加密 ATT、显式 ACK |\n| 日程 | BLE | batch 4；Snapshot protocol 2/schema 5；32 MiB | 已选择绑定设备、两级 ACK、整批事务 |\n| 笔记 | Wolai HTTP | openapi.wolai.com/v1 | 个人 Token 运行时 Bearer；禁止回显/导出 |\n| 听读工坊 | CLI/Tauri/Java Bridge/Worker | 本地 JSON/JSON-lines/TSV | 私人正文不写日志；在线 TTS 每任务授权 |\n| 听读组件更新 | PyPI HTTPS | JSON 最大 1 MiB；wheel 最大 2 MiB | 官方主机、禁止重定向、SHA-256/元数据/音频自检 |\n| 记账备份 | JSON | schema 1；16 MiB/50,000 条 | 未加密，用户选择位置 |\n| 笔记备份 | JSON | schema 1；8 MiB/50,000 条 | 永不含 Token/页面配置 |\n| 听读备份 | JSON | schema 1；4 MiB/每类 10,000 | 不含正文/音频；路径是敏感元数据 |\n| 日程备份 | x3backup | 容器 v1；32 MiB/4096 块 | SHA-256 仅校验完整性，不是签名 |\n| 日程交换 | ICS 2.0 子集 | UTC 单次事件；最多 1000 项 | 不含待办、完成记录、周期/全天/浮动时区 |\n| 听读书籍 | opbook | formatVersion 1.0 | 受限 ZIP、Schema、语义、媒体头与 SHA-256 |\n\n兼容与迁移细节以各接口条目的仓库来源为准。源码基线：\`${sourceCommit}\`。`
  }
]

const wolaiOpenapi = {
  openapi: '3.0.3',
  info: {
    title: 'Watch-App Wolai Open API integration',
    version: '1.0.0',
    description: 'X3WolaiNotes 调用的 Wolai 官方 HTTP 接口子集。个人 Token 只在运行时从 Android Keystore 解密，文档与示例不含真实凭据。'
  },
  servers: [{ url: 'https://openapi.wolai.com/v1' }],
  security: [{ bearerAuth: [] }],
  paths: {
    '/blocks/{pageId}/children': {
      get: {
        operationId: 'listPageChildren', summary: '读取页面子块', tags: ['Wolai Open API'],
        parameters: [{ name: 'pageId', in: 'path', required: true, schema: { type: 'string' }, description: '用户配置的页面 ID；不得写入公开配置。' }],
        responses: { '200': { description: 'Wolai 子块列表 JSON。客户端限制响应体最大 1 MiB。' }, '401': { description: 'Token 无效或失效。' }, '403': { description: '无页面访问权限。' }, '404': { description: '页面不存在。' }, default: { description: '非 2xx 均作为失败处理。' } }
      }
    },
    '/databases/{databaseId}': {
      get: {
        operationId: 'getDatabase', summary: '读取数据库元数据与行', tags: ['Wolai Open API'],
        parameters: [{ name: 'databaseId', in: 'path', required: true, schema: { type: 'string' }, description: '用户配置的数据库或收藏页 ID。' }],
        responses: { '200': { description: 'Wolai 数据库 JSON。客户端限制响应体最大 1 MiB。' }, '401': { description: 'Token 无效或失效。' }, '403': { description: '无访问权限。' }, '404': { description: '数据库不存在。' }, default: { description: '非 2xx 均作为失败处理。' } }
      }
    },
    '/blocks': {
      post: {
        operationId: 'createTextBlock', summary: '在同步页创建文本块', tags: ['Wolai Open API'],
        requestBody: { required: true, content: { 'application/json': { schema: { type: 'object', additionalProperties: false, required: ['parent_id', 'blocks'], properties: { parent_id: { type: 'string', description: '用户配置的同步页 ID。' }, blocks: { type: 'array', minItems: 1, maxItems: 1, items: { type: 'object', additionalProperties: false, required: ['type', 'content'], properties: { type: { type: 'string', enum: ['text'] }, content: { type: 'string', description: '用户明确提交的本地笔记正文。' } } } } } } } } },
        responses: { '200': { description: 'Wolai 创建结果 JSON。客户端限制响应体最大 1 MiB。' }, '201': { description: '创建成功。' }, '400': { description: '请求正文无效。' }, '401': { description: 'Token 无效或失效。' }, '403': { description: '无写入权限。' }, default: { description: '非 2xx 均作为失败处理，本地笔记保留待重试。' } }
      }
    }
  },
  components: { securitySchemes: { bearerAuth: { type: 'http', scheme: 'bearer', description: '用户个人 Wolai Token；只能在运行时使用，禁止保存到 Apifox 示例、Git 或日志。' } } }
}

const edgeUpdateOpenapi = {
  openapi: '3.0.3',
  info: {
    title: 'Tingdu Edge client updater',
    version: '1.0.0',
    description: '听读工坊仅用于检查和下载受检 edge-tts wheel 的官方 HTTPS 子集。禁止重定向，下载后必须校验 PyPI 提供的 SHA-256、包元数据、依赖和公开短句音频。'
  },
  paths: {
    '/pypi/edge-tts/json': {
      get: {
        operationId: 'getEdgeTtsReleaseMetadata', summary: '读取 edge-tts 发布元数据',
        servers: [{ url: 'https://pypi.org' }], security: [],
        responses: { '200': { description: 'PyPI JSON；客户端上限 1 MiB，仅选择未撤回的 py3-none-any wheel。' }, '404': { description: '项目不存在。' }, default: { description: '网络、重定向或非成功响应均视为检查失败，原组件保持不变。' } }
      }
    },
    '/packages/{shard1}/{shard2}/{digest}/{filename}': {
      get: {
        operationId: 'downloadEdgeTtsWheel', summary: '下载已选 edge-tts wheel',
        servers: [{ url: 'https://files.pythonhosted.org' }], security: [],
        parameters: [
          { name: 'shard1', in: 'path', required: true, schema: { type: 'string' }, description: 'PyPI 元数据返回 URL 的首级分片。' },
          { name: 'shard2', in: 'path', required: true, schema: { type: 'string' }, description: 'PyPI 元数据返回 URL 的次级分片。' },
          { name: 'digest', in: 'path', required: true, schema: { type: 'string' }, description: 'PyPI 文件存储摘要路径。' },
          { name: 'filename', in: 'path', required: true, schema: { type: 'string' }, description: '必须是元数据选中的 edge_tts-VERSION-py3-none-any.whl。' }
        ],
        responses: { '200': { description: 'wheel 二进制；客户端上限 2 MiB，并验证元数据给出的 SHA-256。' }, '404': { description: '文件不存在。' }, default: { description: '非官方 HTTPS 主机、端口、重定向或非成功响应均拒绝。' } }
      }
    }
  }
}

writeFileSync(join(output, 'apifox-layout.json'), JSON.stringify({
  sourceCommit, folders, interfaces, documents,
  httpCatalogs: [
    { key: 'wolai', file: 'wolai-openapi.json', importFolder: 'http.wolai', endpointFolder: 'http.wolai.api' },
    { key: 'edge-update', file: 'edge-update-openapi.json', importFolder: 'http.reader', endpointFolder: 'http.reader' }
  ]
}, null, 2) + '\n')
writeFileSync(join(output, 'wolai-openapi.json'), JSON.stringify(wolaiOpenapi, null, 2) + '\n')
writeFileSync(join(output, 'edge-update-openapi.json'), JSON.stringify(edgeUpdateOpenapi, null, 2) + '\n')
console.log(JSON.stringify({ folders: folders.length, interfaces: interfaces.length, documents: documents.length, httpEndpoints: Object.keys(wolaiOpenapi.paths).length + Object.keys(edgeUpdateOpenapi.paths).length }))
