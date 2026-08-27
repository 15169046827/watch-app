# `.opbook` v1 open format

## Container

`.opbook` 是扩展名不同的 ZIP 文件，包根目录直接包含：

```text
manifest.json
checksums.json
cover.webp                 # 可选
content/<chapter-id>.json
timeline/<track-id>.json
audio/<track-id>.m4a
```

ZIP 条目名必须使用 `/` 和 UTF-8，相对路径不得包含空段、`.`、`..`、反斜杠、盘符、绝对路径、NUL 或符号链接。Watch 必须先解压到 staging，限制条目数、单项大小和总展开大小，验证 Schema、语义与校验和后再原子提交；不得直接从 ZIP 播放。`checksums.json` 不校验自身，其他包内普通文件都必须列入，算法固定 SHA-256。

v1 不允许未知附加文件：除 `checksums.json` 外，只能出现 `manifest.json`、可选 `cover.webp`、`content/*.json`、`timeline/*.json` 和 `audio/*.m4a`，且每个都必须在校验清单中。

## Authority and identity

- `manifest.json` 是资源关系的权威来源；未被 manifest 引用的正文、Timeline 或音频不得导入。
- `content/*.json` 是正文与 block 结构的权威来源。
- `timeline/*.json` 是音频时间到正文范围映射的权威来源，其中冗余 `text` 必须与 content 切片一致。
- ID 在一本书内稳定且唯一。数组中的 `order` 从 0 开始连续递增；读取器使用数组顺序并验证 `order`。

## Time and text ranges

时间为非负整数毫秒，`startMs` 包含、`endMs` 不包含。每个 segment 必须满足 `0 <= startMs < endMs <= durationMs`，按时间排序且不得重叠。相邻 segment 可以留有停顿。

`startChar`/`endChar` 是对应 content block 文本中的零基、尾端不含 Unicode code point 索引，不是 UTF-8 byte 或 UTF-16 code unit 索引。Watch 的 Java 实现必须显式转换 code point offset。Timeline 的 `text` 必须等于 `[startChar, endChar)` 切片。

## Versioning

v1 文件统一写 `formatVersion: "1.0"`。读取器应拒绝未知主版本；对同主版本未来新增的可选字段，读取器可以忽略。当前 Schema 使用 `additionalProperties: false` 固化 1.0 契约，扩展字段需通过后续次版本 Schema 明确加入。

## Media

v1 Track 使用 AAC-LC in M4A（`audio/mp4`），由系统解码器播放。逻辑章节和 Track 解耦，长章节可拆成约 10–30 分钟 Track。Builder 先合成句级 PCM/WAV，按真实样本数累计 Timeline，再统一编码章节/Track M4A；Forced Alignment 不属于 v1 Builder 的必选流程。

## Content and safety

格式只用于用户拥有或获授权的无 DRM 内容，不定义或实现 DRM 绕过。包内不得包含 API Key、账号、签名材料或转换器敏感日志。
