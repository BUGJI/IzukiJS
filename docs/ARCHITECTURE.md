# 架构说明

本文面向需要修改代码的贡献者，说明项目的分层、包职责、关键对象与运行链路。
用户视角的功能说明见 [TOOLS.md](TOOLS.md)，脚本接口见 [SCRIPT_API.md](SCRIPT_API.md)。

## 总览

Izuki JS 是**单模块 Android 应用**（`:app`），使用 Kotlin + Jetpack Compose，运行时通过
QuickJS 执行 JavaScript 脚本。整体不是严格 Clean Architecture，而是按「UI / 执行 / 能力 /
数据」分层，关键边界用接口和手写容器隔开：

```text
┌──────────────────────────────────────────────────────────────┐
│ ui/                     Compose 页面、导航、ViewModel          │
├──────────────────────────────────────────────────────────────┤
│ service/ schedule/ flow/ subscription/ update/               │
│   前台服务、无障碍服务、录屏、悬浮窗、定时 / 编排 / 订阅 / 更新  │
├──────────────────────────────────────────────────────────────┤
│ runtime/               脚本执行状态机、RunPreparer、JsEngine   │
│   runtime/api/         脚本 API 门面与 QuickJS 桥接             │
├──────────────────────────────────────────────────────────────┤
│ controller/            能力协商与各控制后端                     │
│ vision/ ocr/ pipeline/ 识别、OCR、声明式流水线                 │
├──────────────────────────────────────────────────────────────┤
│ virtualscreen/ privileged/  后台虚拟屏与 Shizuku UserService   │
├──────────────────────────────────────────────────────────────┤
│ ai/ mcp/ notification/  AI Agent、MCP 服务、通知与推送          │
├──────────────────────────────────────────────────────────────┤
│ data/ core/ model/ security/ backup/ developer/ i18n/         │
│   仓库、日志、性能、权限、偏好、加密、备份、开发者调优          │
└──────────────────────────────────────────────────────────────┘
```

依赖方向总体向下；少数跨层协作出现在：

- `ui` 通过 `AppContainer` 拿到仓库与执行器，不直接持有 `DeviceController`；
- `service` 通过 `RunControl` 端口控制脚本，避免反向依赖 `runtime` 实现；
- `mcp` 与脚本运行时共用 `DeviceApiBundle`，保证外部 AI 与脚本操作语义一致；
- `virtualscreen` 同时依赖 `controller` 与 `privileged`，对上层暴露统一目标。

## 包职责

路径均相对 `app/src/main/java/com/benton/izukijs/`。

