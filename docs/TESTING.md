# 测试指南

项目以 **JVM 单元测试**为主，设备相关能力通过少量 instrumentation 测试和手动矩阵验证。
本文说明测试分层、常用写法、现有覆盖范围与回归方式。

## 测试分层

| 层 | 位置 | 运行环境 | 适合测什么 |
|---|---|---|---|
| JVM 单元测试 | `app/src/test/java/` | 本机 JVM，无 Android 运行时 | 解析器、协议、状态机、纯函数、JSON / 文本处理、录制回放 |
| Instrumentation | `app/src/androidTest/java/` | 真机 / 模拟器 | 需要 `Context`、系统服务、Binder 的集成验证；目前仅少量原型 |
| 手动矩阵 | 真机 | 多版本 / 多后端 | 无障碍、Shizuku、无线调试、Root、HID、虚拟屏、PiP、权限流程 |
| 性能基线 | 真机 | 见 [PERF.md](PERF.md) | 帧耗时、截图 / 找图 / OCR 热路径、日志与悬浮窗开销 |

原则：**能做成纯函数的，不要依赖 Android 框架**。`PipelineRunner`、`RecordingReplay`、
`CronExpression`、`EnvText`、`AdbShellProtocol` 等都是这样保持可测的。

## 运行命令

```bash
# 全部 JVM 单元测试
./gradlew testDebugUnitTest

# 单个类
./gradlew testDebugUnitTest --tests "com.benton.izukijs.pipeline.PipelineRunnerTest"

# 单个方法
./gradlew testDebugUnitTest --tests "com.benton.izukijs.pipeline.PipelineRunnerTest.runsEntryAndFollowsNext"

# Lint
./gradlew lintDebug

# 构建
./gradlew assembleDebug
```

Windows 使用 `gradlew.bat`。测试报告在 `app/build/reports/tests/testDebugUnitTest/index.html`。

Instrumentation 测试需要连接设备：

```bash
./gradlew connectedDebugAndroidTest
```

`AdbPrototypeTest` 是手动原型，需要配对参数，按类注释中的 `adb shell am instrument` 命令运行。

## 现有覆盖范围

`app/src/test/java/` 下约 78 个测试文件，主要覆盖：

| 模块 | 示例 |
|---|---|
| pipeline | `PipelineParserTest`、`PipelineValidatorTest`、`PipelineRunnerTest`、`PipelineDumperTest`、`ReplayPipelineActionsTest` |
| 录制 | `RecordingStoreTest`、`RecordingReplayTest`、`RecordingScriptExporterTest`、`RecordingSuppressionTest`、`RecordingUserActionTest`、`RecordTypesTest` |
| 控制协议 | `HidProtocolGestureTest`、`AdbShellProtocolTest`、`NodeCriteriaMatchTest`、`ScreenAnimationProbeTest`、`ImeStateProbeTest` |
| 运行时 / API | `ColorRegionAccumulatorTest`、`GestureFallbackTest`、`HttpAllowlistTest` |
| OCR | `OcrGeometryTest`、`OcrLineSynthesisTest` |
| MCP | `McpConfigTest`、`HttpServerTest`、`OperationRegistryTest`、`ScriptEditEngineTest`、`ScriptTextDiffTest` |
| 通知 | `NotifyCryptoTest`、`NotifyModelsTest`、`ScriptNotificationOptionsTest` |
| 数据 / 安全 | `AtomicFileTest`、`ScriptNameResolverTest`、`ConfigBackupManagerTest`、`EnvTextTest`、`UnlockRecordingParserTest` |
| AI | `BatchRunnerTest`、`ShellGuardTest`、`ToolPolicyTest` |
| 特权 / 录制解析 | `InputEventReaderTest`、`MultiTouchParserTest`、`TouchCoordMapperTest`、`DisplayPackageResolverTest` |
| 定时 / 编排 | `CronExpressionTest`、`FlowPlannerTest` |

## 写单元测试

### 依赖

只有 JUnit 4 与 `org.json` 可用于 JVM 测试：

```kotlin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
```

没有 Robolectric、MockK、Truth。需要 Android 类时，优先把逻辑抽成不依赖 Android 的
纯函数，或用接口 / lambda 注入。

### 用 Fake 替代设备

`PipelineRunnerTest` 是典型写法：runner 只依赖 `PipelineVision` / `PipelineActions`，
测试用 `FakeVision` / `FakeActions` 控制识别结果与动作记录。

