// Izuki JS 完整自检脚本
// 检测已开启的权限 / 能力 -> 回到桌面 -> 按能力执行截图、OCR、OpenCV、控件树等测试
// -> 计量耗时 -> 输出结构化日志报告（只写应用日志，不生成报告文件）
//
// @need app, shell, images, ocr, selector, clipboard, http, notify, virtualscreen, fs
// @env ROUNDS=3            截图 / 识别重复次数（1-5）
// @env RESTORE=1           测试后恢复原前台应用（1/0）
// @env TEST_HTTP_URL=none   可选：HTTP 连通性测试地址（none 表示不发起网络请求）

var TAG = "[selftest]";
var startedAt = Date.now();
var results = [];
var screenshotPaths = [];
var lastScreenshot = null;
var startPackage = null;
var basicOps = null;
var capabilities = {};
var canControl = false;
var canScreenshot = false;
var runMode = "unknown";

var rounds = clampInt(env.get("ROUNDS", "3"), 1, 5);
var restoreAfterTest = env.get("RESTORE", "1") !== "0";
var testHttpUrl = env.get("TEST_HTTP_URL", "none");
if (!testHttpUrl || testHttpUrl === "none" || !/^https?:\/\//i.test(testHttpUrl)) {
  testHttpUrl = "";
}

// ==================== 基础工具 ====================

function pad2(value) {
  return value < 10 ? "0" + value : String(value);
}

function nowText() {
  var d = new Date();
  return d.getFullYear() + "-" + pad2(d.getMonth() + 1) + "-" + pad2(d.getDate()) +
    " " + pad2(d.getHours()) + ":" + pad2(d.getMinutes()) + ":" + pad2(d.getSeconds());
}

function clampInt(raw, min, max) {
  var value = parseInt(raw, 10);
  if (isNaN(value)) value = min;
  if (value < min) value = min;
  if (value > max) value = max;
  return value;
}

function safe(fn, fallback) {
  try {
    return fn();
  } catch (e) {
    return fallback;
  }
}

function safeBool(fn) {
  return safe(fn, false) === true;
}

function errorText(e) {
  return e && e.message ? e.message : String(e);
}

function brief(value, max) {
  if (value === null || value === undefined) return String(value);
  var text = String(value).replace(/\s+/g, " ");
  return text.length > max ? text.substring(0, max) + "..." : text;
}

function parseArray(raw) {
  if (Array.isArray(raw)) return raw;
  if (typeof raw === "string") {
    try {
      var value = JSON.parse(raw);
      return Array.isArray(value) ? value : null;
    } catch (e) {
      return null;
    }
  }
  return null;
}

function dirname(path) {
  var slash = Math.max(path.lastIndexOf("/"), path.lastIndexOf("\\"));
  return slash > 0 ? path.substring(0, slash) : path;
}

function timeStats(times) {
  var min = times[0];
  var max = times[0];
  var total = 0;
  for (var i = 0; i < times.length; i++) {
    if (times[i] < min) min = times[i];
    if (times[i] > max) max = times[i];
    total += times[i];
  }
  return {
    min: min,
    max: max,
    avg: Math.round(total / times.length),
    total: total
  };
}

// ==================== 结果记录 ====================

function logLine(level, message) {
  var text = TAG + " " + message;
  if (level === "ERROR") {
    error(text);
  } else if (level === "WARN" || level === "FAIL") {
    warn(text);
  } else {
    log(text);
  }
}

function record(name, status, durationMs, detail) {
  var safeDetail = brief(detail || "", 600);
  results.push({
    name: name,
    status: status,
    ms: durationMs || 0,
    detail: safeDetail
  });
  var line = "[" + status + "] " + name + " (" + (durationMs || 0) + "ms)";
  if (safeDetail) line += " - " + safeDetail;
  logLine(status === "FAIL" ? "FAIL" : (status === "WARN" ? "WARN" : "INFO"), line);
}

function skip(name, reason) {
  record(name, "SKIP", 0, reason);
}

