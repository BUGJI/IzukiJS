import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import vm from "node:vm";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const asset = path.resolve(here, "../../../app/src/main/assets/runtime/compat/autojs6-core.js");
const source = fs.readFileSync(asset, "utf8");

const calls = [];
const files = new Map([["/a.txt", "line1\nline2\n"], ["/img.png", ""]]);
const pendingEvents = [];
const pendingSensorEvents = [];

const sandbox = {
  console: { log() {}, warn() {} },
  log(message) { calls.push(["log", message]); },
  warn(message) { calls.push(["warn", message]); },
  toast(message) { calls.push(["toast", message]); },
  sleep(ms) {
    Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, Number(ms) || 0);
  },
  exit(error) { calls.push(["exit", error]); throw new Error("IZUKI_SCRIPT_EXIT"); },
  shouldExit() { return false; },
  device: {
    width() { return 1080; },
    height() { return 1920; },
    screenWidth() { return 1080; },
    screenHeight() { return 1920; },
    rotation() { return 0; },
    model() { return "Pixel"; },
    brand() { return "Google"; },
    androidVersion() { return 34; },
    batteryLevel() { return 80; },
    vibrate(ms) { calls.push(["device.vibrate", ms]); return true; },
    cancelVibration() { calls.push(["device.cancelVibration"]); return true; },
    getMusicVolume() { return 5; },
    setMusicVolume(v) { calls.push(["device.setMusicVolume", v]); return true; },
    getMusicMaxVolume() { return 15; },
    getNotificationVolume() { return 3; },
    setNotificationVolume(v) { calls.push(["device.setNotificationVolume", v]); return true; },
    getNotificationMaxVolume() { return 7; },
    getAlarmVolume() { return 2; },
    setAlarmVolume(v) { calls.push(["device.setAlarmVolume", v]); return true; },
    getAlarmMaxVolume() { return 7; },
    getBrightness() { return 128; },
    setBrightness(v) { calls.push(["device.setBrightness", v]); return true; },
    getBrightnessMode() { return 1; },
    setBrightnessMode(v) { calls.push(["device.setBrightnessMode", v]); return true; },
  },
  app: { currentPackage() { return "com.example"; } },
  events: {
    currentWindow() { return { className: "com.example.Main" }; },
    status() { return { seq: pendingEvents.length }; },
    list(options) {
      calls.push(["events.list", options]);
      const since = options && options.sinceSeq != null ? Number(options.sinceSeq) : 0;
      const types = options && options.types ? options.types : null;
      return pendingEvents
        .filter((event) => event.seq > since && (!types || types.includes(event.type)))
        .slice()
        .reverse();
    },
  },
  clipboard: {
    value: "",
    set(text) { this.value = text; return true; },
    get() { return this.value; },
  },
  permissions: {
    accessibility() { return true; },
    screenCapture() { return true; },
  },
  selector: {
    findAll(criteria) {
      calls.push(["selector.findAll", criteria]);
      const element = criteria && (criteria.editable === true)
        ? {
            id: "com.example:id/input",
            text: "abc",
            desc: null,
            className: "android.widget.EditText",
            package: "com.example",
            left: 10, top: 100, right: 500, bottom: 160,
            clickable: true, longClickable: true, editable: true, scrollable: false,
            enabled: true, visible: true, checkable: false, checked: false,
            focusable: true, focused: true, selected: false, password: false, index: 0,
          }
        : {
            id: "com.example:id/login",
            text: "登录",
            desc: "登录按钮",
            className: "android.widget.Button",
            package: "com.example",
            left: 20, top: 200, right: 220, bottom: 260,
            clickable: true, longClickable: true, editable: false, scrollable: false,
            enabled: true, visible: true, checkable: false, checked: false,
            focusable: true, focused: false, selected: false, password: false, index: 0,
          };
      return [element];
    },
    click(criteria, index) { calls.push(["selector.click", criteria, index]); return true; },
    longClick(criteria, index) { calls.push(["selector.longClick", criteria, index]); return true; },
    setTextById(id, text) { calls.push(["selector.setTextById", id, text]); return true; },
    explain(criteria) {
      calls.push(["selector.explain", criteria]);
      return { ok: true, totalNodes: 2, filters: [] };
    },
    scrollForward(criteria, index) { calls.push(["selector.scrollForward", criteria, index]); return true; },
    scrollBackward(criteria, index) { calls.push(["selector.scrollBackward", criteria, index]); return true; },
    dumpRaw(optionsJson) {
      calls.push(["selector.dumpRaw", optionsJson]);
      return JSON.stringify({
        ok: true,
        total: 2,
        truncated: false,
        tree: {
          className: "android.widget.FrameLayout",
          id: null,
          idHex: null,
          text: null,
          desc: null,
          bounds: { left: 0, top: 0, right: 1080, bottom: 1920 },
          packageName: "com.example",
          indexInParent: 0,
          depth: 0,
          childCount: 1,
          clickable: false,
          longClickable: false,
          scrollable: false,
          checkable: false,
          checked: false,
          enabled: true,
          focusable: false,
          focused: false,
          selected: false,
          editable: false,
          visibleToUser: true,
          password: false,
          actions: [],
          children: [
            {
              className: "android.widget.Button",
              id: "com.example:id/login",
              idHex: null,
              text: "登录",
              desc: null,
              bounds: { left: 20, top: 200, right: 220, bottom: 260 },
              packageName: "com.example",
              indexInParent: 0,
              depth: 1,
              childCount: 0,
              clickable: true,
              longClickable: true,
              scrollable: false,
              checkable: false,
              checked: false,
              enabled: true,
              focusable: true,
              focused: false,
              selected: false,
              editable: false,
              visibleToUser: true,
              password: false,
              actions: ["click", "long_click"],
              children: [],
            },
          ],
        },
      });
    },
    windowsRaw() {
      calls.push(["selector.windowsRaw"]);
      return JSON.stringify([
        {
          type: 1,
          active: true,
          focused: true,
          accessibilityFocused: false,
          layer: 0,
          id: 7,
          packageName: "com.example",
          bounds: [0, 0, 1080, 1920],
        },
      ]);
    },
  },
  captureScreen(path) {
    calls.push(["captureScreen", path]);
    return "/capture.png";
  },
  images: {
    sizeRaw() { return '{"width":100,"height":50}'; },
    pixelRaw() { return "#FF112233"; },
    saveRaw(source, target, quality) {
      calls.push(["images.saveRaw", source, target, quality]);
      return true;
    },
    clipToCacheRaw() { return "/cache/clip.png"; },
    resizeToCacheRaw() { return "/cache/resize.png"; },
    findImageInRaw(screen, template, threshold) {
      calls.push(["images.findImageInRaw", screen, template, threshold]);
      return "10,20,0.95";
    },
    findColorInRaw(screen, color, threshold) {
      calls.push(["images.findColorInRaw", screen, color, threshold]);
      return { x: 30, y: 40 };
    },
    findImage(a, b, c) {
      calls.push(["images.findImage.native", a, b, c]);
      return { x: 1, y: 2 };
    },
    findColor(a, b, c) {
      calls.push(["images.findColor.native", a, b, c]);
      return { x: 3, y: 4 };
    },
  },
  click(x, y) { calls.push(["click", x, y]); return true; },
  longClick(x, y, duration) { calls.push(["longClick", x, y, duration]); return true; },
  press(keyCode) { calls.push(["press", keyCode]); return true; },
  gesture(strokes) { calls.push(["gesture", strokes]); return true; },
  swipe(x1, y1, x2, y2, duration) {
    calls.push(["swipe", x1, y1, x2, y2, duration]);
    return true;
  },
  input: (() => {
    const fn = function (text) { calls.push(["input", text]); return true; };
    fn.notifications = function () { calls.push(["notifications"]); return true; };
    fn.quickSettings = function () { return true; };
    fn.powerDialog = function () { return true; };
    fn.splitScreen = function () { return true; };
    fn.lockScreen = function () { return true; };
    fn.dismissNotifications = function () { return true; };
    return fn;
  })(),
  fs: {
    readText(path) { return files.has(path) ? files.get(path) : null; },
    writeText(path, text, append) {
      files.set(path, append ? (files.get(path) || "") + text : text);
      return true;
    },
    exists(path) { return files.has(path); },
    isDirectory() { return false; },
    mkdirs() { return true; },
    delete(path) { return files.delete(path); },
    copy() { return true; },
    move() { return true; },
    list() { return []; },
  },
  storages: (() => {
    const stores = new Map();
    function store(name) {
      if (!stores.has(name)) stores.set(name, new Map());
      return stores.get(name);
    }
    return {
      getRaw(name, key) {
        const value = store(name).get(key);
        return value === undefined ? null : value;
      },
      putRaw(name, key, value) { store(name).set(key, value); return true; },
      removeRaw(name, key) { return store(name).delete(key); },
      containsRaw(name, key) { return store(name).has(key); },
      clearRaw(name) { store(name).clear(); return true; },
      keysRaw(name) { return JSON.stringify([...store(name).keys()].sort()); },
    };
  })(),
  dialogs: {
    showRaw(type, options, timeout) {
      calls.push(["dialogs.showRaw", type, options, timeout]);
      if (type === "confirm") return '{"ok":true,"action":"positive"}';
      if (type === "prompt") {
        const parsed = typeof options === "string" ? JSON.parse(options || "{}") : (options || {});
        return JSON.stringify({
          ok: true,
          action: "positive",
          text: parsed.defaultValue === "1+2" ? "1+2" : "typed",
        });
      }
      if (type === "select") return '{"ok":true,"action":"positive","index":1}';
      if (type === "multiChoice") return '{"ok":true,"action":"positive","indices":[0,2]}';
      return '{"ok":true,"action":"positive"}';
    },
  },
  http: {
    request(options) {
      return { ok: true, status: 200, headers: { "Content-Type": "application/json" }, body: '{"a":1}', url: options && options.url };
    },
    get(url, options) { return this.request({ url, method: "GET", ...(options || {}) }); },
    post(url, body, options) { return this.request({ url, method: "POST", body, ...(options || {}) }); },
    postJson(url, data, options) {
      return this.request({ url, method: "POST", body: JSON.stringify(data), ...(options || {}) });
    },
  },
  sensors: {
    registerRaw(name, delay) {
      calls.push(["sensors.registerRaw", name, delay]);
      if (name === "missing") {
        return JSON.stringify({ ok: false, error: "不支持的传感器: " + name });
      }
      return JSON.stringify({ ok: true, id: "s1", name: "Accelerometer", type: 1 });
    },
    pollRaw() { return JSON.stringify(pendingSensorEvents.splice(0)); },
    unregisterRaw(id) { calls.push(["sensors.unregisterRaw", id]); return true; },
    unregisterAllRaw() { calls.push(["sensors.unregisterAllRaw"]); return true; },
    listRaw() { return "[]"; },
  },
  require(name) { throw new Error("MODULE_NOT_FOUND:" + name); },
};

