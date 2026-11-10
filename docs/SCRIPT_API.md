# Izuki JS · 脚本 API 速查

脚本语言为 JavaScript（QuickJS）。多字节坐标均使用手机屏幕像素。

## 全局函数

| 函数 | 说明 |
|---|---|
| `log(msg)` / `debug(msg)` / `warn(msg)` / `error(msg)` | 输出日志（同时写入日志文件） |
| `toast(msg)` | 弹出 Toast |
| `sleep(ms)` | 休眠，可被「停止」中断 |
| `waitUntil(condition, timeoutMs=10000, intervalMs=200)` | 轮询直到 `condition()` 返回真值，并返回该值；超时返回 `null` |
| `waitUntilGone(condition, timeoutMs=10000, intervalMs=200)` | 轮询直到 `condition()` 返回假值，返回是否在超时前消失 |
| `exit()` | 立即结束脚本 |
| `captureScreen(path, source?)` | 截图并存为 PNG；`path` 传 `""` 时存到缓存目录，返回文件路径（失败返回 `null`）；`source` 可为 `auto` / `virtual` / `main` |

## 输入（全局）

| 函数 | 说明 |
|---|---|
| `click(x, y)` | 点击绝对坐标 |
| `longClick(x, y, ms)` | 长按 |
| `swipe(x1, y1, x2, y2, ms)` | 滑动 |
| `gesture(strokes)` | 复杂轨迹：曲线 / 多段 / 多指 / 按住停顿（见下） |
| `press(keyCode)` | 按键（Android KeyEvent 码，见下方限制） |
| `input(text)` | 输入文本（自动选方式） |
| `inputWithMethod(text, method)` | 指定输入方式：`auto` / `input` / `clipboard` / `broadcast` |
| `back()` / `home()` / `recents()` | 返回 / 主页 / 最近任务 |

> 底层由控制模式提供：无障碍 / Shizuku / 无线调试 / Root / 蓝牙 HID。具体能力见「控制模式」。

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
> **v2 及以上固件的蓝牙 HID** 都支持曲线与停顿。两者都不可用时（Root / Shizuku /
> 旧固件 HID），才降级执行：无法表达带时间戳的中间点与曲线，单个笔画的**连续移动段会
> 合并为一次滑动**（只保留首尾，时长求和），同点段时长 ≤ 180ms 视为**点击**
> （按 70~180ms 下发按压），更长则视为**按住停顿**（长按）。出现中间点丢弃或多笔画时
> 会输出 warn 提示。
> **只需要一次直线滑动请直接用 `swipe()`**，不要用多点 `gesture()`。
> HID 仅支持**单指**（多笔画会顺序执行）；无障碍单次手势的 Stroke 数量与总时长受系统限制，
> 过长的轨迹可能被拒绝。

## `device`

| 方法 | 返回 |
|---|---|
| `device.width()` / `device.height()` | 当前操作屏的像素宽高；虚拟屏激活且目标为虚拟屏时返回虚拟屏分辨率 |
| `device.screenWidth()` / `device.screenHeight()` | 物理屏真实分辨率；虚拟屏激活且目标为虚拟屏时返回虚拟屏分辨率 |
| `device.model()` / `device.brand()` | 机型 / 品牌 |
| `device.androidVersion()` | SDK 版本号 |
| `device.batteryLevel()` | 电量百分比 |

## `app`

| 方法 | 说明 |
|---|---|
| `app.launch(packageName)` | 启动应用 |
| `app.openUrl(url)` | 打开链接 |
| `app.currentPackage()` | 当前前台包名（无障碍 → Shizuku/无线调试/Root → 使用情况访问 依次兜底） |
| `app.list()` | 已安装应用数组 `[{package,label,system,enabled}]`（需「应用列表」开启） |
| `app.info(packageName)` | 单个应用信息对象，或 `null` |
| `app.isInstalled(packageName)` | 是否已安装 |
| `app.foreground()` | 当前前台包名（需「使用情况访问」），否则 `null` |
| `app.lastUsed(packageName)` | 最后使用时间（毫秒），从未使用返回 -1（需「使用情况访问」） |
| `app.usageStats(sinceMs, limit)` | 使用统计数组 `[{package,totalTimeMs,lastTimeUsed}]`（需「使用情况访问」） |

