# 扩展指南

本文说明几个常见扩展点的落点、步骤与验证方式。先读
[ARCHITECTURE.md](ARCHITECTURE.md) 了解包职责与关键链路。

扩展前请遵守两条原则：

1. **单一事实源**：能从一个注册表 / 枚举派生的东西，不要在多处手写清单。
2. **脚本与 MCP 行为一致**：设备操作优先复用 `runtime/api/DeviceApiBundle` 与
   `ai/AgentTools`，避免两套语义。

## 新增控制后端

适用场景：接入一种新的输入 / 截图 / Shell 通道。现有后端见
[CONTROL_MODES.md](CONTROL_MODES.md)。

### 1. 声明控制模式

在 `model/ControlMode.kt` 增加枚举项：

```kotlin
enum class ControlMode(val displayName: String, val description: String) {
    // ...
    MY_BACKEND(
        displayName = "我的后端",
        description = "说明它通过什么机制工作、需要什么授权。",
    ),
}
```

检查所有 `when (mode)` / `when (ControlMode)` 的穷举分支；
搜索 `ControlMode.` 与 `when (.*mode` 可快速定位。

### 2. 实现 `DeviceController`

在 `controller/<backend>/` 新建控制器，只覆写真正支持的能力：

```kotlin
class MyBackendController(
    private val transport: MyTransport,
) : DeviceController {

    override val mode: ControlMode = ControlMode.MY_BACKEND

    override fun isReady(): Boolean = transport.isConnected

    override fun capabilities(): Set<Capability> = buildSet {
        add(Capability.GESTURE)
        add(Capability.KEY)
        if (transport.canScreenshot) add(Capability.SCREENSHOT)
    }

    override fun click(x: Float, y: Float): Boolean = transport.tap(x, y)

    override fun pressKey(keyCode: Int): Boolean = transport.key(keyCode)

    override fun screenshot(): Bitmap? = transport.screenshot()

    // 不支持的接口保持默认实现（返回 false / null），不要抛异常。
}
```

约定：

- `capabilities()` 应反映**当前**可用能力，而不是固定全集；连接状态变化后由管理器重新注册。
- 需要定向到虚拟屏的后端才覆写 `supportsDisplayTarget()`。
- 输入 / 截图前若会与悬浮窗重叠，参考 `InputApi` 的 `OverlayCoordinator` 处理。
- 耗时操作不要在调用线程里做无限等待；失败返回 `false` / `null`，由上层降级。

### 3. 增加管理器并注册

参考 `ShizukuManager` / `AdbManager` / `RootManager` / `HidManager`：

```kotlin
class MyBackendManager(
    private val context: Context,
    private val controllers: ControllerManager,
    private val logBus: LogBus,
    private val scope: CoroutineScope,
) {
    private val controller by lazy { MyBackendController(/* ... */) }

    fun init() {
        scope.launch {
            transport.state.collect { state ->
                if (state == State.Connected) {
                    controllers.register(controller)
                } else {
                    controllers.unregister(ControlMode.MY_BACKEND)
                }
            }
        }
    }
}
```

要点：

- 同一 `ControlMode` 重复注册会替换旧实例（`ControllerManager.register` 已处理）。
- 断开时务必 `unregister`，否则 `isReady() == false` 的后端仍会被枚举。
- 在 `di/AppContainer.kt` 创建并 `init()`；如果管理器依赖应用作用域，显式传入。

### 4. 权限与设置界面

- 授权入口：`ui/permission/PermissionDeviceCards.kt` / `PermissionScreen.kt`；
- 控制偏好：`controller/ControllerSettings.kt` + `ui/settings/ControlSettingsPage.kt`；
- 专用配置页：参考 `ui/adb/AdbSetupScreen.kt` / `ui/hid/HidSetupScreen.kt`；
- 能力状态展示：`permissions.*` 与运行页的「权限与能力」卡片。

新增权限时同步 `AndroidManifest.xml`，并在 `PermissionsApi` 暴露只读查询。
不要把「是否授权」写死为 `true`；设备 / 系统版本差异应返回真实状态。

### 5. 文档与测试

- 更新 [CONTROL_MODES.md](CONTROL_MODES.md) 的能力矩阵、授权方式、风险与限制；
- 如果新增 `@need` / 脚本可查询能力，更新 `SCRIPT_API.md`；
- 协议帧、状态机、坐标映射优先抽成纯函数并加 JVM 单测
  （参考 `HidProtocolGestureTest`、`AdbShellProtocolTest`）；
- 涉及虚拟屏定向时补 `targetDisplayId` 相关测试或手动验证。

## 新增脚本 API

适用场景：给脚本增加全局函数或命名空间。