sandbox.globalThis = sandbox;
vm.runInNewContext(source, sandbox, { filename: "autojs6-core.js" });

// device：属性与调用两种写法
assert.equal(Number(sandbox.device.width), 1080);
assert.equal(Number(sandbox.device.width()), 1080);
assert.equal(sandbox.device.width + 1, 1081);
assert.equal(String(sandbox.device.model), "Pixel");
assert.equal(Number(sandbox.device.sdkInt), 34);
assert.equal(sandbox.device.getMusicVolume(), 5);
assert.equal(sandbox.device.getMusicMaxVolume(), 15);
assert.equal(sandbox.device.setMusicVolume(9), true);
assert.equal(calls.filter((call) => call[0] === "device.setMusicVolume").pop()[1], 9);
assert.equal(sandbox.device.getBrightness(), 128);
assert.equal(sandbox.device.setBrightness(200), true);
assert.equal(calls.filter((call) => call[0] === "device.setBrightness").pop()[1], 200);
assert.equal(sandbox.device.vibrate(120), true);
assert.equal(calls.filter((call) => call[0] === "device.vibrate").pop()[1], 120);
assert.equal(sandbox.device.setBrightnessMode(0), true);

// 全局工具
sandbox.toastLog("hello");
assert.ok(calls.some((call) => call[0] === "toast" && call[1] === "hello"));
assert.ok(calls.some((call) => call[0] === "log" && call[1] === "hello"));
assert.equal(sandbox.currentPackage(), "com.example");
assert.equal(sandbox.currentActivity(), "com.example.Main");
sandbox.setClip("clip");
assert.equal(sandbox.getClip(), "clip");
assert.equal(sandbox.isStopped(), false);
assert.equal(sandbox.requiresApi(30), true);