## `notify`（通知监听 + 脚本进度通知）

读取 / 等待 / 清除通知需在「权限与能力」页开启「通知监听（通知使用权）」。
`notify.progress` / `notify.update` / `notify.reset` 更新的是脚本运行通知，需要
`POST_NOTIFICATIONS`，并受「设置 → 脚本运行 → 通知」控制。
使用 `// @need notify` 时才会暴露 `notify` 命名空间。

### 监听与清除

| 方法 | 说明 |
|---|---|
| `notify.available()` | 是否已授予通知使用权 |
| `notify.list()` | 当前通知数组 `[{key,package,title,text,subText,postTime,ongoing}]` |
| `notify.latest(pkg, timeoutMs=10000)` | 等待 `pkg` 在调用之后出现的新通知，返回该通知对象；超时返回 `null` |
| `notify.clear(key)` | 清除指定通知 |

### 进度通知

| 方法 | 说明 |
|---|---|
| `notify.progress(options)` | 完整更新运行通知进度；开关关闭 / 未授权 / 服务未运行返回 `false` |
| `notify.update(options)` | 局部更新；进度开关关闭时忽略进度字段，仍可更新标题 / 文本 / 阶段 |
| `notify.reset()` | 清除进度、分段、颜色，回到普通运行通知 |
| `notify.progressEnabled()` | 当前是否可显示进度通知（开关 + `POST_NOTIFICATIONS`） |

**options 字段**

| 字段 | 类型 | 说明 |
|---|---|---|
| `title` / `text` | string | 通知标题 / 正文 |
| `stage` | string \| null | 阶段文本，映射 `setSubText` |
| `value` / `max` | number | 当前进度 / 最大值；`max <= 0` 按不确定进度处理 |
| `indeterminate` | boolean | 不确定进度；提供 `value` 或 `max` 且未指定时自动为 `false` |
| `color` | string | 通知主色，`#RGB` / `#ARGB` / `#RRGGBB` / `#AARRGGBB` |
| `segments` | array | `[{length, color}]`，多段进度条；总长度即最大值 |
| `points` | array | `[{position, color}]`，进度节点；位置裁剪到 `1..max` |
| `shortCriticalText` | string | Android 16+ 状态栏 / 实时更新短文本 |
| `trackerIcon` | string | 内置图标 key：`script` / `progress` / `none` |
| `startIcon` / `endIcon` | string | 同上 |
| `silent` | boolean | 是否静音，默认 `true` |
| `colorized` | boolean | 是否使用着色通知 |
| `visibility` | string | `public` / `private` / `secret` |
| `timeoutMs` | number | 自动消失时间，`0` 表示不设置 |
| `showStopAction` | boolean | 是否保留停止按钮，默认 `true` |
| `promoted` | boolean | Android 16+ 是否请求 promoted ongoing / 实时更新 |

Android 16+ 使用 `NotificationCompat.ProgressStyle`，支持分段、节点、追踪图标、
`shortCriticalText` 与 promoted ongoing；低版本自动降级为普通进度条，忽略
`segments` / `points` / 图标 / `shortCriticalText` / `promoted`。

```js
notify.progress({
  title: "自动战斗",
  text: "正在执行",
  stage: "第 2/5 阶段",
  value: 40,
  max: 100,
  color: "#2196F3",
  segments: [
    { length: 40, color: "#4CAF50" },
    { length: 60, color: "#2196F3" }
  ],
  shortCriticalText: "40%",
  trackerIcon: "progress"
});

notify.update({ text: "正在收尾", stage: "第 3/5 阶段" });
notify.reset();
```

