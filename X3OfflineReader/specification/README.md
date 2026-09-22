# Specification

`.opbook` v1 的机器契约在 `schemas/`，规范性文字在 `OPEN_FORMAT.md`。Schema 负责单文件结构；跨文件引用、时间单调性、正文切片一致性、资源存在性和校验和由语义验证器负责。

- `manifest.schema.json`：书籍→卷→章→Track 以及资源路径的权威来源。
- `content.schema.json`：章节正文、block 顺序和文本的权威来源。
- `timeline.schema.json`：Track 时间与正文范围对应关系的权威来源。
- `checksums.schema.json`：包内其他文件的 SHA-256 清单。

所有 v1 文件使用 UTF-8 JSON、整数毫秒和稳定 ID。重大不兼容变更必须升级主版本，不能静默改变 v1 语义。
Builder 实现与运行规格：

- `BUILDER_UI_VUE_TAURI.md`：0.6.x Vue/PrimeVue/Tauri 展示层、Java 桥接、试听单实例、ETA、生命周期、隐私和验收。
- `BUILDER_RESUME_CACHE.md`：断点、原子句子缓存、Track 复用与缓存整理。
- `BUILDER_PERFORMANCE.md`：Melo 并行度、资源守卫和实测吞吐。
- TTS_PROVIDERS.md：离线/在线 Provider、音色、试听与许可边界。
- DATA_LIFECYCLE.md：Watch 与 Builder 的数据清单、备份/恢复/清理边界和公共安装版签名门禁。

Watch 实现与交互规格：

- `WATCH_INDEXED_LIBRARY.md`：SAF 原地索引、按需音频缓存、源文件管理和安全上限。
- `WATCH_BOOK_SEARCH.md`：0.6.0 本地正文搜索、安全上限、Timeline 定位、圆屏结果和精确跳转契约。
- `WATCH_VOLUME_CONTROLS.md`：0.6.2 系统媒体音量滑杆、圆屏布局、标准/OWW261 表冠事件契约、平滑步进与验证边界。
- `WATCH_PLAYER_PAGING.md`：0.7.6 操作/内容横向分页、全屏原生弧形滚动条、固定按钮状态、保留原始章号的目录定位、正文页表冠滚动、小说倍速滑杆、覆盖式“从此播放”和完整歌词居中浏览契约。
