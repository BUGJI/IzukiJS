import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { analyzeSource, detectSignals, readDirectives, maskNonCode } from "../src/analyzer.mjs";

const here = path.dirname(fileURLToPath(import.meta.url));

assert.deepEqual([...readDirectives("\"ui\";\n\"auto\";\nlog('x')")], ["ui", "auto"]);
assert.deepEqual([...readDirectives("'use strict';\nvar x = 1")], []);
assert.deepEqual([...readDirectives("\"ui auto\";\nlog('x')")], ["ui", "auto"]);

const masked = maskNonCode("// text('x')\nvar s = \"importClass(x)\";\nlog('ok')");
assert.equal(masked.includes("text("), false);
assert.equal(masked.includes("importClass"), false);

const signals = detectSignals("importClass(android.content.Intent);");
assert.ok(signals.some((signal) => signal.code === "java-interop"));

const clean = detectSignals("// text('x')\nvar s = \"importClass(x)\";\nlog('ok')");
assert.deepEqual(clean, []);

const deviceProperty = detectSignals("var w = device.width + 1;");
assert.ok(deviceProperty.some((signal) => signal.code === "legacy-device-property"));
const deviceCall = detectSignals("var w = device.width();");
assert.equal(deviceCall.some((signal) => signal.code === "legacy-device-property"), false);

const ui = detectSignals("\"ui\";\nui.layout(<vertical><button text=\"ok\"/></vertical>);");
assert.ok(ui.some((signal) => signal.code === "ui-mode"));
assert.ok(ui.some((signal) => signal.code === "legacy-ui"));
assert.ok(ui.some((signal) => signal.code === "legacy-e4x"));

const lessThan = detectSignals("for (var i = 0; i < list.length; i++) { log(i); }");
assert.equal(lessThan.some((signal) => signal.code === "legacy-e4x"), false);

const currentSelector = detectSignals("selector.waitFor({ text: '登录' }, 5000);");
assert.equal(currentSelector.some((signal) => signal.code === "legacy-selector-chain"), false);

const analysis = analyzeSource("// @compat autojs6\n\"ui\";\ntext('登录').findOne();", "a.js");
assert.equal(analysis.enabled, true);
assert.equal(analysis.flavor, "autojs6");
assert.ok(analysis.issues.some((issue) => issue.code === "unsupported-directive"));
assert.ok(analysis.blockers.some((signal) => signal.code === "ui-mode"));

const relative = analyzeSource("var utils = require('./utils');", "b.js");
assert.ok(relative.requires.some((item) => item.name === "./utils" && item.relative));
assert.ok(relative.signals.some((signal) => signal.code === "legacy-require-relative"));

// 与 Android 侧共享同一份 corpus，避免两套规则漂移。
const corpus = JSON.parse(
  fs.readFileSync(path.join(here, "../corpus/signals.json"), "utf8"),
);
for (const item of corpus) {
  const codes = detectSignals(item.source).map((signal) => signal.code);
  if (item.contains.length === 0) {
    assert.deepEqual(codes, [], `${item.name} 不应识别出信号`);
  } else {
    for (const code of item.contains) {
      assert.ok(codes.includes(code), `${item.name} 缺少信号: ${code}`);
    }
  }
}

console.log("autojs-compat analyzer tests passed");