function run(name, fn) {
  var started = Date.now();
  var out;
  try {
    out = fn();
  } catch (e) {
    out = { status: "FAIL", detail: "异常: " + errorText(e) };
  }
  var duration = Date.now() - started;
  if (!out) out = { status: "PASS", detail: "" };
  if (typeof out === "string") out = { status: "PASS", detail: out };
  record(name, out.status || "PASS", duration, out.detail || "");
  return out;
}

function bench(name, count, fn) {
  var times = [];
  var successes = 0;
  var failures = 0;
  var details = [];
  var last = null;

  for (var i = 0; i < count; i++) {
    var started = Date.now();
    var value = null;
    try {
      value = fn(i);
    } catch (e) {
      details.push("第" + (i + 1) + "轮异常: " + errorText(e));
    }
    var elapsed = Date.now() - started;
    times.push(elapsed);
    if (value !== null && value !== undefined && value !== false) {
      successes++;
      last = value;
    } else {
      failures++;
    }
  }

  var stats = timeStats(times);
  var status = successes === count ? "PASS" : (successes > 0 ? "WARN" : "FAIL");
  var detail = "轮次=" + count + " 成功=" + successes + " 失败=" + failures +
    " min/avg/max=" + stats.min + "/" + stats.avg + "/" + stats.max + "ms";
  if (details.length > 0) detail += " " + details.join("; ");
  record(name, status, stats.total, detail);
  return {
    status: status,
    success: successes,
    failure: failures,
    times: times,
    last: last
  };
}

// ==================== 权限与能力 ====================

function buildCapabilities() {
  var defs = [
    ["accessibility", "无障碍", function () { return permissions.accessibility(); }],
    ["shizuku", "Shizuku", function () { return permissions.shizuku(); }],
    ["adb", "无线调试", function () { return permissions.adb(); }],
    ["root", "Root", function () { return permissions.root(); }],
    ["hid", "蓝牙 HID", function () { return permissions.hid(); }],
    ["overlay", "悬浮窗", function () { return permissions.overlay(); }],
    ["screenCapture", "持续录屏", function () { return permissions.screenCapture(); }],
    ["notifications", "通知权限", function () { return permissions.notifications(); }],
    ["bluetooth", "蓝牙权限", function () { return permissions.bluetooth(); }],
    ["usageAccess", "使用情况访问", function () { return permissions.usageAccess(); }],
    ["notificationListener", "通知使用权", function () { return permissions.notificationListener(); }],
    ["manageStorage", "全部文件访问", function () { return permissions.manageStorage(); }],
    ["appList", "应用列表", function () { return permissions.appList(); }],
    ["http", "脚本网络", function () { return permissions.http(); }],
    ["virtualScreen", "虚拟屏", function () { return permissions.virtualScreen(); }]
  ];

  logLine("INFO", "--- 权限与能力 ---");
  for (var i = 0; i < defs.length; i++) {
    var key = defs[i][0];
    var label = defs[i][1];
    var value = safeBool(defs[i][2]);
    capabilities[key] = value;
    logLine("INFO", "能力 " + label + " (" + key + ")=" + (value ? "是" : "否"));
  }

  canControl = safeBool(function () { return permissions.canControl(); });
  canScreenshot = safeBool(function () { return permissions.canScreenshot(); });
  runMode = safe(function () { return permissions.runMode(); }, "unknown");

  logLine("INFO", "能力 canControl=" + (canControl ? "是" : "否") +
    " canScreenshot=" + (canScreenshot ? "是" : "否") +
    " runMode=" + runMode);
  logLine("INFO", "已授予: " + safe(function () { return permissions.granted(); }, ""));
  logLine("INFO", "全部能力: " + brief(safe(function () { return permissions.all(); }, ""), 500));
}

// ==================== 各能力测试 ====================

function testDevice() {
  return {
    status: "PASS",
    detail: "brand=" + brief(device.brand(), 40) +
      " model=" + brief(device.model(), 40) +
      " android=" + device.androidVersion() +
      " battery=" + device.batteryLevel() + "%" +
      " screen=" + device.screenWidth() + "x" + device.screenHeight() +
      " target=" + device.width() + "x" + device.height()
  };
}

