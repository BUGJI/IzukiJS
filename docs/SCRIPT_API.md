# Izuki JS · 脚本 API 速查

脚本语言为 JavaScript（QuickJS）。多字节坐标均使用手机屏幕像素。

## 全局函数

| 函数 | 说明 |
|---|---|
| `log(msg)` / `debug(msg)` / `warn(msg)` / `error(msg)` | 输出日志（同时写入日志文件） |
| `toast(msg)` | 弹出 Toast |
| `sleep(ms)` | 休眠，可被「停止」中断 |
| `exit()` | 立即结束脚本 |
| `captureScreen(path)` | 截图并存为 PNG；`path` 传 `""` 时存到缓存目录，返回文件路径（失败返回 `null`） |

## 输入（全局）

| 函数 | 说明 |
|---|---|
| `click(x, y)` | 点击绝对坐标 |
| `longClick(x, y, ms)` | 长按 |
| `swipe(x1, y1, x2, y2, ms)` | 滑动 |
| `gesture(strokes)` | 复杂轨迹：曲线 / 多段 / 多指 / 按住停顿（见下） |
| `press(keyCode)` | 按键（Android KeyEvent 码，见下方限制） |
| `input(text)` | 输入文本 |
| `back()` / `home()` / `recents()` | 返回 / 主页 / 最近任务 |

> 底层由控制模式提供：无障碍 / Shizuku / Root / 蓝牙 HID。具体能力见「控制模式」。

### `gesture(strokes)` 复杂轨迹

无障碍后端可发送带时间戳的多段 / 多指手势，支持曲线、折返与**滑动后按住停顿**
（例如底部上滑并按住打开最近任务）。`strokes` 是「笔画数组」，每个笔画是「点数组」；
点可写成 `{x, y, t}` 或 `[x, y, t]`。

- `x` / `y`：屏幕像素坐标。
- `t`：相对手势开始的时间戳（毫秒），可省略（默认按 16ms/点递推）。
- 相邻两点坐标相同即表示**原地停顿**，时长为两点 `t` 之差。
- 单指单笔画时可省略外层数组，直接传点数组。

```js
// 底部上滑 260ms 到达后，原地按住 700ms 再抬手
gesture([
  [
    { x: 540, y: 2200, t: 0 },
    { x: 540, y: 1200, t: 260 },
    { x: 540, y: 1200, t: 960 }
  ]
]);

// 多指：两指同时画出两条轨迹
gesture([
  [[200, 1600, 0], [200, 900, 300]],
  [[880, 1600, 0], [880, 900, 300]]
]);
```

> 复杂轨迹交给**支持它的后端**执行，并按「控制模式」偏好在其中选择：无障碍与
> **v2 及以上固件的蓝牙 HID** 都支持曲线与停顿。两者都不可用时，才降级为逐段
> `swipe`（同点段等价于长按），此时轨迹会失真。
> HID 仅支持**单指**（多笔画会顺序执行）；无障碍单次手势的 Stroke 数量与总时长受系统限制，
> 过长的轨迹可能被拒绝。

## `device`

| 方法 | 返回 |
|---|---|
| `device.width()` / `device.height()` | 屏幕像素宽高 |
| `device.model()` / `device.brand()` | 机型 / 品牌 |
| `device.androidVersion()` | SDK 版本号 |
| `device.batteryLevel()` | 电量百分比 |

## `app`

| 方法 | 说明 |
|---|---|
| `app.launch(packageName)` | 启动应用 |
| `app.openUrl(url)` | 打开链接 |
| `app.currentPackage()` | 当前前台包名（需无障碍） |

## `selector`（需无障碍）

| 方法 | 说明 |
|---|---|
| `selector.existsById(id)` / `existsByText(t)` / `existsByDesc(d)` | 是否存在 |
| `selector.countByText(t)` | 匹配数量 |
| `selector.clickById(id)` / `clickByText(t)` / `clickByDesc(d)` | 点击（优先 performAction，否则点中心） |
| `selector.longClickById(id)` / `longClickByText(t)` | 长按 |
| `selector.setTextById(id, text)` | 设置文本 |
| `selector.getTextById(id)` / `getTextByText(t)` | 取文本 |
| `selector.boundsById(id)` | 返回 `"left,top,right,bottom"` |

## `shell`（需 Shizuku 或 Root）

