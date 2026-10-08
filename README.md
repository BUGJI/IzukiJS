# Izuki JS

> zuki 源自日语「いづき」（居付き），意为“常驻、附着”。<br>
> 在自动化脚本的语境下，它代表脚本像影子一样附着在系统之上，安静、持续、可靠地执行任务。<br>
> 后缀 -js 表明它仍可以用 JavaScript 编写脚本，并且让同一份脚本更通用

基于 Kotlin / Jetpack Compose 的 Android 自动化脚本运行环境。用 JavaScript（QuickJS）编写脚本，
组合**无障碍 / Shizuku / Root / 蓝牙 HID** 多种控制后端驱动设备，内置找图找色、OCR 与 AI Agent 脱困能力。

[![License: GPL-3.0](https://img.shields.io/badge/License-GPL--3.0-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android%2028%2B-green.svg)]()
[![Kotlin](https://img.shields.io/badge/Kotlin-2.2.10-purple.svg)]()

## 特性

- **多控制后端，能力协商与降级**：无障碍、Shizuku、Root、蓝牙 HID 统一抽象为 `Capability`，
  可全局或按能力指定优先级，自动选择当前真正可用的后端。
- **JavaScript 脚本运行时**：基于 QuickJS，每个脚本独立运行时；提供输入、`device` / `app` /
  `selector` / `shell` / `images` / `ocr` / `permissions` / `ai` 等接口。
- **混合控制**：推荐「无障碍读控件树 + HID/Shizuku 注入输入」——读界面与发指令各取所长。
- **视觉能力**：OpenCV 找图 / 找色，本地 MLKit 中文 OCR 或在线 OCR（百度 / Google Vision / 自定义），
  在线失败自动回退本地。
- **持续录屏截图**：一次授权常驻，为截图 / 找图 / OCR 提供无限屏幕帧，绕开无障碍截图限制。
- **AI Agent 脱困**：脚本卡住时交给 OpenAI 兼容模型观察屏幕并操作设备；支持原生 function calling，
  不支持时自动降级为提示词协议。
- **MCP 服务器**：把设备读屏与控制能力以 Model Context Protocol 暴露给外部 AI 客户端
  （Claude Desktop / Cursor / Cline），支持局域网 / 仅本机监听、令牌鉴权与工具级开关。
- **蓝牙 HID 注入**：通过外部 HID 硬件（如 ESP32 数位板狗）以真实硬件事件控制设备，最难被检测。
- **调试与工具**：布局分析器生成选择器代码、可拖动调试悬浮窗（布局 / OCR 坐标叠加）、
  悬浮控制条、定时任务、日志分文件存储与自动清理。
- **配置备份**：AI / OCR / 控制 / 截图 / 日志 / 编辑器偏好一键导出导入，敏感密钥经 Android Keystore 加密。
  由于 Keystore 密钥无法跨设备迁移，系统级自动备份已排除含密文的配置；**换机请使用应用内导出 / 导入**。

## 控制模式与能力

| 能力 | 无障碍 | Shizuku | Root | 蓝牙 HID |
|---|---|---|---|---|
| 手势 / 绝对坐标 | ✅ | ✅ | ✅ | ✅ |
| 键盘 / 文本 | 部分 | ✅ | ✅ | ✅ |
| 读控件树 | ✅ | ❌ | ❌ | ❌ |
| 截图 | ✅(API30+) | ✅ | ✅ | ❌ |
| Shell | ❌ | ✅ | ✅ | ❌ |

脚本内可用 `permissions.*` 查询就绪状态，详见 [`docs/SCRIPT_API.md`](docs/SCRIPT_API.md)。

## 快速开始

### 环境要求

- Android 9（API 28）及以上
- 构建：JDK 17（运行 Gradle 所需；源码兼容级别为 Java 11）、Android SDK（`compileSdk 37`）

### 构建

```bash
# 克隆
git clone https://github.com/BUGJI/IzukiJS.git
cd IzukiJS

# 构建 Debug
./gradlew :app:assembleDebug

# 构建 Release（未配置签名时回退 debug 签名，仅用于本地验证）
./gradlew :app:assembleRelease
```

正式签名：复制 `keystore.properties.example` 为 `keystore.properties` 并填入密钥信息
（该文件已被 `.gitignore` 忽略，不会提交）。

产物按 ABI 拆分：`arm64-v8a` / `armeabi-v7a` / `x86_64`。

### 首次使用

1. 打开 App，在「运行 → 权限与能力 → 配置」按需开启控制后端：
   - **无障碍**：跳转系统无障碍设置开启（读控件树、API30+ 截图、基础手势）。
   - **Shizuku**：先安装并启动 Shizuku，再在 App 内授权（输入注入、截图、Shell）。
   - **Root**：检测 Root 并通过 `su` 授权。
   - **蓝牙 HID**：扫描并连接外部 HID 数位板狗。
2. 需要无限截图时，在「运行 → 权限与能力 → 屏幕捕获」授权一次并开始。
3. 在「脚本」页新建脚本，编辑并运行。

> 提示：脚本运行时可以收回非必要权限，只保留当前脚本真正需要的模式，以降低被风控识别的风险。

## 脚本示例

```js
// 打开应用 → 等待登录按钮 → 点击
app.launch("com.example.app");
sleep(1500);

var hit = ocr.find("登录");
if (hit) {
  click(hit.x, hit.y);
  log("已点击登录 @ " + hit.x + "," + hit.y);
} else {
  toast("未找到登录按钮");
}

// 读控件树点击
if (selector.existsById("com.example.app:id/confirm")) {
  selector.clickById("com.example.app:id/confirm");
}

// 卡住时交给 AI 脱困
if (ai.available()) {
  ai.run("关闭广告弹窗并回到首页。屏幕文字：" + ocr.recognize(), {
    maxSteps: 6,
    onTool: function (name, args) { log("→ " + name + " " + args); }
  });
}
```

完整 API 见 [`docs/SCRIPT_API.md`](docs/SCRIPT_API.md)。

## 文档

| 文档 | 内容 |
|---|---|
| [`docs/SCRIPT_API.md`](docs/SCRIPT_API.md) | 脚本 API 速查：全局函数、输入、选择器、找图、OCR、AI 等 |
| [`docs/TOOLS.md`](docs/TOOLS.md) | 工具与权限说明：控制模式、持续录屏、调试悬浮窗、定时任务、日志 |
| [`docs/BLE_HID_PROTOCOL.md`](docs/BLE_HID_PROTOCOL.md) | 蓝牙 HID 控制协议：GATT 服务、帧格式、坐标映射、连接流程 |
| [`docs/MCP.md`](docs/MCP.md) | MCP 服务器：启用、客户端配置、工具与资源、安全说明 |

## 项目结构

```
app/src/main/java/com/benton/izukijs/
├── ai/            # AI Agent：配置、客户端、工具调用、提示词
├── mcp/           # MCP 服务器：JSON-RPC 协议、HTTP 传输、工具与资源
├── controller/    # 控制抽象层与各后端实现
│   ├── accessibility/  # 无障碍
│   ├── shizuku/        # Shizuku
│   ├── root/           # Root
│   ├── hid/            # 蓝牙 HID（协议 / GATT 客户端 / 控制器）
│   └── shell/          # 命令后端
├── runtime/       # QuickJS 运行时与脚本 API 绑定（runtime/api）
├── ocr/           # 本地 MLKit / 在线 OCR 引擎
├── service/       # 前台服务：脚本、悬浮窗、调试叠加、录屏
├── schedule/      # 定时任务与开机重注册
├── data/          # 脚本仓库、编辑器设置、配置备份
├── security/      # 密钥加密
├── ui/            # Compose 界面（run / scripts / editor / settings / ...）
└── model/         # ControlMode / Capability 等
```

## 技术栈

- Kotlin 2.2.10 · Jetpack Compose（Material 3）· Navigation Compose
- [QuickJS-Android](https://github.com/taoweiji/quickjs-android)（`io.github.taoweiji.quickjs`）
- [Shizuku](https://shizuku.rikka.app/) · OpenCV · MLKit 中文文字识别
- Kotlin Coroutines · Timber

## 许可证

本项目基于 [GNU General Public License v3.0](LICENSE) 发布。

```
Copyright (C) 2026 BUGJI

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.
```