### 1. 在 `ScriptApiCatalog` 登记

`runtime/api/ScriptApiCatalog.kt` 是命名空间、`@need`、编辑器补全的唯一来源。

```kotlin
Entry(
    name = "myapi",
    kind = Kind.NAMESPACE,
    need = "myapi",
    suggestions = listOf("doThing(", "available("),
),
```

- `need = null`：始终可用；
- `need = "xxx"`：只有 `// @need xxx` 或未声明 `@need` 时才注册；
- `suggestions` 会进入编辑器补全；
- 如果只需要挂在现有命名空间下，新增方法即可，不必加 `Entry`。

### 2. 实现 API 类

放在 `runtime/api/`，每个对脚本暴露的方法都要加 `@JavascriptInterface`：

```kotlin
class MyApi(
    private val logBus: LogBus,
    private val settingsProvider: () -> MySettings,
) {
    @JavascriptInterface
    fun available(): Boolean = settingsProvider().enabled

    /** 复合参数 / 返回值用 JSON 字符串，方法名以 Raw 结尾。 */
    @JavascriptInterface
    fun doThingRaw(optionsJson: String): String {
        val options = JSONObject(optionsJson.ifBlank { "{}" })
        return JSONObject().put("ok", true).toString()
    }
}
```

约定：

- QuickJS 桥接只可靠传递基本类型与字符串；对象 / 数组用 JSON（`Raw` 后缀）。
- `Raw` 方法要捕获异常并返回带 `ok/error` 的 JSON，不要把异常抛回 JS。
- 需要脚本停止时检查 `isExitRequested`，长循环要分片。
- 有状态对象（缓存、连接、录制）按运行创建；无状态对象可复用 `DeviceApiBundle`。

### 3. 在 `ScriptApiEnvironment.bind()` 注册

```kotlin
if (allowed(needs, "myapi")) {
    ctx.addJavascriptInterface(
        MyApi(logBus) { runtimeSettings.current().myApi },
        "myapi",
    )
}
```

`allowed()` 会从 `ScriptApiCatalog` 查 `need`；未登记的名字不会受 `@need` 控制。

### 4. 在 `prelude.js` 包装门面（可选）

如果 `Raw` 返回值需要转成对象、或需要默认参数 / 重载，在
`app/src/main/assets/runtime/prelude.js` 中包装：

```js
if (typeof myapi !== 'undefined') {
  myapi.doThing = function (options) {
    try {
      return JSON.parse(myapi.doThingRaw(JSON.stringify(options || {})));
    } catch (e) {
      return { ok: false, error: String(e) };
    }
  };
}
```

注意：`prelude.js` 在每次运行时加载，门面方法不要缓存跨运行状态。

### 5. 暴露给 MCP / AI（可选）

如果这是设备操作能力，优先在 `ai/AgentTools.kt` 的 `dispatch()` 与
`AgentToolSpecs.kt` 中增加，并由 `McpToolRegistry` 自动复用；这样 AI Agent 与 MCP
同时获得该能力，且统一经过 `ToolPolicy` / `ShellGuard`。

### 6. 文档与测试

- 更新 [SCRIPT_API.md](SCRIPT_API.md)，包括返回结构、失败语义、`@need` 名称；
- 补 `app/src/test/.../runtime/api/` 下的纯逻辑测试；
- 验证三件事：**未声明 `@need` 时可见**、**声明后未列出时不可见**、**补全里存在**。

## 新增 MCP 工具

### 方案 A：复用 `AgentTools`（推荐用于设备操作）

1. 在 `ai/AgentToolSpecs.kt` 增加 `ToolSpec`（名称、描述、JSON Schema）；
2. 在 `ai/AgentTools.dispatch()` 增加分支；
3. 如需安全策略，在 `AgentTools.policyViolation()` 中处理；
4. `McpToolRegistry.sharedTools()` 会自动把它暴露给 MCP；需要 per-call `target`
   时加入 `McpToolRegistry` 的 `TARGETED_SHARED`，需要只读标注时加入 `READ_ONLY_SHARED`。

这样 AI Agent 与 MCP 共用同一实现，避免两套行为分叉。

### 方案 B：新增独立 `McpTool`

适用于截图、日志、运行结果、解锁、录制、脚本、批量等 MCP 专属能力：

```kotlin
private fun myTool(): McpTool = McpTool(
    name = "my_tool",
    description = "做什么、返回什么、失败时是什么样",
    inputSchema = McpSchemas.objectSchema(
        properties = mapOf(
            "query" to McpSchemas.string("查询内容"),
            "limit" to McpSchemas.integer("返回上限"),
        ),
        required = listOf("query"),
    ),
    readOnly = true,
) { args ->
    McpToolResult.text(JSONObject().put("ok", true).toString())
}
```