## `fs`（文件读写）

访问共享存储中的任意路径需在「权限与能力」页开启「全部文件访问」；未授权时受限路径的操作
返回 `null` / `false`。

| 方法 | 说明 |
|---|---|
| `fs.readText(path)` | 读取 UTF-8 文本，失败返回 `null` |
| `fs.writeText(path, text, append)` | 写入 / 追加文本 |
| `fs.exists(path)` / `fs.isDirectory(path)` | 判断存在 / 目录 |
| `fs.list(path)` | 列目录，返回 `[{name,path,dir,size}]` 或 `null` |
| `fs.size(path)` | 文件字节数，失败返回 -1 |
| `fs.mkdirs(path)` / `fs.delete(path)` | 建目录 / 删除 |
| `fs.copy(src, dst)` / `fs.move(src, dst)` | 复制 / 移动 |
| `fs.readBase64(path)` / `fs.writeBase64(path, b64)` | 读写二进制（Base64） |

```js
fs.writeText("/sdcard/izuki/out.txt", "hello\n", false);
var entries = fs.list("/sdcard/izuki");
```

## `http`（需启用「脚本网络」）

默认关闭，可在「权限与能力 → 附加能力 → 脚本网络（HTTP）」启用并配置域名白名单。
白名单留空表示不限制；填写后仅允许这些主机及其子域。只支持 `http` / `https`，
单次响应体上限 2 MB（超出会截断并返回 `truncated: true`）。

| 方法 | 说明 |
|---|---|
| `http.available()` | 脚本网络是否已启用 |
| `http.isAllowed(url)` | 当前开关 / 白名单是否允许该 URL |
| `http.request(options)` | 通用请求，返回结果对象 |
| `http.get(url, options?)` | GET |
| `http.post(url, body, options?)` | POST 文本 |
| `http.postJson(url, data, options?)` | POST JSON（自动设置 `Content-Type: application/json`） |

`options` 字段：

| 字段 | 说明 |
|---|---|
| `method` | 默认 `GET`；支持 GET / POST / PUT / PATCH / DELETE / HEAD 等 |
| `headers` | 请求头对象 |
| `body` | 请求体字符串（GET / HEAD 会忽略） |
| `timeoutMs` | 连接 / 读取超时，默认 15000，范围 1000~60000 |
| `followRedirects` | 是否跟随重定向，默认 `true` |

返回对象：`{ok, status, headers, body, url, durationMs, truncated?, error?}`。
网络错误 / 被拦截时 `ok=false`、`status=0`，并带 `error` 说明。

```js
var r = http.postJson("https://api.example.com/hook", { event: "done" }, {
  headers: { "Authorization": "Bearer xxx" },
  timeoutMs: 5000
});
if (r && r.ok) log("响应: " + r.body);
else warn("请求失败: " + (r && r.error));
```

> 日志只记录方法、主机、状态码与耗时，不记录 Header / 请求体 / 响应体，避免密钥泄漏。
> 但仍请避免在脚本里把密钥写进日志。

## `clipboard`

| 方法 | 说明 |
|---|---|
| `clipboard.get()` | 读取剪贴板文本，空或失败返回 `null` |
| `clipboard.set(text)` | 写入文本，返回是否成功 |
| `clipboard.clear()` | 清空剪贴板 |

> Android 10+ 对后台应用读取剪贴板有额外限制；读不到时可降级为 OCR / 通知读取。

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

### 条件查询与等待

除上面的便捷方法外，`selector` 还提供统一的条件查询，列表 / 多匹配 / 等待场景更省事：

```js
var el = selector.find({ text: "登录" });          // 第 1 个匹配元素
var items = selector.findAll({ textContains: "订单" });
selector.waitFor({ id: "com.example:id/confirm" }, 5000);
selector.waitGone({ text: "加载中" }, 10000);
selector.click({ textRegex: "^同意" });
selector.scrollIntoView({ text: "隐私政策" });
```

