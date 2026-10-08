# 更新日志

本项目的所有重要变更都会记录在此文件。

格式遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [未发布]

### 新增

- 脚本中心支持扫码导入：识别二维码链接并下载脚本。
- MCP 工程化完善：坐标处理、超时控制、连接保活、读屏兜底与工具聚合。

## [1.1.0] - 2026-10-08

### 新增

- 新增 MCP 服务器，向外部 Agent 开放设备读屏与控制能力。

### 构建 / CI

- 新增 tag（`v*`）触发的 Release 在线构建与自动发包。
- 修复 `setup-android` 因 tools 包移除导致的安装失败。

## [1.0.0] - 2026-10-08

首个公开版本。

### 新增

- JS 运行时（QuickJS）与脚本中心，支持脚本的导入、编辑与运行。
- 多控制模式与能力协商：无障碍 / Shizuku / Root / 蓝牙 HID。
- 脚本运行参数与状态（`env` / `state` / `module`）。
- 增强 HID / 输入 API，重构权限与导航界面。
- 中英文本地化与界面交互优化。

### 修复

- 修复停止脚本崩溃，并优化悬浮窗与视觉日志。
- 降低编辑器重组，消除脚本列表加载闪烁。

### 其他

- 恢复界面动效并优化设置页布局。
- 交互体验优化与 Android 16 兼容性修复。

[未发布]: https://github.com/BUGJI/IzukiJS/compare/v1.1.0...HEAD
[1.1.0]: https://github.com/BUGJI/IzukiJS/compare/v1.0.0...v1.1.0
[1.0.0]: https://github.com/BUGJI/IzukiJS/releases/tag/v1.0.0