然后在 `McpToolRegistry.buildCatalog()` 中按配置条件加入：

```kotlin
if (config.allowMyFeature) add(myTool())
```

约定：

- 工具返回错误时优先 `McpToolResult.error("可读原因")` 或 `{"ok":false,...}`；
- 会修改设备状态的长任务放到异步操作表（`OperationRegistry`），用
  `synchronous = true` 提供结果查询工具，避免堵住单线程设备队列；
- 受影响于配置开关的工具要在 `McpConfig` 中增加字段、设置页开关、README / MCP 文档；
- 审计日志与鉴权由 `McpHttpHandler` / `McpAuditLog` 统一处理，不要在工具内重复实现。

### 文档与测试

- 更新 [MCP.md](MCP.md) 的工具清单与参数；
- 补工具 schema / 参数解析测试（参考 `OperationRegistryTest`、`McpConfigTest`）；
- 手动验证 `tools/list` 与 `tools/call`，以及关闭开关后工具消失。

## 新增 pipeline 识别 / 动作

### 识别

1. `pipeline/PipelineSpec.kt` 的 `RecognitionType` 增加枚举与别名；
2. `PipelineParser` 增加参数解析（如需要）；
3. `PipelineValidator` 增加必填字段校验；
4. `ScreenshotPipelineVision` / `VisionEngine` 增加执行分支；
5. `PipelineDumper` 增加归一化输出；
6. 更新 [SCRIPT_API.md](SCRIPT_API.md) 的 pipeline 一节；
7. 补 `PipelineParserTest` / `PipelineValidatorTest` / `PipelineRunnerTest`。

### 动作

1. `ActionType` 增加枚举与别名；
2. `DevicePipelineActions.execute()` 增加真实执行；
3. `ReplayPipelineActions` 增加录制回放匹配（如果可回放）；
4. `PipelineValidator` 校验参数；
5. 如果底层控制器暂时不支持，明确返回失败并说明原因（参考 `LongPressKey`），
   不要静默当成成功；
6. 补回放测试 `ReplayPipelineActionsTest`。

`PipelineRunner` 本身只依赖 `PipelineVision` / `PipelineActions` 抽象，新增类型时不要
把 Android 依赖塞进 runner，否则会破坏纯 JVM 测试。

## 新增通知渠道

1. `core/notification/NotifyModels.kt` 增加 `NotifyChannelType` 与配置字段；
2. `notification/NotificationDispatcher.kt` 增加发送实现（HTTP / 本地）；
3. 需要签名 / 加密时复用 `NotifySigning` / `NotifyCrypto`；
4. `ui/settings/NotificationChannelPage.kt` 增加配置表单；
5. `notification/NotificationSettingsRepository.kt` 负责持久化，敏感字段用 `SecretCipher`；
6. 更新 [TOOLS.md](TOOLS.md) 的渠道说明与 [SCRIPT_API.md](SCRIPT_API.md) 的 `push`；
7. 补渠道参数 / 签名测试（参考 `NotifyModelsTest`、`NotifyCryptoTest`）。

## 修改运行链路

涉及 `ScriptExecutionManager` / `RunPreparer` / `JsEngine` 时，先确认：

- **准备失败可回滚**：`rollbackStart` 必须结束 run 记录、释放锁、停止前台服务；
- **停止语义不混淆**：用户停止用 `stopRequested`，脚本 `exit()` 用 `exitRequested`；
- **旧 run 不污染新 run**：共享字段只在 `activeSession === session` 时清理；
- **前台服务按 runId 停止**：避免旧收尾停掉新 run；
- **虚拟屏只销毁本次创建的**：比较 `startGeneration`。

新增运行参数时优先走 `@env` + `RunPreparer`，不要把新配置直接塞进执行器。

## 扩展检查清单

- [ ] 只改一处事实源，其他位置由它派生
- [ ] 失败路径有降级，不把异常抛到脚本 / MCP 客户端之外
- [ ] 权限、安全策略、配置开关齐全
- [ ] 脚本与 MCP 行为一致（设备操作）
- [ ] 单元测试覆盖纯逻辑；设备相关改动有手动验证步骤
- [ ] 更新 `SCRIPT_API.md` / `TOOLS.md` / `MCP.md` / `CONTROL_MODES.md` 中受影响者
- [ ] 更新 `CHANGELOG.md` 的「未发布」
- [ ] `./gradlew testDebugUnitTest`、`python3 scripts/check_versions.py`、
      `python3 scripts/check_docs.py` 通过