| 包 | 职责 | 关键类 |
|---|---|---|
| `ai/` | OpenAI 兼容客户端、无状态 Agent 循环、AI 可用工具与安全策略 | `AiAgent`、`AiClient`、`AgentTools`、`ToolPolicy`、`ShellGuard`、`AiPrompts` |
| `backup/` | 配置导出 / 导入 / 重置，按 section 注册 | `ConfigBackupManager`、`ConfigSections` |
| `controller/` | 控制抽象、能力协商、控件树 / 胶囊 / Root / HID / ADB 后端、操作录制 | `DeviceController`、`ControllerManager`、`UiautomatorDumper` |
| `controller/record/` | 脚本 / 用户操作录制、回放、标注、导出脚本 | `RecordingSession`、`RecordingStore`、`RecordingReplay` |
| `core/` | 跨模块基础设施 | `LogBus`、`PerfMetrics`、`RunControl`、`AtomicFile`、`ScriptExitException`、`HttpSettings` |
| `data/` | 脚本仓库、历史版本、环境变量、运行历史、编辑器 / 磁贴 / 解锁偏好 | `ScriptRepository`、`ScriptHistoryRepository`、`ScriptEnvRepository` |
| `developer/` | 开发者选项与运行时阈值（超时、日志上限、录制帧数等） | `DeveloperSettings`、`DeveloperTuning` |
| `di/` | 手写依赖容器 | `AppContainer`、`McpContainer` |
| `flow/` | 多脚本串行编排 | `FlowRunner`、`FlowPlanner`、`FlowManager` |
| `i18n/` | 中英文语言切换与 Context 包装 | `LanguagePreferences`、`LanguageRepository` |
| `mcp/` | MCP 服务生命周期、HTTP 传输、JSON-RPC、资源与工具 | `McpServerController`、`McpHttpHandler`、`McpProtocolHandler`、`McpToolRegistry` |
| `model/` | 纯数据模型与枚举 | `ControlMode`、`Capability`、`RunMode`、`EnvText` |
| `notification/` | 本地通知与远程推送渠道、签名、加密 | `NotificationDispatcher`、`NotifyCrypto`、`HttpTransport` |
| `ocr/` | 本地 MLKit 与在线 OCR 路由、行聚合 | `OcrProcessor`、`LocalOcrEngine`、`OnlineOcrEngine` |
| `pipeline/` | MaaFramework 风格 Pipeline 的解析、校验、执行、回放 | `PipelineParser`、`PipelineRunner`、`DevicePipelineActions` |
| `privileged/` | Shizuku UserService、AIDL、隐藏 API、解锁 / 虚拟屏协议 | `VirtualScreenUserService`、`UnlockUserService`、`VirtualScreenServiceClient` |
| `runtime/` | 脚本执行状态机、准备阶段、QuickJS 封装、日志文件 | `ScriptExecutionManager`、`RunPreparer`、`JsEngine`、`ScriptNeeds` |
| `runtime/api/` | 挂到 QuickJS 的 API 门面与底层 `Raw` 方法 | `ScriptApiCatalog`、`ScriptApiEnvironment`、`DeviceApiBundle`、`InputApi` |
| `schedule/` | 定时 / 间隔 / Cron / 事件触发、条件、重试、开机重注册 | `ScheduleManager`、`ScheduleRunner`、`CronExpression` |
| `security/` | Android Keystore AES-GCM 字符串加密 | `SecretCipher` |
| `service/` | 前台服务、无障碍服务、持续录屏、悬浮窗、调试叠加、快捷磁贴 | `ScriptForegroundService`、`IzukiAccessibilityService`、`ScreenCapture` |
| `subscription/` | 脚本远程订阅与定时同步 | `SubscriptionManager`、`SubscriptionSync` |
| `ui/` | Compose 页面、导航、主题、PiP、编辑器 | `IzukiNavHost`、`EditorScreen`、`SettingsScreen` |
| `update/` | GitHub Release 检查、下载、安装 | `UpdateManager`、`UpdateChecker`、`ApkInstaller` |
| `virtualscreen/` | 后台虚拟屏生命周期、帧源、输入、预览、目标切换 | `VirtualScreenManager`、`VirtualScreenSettings` |

`app/src/main/cpp/` 是 QuickJS 中断回调的 JNI 垫片；`app/src/main/assets/runtime/prelude.js`
是脚本门面层；`app/src/main/assets/scripts/` 是首次安装时释放到脚本中心的内置脚本。

## 启动与依赖组装

1. `IzukiApp` 创建 `AppContainer`，`AppContainer.init()` 完成一次性初始化：
   - 日志落盘、性能采集回调、开发者调优阈值；
   - 释放内置脚本；
   - 初始化 Shizuku / Root / HID / ADB 管理器，它们按就绪状态向 `ControllerManager` 注册 / 注销；
   - 启动定时、编排、订阅重注册与自动更新检查；
   - 把持久化的控制偏好实时同步到 `ControllerManager`。
2. `MainActivity` 负责 Compose 入口、主题、语言包装、PiP 和 MCP 前台服务恢复。
3. `AppContainer` 是手写单例图；`McpContainer` 单独组装 MCP 相关对象，避免容器继续膨胀。
   取用方式：`Context.appContainer`。

新增全局单例时优先放入 `AppContainer` 并明确生命周期；只在 MCP 使用的放入 `McpContainer`。

## 控制抽象与能力协商

核心模型：

- `model/ControlMode.kt`：`ACCESSIBILITY` / `SHIZUKU` / `ADB` / `HID` / `ROOT`。
- `model/Capability.kt`：`GESTURE`、`ABSOLUTE_POINTER`、`RELATIVE_POINTER`、`KEY`、`TEXT`、
  `SCREENSHOT`、`NODE_TREE`、`SHELL`、`GLOBAL_ACTION`、`ROOT`，并分为 `READ` / `OPERATE` 两组。
- `controller/DeviceController.kt`：把能力拆成 `PointerController` / `GestureController` /
  `KeyController` / `TextController` / `ScreenshotController` / `NodeTreeController` /
  `ShellController`，每个方法都有安全默认值。
- `controller/ControllerManager.kt`：注册中心。上层按**能力**而不是整体后端取实现：

```kotlin
controllers.controllerFor(Capability.SCREENSHOT)
controllers.controllerFor(Capability.SHELL)
```

选择顺序：

1. `capabilityPreferences[capability]`（按能力指定）；
2. 能力的 `group` 对应的全局 `readMode` / `operateMode`；
3. 注册顺序。