| 方法 | 说明 |
|---|---|
| `shell.available()` | 是否可用 |
| `shell.isRoot()` | 是否 Root 后端 |
| `shell.exec(cmd)` | 执行命令，返回 stdout（无输出则 stderr） |
| `shell.exitCode(cmd)` | 执行命令，返回退出码 |

## `images`（OpenCV）

| 方法 | 说明 |
|---|---|
| `images.findImage(template, threshold=0.8)` | 找图，返回 `{x, y, confidence}` 或 `null`（坐标为模板中心） |
| `images.findColor(color, threshold=4)` | 找色，返回 `{x, y}` 或 `null`，`color` 形如 `"#RRGGBB"` |
| `images.findImageInRaw(screenPath, template, threshold)` | 在指定截图上找图 |
| `images.available()` | OpenCV 是否可用 |

```js
var p = images.findImage("/sdcard/tpl.png", 0.85);
if (p) click(p.x, p.y);
```

## `ocr`（本地 / 在线可切换）

识别引擎由设备配置（设置 → OCR 识别）决定，脚本无需改动：

- **本地**：MLKit 中文模型，端侧离线。
- **在线**：百度 OCR / Google Vision / 自定义，适合低端设备省算力；在线失败自动回退本地。
- 坐标均为屏幕像素包围盒中心。

| 方法 | 说明 |
|---|---|
| `ocr.recognize()` | 识别当前屏幕，返回全文 |
| `ocr.recognizePath(path)` | 识别指定图片 |
| `ocr.find(text)` | 查找包含子串的元素，返回 `{x, y, width, height}` 或 `null` |

```js
var hit = ocr.find("登录");
if (hit) click(hit.x, hit.y);
```

## `permissions`（权限查询）

| 方法 | 说明 |
|---|---|
| `permissions.accessibility()` / `shizuku()` / `root()` / `hid()` | 控制模式是否就绪 |
| `permissions.overlay()` | 悬浮窗权限 |
| `permissions.screenCapture()` | 持续录屏是否开启 |
| `permissions.notifications()` / `bluetooth()` | 通知 / 蓝牙权限 |
| `permissions.granted()` | 已授予项，逗号分隔 |
| `permissions.all()` | 全部项 `key=value` |
| `permissions.has(name)` | 单项查询 |
| `permissions.canControl()` | 是否有可注入输入的后端 |
| `permissions.canScreenshot()` | 是否有截图来源 |

```js
if (!permissions.canScreenshot()) {
  toast("请开启无障碍或持续录屏后再运行");
  exit();
}
```

## `env`（运行参数）

运行前的输入参数，类似环境变量，用于切换脚本运行模式。在脚本头部用注释声明，运行脚本时 App
会自动生成输入表单，用户填写后写入并随脚本保存。

声明语法：`// @env KEY=默认值  说明`（说明可省略，用两个及以上空格或 ` # ` 分隔）。

```js
// @env MODE=auto    运行模式
// @env TARGET=      目标包名
```

| 方法 | 说明 |
|---|---|
| `env.get(key)` | 读取参数，不存在返回 `null` |
| `env.get(key, fallback)` | 带默认值读取 |
| `env.has(key)` | 是否存在 |
| `env.mode()` | 便捷读取 `MODE` / `mode` |
| `env.all()` | 全部参数对象 |

## `state`（运行状态）

脚本运行中可写回的状态。与 `env` 不同，`state` 由脚本主动修改，并会持久化，下次运行或定时任务
仍可读到，适合断点续跑、进度记忆等。

| 方法 | 说明 |
|---|---|
| `state.get(key)` / `state.get(key, fallback)` | 读取状态 |
| `state.set(key, value)` | 写入并立即持久化 |
| `state.has(key)` | 是否存在 |
| `state.remove(key)` / `state.clear()` | 删除单项 / 清空 |
| `state.all()` | 全部状态对象 |

```js
// @env MODE=auto
log("模式: " + env.get("MODE", "auto"));

var step = parseInt(state.get("step", "0"), 10);
log("第 " + (step + 1) + " 次运行");
state.set("step", String(step + 1));
```

## 模块复用 `require`

用 `require(name)` 引入脚本中心里的另一个脚本（同一运行时内执行，带缓存与循环依赖检测）。
`name` 为脚本名，可省略结尾的 `.js`；模块用 `module.exports` / `exports` 导出。

