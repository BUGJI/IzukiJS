# Auto.js 旧脚本兼容

面向从 AutoX / AutoJs6 / Auto.js 迁移脚本的用户。这里只覆盖**经典 v6 Rhino API**；
AutoX v7（Node/Javet）、AutoJs6 6.8 的 speech/tts/compose 等长尾模块不在范围内。

## 启用方式

在脚本首行加入：

```js
// @compat autojs6
```

编辑器检测到 Auto.js 风格写法（`text(...).findOne()`、`setTimeout`、`device.width` 属性、
`"ui";`、`importClass` 等）时会显示提示，可一键写入标记。

标记只影响当前脚本；未标记的脚本保持原有 API 形状与语义。

## 当前阶段能力

兼容层分阶段实现。当前版本已经提供：

| 能力 | 状态 | 说明 |
|---|---|---|
| `// @compat autojs6` 识别 | ✅ | `autojs` / `autox` 视为同一 v6 家族 |
| `"ui";` / `"nodejs";` 拦截 | ✅ | 运行时给出明确错误，不静默执行 |
| 编辑器提示 | ✅ | 不自动切换，由用户确认 |
| 外部诊断 CLI | ✅ | `analyze` / `lint` / `import` |
| `device.width` 属性写法 | ✅ | 同时兼容 `device.width` 与 `device.width()` |
| `device` 音量 / 亮度 / 振动 | ✅ | 媒体 / 通知 / 闹钟音量、亮度与模式、`vibrate` / `cancelVibration` |
| `sensors.register` / `on('change')` | ✅ | 50ms 轮询桥接；`delay` / `ignoresUnsupportedSensor` / `unsupported_sensor` |
| `toastLog` / `currentPackage` / `currentActivity` | ✅ | 基于现有 `toast` / `app` / `events` |
| `setClip` / `getClip` / `random` / `stop` / `isStopped` | ✅ | 纯 JS 门面 |
| `click(text, index)` / `longClick(text, index)` | ✅ | 按参数类型分派到 `selector` |
| `press(x, y, duration)` / `press(keyCode)` | ✅ | 按参数个数分派 |
| `gesture(duration, [x,y], ...)` | ✅ | 转换为目标平台笔画格式 |
| `files.*` / `files.open` | ✅ | 映射到 `fs`，`open` 为内存缓冲实现 |
| `files.createWithDirs` / `files.removeDir` | ✅ | 自动建父目录；`removeDir` 递归删除 |
| `http` 响应对象 | ✅ | `.body.string()` / `.json()` / `.contentType()` |
| `auto()` / `auto.waitFor()` | ✅ | 轮询无障碍状态，默认等待 10 秒 |
| `auto.isRunning` / `auto.explain` | ✅ | 无障碍状态；`explain` 复用 `selector.explain` |
| `auto.enable` / `auto.disable` | ⚠️ | 明确报错：兼容层不修改系统无障碍开关 |
| `auto.service` / `auto.windows` | ✅ | 无障碍就绪时返回窗口门面；`getRoot()` 返回 `null` |
| `auto.dump({format})` | ✅ | `json` / `text` / `xml`；支持 `maxDepth` / `maxNodes` / `visibleOnly` / `properties` |
| `files.getSdcardPath()` | ✅ | 返回 `/sdcard` |
| `automator.captureScreen()` | ✅ | 映射到 `captureScreen()`，返回 Image 包装 |
| `require('images'/'files'/'automator'...)` | ✅ | 内建名映射到全局对象 |
| `scrollUp` / `scrollDown` | ⚠️ | 用整屏滑动近似，不读取滚动容器 |
| `setScreenMetrics` | ⚠️ | 只保留调用，不做坐标缩放 |
| `text(...)` / `desc(...)` / `id(...)` 选择器链 | ✅ | 支持 `findOne` / `findOnce` / `untilFind` / `findAll` / `exists` / `waitFor` |
| `UiObject` 基本属性与动作 | ✅ | `text` / `bounds` / `click` / `longClick` / `setText` 等 |
| `UiObject.parent/child/children/find` | ❌ | 需要节点句柄；会抛出明确错误 |
| `setTimeout` / `setInterval` / `clear*` | ✅ | 协作式定时器：在 `sleep` 与脚本收尾阶段执行 |
| `setText(text)` / `input(text)` | ✅ | 兼容模式下 `setText` 替换、`input` 追加焦点输入框 |
| `images.read` / Image 对象 | ✅ | `read/save/pixel/getSize/clip/resize/captureScreen/findImage/findColor` |
| `images.requestScreenCaptureAsync` | ⚠️ | 只查询已授权状态并立即回调 / resolve，不弹录屏授权框 |
| `colors.*` | ✅ | 32 位有符号 ARGB；`parseColor` / `toString` / `argb` / `rgba` / `toInt` / `toHex` / 分量读取 |
| `images.matchTemplate` / `images.load` | 规划中 | 需要多匹配与二进制下载 |
| `storages.create` | ✅ | JSON 值；底层为应用私有 SharedPreferences |
| `dialogs` | ✅ | `alert/confirm/prompt/rawInput/input/select/singleChoice/multiChoice`；无 Activity 时返回 `null` |
| `events.on` / `once` 回调 | ✅ | 100ms 轮询桥接；`onKeyDown` / `onKeyUp` 支持按键名或 keyCode 过滤 |
| `events.observeTouch` | ⚠️ | 明确提示不支持：无障碍后端不提供原始触摸事件 |
| 多文件项目 | ✅ | 支持 `projects/` 目录、ZIP 导入（自动补兼容标记）、相对 `require`、`index.js`、`package.json` main；编辑器可切换项目文件 |
| `"ui";` / E4X / Java 互操作 | ❌ | 运行时在启动阶段直接拒绝并给出原因，不做静默降级 |
| `threads` / `engines` 并发 | ❌ | 当前单 run 模型，改为顺序或拆分脚本 |