function testPermissionSnapshot() {
  var total = 0;
  var granted = 0;
  for (var key in capabilities) {
    if (Object.prototype.hasOwnProperty.call(capabilities, key)) {
      total++;
      if (capabilities[key]) granted++;
    }
  }
  return {
    status: "PASS",
    detail: "已授予=" + granted + "/" + total +
      " canControl=" + (canControl ? "是" : "否") +
      " canScreenshot=" + (canScreenshot ? "是" : "否") +
      " runMode=" + runMode
  };
}

function testState() {
  var key = "__izuki_selftest_probe_" + startedAt;
  var token = "ok_" + startedAt;
  state.set(key, token);
  var got = state.get(key);
  state.remove(key);
  return {
    status: got === token ? "PASS" : "FAIL",
    detail: "key=" + key + " 读回=" + (got === token ? "一致" : "不一致")
  };
}

function testBasicOps() {
  try {
    basicOps = require("basic_ops");
  } catch (e) {
    return { status: "WARN", detail: "加载失败: " + errorText(e) };
  }
  if (!basicOps || typeof basicOps.backToHome !== "function") {
    return { status: "WARN", detail: "模块已加载，但缺少 backToHome" };
  }
  return { status: "PASS", detail: "模块已加载，可用 backToHome" };
}

function testCurrentPackage() {
  var pkg = app.currentPackage();
  startPackage = pkg;
  return {
    status: pkg ? "PASS" : "INFO",
    detail: "package=" + (pkg || "null")
  };
}

function testAppList() {
  var list = parseArray(app.list());
  if (!list) return { status: "FAIL", detail: "返回值不是数组" };
  return { status: "PASS", detail: "应用数量=" + list.length };
}

function testUsageStats() {
  var stats = parseArray(app.usageStats(Date.now() - 60 * 60 * 1000, 5));
  if (!stats) return { status: "FAIL", detail: "返回值不是数组" };
  return { status: "PASS", detail: "最近使用=" + stats.length };
}

function testForeground() {
  var foreground = app.foreground();
  if (!foreground) {
    return {
      status: "WARN",
      detail: "usageStats 可用，但 foreground 返回 null（最近没有前台事件）"
    };
  }
  return { status: "PASS", detail: "foreground=" + foreground };
}

function testClipboard() {
  var text = clipboard.get();
  if (text === null || text === undefined) {
    return { status: "INFO", detail: "读取为空或受系统限制" };
  }
  return { status: "PASS", detail: "文本长度=" + String(text).length };
}

function testNotification() {
  var available = notify.available();
  var list = parseArray(notify.list());
  if (!list) return { status: "FAIL", detail: "返回值不是数组" };
  return {
    status: "PASS",
    detail: "available=" + available + " 通知数量=" + list.length
  };
}

function testShell() {
  if (!shell.available()) {
    return { status: "SKIP", detail: "Shell 后端未就绪" };
  }
  var token = "izuki_selftest_" + startedAt;
  var output = shell.exec("echo " + token);
  var ok = output !== null && output !== undefined && String(output).indexOf(token) >= 0;
  return {
    status: ok ? "PASS" : "FAIL",
    detail: "echo 输出=" + brief(output, 100)
  };
}

function testHttp() {
  if (!http.available()) {
    return { status: "SKIP", detail: "脚本网络未启用" };
  }
  if (!testHttpUrl) {
    return { status: "INFO", detail: "未配置 TEST_HTTP_URL，跳过实际请求" };
  }
  var result = http.get(testHttpUrl, { timeoutMs: 5000 });
  if (!result) {
    return { status: "WARN", detail: "请求无返回" };
  }
  return {
    status: result.ok ? "PASS" : "WARN",
    detail: "status=" + result.status +
      " durationMs=" + result.durationMs +
      (result.error ? " error=" + brief(result.error, 100) : "")
  };
}

