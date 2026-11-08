# Watch Offline Reader

包名 `com.canace.x3offlinereader`，版本 0.8.0（versionCode 22）。技术栈为 Java、Android Views、Media3 ExoPlayer/MediaSessionService 和 SQLite；minSdk 26、targetSdk 30、compileSdk 35，不依赖 Wear OS、Compose 或 Native 库。

## 已实现

- SAF 可从任意目录多选 `.opbook` 并持久保存文件授权。对可寻址文件，App 直接从文件描述符解析 ZIP/ZIP64 中央目录，完成路径、数量、大小、压缩比与资源覆盖检查，只读取元数据和封面；元数据立即核对 SHA-256，音频媒体头和内容在首次播放提取时核对。不可寻址 Provider 回退顺序流验证。两种路径都只保存元数据索引，不复制整包或完整解压音频。
- 索引任务为进程级单实例；切换页面后文件名/阶段与按钮锁定保持一致。源 URI 与 `bookId` 双重去重，失败和重复文件会释放多余持久授权。
- 播放前只解出前一、当前和下一逻辑章节的 M4A。可寻址 SAF 文件优先随机读取 ZIP 条目，不可寻址 Provider 安全回退到顺序流；缓存音频再次按包内 SHA-256 校验并保留 64MiB 存储余量。
- 源文件是书库唯一权威副本。文件被移动、删除或授权失效后，点击书籍会提示源文件不存在；长按书籍可选择仅移出书库或连同源文件删除。
- 小说书库默认按最近播放时间排序；点击书籍直接从上次位置进入播放器，新书从开头进入。Track 分片对用户隐藏，同章多 Track 自动连续播放，章内累计续听、统一正文逐句高亮及周期/暂停/切轨进度持久化均已实现。
- 0.6.0 增加整本书的本地搜索：章节页可搜索已索引正文，中文和其他 Unicode 字符精确匹配、ASCII 字母忽略大小写；结果显示逻辑章节累计时间与有限上下文，点击后按 Timeline 句段精确跳转。搜索词限制 64 个 Unicode 码点，最多展示 100 条结果，不扫描音频或记录搜索内容。
- 0.6.1 将小说与音乐播放器的离散音量列表替换为圆屏安全的拖动滑杆；弹窗实时显示系统媒体音量百分比，拖动按设备原生步进设置音量，关闭后播放器按钮同步显示最新值。
- 0.6.2 增加播放器表冠调音：顺时针提高、逆时针降低，弹窗打开时同步滑杆与百分比。标准 Android 表冠使用 `SOURCE_ROTARY_ENCODER/AXIS_SCROLL`；OWW261 按硬件任务实测契约使用 `oplus_crown`、鼠标类来源和 `AXIS_VSCROLL`。速度相关事件经过方向锁、增量累积、100ms 限速、150ms 会话重置与反向尾流抑制后才转换为系统音量步进。
- 独立音乐库导入 MP3/M4A/FLAC/LRC；独立 SQLite、队列、进度、设置和 LRC 同步。歌词继续按同目录、同基础文件名匹配。长按音乐可仅移出列表或连同音频文件删除；仅移出的路径不会被下次扫描自动加回。
- 小说和音乐各自保存倍速；小说使用显示倍率的圆屏滑杆，范围 0.5×～5.0×、每 0.1× 一档。
- 小说固定按卷与逻辑章节顺序播放；音乐独立提供“播完暂停、单曲循环、列表循环、列表随机”，随机队列与历史栈可恢复。
- 首页的「小说」「音乐」入口水平等分，「数据与迁移」位于下方圆屏安全区。小说书库和音乐库使用留有行间距的圆角条目，点按及滑到列表边界时分别以青绿和紫色反馈。章节列表保留紧凑双行条目，路径、状态和底部按钮保持单行。
- 0.7.0 将小说和音乐的操作区与正文/歌词拆成横向两页，并把收藏入口从播放器移除；小说点击书籍直接续听并由播放器内目录按钮打开章节。0.7.1 把切页阈值收紧到屏宽约 10%，移除书库与音乐库遗留收藏星标，放大主播放键并缩小辅助图标及其可视内容。0.7.2 缩小页码圆点差异，修正歌词中心定位，并让“从此播放”只在实际浏览后覆盖正文显示。0.7.3 固定主播放键的准备/可用视觉尺度。0.7.4 撤回不符合圆屏习惯的直条方案，让分页与 `ScrollView` 使用完整 466×466 视口并恢复 Android 原生弧形滚动条。0.7.5 固定全部操作图标的准备/可用视觉状态，章节目录增加序号并在打开时定位当前章，小说倍速改为 0.1× 步进滑杆，取消书签功能；操作页表冠调音量，正文页表冠按行滚动正文。0.7.6 修正目录按列表位置覆盖原始章节号的问题：已有“第33章/第三十三章”等编号时直接沿用，仅为没有编号的标题补序号。
- 小说暂停后在正文页上下浏览，会在正文页底部覆盖显示“从此播放”；点击后从屏幕中央对应句段继续播放。正文的滚动视口铺满整个圆屏，文字自身保留安全边距。
- 音乐歌词页展示全部有效歌词，不受旧版前后三句窗口限制，并以 14sp 字号和 5dp 附加行距保持约五句的舒适密度。歌词页保留覆盖式标题栏；当前播放句保持强调，纵向浏览时不会被进度更新抢回位置，播放中停止浏览 4 秒或重新开始播放后恢复当前句居中跟随。
- 倍速、播放模式、音量和输出列表使用固定不透明标题栏，滚动选项不会与页面标题重叠。
- 小说与音乐播放器共享系统媒体音量和输出控制：音量滑杆按设备系统步进从 0% 调到 100%，输出选择使用 Android 公共 MediaRouter，按钮只显示“扬声器 / 蓝牙”，详细设备名只在弹窗中显示；不做超过系统上限的软件增益。
- 两种模式只共享后台 PlaybackService、MediaSession 和 Audio Focus。Home/桌面使播放页留在后台时继续播放；从桌面快捷菜单、启动图标或系统媒体入口重新进入 App 时，会直接回到仍活动的小说/音乐播放页；右滑/返回真正关闭播放页时立即停止并清空队列，之后正常进入首页。
- 小说播放页销毁时先封闭生命周期回调，再终止章节预取线程池；Media3 晚到的切轨/队列回调和线程池关闭竞态会被安全忽略，不再在返回章节列表时抛出 RejectedExecutionException。
- SQLite 内部用 bookId 命名空间隔离各书的 volume/chapter/track ID，避免导入多本书时局部 ID 冲突。
- 0.8.0 增加首次离线数据说明和

