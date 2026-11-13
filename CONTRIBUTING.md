# 贡献指南

感谢你愿意改进 Izuki JS。本文覆盖本地开发、验证、提交与文档约定；
架构和扩展方式分别见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) 与
[docs/EXTENDING.md](docs/EXTENDING.md)。

## 开始之前

- 项目处于早期实验阶段，脚本 API 与后端行为可能变更，优先修复明确问题、保持行为兼容。
- 涉及设备控制、权限、Shell、MCP、解锁、录制等能力时，请先阅读
  [docs/CONTROL_MODES.md](docs/CONTROL_MODES.md) 和 [docs/MCP.md](docs/MCP.md) 的安全说明。
- 不要在 Issue / PR / 日志 / 截图中提交真实令牌、API Key、PIN、设备序列号或他人隐私数据。
- 仅在你拥有或明确获授权的设备上测试。

## 环境要求

| 项目 | 版本 |
|---|---|
| JDK | 17（Gradle 运行所需） |
| Android SDK | `compileSdk 37`、`targetSdk 37`、`minSdk 28` |
| Android NDK | `27.1.12297006` |
| CMake | `3.22.1` |
| Kotlin | 见 `gradle/libs.versions.toml`（不要手写版本） |
| Python | 3.x（仅用于 `scripts/check_versions.py` / `check_docs.py`） |

NDK / CMake 仅用于 `app/src/main/cpp` 的 QuickJS 中断回调 JNI 垫片；改动脚本运行时或
中断行为时才会触发它们的完整构建。

推荐使用 Android Studio 打开仓库根目录；也可以只用命令行：

```bash
# Windows
gradlew.bat :app:assembleDebug

# macOS / Linux
./gradlew :app:assembleDebug
```

首次构建会下载 Gradle、Android SDK 平台与 JitPack 上的 QuickJS / libadb 依赖。
JitPack 偶发超时可重试；仓库地址与依赖白名单见 `settings.gradle.kts`。

## 常用命令

| 目的 | 命令 |
|---|---|
| 构建 Debug APK | `./gradlew :app:assembleDebug` |
| 构建 Release APK | `./gradlew :app:assembleRelease` |
| JVM 单元测试 | `./gradlew testDebugUnitTest` |
| Android Lint | `./gradlew lintDebug` |
| 版本号一致性 | `python3 scripts/check_versions.py` |
| 文档链接检查 | `python3 scripts/check_docs.py` |
| 安装到设备 | `./gradlew :app:installDebug` |

Windows 下把 `./gradlew` 换成 `gradlew.bat`。提交前至少运行 **单元测试 + 版本检查 + 文档检查**；
改动 UI、权限或清单时再运行 `lintDebug` 与 `assembleDebug`。CI 会执行同样的核心步骤。

## 签名

Release 默认读取仓库根目录的 `keystore.properties`；文件不存在时会回退到 debug 签名，
仅用于本地验证，并会打印告警。配置方式：

