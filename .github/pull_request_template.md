## 改动说明

<!-- 这个 PR 解决什么问题？为什么需要这样改？ -->

## 改动范围

- [ ] 脚本运行时 / API
- [ ] 控制后端 / 能力协商
- [ ] UI / 设置
- [ ] MCP / AI Agent
- [ ] 虚拟屏 / 截图 / 识别
- [ ] 定时 / 编排 / 通知 / 更新
- [ ] 构建 / CI / 文档

## 验证

<!-- 列出实际运行的命令与结果；无法运行设备验证时写清原因与需要覆盖的矩阵。 -->

- [ ] `./gradlew testDebugUnitTest`
- [ ] `python3 scripts/check_versions.py`
- [ ] `python3 scripts/check_docs.py`
- [ ] `./gradlew lintDebug` / `./gradlew :app:assembleDebug`（涉及 UI / 权限 / 清单 / 发布时）

设备验证（未验证也请注明）：

- Android 版本：
- 控制后端：无障碍 / Shizuku / 无线调试 / Root / HID
- 场景：前台 / 后台虚拟屏 / PiP / 旋转 / 分屏

## 文档与兼容性

- [ ] 已更新 `CHANGELOG.md` 的「未发布」
- [ ] 已更新受影响的 `docs/`（`SCRIPT_API.md` / `TOOLS.md` / `MCP.md` / `CONTROL_MODES.md` 等）
- [ ] 没有引入破坏性脚本 API / 配置 / 数据格式变更；如有，已在 PR 中说明迁移方式

## 安全

- [ ] 未提交令牌、API Key、PIN、设备序列号或真实用户数据
- [ ] Shell / 解锁 / MCP / 录制相关改动已检查 `ToolPolicy` / `ShellGuard` / 配置开关
