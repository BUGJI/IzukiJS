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
| `press(keyCode)` | 按键（Android KeyEvent 码，见下方限制） |
| `input(text)` | 输入文本 |
| `back()` / `home()` / `recents()` | 返回 / 主页 / 最近任务 |

> 底层由控制模式提供：无障碍 / Shizuku / Root / 蓝牙 HID。具体能力见「控制模式」。

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