```kotlin
private class FakeActions(
    private val onExecute: ((PipelineNode, Box?) -> PipelineActionResult)? = null,
) : PipelineActions {
    val executed = mutableListOf<String>()

    override fun execute(node: PipelineNode, box: Box?, resolveTarget: (TargetSpec, Box?) -> Box?): PipelineActionResult {
        executed += node.name
        return onExecute?.invoke(node, box) ?: PipelineActionResult(true)
    }

    override fun sleep(millis: Long) = Unit
    override fun isStopRequested() = false
}
```

### JSON 测试夹具

`pipeline/JsonTestUtils.kt` 提供 JSON 构造辅助；测试内也可以直接写多行 JSON 字符串，
保持输入与真实脚本一致，便于复制到 Issue。

### 时间与调度

不要依赖真实时间。`PipelineRunner` 接受 `now: () -> Long`，`ReplayClock` 可手动推进；
`CronExpression` 测试直接构造时间点。

### 录制回放

录制回放是设备行为的回归手段：

1. 在真机上用 `record.start` / MCP `recording` 录制目标流程；
2. 用 `record.validate` 检查结构；
3. 用 `pipeline.replay(recordingId, spec, entry, {strict:true})` 回放并比对动作；
4. 纯解析 / 匹配逻辑补 `RecordingReplayTest` 风格的 JVM 测试。

回放不是 UI 自动化替代品：它验证「同样的截图顺序与动作序列下，识别与决策是否一致」，
不验证真实触摸是否落到目标控件。

## 新增功能时该写什么

| 改动 | 最低测试 |
|---|---|
| 新解析器 / 协议帧 / 坐标映射 | 纯函数单元测试，覆盖正常、边界、错误输入 |
| 新 pipeline 识别 / 动作 | parser + validator + runner；可回放时补 replay |
| 新脚本 API | 参数解析、`@need` 门控、失败返回结构；必要时用纯逻辑抽出 |
| 新 MCP 工具 | schema / 参数解析 / 配置开关；`OperationRegistry` 异步语义 |
| 新控制后端 | 协议编解码单测 + 真机手动矩阵 |
| 新通知渠道 | 参数构造、签名 / 加密、过滤逻辑 |
| 修改运行状态机 | 尽量抽出可测的状态转换；真机验证停止 / 超时 / 虚拟屏销毁 |
| 修改持久化 | `AtomicFile`、导入导出、版本兼容、失败回滚 |

## 手动验证矩阵

没有条件跑全部设备时，至少覆盖与你改动相关的组合：

| 维度 | 建议 |
|---|---|
| Android 版本 | 28（最低）、30、33、34、37（`targetSdk`） |
| 控制后端 | 无障碍、Shizuku、无线调试、Root、HID 中至少两种 |
| 屏幕 | 竖屏 / 横屏 / 折叠或分屏（涉及坐标、截图、悬浮窗时必测） |
| 运行模式 | 前台、后台虚拟屏、PiP |
| 触发方式 | 手动、快捷磁贴、定时、编排、MCP |
| 权限 | 首次授权、已授权、撤销后重进、未授权降级 |

重点回归：

- 脚本停止 / 超时 / 异常后，前台服务、WakeLock、悬浮窗、虚拟屏是否正确清理；
- 旋转后截图、点击坐标、悬浮窗位置、HID 分辨率是否同步；
- 虚拟屏与主屏目标切换后，`click` / `ocr` / `captureScreen` 是否打到正确屏幕；
- 撤销权限后，能力协商是否回退到可用后端，而不是继续报成功。

## CI 行为

`.github/workflows/android.yml` 在 push / PR 时执行：

1. `python3 scripts/check_versions.py`
2. `./gradlew testDebugUnitTest --stacktrace`
3. `./gradlew lintDebug --stacktrace`
4. `./gradlew assembleDebug --stacktrace`
5. 上传 lint 报告

当前 `lint.abortOnError = false`，lint 失败不会阻断构建；新增告警仍应尽量修复，
不要依赖该设置长期堆积。

## 排查

| 现象 | 处理 |
|---|---|
| 单元测试找不到 Android 类 | 逻辑依赖了 `android.*`；抽纯函数或改用 instrumentation |
| `org.json` 行为与设备不同 | JVM 使用 `org.json:json`，设备使用系统实现；避免依赖未定义行为 |
| 测试受时区 / 语言影响 | 固定 Locale / 时区，或只断言与它们无关的部分 |
| 回放 mismatch 太多 | 先 `record.validate`，再确认截图帧、动作顺序与 `strict` 设置 |
| CI 通过但真机失败 | 补手动矩阵；设备相关逻辑不能只靠 JVM 测试 |
