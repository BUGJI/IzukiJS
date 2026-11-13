# AGENTS.md

面向在本仓库工作的 AI 协作者。人类贡献流程见 [CONTRIBUTING.md](CONTRIBUTING.md)，
架构见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)。

## 仓库速览

- 单模块 Android 应用：`app/`，Kotlin + Jetpack Compose + QuickJS。
- 包根：`app/src/main/java/com/benton/izukijs/`。
- 脚本门面：`app/src/main/assets/runtime/prelude.js`。
- JNI 中断垫片：`app/src/main/cpp/`。
- 文档入口：`docs/README.md`；开发文档全部为简体中文。
- 构建产物、`.gradle`、`.idea`、`build/` 等已在 `.gitignore`，不要提交。

## 常用命令

```bash
./gradlew :app:assembleDebug
./gradlew testDebugUnitTest
./gradlew lintDebug
python3 scripts/check_versions.py
python3 scripts/check_docs.py
```

Windows 用 `gradlew.bat`。没有单独的 format 命令；遵循 `.editorconfig` 与
`kotlin.code.style=official`。

## 修改前先定位

| 任务 | 先看 |
|---|---|
| 控制后端 / 能力协商 | `model/ControlMode.kt`、`model/Capability.kt`、`controller/DeviceController.kt`、`controller/ControllerManager.kt` |
| 脚本 API | `runtime/api/ScriptApiCatalog.kt`、`runtime/api/ScriptApiEnvironment.kt`、`assets/runtime/prelude.js` |
| 脚本运行状态机 | `runtime/ScriptExecutionManager.kt`、`runtime/RunPreparer.kt`、`runtime/JsEngine.kt` |
| 截图 / 虚拟屏 | `runtime/api/DeviceApiBundle.kt`、`service/ScreenCapture.kt`、`virtualscreen/VirtualScreenManager.kt` |
| 识别 / OCR | `vision/VisionEngine.kt`、`ocr/OcrProcessor.kt` |
| pipeline | `pipeline/PipelineSpec.kt`、`PipelineParser.kt`、`DevicePipelineActions.kt` |
| MCP | `mcp/McpServerController.kt`、`mcp/tools/McpToolRegistry.kt`、`ai/AgentTools.kt` |
| 配置 / 持久化 | `data/`、`core/prefs/PrefsStateStore.kt`、`backup/ConfigSections.kt` |
| UI | `ui/<feature>/`、`ui/navigation/IzukiNavHost.kt` |

## 必须遵守

1. **单一事实源**：脚本命名空间与 `@need` 只改 `ScriptApiCatalog`；pipeline 类型只改
   `PipelineSpec.kt`；控制能力矩阵只改 `docs/CONTROL_MODES.md`。
2. **失败降级**：设备 / 网络 / 权限失败返回 `false` / `null` / 带 `ok:false` 的 JSON，
   不要把异常抛到脚本或 MCP 客户端之外。
3. **并发**：`ScriptExecutionManager` 的启动 / 停止 / 收尾必须在同一把 `runLock` 内完成
   判断与更新；旧 run 不得清理新 run 的状态。
4. **脚本与 MCP 一致**：设备操作优先复用 `DeviceApiBundle` / `AgentTools`，不要另写一套。
5. **安全**：不记录或提交令牌、API Key、PIN、设备序列号；Shell、解锁、MCP、录制相关改动
   要检查 `ToolPolicy` / `ShellGuard` / 配置开关。
6. **文档同步**：用户可见行为变更时更新 `docs/` 对应文档与 `CHANGELOG.md` 的「未发布」。
7. **版本号**：只改 `gradle.properties` 与 `gradle/libs.versions.toml`；
   不要硬编码到 `app/build.gradle.kts` 或 README 徽章。

## 代码约定

- Kotlin 官方风格，4 空格；注释用中文，解释原因与边界，不复述代码。
- 新依赖需有明确理由；当前刻意使用手写 DI、`org.json`、`HttpURLConnection`。
- 新增用户可见文案同时改 `res/values/strings.xml` 与 `res/values-en/strings.xml`。
- Compose 页面不要直接做阻塞设备操作；状态放到 ViewModel / 仓库。
- 纯逻辑尽量不依赖 `android.*`，以便 JVM 单测（参考 `PipelineRunner` / `RecordingReplay`）。

## 验证清单

完成改动后至少运行：

```bash
./gradlew testDebugUnitTest
python3 scripts/check_versions.py
python3 scripts/check_docs.py
```

涉及 UI、权限、清单、发布配置时再运行：

```bash
./gradlew lintDebug
./gradlew :app:assembleDebug
```

无法运行设备验证时，在结果中明确写出「未在真机验证」及需要覆盖的矩阵
（Android 版本、控制后端、前后台 / 虚拟屏、旋转）。

## 常见陷阱

- `prelude.js` 是门面层，Kotlin 侧方法名带 `Raw`；只加 Kotlin 方法而不加门面，
  脚本侧可能拿不到期望的对象形状。
- `@need` 只控制注册，不是安全沙箱；输入、`device`、`permissions`、`captureScreen` 始终可用。
- 虚拟屏激活时 `ControllerManager.targetDisplayId` 非空，只选择 `supportsDisplayTarget()`
  的后端；无障碍 / HID 不应参与。
- 截图前后会经 `OverlayCoordinator` 隐藏悬浮层；新增截图路径不要绕过它。
- 日志只记录键名，不记录环境变量 / Header / 请求体的值。
- 配置备份包含明文密钥；锁屏 PIN / 图案不参与导出，只在重置时清理。
- MCP 工具默认走单线程设备队列；长任务用 `OperationRegistry`，查询类工具设
  `synchronous = true`。
- 发布 CI 缺 keystore 会直接失败，不会回退 debug 签名；签名指纹写死在 `release.yml`。

## 文档地图

- [docs/README.md](docs/README.md)：索引与按任务查找
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)：分层、链路、持久化、线程
- [docs/EXTENDING.md](docs/EXTENDING.md)：新增后端 / API / MCP 工具 / pipeline
- [docs/TESTING.md](docs/TESTING.md)：测试分层与手动矩阵
- [docs/RELEASING.md](docs/RELEASING.md)：构建、签名、CI、Release
- [docs/CONTROL_MODES.md](docs/CONTROL_MODES.md)：能力矩阵与限制
- [docs/SCRIPT_API.md](docs/SCRIPT_API.md)：脚本 API
- [docs/TOOLS.md](docs/TOOLS.md)：界面、权限、工具
- [docs/MCP.md](docs/MCP.md)：MCP 服务
- [docs/BLE_HID_PROTOCOL.md](docs/BLE_HID_PROTOCOL.md)：HID 协议
- [docs/PERF.md](docs/PERF.md)：性能基线
