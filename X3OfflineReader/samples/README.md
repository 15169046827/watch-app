# Samples

- `builder-inputs/`：项目自写短文本，覆盖两卷合并、分别导出、Melo 真离线和长章节拆 Track。
- `schema-fixtures/valid-v1/`：两卷两章 JSON fixture，验证 Schema 与跨文件语义。
- `schema-fixtures/invalid-v1/`：路径穿越/绝对路径/反斜杠/空段、Timeline 重叠/越界/正文不一致、UTF-16 误用及重复 ID。
- `generated/`：Builder 本机生成的 AAC-LC/M4A Golden、SAPI、Melo、拆 Track 与复现性输出。该目录被忽略，避免提交二进制和机器生成校验和。

当前验证器可同时验证任意数量实际 `.opbook`，并从第一个包自动构造和拒绝五种攻击：校验和翻转、ZIP 路径逃逸、修正校验和后的 Timeline 越界、压缩炸弹和未引用受管资源。样例不含私人书籍正文。
