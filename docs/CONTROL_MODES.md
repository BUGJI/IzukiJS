# 控制模式与能力

本文是控制后端能力矩阵、授权方式、风险与限制的**唯一维护处**。
README、脚本 API 与工具说明在涉及这些内容时链接到本文。

## 能力矩阵

| 能力 | 无障碍 | Shizuku | 无线调试 | Root | 蓝牙 HID |
|---|---|---|---|---|---|
| 手势 / 绝对坐标 | ✅ | ✅ | ✅ | ✅ | ✅ |
| 复杂轨迹（曲线 / 多指 / 停顿） | ✅ | 降级 | 降级 | 降级 | v2+ 固件 ✅ |
| 键盘 / 文本 | 部分 | ✅ | ✅ | ✅ | ✅ |
| 读控件树（脚本 `selector.*`） | ✅ | ❌ | ❌ | ❌ | ❌ |
| 截图 | ✅（API 30+） | ✅ | ✅ | ✅ | ❌ |
| Shell | ❌ | ✅ | ✅ | ✅ | ❌ |
| 定向到虚拟屏 display | ❌ | ✅ | ✅ | ✅ | ❌ |

补充：

- 脚本侧「读控件树」只有无障碍后端支持。Shizuku / 无线调试 / Root 的
  `uiautomator dump` 仅用于 AI Agent / MCP 的读屏兜底（常见 0.4~3.5 秒、偶发失败），
  首次超时会自动放大预算重试并按本机实测耗时自适应；它仍不是脚本 `selector.*` 的一部分。
- 后台虚拟屏与 Shizuku 特权解锁依赖 Shizuku；无线调试只提供 Shell 级能力。
- 无线调试需要 Android 11+，并手动开启开发者选项与无线调试；重启后通常需重新开启。
  Android 17+ 首次使用还需允许「本地网络」权限，否则系统会弹出「选择要连接的设备」并中断配对。

## 后端与授权

| 模式 | 开启方式 | 「撤销权限」行为 | 风控风险 |
|---|---|---|---|
| 蓝牙 HID | 「去连接」→ 扫描并连接数位板狗（[固件仓库](https://github.com/BUGJI/IzukiJS_HID)） | 断开并清除记忆设备 | 极低 |
| 无障碍 | 跳转系统无障碍设置开启 | 程序化 `disableSelf()` 关闭 | 高 |
| Shizuku | 「去授权」→ Shizuku 弹窗授权 | 断开后端并跳转 Shizuku 应用 | 中 |
| 无线调试 | 「去连接」→「开始配对（通知输入）」→ 在通知栏输入配对码 | 断开后端或在设置页「忘记配对」删除本地身份 | 中 |
| Root | 「检测 Root」→ `su` 授权 | 撤回后端（彻底撤销去 Magisk） | 中 |

授权页按「读取 / 操作 / 附加能力」分组，并标注精准度、风控风险与授权成本。
运行时可收回非必要权限，只保留当前脚本真正需要的模式，以降低被风控识别的风险。

## 优先级与混合控制

控制后端按**能力**而不是整体选择，因此可以混合：

- 全局读取 / 操作优先模式：`ControllerManager.readMode` / `operateMode`；
- 按能力指定：`capabilityPreferences[Capability]`，优先级高于全局；
- 未指定时按后端注册顺序。

推荐组合：**无障碍读控件树 + HID / Shizuku / 无线调试 注入输入**。
在「设置 → 控制后端 → 读取 / 操作优先级」中指定即可。

## 手势与轨迹

无障碍后端支持带时间戳的多段 / 多指手势，曲线、折返、滑动后按住停顿都可以表达。
蓝牙 HID **v2 及以上固件**也支持曲线与停顿。

两者都不可用时（Root / Shizuku / 旧固件 HID）会降级：

- 无法表达带时间戳的中间点与曲线；
- 单个笔画的连续移动段合并为一次滑动（只保留首尾，时长求和）；
- 同点段时长 ≤ 180ms 视为点击（按 70~180ms 下发按压），更长视为按住停顿（长按）；
- 出现中间点丢弃或多笔画时输出 warn 提示。

其他限制：

- 只需要一次直线滑动时直接用 `swipe()`，不要用多点 `gesture()`；
- HID 仅支持单指，多笔画会顺序执行；
- 无障碍单次手势的 Stroke 数量与总时长受系统限制，过长轨迹可能被拒绝。

`gesture(strokes)` 的参数格式与示例见 [SCRIPT_API.md](SCRIPT_API.md#gesturestrokes-复杂轨迹)。

## 按键限制

`press(keyCode)` 的实际支持范围取决于后端：

- 无障碍：仅 `BACK` / `HOME` / `APP_SWITCH` 有效（映射为全局动作）；
- Shizuku / 无线调试 / Root：通过 `input keyevent` 支持绝大部分键码；
- 蓝牙 HID：仅映射字母、数字、Enter / Esc / Backspace / Tab / Space / Delete、方向键。

pipeline 的 `LongPressKey` / `KeyDown` / `KeyUp` 因底层只提供离散按键暂未支持。

## 截图与屏幕目标

`captureScreen()` / `images.*` / `ocr.*` 的帧来源顺序：

1. **虚拟屏帧源**：虚拟屏激活且当前目标为虚拟屏，最多等待 1 秒首帧；
2. **持续录屏**：`ScreenCapture` 已授权并运行；
3. **控制后端截图**：无障碍 API 30+ / Shizuku / 无线调试 / Root。

`captureScreen(path, source)` / `virtualScreen.capture(path, source)` 的 `source` 支持
`auto` / `virtual` / `main`，可一次性覆盖当前目标。

虚拟屏激活后：

- `click` / `swipe` / `input` / `press` 与截图 / 找图 / OCR 默认定向到虚拟屏；
- 只有支持 `supportsDisplayTarget()` 的后端（Shizuku / 无线调试 / Root）参与协商，
  避免无障碍 / HID 把输入打到主屏；
- `virtualScreen.target("main")` 可临时切回主屏；
- 虚拟屏上不支持 `selector.*` / 无障碍控件树，请改用截图 / OCR / 坐标输入。

## 选择建议

| 场景 | 建议 |
|---|---|
| 需要读控件树 | 必须开启无障碍 |
| 需要 Shell / 虚拟屏 | Shizuku 或 Root；无线调试只提供 Shell |
| 需要硬件级输入 | 蓝牙 HID（单指、需外部硬件） |
| 低风控要求 | HID > 持续录屏 + 坐标输入 > Shizuku > 无障碍 |
| 低授权成本 | 持续录屏 / 无障碍 > 无线调试 > Shizuku > Root > HID |
| 混合精度 | 无障碍读 + HID / Shizuku / 无线调试 写 |

## 相关文档

- 权限与能力页、录屏、悬浮窗、定时任务：[TOOLS.md](TOOLS.md)
- 脚本接口与 `permissions.*`：[SCRIPT_API.md](SCRIPT_API.md)
- 架构与能力协商实现：[ARCHITECTURE.md](ARCHITECTURE.md#控制抽象与能力协商)
- 蓝牙 HID 协议：[BLE_HID_PROTOCOL.md](BLE_HID_PROTOCOL.md)