> `auto.waitFor()` 在旧平台默认无限等待；这里默认 10 秒，避免脚本无感挂起。
> 需要无限等待时传 `-1`。

## 外部诊断 CLI

```bash
node tools/autojs-compat/bin/autojs-compat.mjs analyze path/to/script.js
node tools/autojs-compat/bin/autojs-compat.mjs lint path/to/project
node tools/autojs-compat/bin/autojs-compat.mjs import path/to/project --out out/project
```

- `analyze`：输出 Markdown / JSON 报告，包含指令、require、识别项与行号。
- `lint`：存在阻断项时以非 0 退出，适合 CI。
- `import`：复制目录、补 `// @compat autojs6`、写 `izuki.project.json`；不做全量转译。

阻断项包括 `"ui";`、E4X、`Packages`/`importClass`、`ui.layout`、`threads`/`engines`。
这些需要人工移植，不能靠运行时门面解决；带 `// @compat` 的脚本命中阻断项时会在启动阶段
直接报错，而不是运行到一半才失败。

## 多文件项目

`import` 会生成：

```json
{
  "name": "项目名",
  "entry": "main.js",
  "compat": "autojs6"
}
```

运行时的相对路径 `require('./utils')`、`index.js`、`package.json` 的 `main` 解析在下一阶段
接入；当前仍按扁平脚本名解析。导入后请先看 `analyze` 报告的 `多文件 require` 项。

## 安全边界

兼容层只做 API 形状与重载适配，**不会**：

- 绕过 `@need`、权限开关、HTTP 白名单、`ShellGuard`；
- 自动开启无障碍、录屏或悬浮窗；
- 把 `requestScreenCapture` 当成已授权；
- 让旧脚本访问新的敏感能力。

`auto.waitFor()` 只提示并等待授权，不修改系统设置。

## 已知差异

- `device.width` 是“可调用标量函数”：`device.width()` 与 `device.width + 0` 都可用，
  但 `typeof device.width` 是 `function` 而不是 `number`。
- `UiObject` 是“按条件重查”的轻量对象，不是节点句柄；`parent/child/children/find`
  会明确报错。UI 变化后再次操作可能命中不同节点。
- `findOne()` / `untilFind()` 默认 10 秒超时，避免脚本无感挂起；旧平台默认无限等待。
- 定时器是协作式的：只在 `sleep`、`waitUntil` 与主脚本结束后的收尾循环中执行；
  长时间纯计算会推迟回调。`setInterval` 会让脚本保持运行，最长 24 小时。