function goHome() {
  var actionStarted = Date.now();
  var method = "home()";
  var ok = false;
  var lastError = "";

  try {
    ok = home() === true;
  } catch (e) {
    lastError = errorText(e);
  }

  if (!ok) {
    method = "basic_ops.backToHome()";
    try {
      if (basicOps && typeof basicOps.backToHome === "function") {
        basicOps.backToHome();
        ok = true;
      }
    } catch (e) {
      lastError = errorText(e);
    }
  }

  if (!ok) {
    method = "swipe";
    try {
      var w = device.width();
      var h = device.height();
      ok = swipe(w / 2, h * 0.99, w / 2, h * 0.55, 100) === true;
    } catch (e) {
      lastError = errorText(e);
    }
  }

  var actionMs = Date.now() - actionStarted;
  sleep(800);

  var pkg = null;
  if (typeof app !== "undefined") {
    pkg = safe(function () { return app.currentPackage(); }, null);
  }

  return {
    status: ok ? "PASS" : "FAIL",
    detail: "方式=" + method +
      " 动作耗时=" + actionMs + "ms" +
      " 等待=800ms" +
      " 前台=" + (pkg || "null") +
      (lastError ? " 错误=" + lastError : "")
  };
}

function captureRound() {
  var path = captureScreen("");
  if (!path) return null;
  var size = -1;
  if (typeof fs !== "undefined") size = fs.size(path);
  if (size === 0) return null;
  screenshotPaths.push(path);
  lastScreenshot = path;
  return { path: path, size: size };
}

function testFs() {
  var candidates = [];
  if (capabilities.manageStorage) candidates.push("/sdcard/Download");
  candidates.push("/sdcard/Android/data/com.benton.izukijs/files");
  if (lastScreenshot) candidates.push(dirname(lastScreenshot));

  var lastError = "";
  var publicError = "";
  for (var i = 0; i < candidates.length; i++) {
    var dir = candidates[i];
    var isPublic = dir === "/sdcard/Download";
    if (!fs.mkdirs(dir)) {
      lastError = "mkdirs 失败: " + dir;
      if (isPublic) publicError = lastError;
      continue;
    }
    var path = dir + "/izuki_selftest_probe_" + startedAt + ".tmp";
    var token = "ok_" + startedAt;
    if (!fs.writeText(path, token, false)) {
      lastError = "写入失败: " + dir;
      if (isPublic) publicError = lastError;
      continue;
    }
    var back = fs.readText(path);
    var size = fs.size(path);
    fs.delete(path);
    if (back === token && size > 0) {
      return {
        status: publicError ? "WARN" : "PASS",
        detail: "目录=" + dir + " 字节=" + size +
          (publicError ? " 公共目录失败: " + publicError : "")
      };
    }
    lastError = "读回不一致: " + dir;
    if (isPublic) publicError = lastError;
  }

  return {
    status: "SKIP",
    detail: "没有可写目录" + (lastError ? "（" + lastError + "）" : "")
  };
}

function testImageColor() {
  var hit = images.recognizeIn(lastScreenshot, {
    type: "ColorMatch",
    method: 4,
    lower: [[0, 0, 0], [230, 230, 230]],
    upper: [[40, 40, 40], [255, 255, 255]],
    count: 50,
    orderBy: "Area"
  });
  if (!hit) return null;
  return {
    x: hit.x,
    y: hit.y,
    score: hit.score,
    count: hit.count
  };
}

function testImageCompare() {
  var compared = images.compare(lastScreenshot, lastScreenshot);
  if (!compared || compared.matched !== true) return null;
  return { score: compared.score, matched: compared.matched };
}

function testImageTemplate() {
  var w = device.width();
  var h = device.height();
  var tw = Math.min(120, Math.max(40, Math.floor(w / 6)));
  var th = Math.min(120, Math.max(40, Math.floor(h / 6)));
  var x = Math.max(0, Math.floor((w - tw) / 2));
  var y = Math.max(0, Math.floor((h - th) / 2));

  var template = images.saveTemplate("", x, y, tw, th);
  if (!template) {
    return { status: "SKIP", detail: "模板保存失败" };
  }

  try {
    var hit = images.recognizeIn(lastScreenshot, {
      type: "TemplateMatch",
      template: template,
      threshold: 0.5,
      orderBy: "Score"
    });
    return {
      status: hit ? "PASS" : "INFO",
      detail: "模板=" + template +
        (hit ? " 命中 score=" + hit.score : " 未命中（画面可能已变化）")
    };
  } finally {
    if (typeof fs !== "undefined") fs.delete(template);
  }
}

