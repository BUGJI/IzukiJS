# Izuki JS · MCP 服务

把手机包装成一个 **MCP（Model Context Protocol）服务器**：外部 AI 客户端（Claude Desktop /
Cursor / Cline 等）可以通过标准 MCP 工具读取屏幕并控制设备。工具实现与脚本、AI Agent 完全一致，
复用同一套能力协商（无障碍 / Shizuku / Root / 蓝牙 HID）。

## 快速开始

1. 设置 → 「MCP 服务」。
2. 打开「启用 MCP 服务」。首次启用会自动生成访问令牌。
3. 选择「监听范围」：
   - **局域网**（默认）：监听所有网卡，同一 Wi-Fi 下的电脑可访问。
   - **仅本机**：只监听 `127.0.0.1`，需配合 `adb forward`。
4. 记录「连接地址」，例如 `http://192.168.1.20:8765/mcp` 与访问令牌。
5. 需要 Shell / 运行脚本时，在「工具开关」中显式开启（默认关闭）。

> 安全提示：任何持有令牌的设备都能读屏并控制本机。请仅在可信网络使用，令牌等同于设备控制权。

## 端点

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/mcp` | JSON-RPC 2.0（Streamable HTTP 的 JSON 响应模式），需 `Authorization: Bearer <token>` |
| GET | `/mcp` | 返回 `405`，表示不提供服务器主动推送流 |
| DELETE | `/mcp` | 结束会话（携带 `Mcp-Session-Id`） |
| GET | `/health` | 健康检查，无需鉴权 |

- 协议版本：`2025-06-18`（兼容协商 `2025-03-26` / `2024-11-05`）。
- 会话：`initialize` 响应会下发 `Mcp-Session-Id`，客户端后续请求需回传该头。
- 所有设备操作在单线程串行执行，避免手势 / OCR 互相打断。

## 客户端配置

### 支持 Streamable HTTP 的客户端

```json
{
  "mcpServers": {
    "izuki": {
      "url": "http://192.168.1.20:8765/mcp",
      "headers": { "Authorization": "Bearer <令牌>" }
    }
  }
}
```

### 仅支持 stdio 的客户端（如 Claude Desktop）

用 [`mcp-remote`](https://www.npmjs.com/package/mcp-remote) 桥接：

```json
{
  "mcpServers": {
    "izuki": {
      "command": "npx",
      "args": [
        "-y",
        "mcp-remote",
        "http://192.168.1.20:8765/mcp",
        "--header",
        "Authorization: Bearer <令牌>"
      ]
    }
  }
}
```

### 仅本机模式（adb forward）

```bash
adb forward tcp:8765 tcp:8765
# 然后客户端连接 http://127.0.0.1:8765/mcp
```

## 工具

读屏 / 控制类工具经过聚合（同类操作合并为单工具 + 判别参数），降低模型选错概率：

| 工具 | 说明 |
|---|---|
| `screenshot` | 截图，返回图像块及 `{image_size, screen_size}`（带 region 时附 `region`）；可用 `scale` / `quality` / `region=[l,t,r,b]` / `format=jpeg\|webp` 控制体积 |
| `ocr` | 识别整屏文字，返回结构化文本块（含中心坐标 x/y、宽高、置信度） |
| `ui_dump` | 控件树（无障碍优先；Shizuku / Root 下走 `uiautomator dump` 兜底） |
| `find` | 查找元素并返回匹配项（中心坐标 + 边界）；`text` 模糊匹配，或 `by=id\|desc` + `value` |
| `click` | 点击 / 长按。`by=coord`（默认，x/y 像素）、`normalized=true`（0~1，推荐）或 `by=text\|id\|desc`；支持 `long` / `settle` / `dry_run`，返回实际位置与命中元素 |
| `swipe` | 滑动（支持 `normalized`） |
| `gesture` | 复杂轨迹（曲线 / 多段 / 多指 / 停顿） |
| `text` | 输入文本；`by=id` + `value` 时给指定控件设值；`method` 选 `auto`/`input`/`clipboard`/`broadcast`（无障碍优先，Shizuku/Root 下用控件树定位并聚焦后输入） |
| `press` | 按键：`key` 为 back / home / recents / enter，或 Android KeyEvent 键码 |
| `wait` | 等待：`for=text` 等文字出现，`for=idle` 等界面稳定 |
| `info` | 设备信息：`what=device\|battery\|current_app` |
| `app` | `action=launch`（package；命中黑名单则拒绝）或 `action=open_url`（url） |
| `batch` | 一次调用顺序执行多个动作：`actions=[{tool,arguments}]`；默认失败即停并回传每步结果 |
| `get_result` / `list_operations` | 查询异步操作结果 / 列出最近操作 |

需显式开启后可用：

| 工具 | 开关 | 说明 |
|---|---|---|
| `shell` | 允许 Shell | 执行 Shell 命令（需 Shizuku / Root）；高危命令默认拦截 |
| `list_scripts` / `read_script` / `run_script` / `stop_script` / `running_script` | 允许运行脚本 | 脚本管理 |

## 坐标与截图

- 截图的图像尺寸可能小于真实屏幕（受 `scale` 或 GPU 限制）。响应同时给出 `image_size`
  与 `screen_size`；`click` 使用 `screen_size` 坐标系。
- 推荐直接使用 `click` 的 `normalized=true`（0~1）：等比缩放下归一化坐标不变，永不缩放错。
- `region=[l,t,r,b]` 只截局部以省 token；此时图像是屏幕的子区域，请改用屏幕像素坐标
  （`x = region.left + 图内 x`），不要再配合 `normalized`。`format=webp` 在 API 30+ 生效，
  低版本自动回退 JPEG。
- `ocr` / `ui_dump` / `find` 返回的均为全分辨率屏幕像素。

## 批量动作

`batch` 用于把一串确定的操作合并为一次调用，减少往返与中间截图回传：

```json
{ "actions": [
  { "tool": "press", "arguments": { "key": "home" } },
  { "tool": "wait",  "arguments": { "for": "text", "text": "应用商店" } },
  { "tool": "click", "arguments": { "normalized": true, "x": 0.5, "y": 0.9 } }
] }
```

- 默认 **失败即停**，返回 `{ok, executed, stopped_early, steps:[{index,tool,ok,result}]}`；
  传 `continue_on_error=true` 可继续执行后续动作。
- 建议只放点击 / 按键 / 等待等轻量动作；`ocr` / `ui_dump` 输出大，单独调用更好。
- 不支持嵌套 `batch`；动作数上限 20，超出的部分会被截断并标注 `truncated`。
- 整批仍走单线程设备队列，超过同步窗口同样会返回 `request_id` 转异步。

## 超时语义

- 设备操作在单线程串行执行。若一次调用超过约 20s 未返回，服务器会先回
  `{"status":"running","request_id":"..."}`——这表示**操作已提交、结果未知**，
  并**不代表失败**，请勿直接重试（可能造成重复点击）。
- 用 `get_result(request_id=...)` 查询最终结果；`list_operations` 查看近期操作。
- `get_result` / `list_operations` 在请求线程直接执行，不会被卡住的任务堵塞。

## 后台保活

- MCP 由前台服务 `McpServerService` 常驻，通知栏显示访问地址与停止入口。
- 建议在设置页将本应用加入「电池优化白名单」，否则部分 ROM 在切后台 / 锁屏后会回收进程，
  表现为客户端 `ECONNREFUSED` 或 `Read timed out`。

## 安全护栏

- **预演 `dry_run`**：`click` / `swipe` / `text` / `press` / `gesture` / `app` 支持 `dry_run=true`，
  只回显将要执行的操作而不真正执行；`click` 的 `by=text|id|desc` 还会解析出目标元素，便于执行前自校验。
- **高危 Shell 拦截**：`shell` 工具会拦截 `rm -rf /`、`mkfs`、`dd` 写块设备、`pm uninstall/clear`、
  `settings put`、`mount remount`、`reboot` 等破坏性命令；确需放行时在设置页开启「允许高危 Shell」。
- **敏感 App 黑名单**：设置页可填写包名列表，`app(action=launch)` 命中即拒绝；支持结尾 `*` 前缀通配。
- **审计日志**：所有 Shell 调用都会以 `🛡️ shell` 前缀写入应用日志（含退出码），便于事后追溯。

## 资源

| URI | 内容 |
|---|---|
| `izuki://device` | 屏幕尺寸、机型、Android 版本、电量（JSON） |
| `izuki://status` | 就绪的控制后端、当前运行脚本（JSON） |
| `izuki://scripts` | 脚本列表（JSON） |
| `izuki://scripts/{id}` | 指定脚本源码 |
| `izuki://logs` | 最近日志（尾部） |