条件字段可组合，全部满足才匹配：

| 字段 | 说明 |
|---|---|
| `id` / `viewId` | 完整 viewId |
| `text` | 文本完全相等 |
| `textContains` / `contains` | 文本包含子串 |
| `textRegex` | 文本正则（搜索语义） |
| `desc` / `description` | 描述完全相等 |
| `descContains` / `descRegex` | 描述包含 / 正则 |
| `className` / `class` | 类名完全相等 |
| `clickable` / `editable` | 布尔属性 |
| `package` / `packageName` | 包名 |

| 方法 | 说明 |
|---|---|
| `selector.find(criteria, index=0)` | 第 index 个匹配元素，未找到返回 `null` |
| `selector.findAll(criteria)` | 全部匹配元素数组 |
| `selector.nth(criteria, index)` | 等同于 `find(criteria, index)` |
| `selector.exists(criteria)` / `selector.count(criteria)` | 是否存在 / 匹配数量 |
| `selector.click(criteria, index=0)` / `longClick(criteria, index=0)` | 点击 / 长按 |
| `selector.waitFor(criteria, timeoutMs=5000, intervalMs=200, index=0)` | 等待出现，返回元素或 `null` |
| `selector.waitGone(criteria, timeoutMs=5000, intervalMs=200)` | 等待消失，返回是否成功 |
| `selector.scrollIntoView(criteria, index=0)` | 尝试滚动到可见区域 |

元素对象字段：`{id,text,desc,className,package,left,top,right,bottom,x,y,width,height,clickable,longClickable,editable,scrollable,enabled,index}`。

`waitForText(text, ...)` / `waitForId(id, ...)` / `waitForTextContains` / `waitForTextRegex` 是
`waitFor` 的便捷包装；`findByText` / `findByTextContains` / `findByTextRegex` / `findByDesc*`
同理。所有等待方法都会响应「停止」，不会把界面卡死；单次 `waitFor` / `waitGone` 最长等待 10 分钟。

## `shell`（需 Shizuku、无线调试或 Root）

| 方法 | 说明 |
|---|---|
| `shell.available()` | 是否可用 |
| `shell.isRoot()` | 是否 Root 后端 |
| `shell.exec(cmd)` | 执行命令，返回 stdout（无输出则 stderr） |
| `shell.execResult(cmd)` | 执行命令，返回 `{ok, stdout, stderr, exit_code}`（JSON 字符串） |
| `shell.exitCode(cmd)` | 执行命令，返回退出码 |

> 无线调试后端使用 adb 的合并输出流，`stderr` 会并入 `stdout`；Shizuku / Root 保持原样。

## `images`（OpenCV）

| 方法 | 说明 |
|---|---|
| `images.findImage(template, threshold=0.8, region?)` | 找图，返回 `{x, y, confidence}` 或 `null`（坐标为模板中心） |
| `images.findAllImages(template, threshold=0.8, region?, limit=20)` | 找全部匹配，返回数组 |
| `images.findColor(color, threshold=4, region?)` | 找色，返回 `{x, y}` 或 `null`，`color` 形如 `"#RRGGBB"` |
| `images.findAllColors(color, threshold=4, region?, limit=100)` | 找全部同色像素，返回 `[{x,y}]`（按扫描顺序截断到 limit） |
| `images.findColorRegions(color, threshold=4, options?)` | 按颜色做连通域聚类，返回 `[{x,y,w,h,pixels,cx,cy}]`（按 `pixels` 降序） |
| `images.findColorRegionsInRaw(screenPath, color, threshold=4, options?)` | 在指定截图上做连通域找色，返回同上 |
| `images.findColorInRaw(screenPath, color, threshold=4)` | 在指定截图上找色，返回 `{x, y}` 或 `null` |
| `images.findImageInRaw(screenPath, template, threshold)` | 在指定截图上找图，返回 `"x,y,confidence"` 字符串或 `null` |
| `images.findColorRaw(color, threshold)` | 低层找色，返回 `"x,y"` 字符串或 `null` |
| `images.findImageRaw(template, threshold)` | 低层找图，返回 `"x,y,confidence"` 字符串或 `null` |
| `images.saveTemplate(path, x, y, width, height)` | 从当前屏幕裁剪区域保存为 PNG 模板，返回路径；`path` 传 `""` 存到缓存目录 |
| `images.available()` | OpenCV 是否可用 |