function testOcrText() {
  var text = ocr.recognizePath(lastScreenshot);
  if (typeof text !== "string") return null;
  return { length: text.length, text: text };
}

function testOcrBlocks() {
  var result = ocr.recognizeBlocks();
  if (!result || !Array.isArray(result.blocks) || !result.image) return null;
  return {
    blocks: result.blocks.length,
    width: result.image.w,
    height: result.image.h
  };
}

function testVisionOcr() {
  var hits = images.recognizeAllIn(lastScreenshot, { type: "OCR" });
  if (!Array.isArray(hits)) return null;
  return { count: hits.length };
}

function testSelector() {
  var count = selector.count({});
  var clickable = selector.count({ clickable: true });
  return {
    status: count > 0 ? "PASS" : "FAIL",
    detail: "节点=" + count + " 可点击=" + clickable
  };
}

function testVirtualScreen() {
  var available = virtualScreen.available();
  var active = virtualScreen.active();
  var allowed = virtualScreen.allowed();
  var size = virtualScreen.size();
  var info = active ? virtualScreen.info() : null;
  return {
    status: "INFO",
    detail: "available=" + available +
      " active=" + active +
      " allowed=" + (allowed && allowed.length ? allowed.join("/") : "-") +
      " size=" + (size ? size.width + "x" + size.height + "@" + size.dpi : "-") +
      (info ? " info=" + brief(JSON.stringify(info), 160) : "")
  };
}

function restoreApp() {
  if (typeof app === "undefined") {
    return { status: "SKIP", detail: "app 命名空间不可用" };
  }
  var current = safe(function () { return app.currentPackage(); }, null);
  if (current === startPackage) {
    return { status: "INFO", detail: "已在前台: " + startPackage };
  }
  var ok = app.launch(startPackage);
  sleep(500);
  return {
    status: ok ? "PASS" : "WARN",
    detail: "launch=" + startPackage + " 结果=" + ok
  };
}

// ==================== 报告 ====================

function logHeader() {
  logLine("INFO", "===== Izuki JS 完整自检 =====");
  logLine("INFO", "时间=" + nowText());
  logLine("INFO", "参数 ROUNDS=" + rounds +
    " RESTORE=" + (restoreAfterTest ? "1" : "0") +
    " TEST_HTTP_URL=" + (testHttpUrl ? "已配置" : "未配置"));
}

function logSuggestions() {
  var suggestions = [];

  if (!canControl) {
    suggestions.push("未检测到输入控制后端：请开启无障碍 / Shizuku / 无线调试 / Root / HID 之一");
  }
  if (!canScreenshot) {
    suggestions.push("未检测到截图来源：请开启无障碍（Android 11+）、Shizuku/无线调试/Root，或持续录屏");
  }
  if (!capabilities.accessibility) {
    suggestions.push("selector 控件树测试已跳过：需要无障碍服务");
  }
  if (!capabilities.notificationListener) {
    suggestions.push("通知读取未测试：可在权限与能力页开启通知使用权");
  }
  if (!capabilities.usageAccess) {
    suggestions.push("使用情况访问未测试：可开启后重跑");
  }
  if (!capabilities.manageStorage) {
    suggestions.push("全部文件访问未开启：fs 仅测试应用可写目录");
  }
  if (!capabilities.appList) {
    suggestions.push("应用列表未开启：app.list 测试已跳过");
  }

  if (suggestions.length === 0) {
    logLine("INFO", "建议: 当前能力覆盖较完整");
  } else {
    for (var i = 0; i < suggestions.length; i++) {
      logLine("INFO", "建议: " + suggestions[i]);
    }
  }
}

