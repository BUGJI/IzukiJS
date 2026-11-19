# Auto.js 兼容性诊断 CLI

用于把 AutoX / AutoJs6 的经典 v6 脚本迁移到 Izuki JS 前做静态检查。
只分析源码，不执行脚本，也不做全量代码转译。

## 用法

```bash
# 生成 Markdown / JSON 报告
node tools/autojs-compat/bin/autojs-compat.mjs analyze path/to/script.js
node tools/autojs-compat/bin/autojs-compat.mjs analyze path/to/project --json --out report.json

# CI：存在阻断项时以非 0 退出
node tools/autojs-compat/bin/autojs-compat.mjs lint path/to/project

# 目录导入为 Izuki 项目（复制、补 // @compat autojs6、写 izuki.project.json）
node tools/autojs-compat/bin/autojs-compat.mjs import path/to/project --out out/project
```

无第三方依赖，Node.js 18+ 即可运行。

## 报告分级

| 级别 | 含义 | 例子 |
|---|---|---|
| 阻断 | 当前架构无法直接兼容 | `"ui";`、E4X、`Packages`/`importClass`、`threads`/`engines` |
| 提示 | 需要兼容层或人工改写 | `text(...).findOne()`、`setTimeout`、`device.width` 属性、`storages` |

阻断项不会被自动改写；请以报告为准做人工移植，或等待运行时兼容层覆盖。

## corpus

`corpus/` 用于放可公开的样例脚本。真实业务脚本请放在 `corpus/private/`（已在
`.gitignore` 中忽略），避免把账号、包名或业务逻辑提交到仓库。

建议把脚本按类型命名：

```text
corpus/private/
  coordinate/
  selector/
  timers-events/
  mult-file/
  ui-mode/
  java-interop/
```

每次兼容层升级后，用 `lint` 与真机运行结果更新通过 / 失败清单。

## 与运行时兼容层的关系

- CLI 负责：项目导入、require 图、阻断项清单、CI 回归。
- 运行时兼容层负责：选择器对象、定时器、`Image`/`File` 形状、重载与全局函数。
- 文档见 [docs/LEGACY_COMPAT.md](../../docs/LEGACY_COMPAT.md)。