`region` 为 `{x, y, width, height}`（屏幕像素），省略时搜索整屏。`limit` 上限 200。

`findColorRegions` 的 `options` 为
`{minPixels=1, maxRegions=20, region?: {x, y, width, height}}`。返回区域的 `x/y` 是包围盒左上角，
`w/h` 是尺寸，`pixels` 是命中像素数，`cx/cy` 是像素质心；`minPixels` 用来过滤小杂点，
`maxRegions` 上限 200。

```js
var p = images.findImage("/sdcard/tpl.png", 0.85, { x: 0, y: 300, width: 1080, height: 1500 });
if (p) click(p.x, p.y);

images.saveTemplate("/sdcard/tpl/ok.png", 420, 1800, 240, 80);
var hits = images.findAllImages("/sdcard/tpl/item.png", 0.9, null, 10);
hits.forEach(function (hit) { log(hit.x + "," + hit.y); });

// 区域找色：过滤小杂点后，按面积从大到小依次点击区域中心
var regions = images.findColorRegions("#BEF74F", 40, {
  minPixels: 10000,
  maxRegions: 10,
  region: { x: 0, y: 200, width: 1200, height: 1500 }
});
regions.forEach(function (r) { click(r.cx, r.cy); });

// 同一张截图复用：先截图，再做多种找色判断
var shot = captureScreen("");
var hit = images.findColorInRaw(shot, "#BEF74F", 40);
if (hit) click(hit.x, hit.y);
```

## `ocr`（本地 / 在线可切换）

识别引擎由设备配置（设置 → 识别与截图 → OCR）决定，脚本无需改动：

- **本地**：MLKit 中文模型，端侧离线。
- **在线**：百度 OCR / Google Vision / 自定义，适合低端设备省算力；在线失败自动回退本地。
- 坐标均为全分辨率屏幕像素；`x` / `y` 是包围盒**中心**，不是左上角。结构化的 `blocks` 额外提供
  `left / top / right / bottom`，判断越界请用四边而不是 `x + w`。
- 结构化结果还包含 `image: {w, h}`；超出图像的块会被裁剪并标记 `suspicious`。

| 方法 | 说明 |
|---|---|
| `ocr.recognize()` | 识别当前屏幕，返回全文 |
| `ocr.recognizePath(path)` | 识别指定图片 |
| `ocr.recognizeBlocksJson()` | 识别当前屏幕，返回结构化 JSON 字符串：`{text, image:{w,h}, blocks:[...]}` |
| `ocr.find(text)` | 查找包含子串的元素，返回 `{x, y, width, height}` 或 `null`（x/y 为中心） |

`blocks` 每项为 `{text, x, y, w, h, left, top, right, bottom, conf?, suspicious?}`，
其中 `x/y` 是包围盒中心，`left/top/right/bottom` 是四边坐标。

```js
var hit = ocr.find("登录");
if (hit) click(hit.x, hit.y);

var result = JSON.parse(ocr.recognizeBlocksJson());
result.blocks.forEach(function (block) {
  log(block.text + " @ " + block.x + "," + block.y);
});
```

## `permissions`（权限查询）