## 实现说明

- 无三方依赖：`java.net.ServerSocket` 实现极简 HTTP/1.1（支持 `Content-Length` 与 `chunked`），
  `org.json` 处理 JSON-RPC。
- 前台服务 `McpServerService` 保活进程，通知栏常驻访问地址与「停止」入口；应用再次启动时
  若此前已启用会自动恢复运行。
- 令牌经 Android Keystore（`SecretCipher`）加密存储；请在设置页「重新生成」以轮换令牌。
- 截图统一走「持续录屏优先、控制后端兜底」，并会自动隐藏悬浮层以免被截入画面；MCP 截图
  不再复用「保存截图」的压缩设置，避免图像被缩放而坐标未缩放。
- 控件树读取：无障碍优先；当无障碍未开启但 Shizuku / Root 就绪时，回退到 Shell 执行
  `uiautomator dump` 并解析控件树。
- 文本输入：无障碍优先（`ACTION_SET_TEXT`）。Shizuku / Root 下的 `text.method`：
  - `auto`（默认）：ASCII 走 `input text`，非 ASCII 优先「剪贴板 + 粘贴」（无需额外 App），
    失败再退回 ADBKeyboard 广播；
  - `input`：仅 `input text`（只支持 ASCII）；
  - `clipboard`：写入系统剪贴板后发送 `KEYCODE_PASTE`，兼容性最好，但会改写当前剪贴板；
  - `broadcast`：ADBKeyboard 的 `ADB_INPUT_B64` / `ADB_INPUT_TEXT`（需安装并启用该输入法）。
- 剪贴板方式依赖焦点已在可编辑控件上；`by=id` 只用于定位，用 `by=id` 时框架会先点击聚焦再输入。
- `text` 的 `by=id` 在无障碍不可用时，会用控件树定位控件、点击聚焦后再输入，因此仍可用于设值。

## 排障

- **连接被拒绝**：确认服务已启用、地址 / 端口正确、手机与电脑在同一网段；局域网模式下留意
  路由器的 AP 隔离。
- **401 Unauthorized**：令牌错误或缺少 `Authorization: Bearer <token>`；重新生成令牌后需更新客户端。
- **404 Session not found**：客户端回传了未知的 `Mcp-Session-Id`；重新 `initialize` 或重启服务。
- **工具返回失败**：确认对应的控制后端已就绪（见「权限与能力」页）；`shell` / 脚本工具需在
  「工具开关」中开启。
- **Claude Desktop 无响应**：该客户端仅支持 stdio，请使用上面的 `mcp-remote` 配置。