// 输入重载
sandbox.click("登录", 1);
const selectorClick = calls.find((call) => call[0] === "selector.click");
assert.equal(selectorClick[1].text, "登录");
assert.equal(selectorClick[2], 1);
sandbox.press(1, 2, 300);
assert.deepEqual(calls.find((call) => call[0] === "longClick"), ["longClick", 1, 2, 300]);
sandbox.press(4);
assert.deepEqual(calls.find((call) => call[0] === "press"), ["press", 4]);
sandbox.gesture(300, [0, 0], [30, 40]);
const gestureCall = calls.find((call) => call[0] === "gesture");
assert.equal(gestureCall[1][0][0].x, 0);
assert.equal(gestureCall[1][0][1].x, 30);
assert.equal(gestureCall[1][0][1].t, 300);
sandbox.scrollDown();
assert.ok(calls.some((call) => call[0] === "swipe"));

// 选择器链与 UiObject
const login = sandbox.text("登录").findOne(100);
assert.equal(login.text(), "登录");
assert.equal(login.clickable(), true);
assert.equal(login.bounds().width, 200);
assert.equal(login.click(), true);
assert.equal(sandbox.editable(true).findOne(100).text(), "abc");
assert.equal(sandbox.text("登录").find().size(), 1);
assert.equal(sandbox.text("登录").findAll().size(), 1);
sandbox.id("login").findOnce();
const idCall = calls.filter((call) => call[0] === "selector.findAll").pop();
assert.equal(idCall[1].id, "com.example:id/login");