| 方法 | 说明 |
|---|---|
| `permissions.accessibility()` / `shizuku()` / `adb()` / `root()` / `hid()` | 控制模式是否就绪 |
| `permissions.overlay()` | 悬浮窗权限 |
| `permissions.screenCapture()` | 持续录屏是否开启 |
| `permissions.notifications()` / `bluetooth()` | 通知 / 蓝牙权限 |
| `permissions.usageAccess()` | 使用情况访问 |
| `permissions.notificationListener()` | 通知使用权 |
| `permissions.manageStorage()` | 全部文件访问 |
| `permissions.appList()` | 应用列表是否开启 |
| `permissions.http()` | 脚本网络（HTTP）是否启用 |
| `permissions.granted()` | 已授予项，逗号分隔 |
| `permissions.all()` | 全部项 `key=value` |
| `permissions.has(name)` | 单项查询 |
| `permissions.canControl()` | 是否有可注入输入的后端 |
| `permissions.canScreenshot()` | 是否有截图来源 |
| `permissions.virtualScreen()` | 后台虚拟屏的 Shizuku 特权后端是否可用 |
| `permissions.runMode()` | 本次脚本实际运行模式：`foreground` / `background` |

```js
if (!permissions.canScreenshot()) {
  toast("请开启无障碍或持续录屏后再运行");
  exit();
}
```

## `virtualScreen`（后台虚拟屏）

需要 `// @need virtualscreen`。后台模式由 Shizuku 特权服务创建虚拟屏，目标应用运行在虚拟屏上，
主屏可正常使用。虚拟屏激活后，`click` / `swipe` / `input` / `press` 与截图 / 找图 / OCR
会自动定向到虚拟屏，脚本无需改坐标。

```js
// @need virtualscreen
// @env RUN_MODE=background
// @env TARGET=com.example.app

if (!virtualScreen.available()) {
  log("需要 Shizuku 授权（Android 10+）");
  exit();
}
virtualScreen.start({ width: 1280, height: 720, dpi: 240 });
virtualScreen.launch(env.get("TARGET"));
// 之后的 click / images / ocr 都作用于虚拟屏
virtualScreen.stop();
```

| 方法 | 说明 |
|---|---|
| `virtualScreen.available()` | Shizuku 特权后端是否可用 |
| `virtualScreen.active()` | 虚拟屏是否已启动 |
| `virtualScreen.mode()` | 当前模式 `foreground` / `background` |
| `virtualScreen.allowed()` | 允许的模式数组 |
| `virtualScreen.start(options)` | 启动虚拟屏；`options` 可含 `width` / `height` / `dpi`。已激活且分辨率不同会重建 |
| `virtualScreen.stop()` | 停止虚拟屏 |
| `virtualScreen.launch(pkg)` | 在虚拟屏上启动应用（已在主屏运行时先强停再迁移） |
| `virtualScreen.displayId()` | 虚拟屏 displayId |
| `virtualScreen.size()` | `{ width, height, dpi }` |
| `virtualScreen.info()` | 状态、模式、目标、帧序号、分辨率、displayId 与最近停止原因 / 时间 |
| `virtualScreen.target(value?)` | 查询或切换操作目标：`auto` / `virtual` / `main` |
| `virtualScreen.awaitFrame(timeoutMs)` | 等待虚拟屏首帧，默认 1000ms |
| `virtualScreen.capture(path?, source?)` | 直接捕获并保存；`source` 缺省 `virtual` |

显式捕获与目标切换：

```js
// 后台脚本默认 AUTO：虚拟屏激活时截图/找图/OCR/点击都自动指向虚拟屏
virtualScreen.start({ width: 1280, height: 720, dpi: 240 });
virtualScreen.launch("com.example.app");
if (!virtualScreen.awaitFrame(2000)) log("虚拟屏还没有画面");

// 直接保存虚拟屏截图
var shot = virtualScreen.capture("/sdcard/virtual.png");

// 临时捕获主屏（不改变后续点击目标）
var mainShot = captureScreen("/sdcard/main.png", "main");

// 切换后续点击/输入目标；captureScreen 仍可用 source 参数一次性覆盖
virtualScreen.target("main");
click(100, 200);
virtualScreen.target("virtual");
```

