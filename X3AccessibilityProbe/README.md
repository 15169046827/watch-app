# X3AccessibilityProbe

面向 OPPO Watch X3（OWW261、Android 11 / API 30）的统一硬件与系统能力探针。

用户可见名称：`能力测试`。项目目录、包名和内部类名继续保留，确保覆盖升级与历史文档兼容。

除 AccessibilityService 外，版本 1.4 增加可手动运行的 BLE 能力测试，覆盖广播、双路并行广播、GATT Server、扫描和短时前台服务后台存活。版本 1.6 增加支付页面的结构化脱敏节点快照和离线暂存。探针测试代码与正式记账应用保持隔离。

## 第一阶段范围

- 注册 `ProbeAccessibilityService`
- Service 连接、收到事件、被中断时写入 Logcat
- Activity 仅显示安装状态
- 不包含支付识别、通知监听、记账、Wear OS 或第三方库

应用包名：`com.example.x3accessibilityprobe`

Service 组件：`com.example.x3accessibilityprobe/.ProbeAccessibilityService`

## 构建

在项目根目录执行：

```powershell
.\gradlew.bat assembleDebug
```

Debug APK 输出到：

```text
.build\app\outputs\apk\debug\app-debug.apk
```

## 安装前后验证

请先把 Android SDK 的 `platform-tools` 加入 `PATH`，或设置 `ANDROID_SDK_ROOT`。以下命令不会依赖特定电脑的目录结构：

```powershell
adb devices
adb shell dumpsys accessibility
adb install -r ".\.build\app\outputs\apk\debug\app-debug.apk"
adb shell dumpsys accessibility
```

安装前记录 `installedServiceCount`。安装后理想结果是它从 `0` 变为 `1`。

本阶段不要修改 `enabled_accessibility_services`，也不要尝试启用 Service。只有确认系统已识别所安装的 AccessibilityService 后，才进入第二阶段。

## OPPO Watch X3 实机结果

2026-08-17 在 `OWW261`（Android 11 / API 30）上验证：

- APK 安装成功，Activity 冷启动成功
- `installedServiceCount`：`0` → `1`
- 系统识别组件：`com.example.x3accessibilityprobe/.ProbeAccessibilityService`
- `accessibility_enabled` 仍为 `0`
- `enabled_accessibility_services` 仍为 `null`
- `Bound services` 和 `Enabled services` 均为空

结论：第一阶段通过；系统 Framework 能识别第三方 AccessibilityService。尚未启用该 Service。

## 第二阶段实机结果

2026-08-17 通过 OPPO Settings 的正常系统界面完成启用，没有使用 ADB 写入 secure settings：

- 隐藏入口：`com.android.settings/.Settings$AccessibilitySettingsActivity`
- 系统列表正常显示 `X3 Accessibility Probe Service`
- 系统权限确认弹窗正常出现
- `accessibility_enabled=1`
- `enabled_accessibility_services=com.example.x3accessibilityprobe/com.example.x3accessibilityprobe.ProbeAccessibilityService`
- `Bound services` 包含 Probe
- Logcat 出现 `lifecycle=CONNECTED`

结论：第二阶段通过；OPPO Watch X3 支持通过系统 Settings 正常、持久地启用第三方 AccessibilityService。

## 第三阶段普通页面结果

Service 启用后，已从 OPPO Settings 收到并解析：

- `TYPE_WINDOW_STATE_CHANGED`
- `TYPE_WINDOW_CONTENT_CHANGED`
- `TYPE_VIEW_SCROLLED`
- `TYPE_VIEW_CLICKED`
- `packageName`、`className`、`event.text`、`contentDescription`
- source 节点的包名、类名、View ID、文字、描述、子节点数、可点击状态和屏幕边界

结论：第三阶段的普通 App 页面验证通过。尚未进行支付 App 测试。

## 第四阶段支付页面结果

2026-08-24 在支付宝 NFC 实际支付成功页验证：

- 来源包：`com.eg.android.AlipayGphone`
- 页面类：`com.alipay.watch.npay.ui.NFCPaySuccessActivity`
- 收到 `TYPE_WINDOW_STATE_CHANGED` 和 `TYPE_WINDOW_CONTENT_CHANGED`
- `getRootInActiveWindow()` 成功，完整成功页快照包含 14 个节点
- `nfc_pay_text` 节点命中“付款成功”特征
- `tv_amount` 节点包含可解析的小数金额特征，独立节点包含人民币符号
- 支付渠道可由包名确定，支付时间可由事件时间确定
- 本次成功页节点树没有发现商户名称字段
- 支付页退出后无法倒推其窗口是否设置 `FLAG_SECURE`，本项保持未判定

结论：Accessibility 路线已经证明可稳定获得“支付宝 NFC 支付成功 + 金额 + 渠道 + 时间”；商户名称需要其他来源或由用户补充。公开仓库不包含真实设备日志、支付记录或验收截图。

## 上方物理按键结果

2026-08-31 在 OWW261 前台 Activity 上验证：