// setText 替换、input 追加
assert.equal(sandbox.setText("xyz"), true);
assert.ok(calls.some((call) => call[0] === "selector.setTextById" && call[2] === "xyz"));
sandbox.input("!");
assert.ok(calls.some((call) => call[0] === "selector.setTextById" && call[2] === "abc!"));

// 定时器：sleep 期间协作执行
let fired = 0;
sandbox.setTimeout(function () { fired += 1; }, 5);
sandbox.sleep(30);
assert.equal(fired, 1);
const intervalId = sandbox.setInterval(function () { fired += 1; }, 10);
sandbox.sleep(35);
sandbox.clearInterval(intervalId);
assert.ok(fired >= 3);
assert.equal(sandbox.__izukiCompatHasPending(), false);

// events.on：轮询桥接
let keyCode = 0;
sandbox.events.on("key", function (code) { keyCode = code; });
pendingEvents.push({ seq: 1, type: "key_down", keyCode: 24, text: "VOLUME_DOWN", time: 1 });
sandbox.sleep(120);
assert.equal(keyCode, 24);
let toastCount = 0;
sandbox.events.once("toast", function () { toastCount += 1; });
pendingEvents.push({ seq: 2, type: "toast", text: "hi", time: 2 });
sandbox.sleep(120);
assert.equal(toastCount, 1);
pendingEvents.push({ seq: 3, type: "key_up", keyCode: 24, time: 3 });
sandbox.sleep(120);
assert.equal(keyCode, 24);
let volumeUp = 0;
let lastKeyEvent = null;
sandbox.events.onKeyDown("volume_up", function (event) {
  volumeUp += 1;
  lastKeyEvent = event;
});
pendingEvents.push({ seq: 10, type: "key_down", keyCode: 25, time: 10 });
sandbox.sleep(120);
assert.equal(volumeUp, 0);
pendingEvents.push({ seq: 11, type: "key_down", keyCode: 24, time: 11 });
sandbox.sleep(120);
assert.equal(volumeUp, 1);
assert.equal(lastKeyEvent.keyCode, 24);
sandbox.events.removeAllListeners();
assert.equal(sandbox.__izukiCompatHasPending(), false);

// sensors：注册 / 轮询 / 注销
assert.equal(sandbox.sensors.delay.ui, 2);
let sensorArgs = null;
const sensorEmitter = sandbox.sensors.register("accelerometer", 2);
assert.ok(sensorEmitter);
sensorEmitter.on("change", function (event, x, y, z) {
  sensorArgs = [event.values.join(","), x, y, z];
});
pendingSensorEvents.push({
  id: "s1",
  name: "Accelerometer",
  kind: "change",
  values: [1, 2, 3],
  accuracy: 3,
  timestamp: 10,
});
sandbox.sleep(120);
assert.equal(sensorArgs.join("|"), "1,2,3|1|2|3");
assert.equal(sensorEmitter.unregister(), true);
assert.equal(sandbox.sensors.register("missing"), null);
let unsupported = null;
sandbox.sensors.ignoresUnsupportedSensor = true;
sandbox.sensors.on("unsupported_sensor", function (name) { unsupported = name; });
const noopEmitter = sandbox.sensors.register("missing");
assert.equal(unsupported, "missing");
assert.equal(noopEmitter.unregister(), false);
sandbox.sensors.ignoresUnsupportedSensor = false;
assert.equal(sandbox.require("sensors"), sandbox.sensors);