过滤条件：未被 `disabledModes` 停用、`isReady()` 为真、声明包含该能力；当
`targetDisplayId != null`（虚拟屏激活）时，还要求 `supportsDisplayTarget()`，避免无障碍 / HID
把输入打到主屏。

后端的注册时机：

| 后端 | 注册者 | 就绪条件 |
|---|---|---|
| 无障碍 | `IzukiAccessibilityService` 连接时创建 `AccessibilityController` | 服务实例非空 |
| Shizuku | `ShizukuManager` 监听授权 / 进程状态 | 已授权且后端可用 |
| 无线调试 | `AdbManager` 监听连接状态 | 配对身份存在且连接成功 |
| Root | `RootManager` 异步探测 `su` | `su` 可用 |
| 蓝牙 HID | `HidManager` 监听 GATT 连接 | 设备已连接 |

能力矩阵、降级规则和按键 / 手势限制见 [CONTROL_MODES.md](CONTROL_MODES.md)。

## 脚本运行链路

入口可能来自：运行页、脚本中心、快捷磁贴、定时任务、编排、MCP。最终都收敛到
`runtime/ScriptExecutionManager.run()`。

```text
调用方
  └─ ScriptExecutionManager.run(scriptId, name, source, values, trigger)
       ├─ ScriptNeeds.parse(source)                 // 解析 // @need
       ├─ 同步占用运行锁（同一时间只允许一个脚本）
       ├─ 后台协程 → RunPreparer.prepare(...)
       │    ├─ 解析 @env 默认值并合并全局 / 脚本 / 本次输入
       │    ├─ 决定 RunMode（前台 / 后台虚拟屏）
       │    ├─ 写运行历史、打印参数键名（不打印值）
       │    ├─ 启动 ScriptForegroundService 与日志悬浮窗
       │    └─ 返回 ScriptEnvSession / 虚拟屏设置
       └─ executeRun(...)
            ├─ 自动解锁（可选）
            ├─ WakeLock / 点亮屏幕（可选）
            ├─ 后台模式：启动虚拟屏（可读取脚本字面量分辨率）
            ├─ 预热 OpenCV / OCR
            ├─ 创建 JsEngine
            ├─ 发布引擎并处理「启动期间已请求停止」
            ├─ execute(source, timeout, needs)
            └─ finally：销毁本次虚拟屏、落运行状态、发通知、释放 WakeLock、
                        停止前台服务、重置运行标记
```

关键并发点：

- `runLock` 保护 `engine` / `activeSession` / `_running` 的状态转换，避免停止请求丢失和
  ABA 问题；旧 run 的收尾不会清掉新 run 的状态。
- 用户停止与脚本 `exit()` 都置退出标志，但只有 `stopRequested` 表示用户停止，
  因此编排不会把用户停止当成成功继续下一步。
- 准备阶段抛异常会走 `rollbackStart`，防止 `_running` 永久卡在 `true`。

## QuickJS 与脚本 API 桥接

- `runtime/JsEngine.kt`：每个脚本一个 `QuickJS` runtime + context；安装中断回调、
  加载 `prelude.js`、执行脚本源码，并由看门狗线程处理运行超时。
- `runtime/QuickJsInterrupt.kt` + `cpp/izuki_quickjs_shim.cpp`：让纯计算死循环也能被停止。
  原生库不可用时退化为在 `sleep` / 等待 / 原生调用返回时停止。
- `runtime/api/ScriptApiCatalog.kt`：**脚本 API 的单一事实源**。命名空间是否注册、
  `@need` 可声明哪些名字、编辑器补全都从这里派生。
- `runtime/api/ScriptApiEnvironment.kt`：按本次运行创建 API 对象并绑定到 JS context。
  - 无状态 API（`DeviceApi`、`AppApi`、`ShellApi` 等）复用 `DeviceApiBundle`；
  - 有状态 API（`SelectorApi`、`PipelineApi`、`RecordApi`、`ImageApi`）按运行创建；
  - `@need` 只决定是否注册，不改变 API 行为。
- `assets/runtime/prelude.js`：把 `*Raw` 底层方法包装成好用的 JS 门面（如
  `images.findImage` 包装 `findImageRegionRaw`），并实现 `require()` 模块系统。
- `runtime/ScriptNeeds.kt`：解析 `// @need`，未知能力直接拒绝运行；可声明名字来自
  `ScriptApiCatalog.needKeys`。

