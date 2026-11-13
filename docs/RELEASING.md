# 构建与发布

本文覆盖本地构建、签名、版本号、CI 与 GitHub Release 流程。日常开发命令见
[CONTRIBUTING.md](../CONTRIBUTING.md)。

## 版本号

唯一来源：

| 内容 | 位置 |
|---|---|
| App 版本名 / 版本号 | `gradle.properties` 的 `izuki.versionName` / `izuki.versionCode` |
| Kotlin 版本 | `gradle/libs.versions.toml` 的 `[versions].kotlin` |
| 依赖版本 | `gradle/libs.versions.toml` |

`app/build.gradle.kts` 通过 `providers.gradleProperty(...)` 读取，不允许硬编码。
`scripts/check_versions.py` 会校验：

- `gradle.properties` 存在两个字段；
- `app/build.gradle.kts` 没有硬编码 `versionCode` / `versionName`；
- 两个 README 的 Kotlin 徽章 / 技术栈版本与 `libs.versions.toml` 一致。

发版前改版本号：

```properties
# gradle.properties
izuki.versionCode=18
izuki.versionName=1.2.3
```

并在 `CHANGELOG.md` 增加对应小节：

```markdown
## [1.2.3] - 2026-11-14
### 新增
- ...
### 修复
- ...
```

`release.yml` 会按 `## [版本]` 抽取该小节作为 Release 说明；找不到时回退到
GitHub 自动生成的 notes。`未发布` 小节不会被自动抽取，发版时应把它改为正式版本小节
或新建正式小节。

## 本地构建

```bash
# Debug
./gradlew :app:assembleDebug

# Release（未配置签名时回退 debug 签名，仅用于本地验证）
./gradlew :app:assembleRelease
```

产物：

```text
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/apk/release/app-universal-release.apk
app/build/outputs/apk/release/app-arm64-v8a-release.apk
app/build/outputs/apk/release/app-armeabi-v7a-release.apk
app/build/outputs/apk/release/app-x86_64-release.apk
```

`app/build.gradle.kts` 启用 ABI 拆分并保留 universal APK，`abiFilters` 与
`splits.abi.include` 保持一致，避免把 AAR 自带的 32 位 x86 库打进包。

## 签名

本地正式签名：复制 `keystore.properties.example` 为 `keystore.properties` 并填写：

```properties
storeFile=/absolute/path/to/release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

文件存在时 Release 使用该签名；不存在时使用 debug 签名并打印告警。
**debug 签名产物不能用于正式分发**，尤其是自动更新：不同机器的 debug 证书不同，
旧版本无法覆盖安装。

CI 发布不会回退：`release.yml` 在缺少 `KEYSTORE_BASE64` 时直接失败。

## CI

### `android.yml`（push / PR）

1. JDK 17 + Android SDK platform-tools + `platforms;android-37`
2. NDK `27.1.12297006`、CMake `3.22.1`
3. `python3 scripts/check_versions.py`
4. `./gradlew testDebugUnitTest`
5. `./gradlew lintDebug`
6. `./gradlew assembleDebug`
7. 上传 `lint-results-*.html`

`lint.abortOnError = false`，lint 不会阻断构建；新增告警仍应尽量修复。

### `release.yml`（tag 或手动）

触发：

- push tag `v*`；
- 或手动 `workflow_dispatch` 输入 tag。

步骤：

1. 安装 JDK / SDK / NDK / CMake；
2. 从仓库 secrets 还原 keystore 并写 `keystore.properties`：
   `KEYSTORE_BASE64`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`；
3. `./gradlew :app:assembleRelease`；
4. 把 `app-*` 产物重命名为稳定资源名：

   | 产物 | Release 资源名 |
   |---|---|
   | universal | `izukijs-universal-release.apk` |
   | arm64-v8a | `izukijs-arm64-v8a-release.apk` |
   | armeabi-v7a | `izukijs-armeabi-v7a-release.apk` |
   | x86_64 | `izukijs-x86_64-release.apk` |

   同时复制一份 `app-<abi>-release.apk` 别名，兼容 1.2.0 及更早客户端的应用内更新；
