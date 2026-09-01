# X3 Wolai Notes

OPPO Watch X3（OWW261）端的轻量 Wolai 速记应用。项目独立于同目录下的其他手表应用。

## MVP

- 创建短文本笔记和待办（待办以 `☐` 前缀写入，避免依赖未稳定开放的专用块类型）
- 本地 SQLite 先保存，失败时保留队列并支持重试
- 写入前持久化“待确认”状态；网络结果不明确或进程中断后，先在线核对近期同内容块，至少等待 2 分钟再重试，降低重复写入风险
- 在线读取“手表收藏”中的页面引用，支持子页面导航和常用文字块展示
- 在已加载的当前页面内按关键词筛选收藏页、文字块和数据库行；支持空格分隔的多关键词，筛选不发起额外请求且不落盘
- 使用 Wolai 官方 GET /databases/{id} 在线读取嵌入式数据表格；按行卡片展示列名与单元格，并可进入行页面
- 阅读内容只保存在当前界面内存中，退出后不落盘；图片、视频和附件只显示占位提示
- 针对圆屏预留安全边距
- 从左向右滑动退出
- 仅使用 HTTPS；Token 不写入源码、Gradle 配置或 APK
- 安装时通过 ADB 标准输入注入 Token，随后使用 Android Keystore 加密保存并删除明文文件

目标页面和收藏目录的页面 ID 属于本地配置，不写入源码。复制 `local.properties.example` 中的示例项到未纳入版本控制的 `local.properties`，或设置对应环境变量。

未配置页面 ID 时，速记仍可保存到本地但不会发起写入；收藏页显示明确的配置提示。授权失效、无权限、页面不存在、限流、服务异常和断网均转换为适合圆屏阅读的提示，不展示服务端原始响应。

## 版本

- 0.3.3（versionCode 6）：增加仅 Debug 构建可启用的确定性断网界面与不确定写入故障注入回归；Release 构建无法启用测试故障。
- 0.3.2（versionCode 5）：增加当前在线页面的内存筛选，覆盖收藏页、正文块和数据库行。
- 0.3.1（versionCode 4）：增加不确定写入对账、配置校验和分类错误提示。
- 对账是基于官方开放 API 返回的近期文本块做的本地保护，并非服务端幂等保证；相同文本在非常接近的时间内重复提交时仍应进行人工核对。

## 构建

```powershell
.\gradlew.bat :app:assembleDebug :app:lintDebug
```

## 设备测试

安装脚本只从进程或 Windows 用户环境变量读取 `WOLAI_OPENAPI_TOKEN`，不会打印 Token：

```powershell
.\scripts\check_device.ps1
.\scripts\install_and_provision.ps1
```

安装 Debug 主 APK 和 Android 测试 APK 后，可运行不输出私人内容的自动回归：

```powershell
adb shell am instrument -w -e mode offline-ui com.canace.x3wolainotes.test/com.canace.x3wolainotes.MvpTestRunner
adb shell am instrument -w com.canace.x3wolainotes.test/com.canace.x3wolainotes.MvpTestRunner
```

脚本从 `ANDROID_SERIAL` 读取设备序列号，并从 `ANDROID_SDK_ROOT`、`ANDROID_HOME` 或 `PATH` 查找 ADB；也可显式传入 `-Serial` 和 `-AndroidSdk`。仓库不保存具体设备编号。

## 安全边界

- 不 Root、不解锁 Bootloader、不修改系统分区。
- Debug APK 不包含 Wolai App Secret 或 Token。
- Release APK 在本地保持未签名；正式发布必须使用单独、安全备份的 Release Key，不使用共享 Debug Key。
- 卸载应用会删除设备端加密 Token 和本地缓存。
- 当前是单用户自建应用 MVP；若未来面向其他 Wolai 用户发布，应改用正式用户授权流程或手机中转。
- 数据表格要求自建应用具备所需的数据表格权限；接口异常通过 Wolai 官方开发者文档底部的问题反馈入口提交。
