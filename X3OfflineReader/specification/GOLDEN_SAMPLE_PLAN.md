# Golden Sample plan and evidence

Golden 输入全部位于 `samples/builder-inputs`，只含项目自写短句；生成包位于被忽略的 `samples/generated`。固定 `--created-at`、输入、provider 参数、文件顺序和 ZIP 时间戳后，tone Golden 两次构建的 SHA-256 完全一致。

已完成的本机矩阵：

1. `GS-VALID-01`：两卷合并、两卷分别导出；Schema、跨文件 ID/order、Unicode code point 切片、SHA 覆盖全部通过。
2. `GS-MEDIA-01`：FFprobe 确认 AAC-LC、mono、M4A 容器和 512×512 WebP。
3. `GS-TTS-01`：Windows SAPI、源码 Melo、设置 `HF_HUB_OFFLINE=1`/`TRANSFORMERS_OFFLINE=1` 的便携 Melo 均成功生成有效包。
4. `GS-SPLIT-01`：长章节按一分钟阈值拆成两个 Track，content 仍保持章节正文权威，manifest/timeline 关系通过验证。
5. `GS-CHECKSUM-01`：翻转包内 byte 后拒绝。
6. `GS-PATH-01`：加入 `../escape` 条目后拒绝。
7. `GS-TIMELINE-01`：重叠、越界、倒序、错误正文切片、Unicode 索引错误及重复 ID 分别拒绝。
8. `GS-LIMIT-01`：压缩比炸弹拒绝；Watch 实现另固定 4096 条目、512MiB 单项、4GiB 总展开上限。
9. `GS-RESOURCE-01`：未被 manifest 引用的 content/timeline/audio 以及未知包内文件拒绝。

待 OWW261 实机完成：真实导入、系统 AAC 解码、连续多 Track、Seek/逐句边界、进度恢复、后台/熄屏、Audio Focus、扬声器、蓝牙按键、右滑圆屏安全区和 OPPO 进程回收。完成这些前不得把本机结果表述为实机兼容结论。
