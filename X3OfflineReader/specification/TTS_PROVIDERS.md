# Builder 0.4.0 中文语音 Provider 决策

更新：2026-08-24。`.opbook` 格式仍为 v1；本次只扩展 PC 端语音来源，不改变正文、Timeline 或资源权威关系。

## 已纳入

| 下拉框标签 | 可选中文音色 | 网络 | 定位 | 当前证据 |
|---|---:|---|---|---|
| `[离线] MeloTTS 中文` | 1 | 仅安装时 | 原默认，质量基线 | 已有长书转换与缓存/并行回归 |
| `[离线] Kokoro 中文（多音色）` | 模型 100；随包可用试听 81 | 仅安装时 | 更自然、多音色 | sherpa-onnx CPU Runtime；官方 int8 模型约 114 MB（完整资源目录约 215 MB） |
| `[离线·轻量] AISHELL-3（多音色）` | 模型 174；随包可用试听 130 | 仅安装时 | CPU 推理较快、低采样率 | 当前官方完整模型/规则目录约 328 MB，音质上限低于 Kokoro |
| `[离线] Windows 系统语音` | 随系统 | 否 | 无额外模型的保底 | 本机 SAPI 动态枚举 |
| `[在线] Microsoft Edge 中文` | 当前动态枚举 14；随包可用试听 11 | 是 | 多普通话/粤语/台湾/地方口音选择 | 公开测试短句端到端 `.opbook` 已通过 v1 Schema/攻击回归 |
| `[测试] 固定音调` | 1 | 否 | 只验证格式流程 | 不作为自然语音 |

所有选项共用 `TtsProvider`，输出句级 WAV，以实际 WAV 时长生成 Timeline，章节末再编码 M4A。Kokoro 与 AISHELL-3 使用单个长驻进程，避免重复加载模型放大内存。

试听资源在发布前生成并随包分发，统一为 16 kHz、单声道 PCM WAV。用户选择声音后点击“试听”即可立即播放，不在点击时加载模型或联网。生成器固定使用 1× 自然速率，不做 `atempo` 或其他后期变速；因不同模型/音色的自然时长差异很大，公开短句会按模型和音色缩短或延长，最终真实时长必须为 5–10 秒。Kokoro 19 个、AISHELL-3 44 个以及 Edge 3 个异常音色在调整文本后仍不合格，故不进入发布索引。

## 在线模型边界

Edge 下拉项始终标为 `[在线]`。枚举音色不包含书籍正文；在线试听只发送固定公开示例句。正式转换前 GUI 显示正文外发警告并要求确认，CLI 则拒绝静默使用，除非显式提供 `--allow-online-tts`。请求正文通过子进程标准输入传递，不放入命令行或日志；Builder 不保存 API Key（Edge 客户端不需要 Key）、账号或正文日志。

在线服务可能限流、变更或暂时不可用，因此离线 Provider 继续作为默认和可复现路径。在线失败不得自动切换到另一音色，以免用户在同一本书里得到混合声音。

## 许可与候选取舍

- [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) Runtime：Apache-2.0。
- [Kokoro-82M v1.1 zh](https://huggingface.co/hexgrad/Kokoro-82M-v1.1-zh)：官方模型卡标记 Apache-2.0；sherpa 导出保留 100 个中文 sid。
- [AISHELL-3](https://www.openslr.org/93/)：语料公开页/论文采用 Apache-2.0；使用 sherpa 官方 VITS 导出。
- [edge-tts](https://github.com/rany2/edge-tts)：客户端为 LGPL-3.0（上游注明的单文件例外除外），但 Microsoft 服务和音色模型本身不是开源模型。
- Piper `zh_CN-huayan-medium` 未纳入：上游模型卡把底层 HuaYan 数据集许可标为 `Unknown`，不能因为运行时代码许可开放就推断音色权重可安全分发。
- CosyVoice、Fish Speech、GPT-SoVITS、零样本克隆类模型本轮不随包分发：模型更大、Windows CPU 长书吞吐或权重/训练数据许可尚未达到可复现的便携发行门槛。不得随包加入未经授权的真人参考音频。

第三方随包清单见 `builder/THIRD_PARTY_TTS.md`，安装脚本固定 Runtime/客户端版本，不写入凭据。

## 验收门槛

每个新增离线模型必须完成：固定短句、Unicode 标点、至少一段长文本、完全断网、WAV 时长、整包 v1 Schema/跨文件/压缩包攻击校验；记录冷启动、实时率、峰值内存和包体。在线 Provider 另测无授权 CLI 拒绝、GUI 警告、正文不进入命令行/日志、网络失败可读错误。