## 导入安全上限

- 4096 个普通 ZIP 条目；单项展开不超过 512MiB；总展开不超过 4GiB。
- 大于 1MiB 的条目压缩比不得超过 500。
- JSON 文件不超过 16MiB；封面不超过 8MiB。
- 只接受 manifest 允许的 `manifest.json`、`cover.webp`、`content/*.json`、`timeline/*.json`、`audio/*.m4a` 和 `checksums.json`。
- 音乐 SAF 只接受 MP3/M4A/FLAC/LRC；音频单文件上限 1GiB，LRC 上限 8MiB，避免歌词一次读取导致内存风险。

## OPPO Watch X3 实机状态

已通过签名更新安装、Golden 导入、AAC/M4A/MP3/FLAC 解码、Timeline/LRC、实际 5× 播放、三种播放模式状态转换、圆屏边界、固定弹窗标题、右滑、自动 Track、熄屏后台、MediaSession/AVRCP、桌面快捷菜单与系统媒体入口回到原播放页、Audio Focus 暂停/恢复和进程重启持久化。用户已确认耳机实际可听到声音，物理播放、暂停和下一首可触发，且声音输出可在 OPPO Watch X3 扬声器与蓝牙设备之间成功切换。

播放页返回章节列表的生命周期专项回归已在 OPPO Watch X3 通过：等待播放队列就绪后关闭播放页，继续等待延迟回调，章节列表保持存活；测试后日志中没有线程池任务拒绝或应用崩溃。覆盖安装保留原首次安装时间和用户数据，公开测试书在用例结束后清理。

桌面快捷菜单恢复专项回归会启动真实音乐播放、退回桌面，再以 Launcher 的 `MAIN/LAUNCHER` 路径重新进入 App，并验证仍回到同一个播放页且音频不中断；随后还会复测 MediaSession 入口与右滑停止。OPPO Watch X3 结果为 `OK (1 test)`，测试后错误日志为 0，公开音频夹具已自动删除；右滑结束后再次冷启动仍正常进入首页。

`WatchMusicModesProbeTest` 在专用验收环境点击播放器的上一首和下一首，以曲目 ID 和队列下标确认切歌。它使用公开 MP3 样本跨越曲尾，核验播完暂停、单曲重播、列表回绕及随机完整遍历，并同时核对 MediaSession 的循环与随机配置。用例要求空音乐库、无活动播放页和空会话队列。用例结束后移除自身创建的样本、曲目记录和播放页，恢复原播放模式设置。

