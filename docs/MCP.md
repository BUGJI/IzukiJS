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

读屏 / 控制类工具默认可用（取决于当前就绪的控制后端）：

| 工具 | 说明 |
|---|---|
| `ocr_screen` | OCR 识别整屏文字 |
| `ui_dump` | 读取可点击控件树（含坐标） |
| `find_text` | 查找包含指定文字的元素并返回中心坐标 |
| `screenshot` | 截图，返回 JPEG 图像内容块 |
| `click` / `long_click` / `swipe` | 绝对坐标手势 |
| `gesture` | 复杂轨迹（曲线 / 多段 / 多指 / 停顿） |
| `input_text` / `press_key` | 文本输入 / 物理按键 |
| `press_back` / `press_home` / `press_recents` / `press_enter` | 全局动作 |
| `launch_app` / `current_app` / `open_url` | 应用相关 |
| `click_by_id` / `click_by_text` / `click_by_desc` | 无障碍选择器点击 |
| `long_click_by_id` / `long_click_by_text` | 无障碍选择器长按 |
| `set_text_by_id` / `get_text_by_id` / `bounds_by_id` / `exists_by_id` | 无障碍控件操作 |
| `device_info` / `battery` | 设备信息 |

需显式开启后可用：

| 工具 | 开关 | 说明 |
|---|---|---|
| `shell` | 允许 Shell | 执行 Shell 命令（需 Shizuku / Root） |
| `list_scripts` / `read_script` / `run_script` / `stop_script` / `running_script` | 允许运行脚本 | 脚本管理 |

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
- 截图统一走「持续录屏优先、控制后端兜底」，并会自动隐藏悬浮层以免被截入画面。

## 排障

- **连接被拒绝**：确认服务已启用、地址 / 端口正确、手机与电脑在同一网段；局域网模式下留意
  路由器的 AP 隔离。
- **401 Unauthorized**：令牌错误或缺少 `Authorization: Bearer <token>`；重新生成令牌后需更新客户端。
- **404 Session not found**：客户端回传了未知的 `Mcp-Session-Id`；重新 `initialize` 或重启服务。
- **工具返回失败**：确认对应的控制后端已就绪（见「权限与能力」页）；`shell` / 脚本工具需在
  「工具开关」中开启。
- **Claude Desktop 无响应**：该客户端仅支持 stdio，请使用上面的 `mcp-remote` 配置。
