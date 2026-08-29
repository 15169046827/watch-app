# 第三方语音组件与数据说明

版本：Builder 0.4.0（2026-08-24）

## 模型选择中的含义

- `[离线]`：正文仅在本机处理，完成安装后转换不需要联网。
- `[在线]`：正文会发送给所标明的第三方语音服务。GUI 在正式转换前必须再次确认；CLI 必须显式添加 `--allow-online-tts`。
- `[测试]`：仅用于格式/流程测试，不代表自然语音质量。

## 随包组件

| 组件 | 用途 | 代码许可 | 模型/数据许可要点 |
|---|---|---|---|
| MeloTTS | 默认离线中文语音 | MIT | 随现有便携环境使用，当前中文模型仅 `ZH` |
| sherpa-onnx 1.13.4 | Kokoro/AISHELL-3 离线 CPU 推理 | Apache-2.0 | 仅纳入许可来源明确的模型 |
| Kokoro-82M v1.1 zh（int8） | 100 个中文音色，24 kHz | Apache-2.0 模型卡 | 使用官方 int8 导出减小包体；Builder 暴露中文 sid 3–102 |
| AISHELL-3 VITS | 174 个中文说话人，8 kHz | AISHELL-3 数据集 Apache-2.0 | 当前随包官方完整模型/规则目录约 328 MB；“轻量”指 CPU 推理定位，不代表只有 ONNX 单文件大小 |
| edge-tts 7.2.8 | Microsoft Edge 在线语音客户端 | LGPL-3.0（上游注明的例外文件除外） | 服务和语音模型不是开源模型；使用受 Microsoft 在线服务可用性与条款约束 |
| Windows SAPI | 系统离线语音 | Windows 系统组件 | 音色由本机 Windows 安装状态决定，不随 Builder 分发 |

没有纳入 Piper `zh_CN-huayan-medium`，因为其官方模型卡将底层 HuaYan 数据集许可标为 `Unknown`。没有在许可未澄清前分发 Matcha/Fish Speech/真人克隆音色。

每个下拉框中的声音都可用固定公开短句单独试听。在线试听只发送固定短句；正式在线转换会发送书籍正文。Builder 不需要也不保存 API Key、账号或私人正文日志。