0.8.3 在 OPPO Watch X3 的独立验收安装中通过了上述切歌和四种播放模式，以及 MP3/FLAC 解码进度、删除曲目后的延迟保存、启动器与媒体入口恢复、右滑逐级返回、音频焦点和音量读回。熄屏验收确认显示屏关闭 8 秒期间播放继续。验收结束后两个验收包均已移除，公开样本无残留。正式版已保数据覆盖至 0.8.3，保留原首次安装时间。

### 独立验收安装

在产品根目录运行 `gradlew.bat -PreaderValidation :watch:assembleValidationDebug :watch:assembleValidationDebugAndroidTest`。生成的两个包仅用于实机验收，并使用工作区共享 Debug 证书。省略该选项时仍使用普通 Debug 和对应的 AndroidTest 构建。

| 用途 | 应用 ID | 相对产品根目录的 APK 路径 |
| --- | --- | --- |
| 听读（验收） | `com.canace.x3offlinereader.validation` | `watch/build/outputs/apk/validationDebug/watch-validationDebug.apk` |
| 验收测试 | `com.canace.x3offlinereader.validation.test` | `watch/build/outputs/apk/androidTest/validationDebug/watch-validationDebug-androidTest.apk` |

安装前可从工作区根目录运行 `scripts/verify-debug-signatures.ps1` 的全量发现模式验证两个验收 APK，或分别用 Android SDK 的 `apksigner verify --print-certs` 核对共享 Debug 证书。应用 ID 隔离应用数据、外部应用目录和各自的 MediaSession。设备的音频焦点与系统媒体音量仍为系统共享状态，验收前关闭正式播放。

两个验收包安装后可运行下列命令。结束时只卸载表中的两个验收应用 ID。

```powershell
adb shell am instrument -w -e class com.canace.x3offlinereader.WatchMusicModesProbeTest com.canace.x3offlinereader.validation.test/android.test.InstrumentationTestRunner
```

小说验收用例会自行导入并清理公开 Golden 样本，支持单独运行。若早期用例曾在正式应用中运行并遗留该样本，可安装与正式应用签名兼容的普通 Debug AndroidTest 包并单独执行下列清理用例。它只处理固定的 Golden 样本书籍 ID、索引、缓存和测试包文件。

```powershell
adb shell am instrument -w -e class com.canace.x3offlinereader.WatchGoldenFixtureCleanupTest#testRemoveLegacyGoldenBookFixture com.canace.x3offlinereader.test/android.test.InstrumentationTestRunner
```

原下一阶段体验清单已在 0.3.0 完成，包括最近章节定位、每章内部进度与一键续听、章节级上一项/下一项、音乐随机、Music 初始目录、多文件导入反馈、统一段落高亮、首段标题和多 Track 无感播放。详见 [`../specification/NEXT_PHASE_BACKLOG.md`](../specification/NEXT_PHASE_BACKLOG.md)。

书内搜索、安全上限与 Timeline 定位契约见 [`../specification/WATCH_BOOK_SEARCH.md`](../specification/WATCH_BOOK_SEARCH.md)。
音量滑杆与表冠接入契约见 [`../specification/WATCH_VOLUME_CONTROLS.md`](../specification/WATCH_VOLUME_CONTROLS.md)。
播放器分页、正文定位和完整歌词交互见 [`../specification/WATCH_PLAYER_PAGING.md`](../specification/WATCH_PLAYER_PAGING.md)。

已知设备限制：当前耳机/手表组合没有向 App 发送“上一首”的 AVRCP `76`；重复尝试仍失败，而 App 的 MediaSession 已提供上一首能力。OPPO 固件不允许 shell 强制 deep idle；USB 充电层会覆盖并暂停 Activity，但后台播放与暂停/切轨进度仍正常。扬声器与蓝牙听感均已确认；扬声器偏小是因为当时媒体音量仅为系统 6/16，现可在播放器内直接调整。

0.5.0 的文件描述符随机读取路径已在 OPPO Watch X3 上用 2,742,410,282 字节 `.opbook` 连续实测 15.926 秒和 15.684 秒，折合约 6.2 秒/GiB；源文件大小不变，临时目录清空，应用私有缓存仅 4 KiB、数据库约 372 KiB，没有大文件副本。最终 16 项设备回归在 190.521 秒内全部通过，覆盖完整播放器、Audio Focus、后台/熄屏、生命周期、持久化、存储选择器和大包性能；源文件缺失、双模式删除与按需缓存仍保留非破坏性之外的专项人工验收。
