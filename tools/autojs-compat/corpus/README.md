# 兼容性 corpus

这里只放可公开的样例脚本；真实业务脚本请放到 `private/`（已被 `.gitignore` 忽略）。

建议每个脚本在文件头注明：

```js
// 来源: AutoX / AutoJs6 / Auto.js 4.x
// 依赖: selector, images, http, threads...
// 预期: 可直接运行 / 需要兼容层 / 不支持
```

新增脚本后至少运行：

```bash
node tools/autojs-compat/bin/autojs-compat.mjs analyze tools/autojs-compat/corpus
node tools/autojs-compat/bin/autojs-compat.mjs lint tools/autojs-compat/corpus
```

`sample-legacy.js` 是分析器的自检样例，不承诺在目标平台直接运行。