// files
sandbox.files.write("/b.txt", "hello");assert.equal(files.get("/b.txt"), "hello");
assert.equal(sandbox.files.getName("/a/b.txt"), "b.txt");
assert.equal(sandbox.files.getExtension("/a/b.txt"), "txt");
assert.equal(sandbox.files.getSdcardPath(), "/sdcard");
assert.equal(sandbox.files.createWithDirs("/dir/new.txt"), true);
assert.equal(files.get("/dir/new.txt"), "");
assert.equal(sandbox.files.removeDir("/missing-dir"), false);
const handle = sandbox.files.open("/a.txt", "r");
assert.equal(handle.readline(), "line1");
assert.equal(handle.readline(), "line2");

// http 响应形状
const response = sandbox.http.get("/api");
assert.equal(response.statusCode, 200);
assert.equal(response.body.json().a, 1);
let callbackResponse = null;
sandbox.http.get("/api", (item) => { callbackResponse = item; });
assert.equal(callbackResponse.statusCode, 200);

// images：Image 对象、findImage / findColor、裁剪 / 缩放
const image = sandbox.images.read("/img.png");
assert.equal(image.width, 100);
assert.equal(image.height, 50);
assert.equal(image.getPath(), "/img.png");
assert.equal(sandbox.images.read("/missing.png"), null);
const captured = sandbox.images.captureScreen();
assert.equal(captured.__image, true);
assert.equal(captured.path, "/capture.png");
const found = sandbox.images.findImage(image, "/tpl.png", { threshold: 0.9 });
assert.equal(found.x, 10);
assert.equal(found.y, 20);
assert.equal(found.confidence, 0.95);
const foundColor = sandbox.images.findColor(image, "#ff0000", {});
assert.equal(foundColor.x, 30);
assert.equal(foundColor.y, 40);
assert.equal(sandbox.images.getSize(image).width, 100);
assert.equal(sandbox.images.pixel(image, 1, 2), 0xFF112233 | 0);
assert.equal(sandbox.colors.parseColor("#ff0000"), sandbox.colors.RED);
assert.equal(sandbox.colors.parseColor("#f00"), sandbox.colors.RED);
assert.equal(sandbox.colors.toString(sandbox.colors.RED), "#FFFF0000");
assert.equal(sandbox.colors.alpha(sandbox.colors.RED), 255);
assert.equal(sandbox.colors.red(sandbox.colors.RED), 255);
assert.equal(sandbox.colors.blue(sandbox.colors.RED), 0);
assert.equal(sandbox.colors.rgba("#005B4F91"), sandbox.colors.argb("#91005B4F"));
assert.equal(sandbox.colors.rgba(0x00, 0x5B, 0x4F, 0x91), sandbox.colors.argb(0x91, 0x00, 0x5B, 0x4F));
assert.equal(sandbox.colors.argb("#FF224466"), sandbox.colors.parseColor("#FF224466"));
assert.equal(sandbox.colors.toInt("#FF0000"), sandbox.colors.RED);
assert.equal(sandbox.colors.toHex(sandbox.colors.RED), "#FF0000");
assert.equal(sandbox.images.save(image, "/out.png"), true);
assert.equal(sandbox.images.clip(image, 1, 2, 3, 4).path, "/cache/clip.png");
assert.equal(sandbox.images.resize(image, 20, 10).path, "/cache/resize.png");
assert.equal(sandbox.images.findImage("/tpl.png", 0.8).x, 1);
assert.equal(sandbox.images.findColor("#ff0000", 4).x, 3);
assert.equal(sandbox.requestScreenCapture(), true);
let asyncGranted = null;
const asyncResult = sandbox.images.requestScreenCaptureAsync((granted) => { asyncGranted = granted; });
assert.equal(asyncGranted, true);
assert.equal(await asyncResult, true);

