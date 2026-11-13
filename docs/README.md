# Izuki JS 文档索引

本目录同时包含**使用文档**和**开发文档**。除特别说明外，文档以简体中文维护；
英文 README 仅覆盖项目介绍与上手，链接到本目录时保持中文原文。

## 按角色阅读

### 我是使用者 / 脚本作者

| 文档 | 内容 |
|---|---|
| [README](../README.md) | 项目介绍、特性、安装、首次使用 |
| [SCRIPT_API.md](SCRIPT_API.md) | 脚本 API：全局函数、输入、选择器、找图、OCR、AI、pipeline 等 |
| [TOOLS.md](TOOLS.md) | 界面与工具：权限与能力、录屏、悬浮窗、定时任务、日志、设置项 |
| [CONTROL_MODES.md](CONTROL_MODES.md) | 控制模式能力矩阵、权限风险、手势 / 按键限制、截图来源 |
| [MCP.md](MCP.md) | 把设备能力暴露给外部 AI 客户端 |
| [BLE_HID_PROTOCOL.md](BLE_HID_PROTOCOL.md) | 蓝牙 HID 硬件协议 |

### 我是贡献者

| 文档 | 内容 |
|---|---|
| [CONTRIBUTING.md](../CONTRIBUTING.md) | 环境搭建、构建、测试、代码风格、提交与 PR |
| [ARCHITECTURE.md](ARCHITECTURE.md) | 分层、包职责、脚本运行链路、数据与线程模型 |
| [EXTENDING.md](EXTENDING.md) | 新增控制后端、脚本 API、MCP 工具、pipeline 识别 / 动作 |
| [TESTING.md](TESTING.md) | 测试分层、现有测试覆盖、录制回放回归、设备矩阵 |

### 我是维护者 / 发布者

| 文档 | 内容 |
|---|---|
| [RELEASING.md](RELEASING.md) | 版本号、CHANGELOG、签名、CI、Release 流程 |
| [PERF.md](PERF.md) | 性能基线指标、采集开关与对比场景 |
| [AGENTS.md](../AGENTS.md) | 面向 AI 协作者的命令、约定与文档地图 |

## 按任务查找

| 我想…… | 先看 |
|---|---|
| 写一个能跑起来的脚本 | [README 快速开始](../README.md#快速开始) → [SCRIPT_API.md](SCRIPT_API.md) |
| 知道某个权限能做什么、有什么风险 | [CONTROL_MODES.md](CONTROL_MODES.md) → [TOOLS.md](TOOLS.md) |
| 理解脚本从点击运行到结束发生了什么 | [ARCHITECTURE.md](ARCHITECTURE.md#脚本运行链路) |
| 新增一种控制后端 | [EXTENDING.md](EXTENDING.md#新增控制后端) |
| 给脚本加一个新命名空间 / 函数 | [EXTENDING.md](EXTENDING.md#新增脚本-api) |
| 给 MCP 加一个工具 | [EXTENDING.md](EXTENDING.md#新增-mcp-工具) |
| 给 pipeline 加识别或动作 | [EXTENDING.md](EXTENDING.md#新增-pipeline-识别--动作) |
| 跑测试、判断该写哪类测试 | [TESTING.md](TESTING.md) |
| 发版、改版本号、看 CI 做了什么 | [RELEASING.md](RELEASING.md) |

## 文档约定

- **单一事实源**：
  - 脚本能力的注册与 `@need` 名单以 `runtime/api/ScriptApiCatalog.kt` 为准；
  - pipeline 的识别 / 动作类型以 `pipeline/PipelineSpec.kt` 为准；
  - 控制模式能力矩阵只在 [CONTROL_MODES.md](CONTROL_MODES.md) 维护，其他文档链接过来。
- **相对链接**：跨文档链接使用相对路径，便于在 GitHub、IDE 和本地同时打开。
- **改动即文档**：新增 / 修改用户可见行为时，同步更新对应文档与 [CHANGELOG](../CHANGELOG.md)。
- **不记录密钥**：文档示例中的令牌、API Key、PIN 一律使用占位符。
- **链接检查**：CI 会运行 `python3 scripts/check_docs.py` 检查相对链接是否失效。
