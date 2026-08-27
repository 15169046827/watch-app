# Watch Offline Reader

包名 `com.canace.x3offlinereader`，版本 0.2.0（versionCode 2）。技术栈为 Java、Android Views、Media3 ExoPlayer/MediaSessionService 和 SQLite；minSdk 26、targetSdk 30、compileSdk 35，不依赖 Wear OS、Compose 或 Native 库。

## 已实现

- SAF 选择 `.opbook`，复制到应用 inbox；staging 解压后做路径、数量、大小、压缩比、格式版本、资源路径、M4A/WebP 头、Timeline/正文、SHA-256 和未引用文件检查，再原子提交。
- 小说书库按卷→章→Track 展示；章节队列、自动跨 Track、±15秒、收藏、句级高亮、全文和每5秒/暂停/切轨进度持久化。
- 独立音乐库导入 MP3/M4A/LRC；独立 SQLite、队列、进度、收藏、设置、倍速和 LRC 同步。
- 小说和音乐各自保存倍速；选择框提供 0.8×、1×、1.25×、1.5×、2×、2.5×、3×、5×。
- 小说和音乐各自保存播放模式；选择框提供“播完暂停、单曲循环、列表循环”，默认“播完暂停”。
- App 首页只提供横向等分的同级“小说 / 音乐”入口；小说书库、音乐库和章节列表使用圆屏中部 360px 以上宽度的紧凑双行条目，路径/状态压缩为单行，底部按钮不换行。
- 小说与音乐播放页的八个操作统一为等分 4×2 网格；倍速、播放模式、音量和输出列表使用固定不透明标题栏，滚动选项不会与页面标题重叠。
- 小说与音乐播放器共享系统媒体音量和输出控制：音量按设备系统步进从 0% 调到 100%，输出选择使用 Android 公共 MediaRouter，按钮只显示“扬声器 / 蓝牙”，详细设备名只在弹窗中显示；不做超过系统上限的软件增益。
- 两种模式只共享后台 PlaybackService、MediaSession 和 Audio Focus。Home/桌面使播放页留在后台时继续播放，系统媒体入口会回到该播放页；右滑/返回真正关闭播放页时立即停止并清空队列。
- SQLite 内部用 bookId 命名空间隔离各书的 volume/chapter/track ID，避免导入多本书时局部 ID 冲突。

## 导入安全上限

- 4096 个普通 ZIP 条目；单项展开不超过 512MiB；总展开不超过 4GiB。
- 大于 1MiB 的条目压缩比不得超过 500。
- JSON 文件不超过 16MiB；封面不超过 8MiB。
- 只接受 manifest 允许的 `manifest.json`、`cover.webp`、`content/*.json`、`timeline/*.json`、`audio/*.m4a` 和 `checksums.json`。
- 音乐 SAF 只接受 MP3/M4A/LRC；音频单文件上限 1GiB，LRC 上限 8MiB，避免歌词一次读取导致内存风险。

## OWW261 实机状态

已通过签名更新安装、Golden 导入、AAC/M4A/MP3 解码、Timeline/LRC、实际 5× 播放、三种播放模式状态转换、圆屏边界、固定弹窗标题、右滑、自动 Track、熄屏后台、MediaSession/AVRCP、系统媒体入口回到原播放页、Audio Focus 暂停/恢复和进程重启持久化。用户已确认耳机实际可听到声音，物理播放、暂停和下一首可触发，且声音输出可在 OWW261 扬声器与蓝牙设备之间成功切换。

下一阶段体验优化已记录但尚未开始，包括上次播放章节定位、每章内部进度与小说详情页一键续听、章节级上一项/下一项、小说固定顺序与音乐列表随机、Music 默认导入目录、导入进度、统一段落听读高亮、章节首段标题以及多 Track 对用户透明。详见 [`../specification/NEXT_PHASE_BACKLOG.md`](../specification/NEXT_PHASE_BACKLOG.md)。

已知设备限制：当前耳机/手表组合没有向 App 发送“上一首”的 AVRCP `76`；重复尝试仍失败，而 App 的 MediaSession 已提供上一首能力。OPPO 固件不允许 shell 强制 deep idle；USB 充电层会覆盖并暂停 Activity，但后台播放与暂停/切轨进度仍正常。扬声器与蓝牙听感均已确认；扬声器偏小是因为当时媒体音量仅为系统 6/16，现可在播放器内直接调整。