说明：

- 后台模式需要 Android 10+、Shizuku 授权，并在「设置 → 悬浮窗与虚拟屏 → 后台虚拟屏」中允许。
- `@env RUN_MODE=background` 会在脚本启动时自动创建虚拟屏；未允许或不可用时回退前台并记录警告。
- 脚本开始时目标按运行模式重置：后台脚本为 `auto`（跟随虚拟屏），前台脚本为主屏；脚本内仍可用
  `virtualScreen.target("virtual")` 显式切换。未显式指定目标时，脚本自行调用
  `virtualScreen.start()` 后会自动指向虚拟屏。
- 虚拟屏激活后，`captureScreen` / `images` / `ocr` / `click` / `swipe` / `input` /
  `press` 默认自动定向到虚拟屏；`virtualScreen.target("main")` 可临时切回主屏。
- 「脚本运行结束后自动销毁」默认开启，且只销毁本次脚本启动/重建的虚拟屏；关闭后脚本结束时保留
  虚拟屏，需脚本调用 `virtualScreen.stop()` 或在「运行状态 → 虚拟屏」页手动停止。App 进程被杀时
  保留的虚拟屏也会随之销毁。
- `start()` 实际使用的分辨率会写回设置，下次启动沿用上次尺寸；`info()` 的 `lastStopReason`
  会记录 `user_stop` / `service_disconnected` / `app_restart` / `resolution_changed` / `run_finished`。
- 虚拟屏激活期间每 30 秒调用一次特权服务心跳，降低后端被系统空闲回收的概率。
- `virtualScreen.capture()` 与 `captureScreen(path, source)` 的 `source` 支持
  `auto` / `virtual` / `main`；对不存在的虚拟屏做 `virtual` 捕获会返回 null 并记录警告。
- `device.width()/height()/screenWidth()/screenHeight()` 在目标为虚拟屏时返回虚拟屏分辨率。
- `app.launch()` 在虚拟屏激活时走虚拟屏；目标应用已在主屏运行时会先强停再迁移。
  `app.openUrl()` 也会定向到虚拟屏，但不会强停。
- `app.currentPackage()` 在虚拟屏激活时返回虚拟屏上的前台包名。
- 虚拟屏上**不支持 `selector.*` / 无障碍控件树**，请改用 `capture` / `ocr` / 坐标输入。
- 脚本结束（正常、异常或超时）时会自动销毁本次创建的虚拟屏；`stop()` 用于脚本内提前释放。
- 后台运行中停在「运行」页按 Home，可进入系统画中画预览；小窗只读。
- 只有 `virtualscreen` 在 `@need` 中声明时，全局 `virtualScreen` 与 `require("virtualScreen")` 才存在。

## `env`（运行参数）

运行前的输入参数，类似环境变量，用于切换脚本运行模式。在脚本头部用注释声明，运行脚本时 App
会自动生成输入表单，用户填写后写入并随脚本保存。

声明语法：`// @env KEY=默认值  说明`（说明可省略，用两个及以上空格或 ` # ` 分隔）。

```js
// @env MODE=auto    运行模式
// @env TARGET=      目标包名
```

除脚本声明的参数外，还可以在「设置 → 环境变量」定义全局变量，并在脚本详情的「环境变量」
标签页添加仅适用于该脚本的变量。运行时按以下顺序合并（后者覆盖前者）：

1. `@env` 默认值
2. 全局环境变量（脚本开关开启时）
3. 脚本级环境变量
4. 运行时输入表单

全局变量与脚本变量同样通过 `env.get` / `env.all` / `env.has` 读取，无需 `@env` 声明。

| 方法 | 说明 |
|---|---|
| `env.get(key)` | 读取参数，不存在返回 `null` |
| `env.get(key, fallback)` | 带默认值读取 |
| `env.has(key)` | 是否存在 |
| `env.mode()` | 便捷读取 `MODE` / `mode` |
| `env.all()` | 全部参数对象 |