- Linux input 来源为 `qpnp_pon`，原始键为 `KEY_F1`；短按序列是 `DOWN → SYN_REPORT → UP → SYN_REPORT`。
- 系统 key-layout 将 scan code `59` 映射为 Android `KEYCODE_F1`（keyCode `131`）。
- 屏幕点亮且探针 Activity 已恢复时，真实短按在 Linux 层出现，但未进入 `dispatchKeyEvent`；短按后屏幕关闭，系统窗口策略把它按电源键行为处理。
- 前台长按的可交付阶段会出现 `KEYCODE_F1`：约 401 ms 的首个 repeat 带 `isLongPress=true`，随后产生重复 DOWN；系统策略同时声明长按电源键进入全局电源操作。
- 探针始终把事件交回 `super.dispatchKeyEvent`，没有拦截或改变电源、返回和安全相关系统行为。

结论：上方物理键虽然在 Linux/Android 映射中名为 F1，但产品行为属于系统电源键，不适合作为普通 App 的必需快捷键。媒体播放应使用 `MediaSession` 的标准媒体按键回调；前台 `KEYCODE_F1` 只能作为不消费系统行为的可选诊断信号，不能依赖其短按或长按一定到达。

## 当前本地版本 1.10

版本 1.10 增加只观察、不消费事件的 `dispatchKeyEvent` 按键探针，用于记录 action、Android keyCode/keyName、Linux scan code、repeat、long-press 标志和按压时长。版本 1.9 的图标与既有 Accessibility/BLE 能力保持不变。

版本 1.9 将启动器图标升级为“日常工具”v2.1 规范：矿物蓝底色完整覆盖正方形画布，暖白雷达/准星严格居中；Android 11 使用标准自适应图标，由系统负责圆形或圆角方形蒙版。实机校准后，自适应前景以中心缩放至 72%，同时消除原先的双重留白并保留清楚的外侧底色边框。API 30 fallback、包名、应用数据和无障碍组件均不变。

版本 1.1 已在电脑上构建并通过 Android Lint，但尚未安装到手表。它为下一次连接准备了：

- Activity 显示 Probe 是否注册、Accessibility Manager 是否开启、Probe 是否启用
- 检查系统是否有 Activity 能处理官方 `android.settings.ACCESSIBILITY_SETTINGS` Intent
- “Open accessibility settings”按钮只调用官方设置 Intent，不修改 secure settings
- 结构化 Logcat：事件类型、包名、类名、View ID、窗口和节点属性
- 文本只保留长度、数字数量、货币标志、金额格式和支付关键词，不写入原文
- 页面稳定约 250 毫秒后递归采集当前窗口，最多 80 个节点
- 仅对支付宝、微信手表版和 OPPO 钱包写入最大 512 KB 的应用私有离线脱敏记录

设备重新连接后，可以运行只读状态检查：

```powershell
.\scripts\check_accessibility.ps1
```

Service 成功启用后，可以观察 Probe 日志：

```powershell
.\scripts\watch_probe_logcat.ps1
```

版本 1.6 的新增记录不包含屏幕原文、真实金额或商户名称，但仍只应用于自己的测试设备，不对外分享设备诊断记录。

## BLE 实机结果

2026-08-17 在同一台 `OWW261` 上通过统一 Probe 真机验证：

- `BluetoothLeAdvertiser`：可用
- 第一路 `startAdvertising`：成功（low-latency、medium TX power）
- 系统报告 `isMultipleAdvertisementSupported=true`
- 第二路广播与第一路并发启动：成功，没有返回错误码
- 最大扩展广播数据长度：`1213` 字节；正式兼容方案仍应使用小型 legacy payload
- `BluetoothGattServer`：打开成功
- `addService`：调用被接受，异步回调 `status=0 / GATT_SUCCESS`
- `BluetoothLeScanner`：可用；`startScan` 被接受，运行 15 秒后正常停止，没有 `onScanFailed`
- 本次环境没有发现周边设备，扫描回调结果数为 0；因此已证明扫描注册和运行正常，尚未用第二台广播设备验证发现数据内容
- App 退到桌面后，前台服务中的双路广播和 GATT Service 在 15 秒检查点仍存活
- 25 秒测试结束后主动停止扫描、广播、GATT Server 和前台服务，没有遗留后台连接

Android 11 / API 30 上，本探针声明 `BLUETOOTH`、`BLUETOOTH_ADMIN`、`ACCESS_FINE_LOCATION` 和 `FOREGROUND_SERVICE`。扫描需要用户授予精确位置权限。短时后台测试通过 `connectedDevice` 类型前台服务完成，并产生低优先级常驻通知；普通后台进程不应被视为可靠的长期同步机制。

OPPO ROM 的实测限制：应用不在 Doze 白名单；侧载覆盖安装后的厂商权限同步可能重置运行时位置权限，并且本次多次覆盖安装后清除了已启用的 AccessibilityService 状态。正式应用必须使用正常权限界面，不能依赖 ADB 授权；长期 BLE 同步还需要单独验证熄屏、Doze 和厂商后台清理。

## 圆屏布局

版本 1.2 起不再按矩形手机屏幕直接排版。Activity 会通过 Android `isScreenRound()` 检测圆屏，并根据实际屏幕直径计算内接矩形的圆角内缩：

- 文字和按钮限制在中央安全列
- 滚动视口固定避开顶部、底部圆弧区域
- 状态页显示 `Layout: ROUND SAFE AREA`，便于实机确认

## 手表返回手势

版本 1.3 起，Activity 自行识别明显的水平向右滑动并调用 `finish()` 退出。识别条件会排除主要为垂直方向的移动，避免与状态页上下滚动冲突；Android 默认返回行为仍然保留。