新增 API 的完整步骤见 [EXTENDING.md](EXTENDING.md#新增脚本-api)。注意不要在多处手写
命名空间清单，否则容易出现「注册了但补全没有」「@need 能写但不生效」的漂移。

## 截图、识别与虚拟屏

`runtime/api/DeviceApiBundle.kt` 是脚本与 MCP 共用的设备 API 装配：

- `captureProvider(source, waitMs)`：按 `ScreenTarget` 解析到主屏或虚拟屏；
- 主屏截图优先 `ScreenCapture`（MediaProjection 持续录屏），失败才回退到控制后端；
- 截图前后经 `OverlayCoordinator` 临时隐藏悬浮层，避免把悬浮窗截进画面；
- 截图观察点 `RecordingHooks` 供录制复用一个实例。

虚拟屏：

- `virtualscreen/VirtualScreenManager.kt` 负责生命周期、帧源、目标切换、输入、预览、心跳；
- 特权侧 `privileged/VirtualScreenUserService.kt` 通过 AIDL
  `IVirtualScreenService` 在 Shizuku UserService 进程中创建 `VirtualDisplay` 并拉起应用；
- 画面采集与 PiP 预览在 App 进程内完成，特权进程只做创建 / 销毁 / 前台包名查询；
- 虚拟屏激活时 `ControllerManager.targetDisplayId` 非空，只选择支持 display 定向的后端。

识别：

- `vision/VisionEngine.kt` 统一解析 ROI、加载模板、分派 `TemplateMatch` / `ColorMatch` /
  `FeatureMatch` / `OCR` / 神经网络 / `And` / `Or`；
- `ocr/OcrProcessor.kt` 按设置路由本地 MLKit 或在线服务，在线失败回退本地；
- `pipeline/` 在识别之上提供节点图执行与录制回放，`PipelineRunner` 依赖抽象的
  `PipelineVision` / `PipelineActions`，便于纯 JVM 测试。

## MCP 服务

```text
McpServerService（前台服务保活）
  └─ McpServerController（生命周期、状态、会话表、异步操作表）
       └─ HttpServer（loopback / LAN）
            └─ McpHttpHandler（Bearer 鉴权、会话、CORS/端点）
                 └─ McpProtocolHandler（initialize / tools / resources）
                      ├─ McpToolRegistry → McpTool / AgentTools
                      └─ McpResources
```

- `McpToolRegistry` 根据 `McpConfig` 动态构建工具目录；配置变化后缓存失效。
- 设备类工具优先复用 `ai/AgentTools`，保证 AI Agent 与 MCP 的点击 / OCR / Shell 行为一致；
  MCP 额外提供截图、虚拟屏、日志、运行结果、解锁、录制、脚本、批量等工具。
- 工具在单线程设备队列执行；`synchronous = true` 的工具用于查询异步结果，避免被长任务堵死。
- 安全护栏在 `AgentTools.dispatch()` 统一处理，`batch` 内嵌动作也无法绕过。
- 协议、工具清单、客户端配置与排障见 [MCP.md](MCP.md)。

## 自动化与通知

| 模块 | 说明 |
|---|---|
| `schedule/` | `AlarmManager` 注册定时 / 间隔 / Cron，事件触发有 60 秒去抖，开机重注册；`ScheduleRunner` 检查条件后交给 `ScriptExecutionManager` |
| `flow/` | 多脚本串行编排，`runToCompletion` 等一步结束再启动下一步；可被定时任务触发 |
| `subscription/` | 从远程 URL 定时同步脚本，失败保留旧内容 |
| `service/ScriptTileService` | 快捷磁贴：运行中 → 停止，空闲 → 运行钉选脚本 |
| `notification/` | `ScriptExecutionManager` 把运行结果转成 `NotifyEvent`，`NotificationDispatcher` 按渠道、级别、事件、标签过滤后投递；密钥经 `SecretCipher` 加密 |
| `update/` | 检查 GitHub Release、下载 APK、交给 `ApkInstaller` |

## 数据与持久化

应用私有目录（`Context.filesDir`）：

| 路径 | 内容 |
|---|---|
| `scripts/*.js` | 用户脚本；文件名即脚本 id |
| `script_history/<scriptId>/<timestamp>.js` | 脚本历史版本（10 分钟节流，最多 20 个） |
| `logs/app.log` | 运行日志，格式 `时间戳|级别|内容` |
| `recordings/<id>/` | 操作录制：`meta.json` + `events.jsonl` + 帧文件 |
| `global_env.json` | 全局环境变量 |
| `script_env.json` | 脚本级环境变量与运行输入 |
| `schedules.json` | 定时任务 |
| `flows.json` | 脚本编排 |
| `subscriptions.json` | 脚本订阅 |
| `mcp_audit.jsonl` | MCP 脱敏审计日志 |
| `adb_identity.enc` | 无线调试本地身份（加密） |

`Context.cacheDir`：`screenshot_*` / `template_*` / `izuki_shot.png` 等临时图片，
由 `CacheImageStore` 统计和清理。

SharedPreferences（名称即文件）：

| 名称 | 内容 |
|---|---|
| `izukijs_controller` | 控制后端优先级、启停、按能力偏好 |
| `izukijs_capture` | 截图缩放 / JPEG 质量 |
| `izukijs_runtime` | 保持唤醒、超时、磁贴、应用列表、性能基线等 |
| `izukijs_log` | 日志级别、自动清理、体积 / 天数 |
| `izukijs_ai` | AI 接口与系统提示词 |
| `izukijs_ocr` | OCR 路由与在线服务商 |
| `izukijs_http` | 脚本网络开关与域名白名单 |
| `izukijs_mcp` | MCP 端口、绑定、令牌、工具开关 |
| `izukijs_notification` | 通知渠道与过滤 |
| `izukijs_unlock` | 解锁方式与凭据（值经 Keystore 加密） |
| `izukijs_virtual_screen` | 运行模式与虚拟屏分辨率 |
| `izukijs_editor` / `izukijs_appearance` / `izukijs_language` | 编辑器、外观、语言 |
| `izukijs_developer` | 开发者选项 |
| `izukijs_update` / `izukijs_tile` / `izukijs_run_history` / `izukijs_script_list` / `izukijs_scripts` / `izukijs_hid` | 其余偏好 |

加密：`security/SecretCipher.kt` 使用 Android Keystore 的 AES-GCM 密钥
（别名 `izuki_secret_v1`），密文格式 `v1:<base64(iv+ciphertext)>`。Keystore 异常时
加密降级为明文、解密返回空串，保证功能不中断。该密钥无法跨设备迁移，因此含密文的配置
不参与系统自动备份，换机需应用内导出 / 导入。

配置备份：`backup/ConfigSections.kt` 是注册表；新增配置项应在对应仓库实现
`ConfigSection` 并注册，导出 / 导入 / 重置会自动包含。锁屏 PIN / 图案只在重置时清理，
不参与导出。

## 线程与并发

- UI：`AppContainer.uiScope` 为 `Dispatchers.Main.immediate`；Compose 状态通过
  `StateFlow.collectAsStateWithLifecycle()` 订阅。
- 脚本执行：`ScriptExecutionManager` 使用 `SupervisorJob + Dispatchers.Default`，
  准备阶段不阻塞主线程。
- 日志落盘：`LogFileStore` 单线程调度器串行写入，窗口聚合减少 IO。
- MCP：工具在单线程设备队列执行；HTTP / 服务启停在自己的 IO 作用域。
- 录制：写入器后台串行；截图 / 事件按时序落盘。
- 控制后端：大部分操作是同步的 Binder / 进程调用，调用方需避免在主线程直接执行长操作。
- 脚本停止：`runLock` + QuickJS 中断回调；不依赖协作式检查也能打断纯计算循环。

修改并发相关代码时，优先保持「同一把锁内完成判断 + 更新」，并在注释里写清竞态场景。

## 失败与降级语义

- 控制能力不可用时：`controllerFor()` 返回 `null`，上层记录警告或返回 `false` / `null`，
  不抛出到脚本之外。
- 截图：持续录屏 → 控制后端；虚拟屏目标不存在时返回 `null` 并记录警告。
- 控件树：无障碍优先；AI / MCP 在 Shell 后端下用 `uiautomator dump` 兜底，但脚本
  `selector.*` 仍只支持无障碍。
- 后台虚拟屏启动失败：本次回退前台模式并记录警告，不阻断脚本。
- 在线 OCR 失败：自动回退本地。
- AI 服务不支持工具调用：自动切换提示词协议。
- MCP 局域网模式缺少本地网络权限：服务不启动，状态里标记需要授权。
- Keystore 不可用：加密降级，功能继续。

## 相关文档

- [CONTROL_MODES.md](CONTROL_MODES.md)：能力矩阵、风险、限制
- [EXTENDING.md](EXTENDING.md)：扩展点与步骤
- [TESTING.md](TESTING.md)：测试分层与回归方式
- [RELEASING.md](RELEASING.md)：构建、签名、CI
- [PERF.md](PERF.md)：性能采集与对比
