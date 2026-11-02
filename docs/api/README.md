# Watch-App Apifox API 维护

本目录是 Watch-App 对外及跨边界通信契约的公开维护入口。Apifox 项目固定为 `Watch-App`（项目 `8948095`、主分支 `8751743`、默认模块 `8741710`）。同一账户可访问的其他项目不在本工具范围内。

## 覆盖范围

- Wolai 官方 Open API 与听读工坊 Edge 组件更新端点：以真实 HTTP method/path 导入；
- 记账、日程及能力探针的 BLE GATT 协议；
- 听读工坊公开 CLI、Tauri IPC、Java Bridge、TTS 子进程与 Edge 在线服务边界；
- 备份、开放书籍格式与日程 ICS 子集；
- 由 Android 系统或其他 App 触发的业务回调，包括一木记账 URI。

普通内部函数、私有数据库实现、纯 UI 事件、Launcher Activity、构建依赖下载、开发服务器及普通媒体/封面文件选择不属于产品 API。当前清单由 `scripts/build-apifox-layout.js` 生成，共 40 个原生通用协议接口、5 个 HTTP 接口和 2 份规范文档。

### 当前供应商连接状态（2026-11-02）

Apifox 新版 MCP 已恢复。只读现场确认项目 `8948095`、主分支 `8751743` 和默认模块
`8741710`，并使用 PowerShell 7 运行默认只读同步：24 个目录、40 个通用协议接口、
2 份文档和 5 个 HTTP 接口全部 `UNCHANGED`，`apply=False`。令牌只从当前用户环境
变量加载，未打印或持久化。

2026-11-01 两个官方 Streamable HTTP 入口曾返回供应商侧 `Invalid Host header`；该
故障已解除，但安全边界不变：不得猜测其他主机、关闭 TLS 校验，或在只读结果未核对
前运行 `-Apply`。脚本保留脱敏错误诊断，以便故障复发时区分提供方网关问题与凭据问题。

## 安全同步

1. 令牌只从运行时环境变量 `ApiFox_MCP_TOKEN` 读取，不进入参数、输出、本目录或 Git。
2. `scripts/apifox-mcp.ps1` 仅允许官方 HTTPS 地址、固定 Watch-App 项目绑定和已审查操作；不跟随重定向。
3. `scripts/sync-apifox-api.ps1` 默认只输出计划；只有 `-Apply` 才写入。
4. 受管实体 ID、本地内容摘要和远端读回摘要保存在 `apifox-manifest.json`。发现人工修改、未受管同名项或不一致绑定时停止。
5. 每次创建/更新后立即保存 ID 并读回检查；写入结果不明时不自动重试。
6. 动态开放接口 ID 记录在 `apifox-operations.json`，更新前必须现场重新发现并复核契约；清单故意不包含删除操作。

若 OpenAPI 导入已返回但首次读回中断，先只读核对唯一的 method/path、名称、目录和详情契约；全部一致时才可用 `-RecoverImportedHttp` 将已创建实体接回清单。该开关不重新导入，也不能用于接管未核实条目。

生成并检查：

```powershell
node .\scripts\build-apifox-layout.js
node --test .\scripts\apifox-contract.test.js
pwsh -NoProfile -File .\scripts\sync-apifox-api.ps1
```

确认计划后才执行：

```powershell
pwsh -NoProfile -File .\scripts\sync-apifox-api.ps1 -Apply
pwsh -NoProfile -File .\scripts\sync-apifox-api.ps1
```

同步脚本使用 `ConvertFrom-Json -AsHashtable`，因此要求 PowerShell 7；Windows PowerShell
5.1 不属于受支持的执行环境。

最后一次只读运行必须全部显示 `UNCHANGED`，且总数为 24 个目录、40 个通用协议接口、2 份文档和 5 个 HTTP 接口。

## 文件说明

- `apifox-layout.json`：目录、通用协议接口和参考文档的生成结果；
- `wolai-openapi.json`：X3WolaiNotes 实际调用的 3 个 HTTP 接口；
- `edge-update-openapi.json`：听读工坊 Edge 组件更新实际调用的 2 个官方 HTTP 接口；
- `apifox-manifest.json`：远端绑定和摘要，不含凭据；
- `apifox-operations.json`：已审查动态操作白名单；
- `scripts/apifox-mcp.ps1`：认证、握手与范围保护；
- `scripts/sync-apifox-api.ps1`：计划、写入、读回和漂移保护。

接口正文的权威来源仍是各产品源码和规格；功能、协议、版本、容量或安全边界变化时，应在同一次任务中更新源文档、本目录、Apifox、在线开发索引和本地交接资料。