- `setText` 替换焦点输入框文本；`input` 追加到焦点输入框。没有可编辑焦点时退回原生行为。
- `files.cwd()` 返回 `/sdcard`，`files.path()` 不解析脚本目录；相对路径请显式拼接。
- `files.open().readBytes()` / `writeBytes()` 尚未提供。
- `Image` 是文件路径包装：`getBitmap()` 不可用；`matchTemplate` / `load` 尚未提供。
- `storages` 只保存 JSON 值；不能直接存 Java 对象。
- `events.on` 是轮询桥：只在 `sleep` / 收尾循环中派发；按键拦截与 `events.on('exit')` 不支持。
- 项目通过 ZIP 导入到当前脚本根目录的 `projects/`（默认应用私有目录，可切换到共享存储）；
  `node_modules` 不参与文件列表，但相对/本地 JS 与 JSON 可正常解析。
- 项目入口由 `izuki.project.json` / `project.json` 的 `entry`/`main` 决定，缺省尝试 `main.js`、`index.js`。
- `currentActivity()` 尽力返回窗口类名，部分界面只能拿到视图类名。
- `auto.dump` 走无障碍 `nodeTree()`：最深 24 层（更深的节点会被截断），无无障碍时返回
  `ok:false`；`idHex` 固定为 `null`，`actions` 由节点标志推导，不是真实 `ActionList`。
  `dump` / `windows` 依赖 `selector` 能力；兼容脚本即使写了 `@need`，也会自动补注册
  这些兼容门面依赖的命名空间（仍受无障碍 / 权限等运行期校验约束）。
- 单次 `auto.dump` 最多导出 20000 个节点，超出部分以 `truncated` 标记。
- `auto.windows[].getRoot()` 返回 `null`：窗口的 `packageName` 与 `bounds` 已由兼容层填好，
  但节点句柄尚未实现；需要节点树请改用 `auto.dump`。
- `auto.service` 只在无障碍就绪时非 `null`，是轻量门面（`getWindows` 可用），
  不是真实的 `AccessibilityService`；`getRootInActiveWindow()` 会抛出明确错误。
- `requestScreenCapture()` 只查询是否已授权，不会主动弹出录屏授权框；未授权时返回 `false`。
- `auto.stats` 暂未提供；`auto.dump` 忽略 `root` 参数（节点句柄未实现），始终导出当前窗口。
- `colors.toHex` 支持 `auto` / 是否带 alpha / 6 / 8 位；3 位缩写不做“能否缩写”校验。
- `events.onNotification` 只保证包名、类名、文本、时间与 keyCode；通知优先级 / 分类等字段为空。
- `device.setBrightness` / `setBrightnessMode` 需要「修改系统设置」；未授权返回 `false`。
- `sensors` 回调只在 `sleep` / `waitUntil` / 脚本收尾的兼容定时器里派发；纯计算期间会
  推迟，且同名传感器每次注册都会创建独立监听（最多 16 个）。
- 依赖 Android 系统能力的模块（`media`、`tasks`、`http.postMultipart`、
  `app.startActivity` 等）尚未接入兼容层，需要原生 API 先行。

## 验证

```bash
# CLI 自检
node tools/autojs-compat/test/analyze.test.mjs

# Kotlin 单测
./gradlew testDebugUnitTest --tests "com.benton.izukijs.runtime.CompatModeTest"

# 文档与版本检查
python3 scripts/check_docs.py
python3 scripts/check_versions.py
```

真机需要覆盖：Android 10 / 12 / 14，无障碍开 / 关，主屏 / 虚拟屏，旋转与前后台。

## 路线图

已完成：选择器条件扩展、`UiSelector` / `UiObject` 基本门面、协作式定时器、
Image 对象、`storages`、`dialogs`、`events.on` 轮询桥、多文件项目与 ZIP 导入。

1. 节点句柄：`UiObject.parent/child/children` 与任意 `performAction`。
2. `images.matchTemplate` / `images.load` 与更多图像操作。
3. 按真实 corpus 结果决定是否补 `threads` 协作式调度。

维护约定：新增兼容 API 时同步更新本文与 `CHANGELOG.md`；阻断项不要在运行时静默降级。