```properties
storeFile=/absolute/or/relative/path/to/release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

`keystore.properties` 与 `*.jks` / `*.keystore` 已在 `.gitignore` 中，**不要提交**。
正式发布流程见 [docs/RELEASING.md](docs/RELEASING.md)。

## 代码风格

- Kotlin 使用官方风格（`kotlin.code.style=official`），4 空格缩进；换行、编码等由
  `.editorconfig` 约束。
- 现有代码大量使用中文注释说明「为什么」和边界条件；新增逻辑保持同样的解释密度，
  不要只复述代码在做什么。
- 优先沿用现有抽象，不引入新的 DI / 网络 / JSON / 图像库。项目当前刻意保持轻依赖：
  - 依赖注入：手写 `AppContainer` / `McpContainer`；
  - JSON：`org.json`；
  - 网络：`HttpURLConnection` / 现有 HTTP 栈；
  - 日志：`LogBus` + Timber / logcat。
- Compose 页面放在 `ui/<feature>/`，状态提升到 `ViewModel` 或 `AppContainer` 暴露的仓库；
  不要在 Composable 里直接做阻塞设备操作。
- 新增用户可见文案时同时补 `res/values/strings.xml` 与 `res/values-en/strings.xml`。
- 注释与文档使用简体中文；变量名、API 名、协议字段保持英文。

## 代码组织

新增文件时优先放进现有包，不要为单个类新建顶层包。常用落点：

| 要改的东西 | 位置 |
|---|---|
| 控制后端 / 能力协商 | `controller/`、`model/ControlMode.kt`、`model/Capability.kt` |
| 脚本运行时 / API 桥接 | `runtime/`、`runtime/api/`、`assets/runtime/prelude.js` |
| 识别 / OCR | `vision/`、`ocr/` |
| MCP 协议 / 工具 | `mcp/`、`ai/AgentTools.kt` |
| 前台服务 / 无障碍 / 录屏 | `service/` |
| 定时 / 编排 / 订阅 | `schedule/`、`flow/`、`subscription/` |
| 设置持久化 | `data/`、`core/prefs/PrefsStateStore.kt`、`developer/` |
| 配置备份 | `backup/ConfigSections.kt` |
| Compose UI | `ui/<feature>/` |

完整包职责与关键链路见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)。

## 提交规范

提交信息沿用仓库现有习惯，采用 Conventional Commits 风格，描述用中文：

```text
feat(images): 新增连通域找色与区域点击
fix(virtualscreen): 修复旋转后截图只占一角
refactor(settings): 整理设置页信息架构
perf(runtime): 降低截图与日志热路径开销
docs(dev): 补充架构与扩展指南
chore(release): 发布 1.2.2
```

- `type`：`feat` / `fix` / `refactor` / `perf` / `test` / `docs` / `chore` 等。
- `scope`：尽量写模块名（`mcp` / `record` / `images` / `controller` / `ui`）。
- 一个提交只做一件事；格式化、重命名不要和功能修复混在一起。
- 破坏性变更在正文写清迁移方式，并在 [CHANGELOG.md](CHANGELOG.md) 标注。

## 分支与 PR

- 从 `main` 拉出功能分支，完成后向 `main` 提 PR；不要直接推 `main`。
- PR 描述至少包含：动机、改动范围、验证方式、是否影响脚本 API / 配置 / 数据格式。
- 用户可见变更必须同步：
  - [CHANGELOG.md](CHANGELOG.md) 的「未发布」；
  - 对应文档（脚本 API、工具说明、MCP、控制模式等）。
- 涉及权限、Shell、MCP、解锁、录制、配置备份的改动，请在 PR 中明确安全影响。
- 使用 `.github/pull_request_template.md` 的检查项。

## 版本号

版本号有两个唯一来源：

- App 版本：`gradle.properties` 的 `izuki.versionName` / `izuki.versionCode`；
- Kotlin 版本：`gradle/libs.versions.toml` 的 `[versions].kotlin`。

不要在 `app/build.gradle.kts` 里硬编码版本号，也不要单独改 README 里的 Kotlin 徽章；
`scripts/check_versions.py` 会在 CI 中校验它们一致。发版步骤见
[docs/RELEASING.md](docs/RELEASING.md)。

## 文档要求

- 文档为简体中文，放在 `docs/`；入口是 [docs/README.md](docs/README.md)。
- 跨文档使用相对链接；不要把 GitHub 绝对地址写进正文。
- 新增脚本 API 时同步更新 `docs/SCRIPT_API.md` 与编辑器补全来源
  `runtime/api/ScriptApiCatalog.kt`。
- 新增控制模式能力时同步更新 `docs/CONTROL_MODES.md`（能力矩阵的唯一维护处）。
- 新增配置项时评估是否需要注册到 `backup/ConfigSections.kt`。
- `python3 scripts/check_docs.py` 必须通过。

## 开发者证书

Android Keystore 中的密钥（`SecretCipher`）无法跨设备迁移。测试配置备份 / 恢复时，
请使用应用内导出 / 导入，不要依赖系统自动备份；锁屏 PIN / 图案按设计不参与导出。

## 需要帮助

- 先查 [docs/README.md](docs/README.md) 的「按任务查找」；
- 仍无法解决时提 Issue，附：设备型号 / Android 版本、控制后端、复现步骤、脱敏后的日志；
- 安全敏感问题不要公开贴出令牌或可复现的隐私数据。