// storages
const store = sandbox.storages.create("demo");
assert.equal(store.get("missing", "fallback"), "fallback");
assert.equal(store.put("name", { value: 1 }), true);
assert.equal(store.contains("name"), true);
assert.equal(store.get("name").value, 1);
assert.equal(store.keys().length, 1);
assert.equal(store.keys()[0], "name");
assert.equal(store.remove("name"), true);
assert.equal(store.contains("name"), false);
store.put("a", 1);
assert.equal(store.clear(), true);
assert.equal(store.keys().length, 0);

// dialogs
assert.equal(sandbox.dialogs.alert("提示", "内容"), true);
assert.equal(sandbox.confirm("确认", "继续？"), true);
assert.equal(sandbox.prompt("输入", "名称", "默认"), "typed");
assert.equal(sandbox.select("选择", ["a", "b"]), 1);
assert.equal(sandbox.multiChoice("多选", ["a", "b", "c"], [0])[1], 2);
assert.equal(sandbox.dialogs.singleChoice("选择", ["a", "b"]), 1);
assert.equal(sandbox.dialogs.rawInput("输入", "默认"), "typed");
assert.equal(sandbox.dialogs.input("算式", "1+2"), 3);
let dialogCallback = null;
sandbox.dialogs.confirm("确认", "继续？", function (value) { dialogCallback = value; });
assert.equal(dialogCallback, true);

// auto 与 require
assert.equal(sandbox.auto(), true);
assert.equal(sandbox.auto.waitFor(), true);
assert.ok(sandbox.auto.service);
assert.equal(sandbox.auto.service.getWindows().length, 1);
assert.equal(sandbox.auto.windows.length, 1);
const windowInfo = sandbox.auto.windows[0];
assert.equal(windowInfo.getType(), 1);
assert.equal(windowInfo.isActive(), true);
assert.equal(windowInfo.getPackageName(), "com.example");
const windowBounds = windowInfo.getBounds();
assert.equal(windowBounds.left, 0);
assert.equal(windowBounds.right, 1080);
assert.equal(windowBounds.bottom, 1920);
assert.equal(windowInfo.getRoot(), null);

const dumpJson = JSON.parse(sandbox.auto.dump({ format: "json", maxDepth: -1, properties: ["text", "bounds"] }));
assert.equal(dumpJson.className, "android.widget.FrameLayout");
assert.equal(dumpJson.children[0].text, "登录");
const dumpOptions = JSON.parse(calls.filter((call) => call[0] === "selector.dumpRaw").pop()[1]);
assert.equal(dumpOptions.maxDepth, -1);
assert.equal(dumpOptions.properties.join(","), "text,bounds");

const dumpText = sandbox.auto.dump({ format: "text" });
assert.ok(dumpText.includes('text="登录"'));
assert.ok(dumpText.includes("bounds=[20,200][220,260]"));
const dumpXml = sandbox.auto.dump({ format: "xml" });
assert.ok(dumpXml.startsWith("<?xml"));
assert.ok(dumpXml.includes('text="登录"'));
assert.ok(dumpXml.includes('resource-id="com.example:id/login"'));
assert.throws(() => sandbox.auto.dump({ format: "yaml" }), /不支持的 auto.dump 格式/);

sandbox.permissions.accessibility = () => false;
assert.equal(sandbox.auto.service, null);
assert.equal(sandbox.auto.windows.length, 0);
assert.equal(sandbox.auto.isRunning(), false);
sandbox.permissions.accessibility = () => true;
assert.equal(sandbox.auto.isRunning(), true);
const explain = sandbox.auto.explain(sandbox.text("登录"));
assert.equal(explain.ok, true);
const explainCall = calls.filter((call) => call[0] === "selector.explain").pop();
assert.equal(explainCall[1].text, "登录");
assert.throws(() => sandbox.auto.enable(), /不会自动开启无障碍/);
assert.throws(() => sandbox.auto.disable(), /不会自动关闭无障碍/);

assert.equal(sandbox.require("files"), sandbox.files);
assert.equal(typeof sandbox.require("automator").click, "function");
const automatorCapture = sandbox.require("automator").captureScreen();
assert.equal(automatorCapture.__image, true);
assert.equal(automatorCapture.path, "/capture.png");
assert.throws(() => sandbox.require("missing"), /MODULE_NOT_FOUND/);

console.log("autojs6-core compat tests passed");