function summarize() {
  var counts = { PASS: 0, FAIL: 0, WARN: 0, SKIP: 0, INFO: 0 };
  for (var i = 0; i < results.length; i++) {
    var status = results[i].status;
    if (counts[status] === undefined) counts[status] = 0;
    counts[status]++;
  }

  logLine("INFO", "===== 汇总 =====");
  logLine("INFO", "PASS=" + counts.PASS +
    " FAIL=" + counts.FAIL +
    " WARN=" + counts.WARN +
    " SKIP=" + counts.SKIP +
    " INFO=" + counts.INFO);
  logLine("INFO", "总耗时=" + (Date.now() - startedAt) + "ms");

  if (screenshotPaths.length > 0) {
    logLine("INFO", "截图文件: " + screenshotPaths.join(" , "));
  }

  logSuggestions();

  if (counts.FAIL > 0) {
    logLine("FAIL", "自检存在失败项，请查看上面的 FAIL 日志");
  } else {
    logLine("INFO", "自检完成，无失败项");
  }

  toast("自检完成: PASS " + counts.PASS + " / FAIL " + counts.FAIL + " / SKIP " + counts.SKIP);
}

// ==================== 主流程 ====================

function main() {
  logHeader();
  buildCapabilities();

  run("权限与能力快照", testPermissionSnapshot);
  run("设备信息", testDevice);
  run("运行状态 state 读写", testState);
  run("模块 basic_ops", testBasicOps);

  if (typeof app === "undefined") {
    skip("前台应用 currentPackage", "app 命名空间不可用");
  } else {
    run("前台应用 currentPackage", testCurrentPackage);
  }

  if (!capabilities.appList) {
    skip("应用列表 app.list", "未开启应用列表");
  } else if (typeof app === "undefined") {
    skip("应用列表 app.list", "app 命名空间不可用");
  } else {
    run("应用列表 app.list", testAppList);
  }

  if (!capabilities.usageAccess) {
    skip("使用情况访问 app.usageStats", "未开启使用情况访问");
    skip("前台应用 app.foreground", "未开启使用情况访问");
  } else if (typeof app === "undefined") {
    skip("使用情况访问 app.usageStats", "app 命名空间不可用");
    skip("前台应用 app.foreground", "app 命名空间不可用");
  } else {
    run("使用情况访问 app.usageStats", testUsageStats);
    run("前台应用 app.foreground", testForeground);
  }

  if (typeof clipboard === "undefined") {
    skip("剪贴板读取 clipboard.get", "clipboard 命名空间不可用");
  } else {
    run("剪贴板读取 clipboard.get", testClipboard);
  }

  if (!capabilities.notificationListener) {
    skip("通知读取 notify.list", "未开启通知使用权");
  } else if (typeof notify === "undefined") {
    skip("通知读取 notify.list", "notify 命名空间不可用");
  } else {
    run("通知读取 notify.list", testNotification);
  }

  if (!(capabilities.shizuku || capabilities.adb || capabilities.root)) {
    skip("Shell echo", "Shizuku / 无线调试 / Root 均未就绪");
  } else if (typeof shell === "undefined") {
    skip("Shell echo", "shell 命名空间不可用");
  } else {
    run("Shell echo", testShell);
  }

  if (!capabilities.http) {
    skip("脚本网络 HTTP", "脚本网络未启用");
  } else if (typeof http === "undefined") {
    skip("脚本网络 HTTP", "http 命名空间不可用");
  } else {
    run("脚本网络 HTTP", testHttp);
  }

  var virtualActive = false;
  if (typeof app !== "undefined") {
    virtualActive = safeBool(function () { return app.virtualScreenActive(); });
  }

  if (!canControl) {
    skip("回桌面", "无输入控制后端");
  } else if (runMode === "background" || virtualActive) {
    skip("回桌面", "后台 / 虚拟屏模式不适用主屏桌面");
  } else {
    run("回桌面", goHome);
  }

  if (!canScreenshot) {
    skip("截图 captureScreen", "无截图来源");
  } else {
    var capture = bench("截图 captureScreen", rounds, captureRound);
    if (capture.last) {
      logLine("INFO", "最后一次截图: " + capture.last.path +
        " (" + capture.last.size + " bytes)");
    }
  }

  if (typeof fs === "undefined") {
    skip("文件读写 fs", "fs 命名空间不可用");
  } else {
    run("文件读写 fs", testFs);
  }

  var imagesReady = false;
  if (typeof images === "undefined") {
    skip("OpenCV images.available", "images 命名空间不可用");
  } else {
    var imageAvailability = run("OpenCV images.available", function () {
      var ok = images.available() === true;
      return {
        status: ok ? "PASS" : "FAIL",
        detail: ok ? "OpenCV 可用" : "OpenCV 不可用"
      };
    });
    imagesReady = imageAvailability.status === "PASS";
  }

  if (!imagesReady) {
    skip("视觉识别 ColorMatch", "OpenCV 不可用");
    skip("视觉对比 images.compare", "OpenCV 不可用");
    skip("视觉找图 TemplateMatch", "OpenCV 不可用");
  } else if (!lastScreenshot) {
    skip("视觉识别 ColorMatch", "无可用截图");
    skip("视觉对比 images.compare", "无可用截图");
    skip("视觉找图 TemplateMatch", "无可用截图");
  } else {
    var color = bench("视觉识别 ColorMatch", rounds, testImageColor);
    if (color.last) {
      logLine("INFO", "ColorMatch 最后结果: x=" + color.last.x +
        " y=" + color.last.y +
        " score=" + color.last.score +
        " count=" + color.last.count);
    }

    var compare = bench("视觉对比 images.compare", rounds, testImageCompare);
    if (compare.last) {
      logLine("INFO", "compare 最后结果: score=" + compare.last.score +
        " matched=" + compare.last.matched);
    }

    run("视觉找图 TemplateMatch", testImageTemplate);
  }

  if (typeof ocr === "undefined") {
    skip("OCR recognizePath", "ocr 命名空间不可用");
    skip("OCR recognizeBlocks", "ocr 命名空间不可用");
  } else {
    if (!lastScreenshot) {
      skip("OCR recognizePath", "无可用截图");
    } else {
      var ocrText = bench("OCR recognizePath", rounds, testOcrText);
      if (ocrText.last) {
        logLine("INFO", "OCR 文本长度=" + ocrText.last.length +
          " 预览=" + brief(ocrText.last.text, 100));
      }
    }

    if (!canScreenshot) {
      skip("OCR recognizeBlocks", "无截图来源");
    } else {
      var ocrBlocks = bench("OCR recognizeBlocks", rounds, testOcrBlocks);
      if (ocrBlocks.last) {
        logLine("INFO", "OCR 结构化: 块数=" + ocrBlocks.last.blocks +
          " 图像=" + ocrBlocks.last.width + "x" + ocrBlocks.last.height);
      }
    }
  }

  if (!imagesReady || !lastScreenshot) {
    skip("视觉 OCR recognizeAllIn", imagesReady ? "无可用截图" : "OpenCV 不可用");
  } else {
    var visionOcr = bench("视觉 OCR recognizeAllIn", rounds, testVisionOcr);
    if (visionOcr.last) {
      logLine("INFO", "视觉 OCR 行数=" + visionOcr.last.count);
    }
  }

  if (!capabilities.accessibility) {
    skip("控件树 selector.count", "未开启无障碍");
  } else if (runMode === "background" || virtualActive) {
    skip("控件树 selector.count", "后台 / 虚拟屏模式不支持控件树");
  } else if (typeof selector === "undefined") {
    skip("控件树 selector.count", "selector 命名空间不可用");
  } else {
    run("控件树 selector.count", testSelector);
  }

  if (!capabilities.virtualScreen) {
    skip("虚拟屏状态", "虚拟屏能力不可用");
  } else if (typeof virtualScreen === "undefined") {
    skip("虚拟屏状态", "virtualScreen 命名空间不可用");
  } else {
    run("虚拟屏状态", testVirtualScreen);
  }

  if (!restoreAfterTest) {
    skip("恢复原应用", "RESTORE=0");
  } else if (runMode === "background" || virtualActive) {
    skip("恢复原应用", "后台 / 虚拟屏模式不恢复主屏应用");
  } else if (!startPackage) {
    skip("恢复原应用", "未获取到初始前台应用");
  } else {
    run("恢复原应用", restoreApp);
  }
}

try {
  main();
} catch (e) {
  logLine("ERROR", "未捕获异常: " + errorText(e));
} finally {
  summarize();
}