5. 用 `apksigner verify --print-certs` 校验证书 SHA-256 指纹，必须匹配
   `95392aa229296dbe946c52328450e22b73fc05b417e068dbf6ba2193a7771ea5`；
6. 从 `CHANGELOG.md` 抽取对应版本小节，生成 Release body，创建或更新 Release 并上传 APK。

如果轮换签名证书，必须同步更新 `release.yml` 中的 `EXPECTED` 指纹，并评估老用户
覆盖安装 / 自动更新的影响。

## 依赖与构建环境注意事项

- **JitPack**：QuickJS（`com.github.hjhrq1991:quickjs-android`）与 libadb
  （`com.github.MuntashirAkon:libadb-android`）从 JitPack 拉取；仓库与 group 白名单见
  `settings.gradle.kts`。JitPack 偶发超时，可重试，不要把依赖改回未修复的旧版本。
- **Shizuku**：`dev.rikka.shizuku:api/provider` 固定在 `13.1.0`。Dependabot 已忽略
  `>= 13.1.1`，因为 `Shizuku.newProcess` 变为 private，需要先迁移到 UserService 方案。
- **NDK / CMake**：仅 `app/src/main/cpp` 使用；升级 NDK 时验证 QuickJS 中断垫片的
  ABI 与 16 KB 页兼容参数 `ANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON`。
- **OpenCV / ONNX Runtime / MLKit**：体积较大；`abiFilters` 与 `splits` 必须一致。
- **compileSdk 37**：CI 中 `sdkmanager --install "platforms;android-37"` 允许失败，
  由 AGP 兜底；本地缺少平台时先安装。

## 发布流程

1. 确认 `main` 上的改动已通过 CI；`CHANGELOG.md` 的「未发布」内容完整。
2. 修改 `gradle.properties` 的 `versionName` / `versionCode`，并整理 CHANGELOG。
3. `python3 scripts/check_versions.py`、`python3 scripts/check_docs.py`、
   `./gradlew testDebugUnitTest` 通过。
4. 提交并发版提交（仓库习惯：`chore(release): 发布 x.y.z`）。
5. 打 tag 并推送：

   ```bash
   git tag v1.2.3
   git push origin v1.2.3
   ```

6. 等待 `Release` workflow 完成；检查：
   - 4 个 APK 均存在；
   - 签名指纹校验通过；
   - Release 说明包含正确的 CHANGELOG 小节；
   - `latest/download/izukijs-universal-release.apk` 链接可下载。
7. 在真机上抽测安装、覆盖升级、应用内更新检查。

## 故障排查

| 现象 | 原因 / 处理 |
|---|---|
| Release workflow 报缺少 `KEYSTORE_BASE64` | 仓库 secrets 未配置；不允许 debug 签名发布 |
| 签名指纹不匹配 | keystore 不是发布证书，或轮换后未更新 `EXPECTED` |
| 产物名无法识别 | `splits.abi` 或 AGP 输出名变化；更新 `Stage release assets` 的 case 分支 |
| Release 说明为空 | CHANGELOG 缺少 `## [版本]` 小节，或 tag 与版本号不一致 |
| JitPack 依赖失败 | 重试；确认 `settings.gradle.kts` 的 JitPack 白名单包含该 group |
| NDK / CMake 缺失 | 安装 `ndk;27.1.12297006` 与 `cmake;3.22.1` |
| `compileSdk 37` 找不到 | 安装 `platforms;android-37`，或让 AGP 使用已安装的更高平台 |
| 老客户端收不到更新 | 确认 `app-<abi>-release.apk` 别名仍在上传；该兼容逻辑计划在老版本退场后移除 |

## 安全

- 不要提交 `keystore.properties`、`*.jks`、`*.keystore`、`local.properties`；
- 不要把密钥写进 workflow、日志或 Release 说明；
- 导出配置备份包含明文密钥，发版说明与测试截图中不要展示；
- 发布 APK 前确认没有把调试开关、测试账号或私有地址打进包里。