```js
// common.js
module.exports = {
  greet: function (name) { log("hi " + name); }
};

// main.js
var lib = require("common");
lib.greet("izuki");
```

- 模块与主脚本共享同一 QuickJS 环境，可直接使用 `log` / `click` / `env` / `state` 等全部 API。
- 首次 `require` 执行一次并缓存，重复引入返回同一份 `exports`。
- 找不到模块或存在循环依赖时抛出错误。

内置基础操作库 `basic_ops`，首次安装时自动写入脚本中心，其他脚本可直接复用：

```js
var ops = require("basic_ops");
ops.backToHome();
ops.cleanJunk();
ops.goBack();
```

| 导出 | 说明 |
|---|---|
| `backToHome()` | 底部中间上滑返回桌面 |
| `cleanJunk()` | 上滑停顿打开最近任务并清理 |
| `goBack()` | 右侧滑到中间返回上一级 |
| `main()` | 依次执行上面三个操作 |
| `width` / `height` | 加载时记录的屏幕像素宽高 |

`basic_ops` 同时也是可直接运行的脚本（单独运行会执行一遍 `main()`）。

## `ai`（AI Agent）

脚本卡住或遇到棘手界面时，交给大模型观察屏幕并操作设备脱困。每次调用都是**无状态**的：
框架只发送「系统提示词 + 本次 prompt」，多轮上下文（如最近的 OCR 文本、当前包名）需脚本自行拼接。
需先在「设置 → AI Agent」启用并配置 OpenAI 兼容接口。

| 方法 | 说明 |
|---|---|
| `ai.available()` | 是否已配置可用 |
| `ai.config()` | 返回配置摘要 JSON（不含 Key） |
| `ai.chat(prompt, options?)` | 纯对话，不调用工具，返回回复文本 |
| `ai.run(prompt, options?)` | 带工具的 Agent 循环，可自动操作设备，返回最终总结 |

`options`（可选对象）字段：

| 字段 | 说明 |
|---|---|
| `maxSteps` | 覆盖本次最大步数（优先于设置里的全局值） |
| `onDelta(text)` | 流式增量文本回调 |
| `onTool(name, args)` | 模型决定调用某工具时回调 |
| `onToolResult(name, result)` | 工具执行完成后回调 |

模型可调用的工具由设备能力决定，包括 `ocr_screen` / `ui_dump` / `find_text` / `click` /
`long_click` / `swipe` / `input_text` / `press_back` / `press_home` / `press_recents` /
`press_enter` / `launch_app` / `current_app` / `device_info` / `screenshot`，以及按需开启的 `shell`。

```js
if (ai.available()) {
  var answer = ai.run(
    "当前卡在广告弹窗，请帮我关闭并回到首页。屏幕文字：" + ocr.recognize(),
    {
      maxSteps: 6,
      onDelta: function (t) { log(t); },
      onTool: function (name, args) { log("→ " + name + " " + args); }
    }
  );
  log("脱困结果：" + answer);
}
```

> 服务不支持原生 function calling 时会自动降级为提示词协议，脚本无需改动。

## 截图来源优先级

`captureScreen()` / `images.*` / `ocr.*` 的屏幕帧来源顺序：

1. **持续录屏**（若已授权，见 `docs/TOOLS.md`）
2. 控制后端的截图能力（无障碍 API30+ / Shizuku / Root）

## `press` 的按键限制

- 无障碍模式：仅 `BACK` / `HOME` / `APP_SWITCH` 有效（映射为全局动作）。
- Shizuku / Root：通过 `input keyevent` 支持绝大部分键码。
- 蓝牙 HID：仅映射了字母、数字、Enter/Esc/Backspace/Tab/Space/Delete、方向键。

## 控制模式与能力组合

| 能力 | 无障碍 | Shizuku | Root | HID |
|---|---|---|---|---|
| 手势/绝对坐标 | ✅ | ✅ | ✅ | ✅ |
| 键盘/文本 | 部分 | ✅ | ✅ | ✅ |
| 读控件树 | ✅ | ❌ | ❌ | ❌ |
| 截图 | ✅(30+) | ✅ | ✅ | ❌ |
| Shell | ❌ | ✅ | ✅ | ❌ |

推荐组合：**无障碍读控件树 + HID/Shizuku 注入输入**。在「设置 → 控制模式优先级」中指定优先后端即可。