## 能力声明 `@need`

为了让脚本只暴露真正需要的高风险命名空间，可在头部用注释声明：

```js
// @need shell, fs
// @need http
```

- 声明后，**只有列出的命名空间会被注册**；未声明的 `shell` / `fs` / `notify` /
  `http` / `clipboard` / `ai` / `app` / `selector` / `images` / `ocr` /
  `virtualscreen` 在脚本里不存在。
- 没有 `@need` 时保持旧行为：暴露所有已启用的命名空间。
- 支持逗号、分号或空格分隔，大小写不敏感；多行会合并。
- 未知能力名会直接拒绝运行；缺少前置条件（如 `http` 未启用、`shell` 后端未就绪）会记录警告。
- 这是「最小暴露」而不是安全沙箱：输入（`click` / `swipe` 等）、`device`、`permissions`、
  `env` / `state` 与 `captureScreen()` 始终可用。
- `@need` 只控制脚本可直接调用的命名空间；AI Agent 自身的工具（例如 AI 设置里允许的
  `shell`）不受此声明限制。

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
- 内置模块 `virtualScreen` 可直接 `require("virtualScreen")`，返回与全局 `virtualScreen`
  相同的对象；声明 `@need` 时仍需包含 `virtualscreen`。

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

模型可调用的工具由设备能力决定，包括 `ocr` / `ui_dump` / `find` / `click`（`by` 区分
coord / text / id / desc，`long` 长按、`normalized` 归一化坐标）/ `swipe` / `gesture` /
`text` / `press`（`key` 为 back / home / recents / enter 或键码）/ `wait` / `info` / `app` /
`batch`（一次调用顺序执行多个动作）/ `screenshot`，以及按需开启的 `shell`。

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

1. **虚拟屏帧源**（虚拟屏激活且当前目标为虚拟屏；会等待最多 1 秒首帧）
2. **持续录屏**（若已授权，见 `docs/TOOLS.md`）
3. 控制后端的截图能力（无障碍 API30+ / Shizuku / 无线调试 / Root）

`captureScreen(path, source)` 可忽略默认目标，用 `source="main"` / `"virtual"` 一次性指定来源。

## `press` 的按键限制

- 无障碍模式：仅 `BACK` / `HOME` / `APP_SWITCH` 有效（映射为全局动作）。
- Shizuku / 无线调试 / Root：通过 `input keyevent` 支持绝大部分键码。
- 蓝牙 HID：仅映射了字母、数字、Enter/Esc/Backspace/Tab/Space/Delete、方向键。

## 控制模式与能力组合

| 能力 | 无障碍 | Shizuku | 无线调试 | Root | HID |
|---|---|---|---|---|---|
| 手势/绝对坐标 | ✅ | ✅ | ✅ | ✅ | ✅ |
| 键盘/文本 | 部分 | ✅ | ✅ | ✅ | ✅ |
| 读控件树 | ✅ | ❌ | ❌ | ❌ | ❌ |
| 截图 | ✅(30+) | ✅ | ✅ | ✅ | ❌ |
| Shell | ❌ | ✅ | ✅ | ✅ | ❌ |

> 上表的「读控件树」指脚本侧 `selector.*` 的能力，**只有无障碍后端支持**。
> 补充：AI Agent 的读屏工具额外支持在 Shizuku / 无线调试 / Root 下通过 `uiautomator dump` 兜底
> （较慢，约 1~2 秒且偶发失败），但这些都不是脚本 API 的一部分。
> 无线调试需要 Android 11+；后台虚拟屏与 Shizuku 特权解锁仍依赖 Shizuku。

推荐组合：**无障碍读控件树 + HID/Shizuku/无线调试 注入输入**。在「设置 → 控制后端 → 读取 / 操作优先级」中指定优先后端即可。
