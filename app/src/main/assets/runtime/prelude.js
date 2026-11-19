(function (global) {
  var __modules = {};
  var __loading = {};
  var __builtins = {};
  var __requireStack = [];
  global.require = function (name) {
    var key = String(name);
    if (Object.prototype.hasOwnProperty.call(__builtins, key)) return __builtins[key];
    var from = __requireStack.length ? __requireStack[__requireStack.length - 1] : "";
    var resolved = null;
    if (typeof modules !== 'undefined' && modules.resolve) {
      resolved = modules.resolve(key, from);
    }
    var cacheKey = resolved || (from ? from + "|" + key : key);
    if (Object.prototype.hasOwnProperty.call(__modules, cacheKey)) {
      return __modules[cacheKey].exports;
    }
    if (__loading[cacheKey]) throw new Error("检测到循环依赖: " + key);
    var src = null;
    if (typeof modules !== 'undefined' && modules.sourceFrom) {
      src = modules.sourceFrom(key, from);
    } else if (typeof modules !== 'undefined' && modules.source) {
      src = modules.source(key);
    }
    if (src == null) throw new Error("找不到脚本模块: " + key);
    __loading[cacheKey] = true;
    var slash = cacheKey.lastIndexOf('/');
    var dirname = slash < 0 ? "." : cacheKey.substring(0, slash);
    var mod = { exports: {}, filename: cacheKey, path: dirname };
    __modules[cacheKey] = mod;
    try {
      if (resolved && /\.json$/i.test(resolved)) {
        try {
          mod.exports = JSON.parse(src);
        } catch (e) {
          throw new Error("JSON 模块解析失败: " + key);
        }
      } else {
        var factory;
        try {
          factory = new Function(
            "module", "exports", "require", "__filename", "__dirname", src
          );
        } catch (e) {
          factory = function (module, exports, require) { eval(src); };
        }
        __requireStack.push(resolved || cacheKey);
        try {
          factory(mod, mod.exports, global.require, resolved || cacheKey, dirname);
        } finally {
          __requireStack.pop();
        }
      }
    } catch (e) {
      // 初始化失败的模块不能留在缓存里，否则再次 require 会拿到空 exports。
      delete __modules[cacheKey];
      throw e;
    } finally {
      delete __loading[cacheKey];
    }
    return mod.exports;
  };
  // ---- virtualScreen ----
  if (typeof virtualScreen !== 'undefined') {
    __builtins['virtualScreen'] = virtualScreen;
    __builtins['virtualscreen'] = virtualScreen;
    virtualScreen.allowed = function () {
      var raw = virtualScreen.allowedRaw();
      if (!raw) return [];
      return raw.split(',');
    };
    virtualScreen.size = function () {
      try { return JSON.parse(virtualScreen.sizeRaw()); } catch (e) { return null; }
    };
    virtualScreen.info = function () {
      try { return JSON.parse(virtualScreen.infoRaw()); } catch (e) { return null; }
    };
    virtualScreen.start = function (options) {
      return virtualScreen.startRaw(JSON.stringify(options || {}));
    };
    virtualScreen.target = function (value) {
      if (value === undefined || value === null) return virtualScreen.targetRaw();
      return virtualScreen.setTargetRaw(String(value));
    };
    virtualScreen.awaitFrame = function (timeoutMs) {
      return virtualScreen.awaitFrameRaw(timeoutMs == null ? 1000 : timeoutMs);
    };
    virtualScreen.frameSeq = function () {
      return virtualScreen.frameSeqRaw();
    };
    virtualScreen.awaitChange = function (afterFrameSeq, timeoutMs) {
      return virtualScreen.awaitChangeRaw(
        (afterFrameSeq == null ? -1 : afterFrameSeq),
        (timeoutMs == null ? 2000 : timeoutMs)
      );
    };
    virtualScreen.awaitStable = function (options) {
      var raw = virtualScreen.awaitStableRaw(JSON.stringify(options || {}));
      try { return JSON.parse(raw); } catch (e) { return { ok: false, frameSeq: 0, elapsedMs: 0 }; }
    };
    var __virtualCaptureOptions = function (options, source) {
      if (!options) return;
      var target = source == null ? 'virtual' : String(source);
      if (target === 'main') return;
      if (options.waitNextFrame) {
        virtualScreen.awaitChange(options.afterFrame, options.timeoutMs);
      }
      if (options.waitStable) {
        virtualScreen.awaitStable({
          stableMs: options.stableMs,
          timeoutMs: options.timeoutMs,
          intervalMs: options.intervalMs
        });
      }
    };
    virtualScreen.capture = function (path, source, options) {
      __virtualCaptureOptions(options, source);
      return virtualScreen.captureRaw(
        path == null ? "" : String(path),
        source == null ? "virtual" : String(source)
      );
    };
  }
  // ---- captureScreen ----
  if (typeof captureScreenSource === 'function') {
    var __izukiCaptureScreen = captureScreen;
    global.captureScreen = function (path, source, options) {
      var target = (path == null ? "" : String(path));
      if (options && typeof virtualScreen !== 'undefined' && source !== 'main') {
        if (typeof virtualScreen.active === 'function' && !virtualScreen.active()) {
          return source == null ? __izukiCaptureScreen(target) : captureScreenSource(target, String(source));
        }
        if (options.waitNextFrame) {
          virtualScreen.awaitChange(options.afterFrame, options.timeoutMs);
        }
        if (options.waitStable) {
          virtualScreen.awaitStable({
            stableMs: options.stableMs,
            timeoutMs: options.timeoutMs,
            intervalMs: options.intervalMs
          });
        }
      }
      if (source == null) return __izukiCaptureScreen(target);
      return captureScreenSource(target, String(source));
    };
  }
  // ---- images ----
  if (typeof images !== 'undefined') {
    var __regionJson = function (region) {
      return region == null ? "" : JSON.stringify(region);
    };
    images.findImage = function (template, threshold, region) {
      var raw = images.findImageRegionRaw(
        template,
        (threshold == null ? 0.8 : threshold),
        __regionJson(region)
      );
      if (!raw) return null;
      var p = raw.split(',');
      return { x: parseInt(p[0], 10), y: parseInt(p[1], 10), confidence: parseFloat(p[2]) };
    };
    images.findAllImages = function (template, threshold, region, limit) {
      var raw = images.findAllImagesRaw(
        template,
        (threshold == null ? 0.8 : threshold),
        __regionJson(region),
        (limit == null ? 20 : limit)
      );
      try { return JSON.parse(raw); } catch (e) { return []; }
    };
    images.findColor = function (color, threshold, region) {
      var raw = images.findColorRegionRaw(
        color,
        (threshold == null ? 4 : threshold),
        __regionJson(region)
      );
      if (!raw) return null;
      var p = raw.split(',');
      return { x: parseInt(p[0], 10), y: parseInt(p[1], 10) };
    };
    images.findAllColors = function (color, threshold, region, limit) {
      var raw = images.findAllColorsRaw(
        color,
        (threshold == null ? 4 : threshold),
        __regionJson(region),
        (limit == null ? 100 : limit)
      );
      try { return JSON.parse(raw); } catch (e) { return []; }
    };
    images.findColorRegions = function (color, threshold, options) {
      var raw = images.findColorRegionsRaw(
        color,
        (threshold == null ? 4 : threshold),
        JSON.stringify(options || {})
      );
      try { return JSON.parse(raw); } catch (e) { return []; }
    };
    var __findColorRegionsInRaw = images.findColorRegionsInRaw;
    images.findColorRegionsInRaw = function (screenPath, color, threshold, options) {
      var raw = __findColorRegionsInRaw(
        screenPath,
        color,
        (threshold == null ? 4 : threshold),
        JSON.stringify(options || {})
      );
      try { return JSON.parse(raw); } catch (e) { return []; }
    };
    var __findColorInRaw = images.findColorInRaw;
    images.findColorInRaw = function (screenPath, color, threshold) {
      var raw = __findColorInRaw(
        screenPath,
        color,
        (threshold == null ? 4 : threshold)
      );
      if (!raw) return null;
      var p = raw.split(',');
      return { x: parseInt(p[0], 10), y: parseInt(p[1], 10) };
    };
    var __visionSpecJson = function (spec) {
      return JSON.stringify(spec || {});
    };
    images.recognize = function (spec) {
      var raw = images.recognizeRaw(__visionSpecJson(spec));
      if (!raw) return null;
      try { return JSON.parse(raw); } catch (e) { return null; }
    };
    images.recognizeAll = function (spec) {
      var raw = images.recognizeAllRaw(__visionSpecJson(spec));
      try { return JSON.parse(raw); } catch (e) { return []; }
    };
    images.recognizeIn = function (screenPath, spec) {
      var raw = images.recognizeInRaw(screenPath, __visionSpecJson(spec));
      if (!raw) return null;
      try { return JSON.parse(raw); } catch (e) { return null; }
    };
    images.recognizeAllIn = function (screenPath, spec) {
      var raw = images.recognizeAllInRaw(screenPath, __visionSpecJson(spec));
      try { return JSON.parse(raw); } catch (e) { return []; }
    };
    images.compare = function (lhs, rhs, options) {
      var raw = images.compareRaw(lhs, rhs, JSON.stringify(options || {}));
      if (!raw) return null;
      try { return JSON.parse(raw); } catch (e) { return null; }
    };
  }
  // ---- pipeline ----
  if (typeof pipeline !== 'undefined') {
    pipeline.run = function (spec, entry, options) {
      var specJson = typeof spec === 'string' ? spec : JSON.stringify(spec || {});
      var optionsJson = options == null ? "" : JSON.stringify(options);
      var raw = pipeline.runRaw(specJson, entry == null ? "" : String(entry), optionsJson);
      if (typeof shouldExit === 'function' && shouldExit()) throw new Error("IZUKI_SCRIPT_EXIT");
      try { return JSON.parse(raw); } catch (e) { return { ok: false, error: String(e) }; }
    };
    pipeline.validate = function (spec) {
      var specJson = typeof spec === 'string' ? spec : JSON.stringify(spec || {});
      var raw = pipeline.validateRaw(specJson);
      try { return JSON.parse(raw); } catch (e) { return { ok: false, issues: [] }; }
    };
    pipeline.dump = function (spec) {
      var specJson = typeof spec === 'string' ? spec : JSON.stringify(spec || {});
      var raw = pipeline.dumpRaw(specJson);
      var result;
      try { result = JSON.parse(raw); } catch (e) { throw new Error("pipeline.dump 失败"); }
      if (!result.ok) throw new Error(result.error || "pipeline.dump 失败");
      return result.spec;
    };
    pipeline.registerRecognition = function (name, fn) {
      if (typeof fn !== "function") return false;
      return pipeline.registerRecognitionRaw(String(name), function (paramJson, roisJson) {
        var result = fn(JSON.parse(paramJson), JSON.parse(roisJson));
        return result == null ? "" : JSON.stringify(result);
      });
    };
    pipeline.registerAction = function (name, fn) {
      if (typeof fn !== "function") return false;
      return pipeline.registerActionRaw(String(name), function (paramJson, boxJson) {
        var result = fn(JSON.parse(paramJson), boxJson ? JSON.parse(boxJson) : null);
        return result == null ? "true" : JSON.stringify(result);
      });
    };
    pipeline.replay = function (recordingId, spec, entry, options) {
      var specJson = typeof spec === 'string' ? spec : JSON.stringify(spec || {});
      var optionsJson = options == null ? "" : JSON.stringify(options);
      var raw = pipeline.replayRaw(
        String(recordingId),
        specJson,
        entry == null ? "" : String(entry),
        optionsJson
      );
      if (typeof shouldExit === 'function' && shouldExit()) throw new Error("IZUKI_SCRIPT_EXIT");
      try { return JSON.parse(raw); } catch (e) { return { ok: false, error: String(e) }; }
    };
  }
  // ---- record ----
  if (typeof record !== 'undefined') {
    record.start = function (name) {
      var raw = record.startRaw(name == null ? "recording" : String(name));
      try { return JSON.parse(raw); } catch (e) { return { ok: false, error: String(e) }; }
    };
    record.stop = function () {
      var raw = record.stopRaw();
      try { return JSON.parse(raw); } catch (e) { return { ok: false, error: String(e) }; }
    };
    record.active = function () { return record.activeRaw(); };
    record.list = function () {
      var raw = record.listRaw();
      try { return JSON.parse(raw); } catch (e) { return []; }
    };
    record.info = function (id) {
      var raw = record.infoRaw(String(id));
      if (!raw) return null;
      try { return JSON.parse(raw); } catch (e) { return null; }
    };
    record.delete = function (id) { return record.deleteRaw(String(id)); };
    record.validate = function (id) {
      var raw = record.validateRaw(String(id));
      try { return JSON.parse(raw); } catch (e) { return { ok: false, error: String(e) }; }
    };
  }
  if (typeof http !== 'undefined' && http.requestRaw) {
    var __httpOptions = function (options) {
      var copy = {};
      if (options) {
        for (var key in options) {
          if (Object.prototype.hasOwnProperty.call(options, key)) copy[key] = options[key];
        }
      }
      return copy;
    };
    http.request = function (options) {
      var raw = http.requestRaw(JSON.stringify(options || {}));
      if (!raw) return null;
      try { return JSON.parse(raw); } catch (e) { return null; }
    };
    http.get = function (url, options) {
      var copy = __httpOptions(options);
      copy.url = url;
      copy.method = 'GET';
      return http.request(copy);
    };
    http.post = function (url, body, options) {
      var copy = __httpOptions(options);
      copy.url = url;
      copy.method = 'POST';
      copy.body = body;
      return http.request(copy);
    };
    http.postJson = function (url, data, options) {
      var copy = __httpOptions(options);
      copy.url = url;
      copy.method = 'POST';
      copy.headers = __httpOptions(copy.headers);
      if (!copy.headers['Content-Type'] && !copy.headers['content-type']) {
        copy.headers['Content-Type'] = 'application/json';
      }
      copy.body = JSON.stringify(data);
      return http.request(copy);
    };
  }
  if (typeof gestureRaw === 'function') {
    global.gesture = function (strokes) {
      if (strokes == null) return false;
      try { return gestureRaw(JSON.stringify(strokes)); }
      catch (e) { return false; }
    };
  }
  if (typeof inputInfoRaw === 'function') {
    global.inputInfo = function (text, options) {
      var raw = inputInfoRaw(
        text == null ? "" : String(text),
        (options && options.method) ? String(options.method) : "auto",
        JSON.stringify(options || {})
      );
      try { return JSON.parse(raw); } catch (e) { return { ok: false, delivered: false, error: String(e) }; }
    };
  }
  // ---- ocr ----
  if (typeof ocr !== 'undefined') {
    var __ocrFindRaw = ocr.findRaw;
    ocr.find = function (text, options) {
      var raw = (options == null)
        ? __ocrFindRaw(text)
        : ocr.findWithOptionsRaw(text, JSON.stringify(options));
      if (!raw) return null;
      var p = raw.split(',');
      return {
        x: parseInt(p[0], 10),
        y: parseInt(p[1], 10),
        width: parseInt(p[2], 10),
        height: parseInt(p[3], 10)
      };
    };
    ocr.recognizeBlocks = function (options) {
      var raw = (options == null)
        ? ocr.recognizeBlocksJson()
        : ocr.recognizeBlocksJsonWithOptions(JSON.stringify(options));
      if (!raw) return null;
      try { return JSON.parse(raw); } catch (e) { return null; }
    };
    ocr.lines = function (options) {
      var raw = ocr.recognizeLinesJsonWithOptions(JSON.stringify(options || {}));
      if (!raw) return null;
      try { return JSON.parse(raw); } catch (e) { return null; }
    };
    ocr.dedupeLines = function (lines, options) {
      if (!lines || !lines.length) return [];
      var windowSize = (options && options.window != null) ? options.window : 20;
      var normalize = function (text) {
        return String(text == null ? "" : text).replace(/\s+/g, "").toLowerCase();
      };
      var out = [];
      var recent = [];
      for (var i = 0; i < lines.length; i++) {
        var line = lines[i];
        var key = normalize(line && line.text != null ? line.text : line);
        if (key && recent.indexOf(key) >= 0) continue;
        out.push(line);
        if (key) {
          recent.push(key);
          if (recent.length > windowSize) recent.shift();
        }
      }
      return out;
    };
  }
  // ---- selector ----
  if (typeof selector !== 'undefined' && selector.findRaw) {
    selector.parse = function (dsl) {
      var raw = selector.parseDslRaw(String(dsl));
      try { return JSON.parse(raw); } catch (e) { return { ok: false, error: String(e) }; }
    };
    var __selDslKeys = /^(text|desc|description|contentDescription|textContains|textRegex|descContains|descRegex|id|viewId|class|className|package|packageName|clickable|editable|scrollable|enabled|visible)([~^$]?=|\s|$)/;
    var __selCriteria = function (criteria) {
      if (criteria == null) return {};
      if (typeof criteria === 'string') {
        if (__selDslKeys.test(criteria) || /^!\w/.test(criteria)) {
          var parsed = selector.parse(criteria);
          if (parsed && parsed.ok !== false) return parsed;
        }
        return { text: criteria };
      }
      return criteria;
    };
    var __selJson = function (criteria) {
      return JSON.stringify(__selCriteria(criteria));
    };
    var __selParse = function (raw) {
      if (!raw) return null;
      try { return JSON.parse(raw); } catch (e) { return null; }
    };
    selector.explain = function (criteria) {
      try {
        return JSON.parse(selector.explainRaw(__selJson(criteria)));
      } catch (e) {
        return { ok: false, error: String(e) };
      }
    };
    selector.codegen = function (criteria) {
      try {
        return JSON.parse(selector.codegenRaw(__selJson(criteria)));
      } catch (e) {
        return { ok: false, error: String(e) };
      }
    };
    selector.find = function (criteria, index) {
      return __selParse(selector.findRaw(__selJson(criteria), index == null ? 0 : index));
    };
    selector.findAll = function (criteria) {
      try { return JSON.parse(selector.findAllRaw(__selJson(criteria))); } catch (e) { return []; }
    };
    selector.nth = function (criteria, index) {
      return selector.find(criteria, index);
    };
    selector.exists = function (criteria) {
      return selector.find(criteria) != null;
    };
    selector.count = function (criteria) {
      return selector.countRaw(__selJson(criteria));
    };
    selector.click = function (criteria, index) {
      return selector.clickRaw(__selJson(criteria), index == null ? 0 : index);
    };
    selector.longClick = function (criteria, index) {
      return selector.longClickRaw(__selJson(criteria), index == null ? 0 : index);
    };
    selector.scrollIntoView = function (criteria, index) {
      return selector.scrollIntoViewRaw(__selJson(criteria), index == null ? 0 : index);
    };
    selector.scrollForward = function (criteria, index) {
      return selector.scrollForwardRaw(__selJson(criteria), index == null ? 0 : index);
    };
    selector.scrollBackward = function (criteria, index) {
      return selector.scrollBackwardRaw(__selJson(criteria), index == null ? 0 : index);
    };
    selector.waitFor = function (criteria, timeoutMs, intervalMs, index) {
      var result = __selParse(selector.waitForRaw(
        __selJson(criteria),
        timeoutMs == null ? 5000 : timeoutMs,
        intervalMs == null ? 200 : intervalMs,
        index == null ? 0 : index
      ));
      if (typeof shouldExit === 'function' && shouldExit()) {
        throw new Error('IZUKI_SCRIPT_EXIT');
      }
      return result;
    };
    selector.waitGone = function (criteria, timeoutMs, intervalMs) {
      var result = selector.waitGoneRaw(
        __selJson(criteria),
        timeoutMs == null ? 5000 : timeoutMs,
        intervalMs == null ? 200 : intervalMs
      );
      if (typeof shouldExit === 'function' && shouldExit()) {
        throw new Error('IZUKI_SCRIPT_EXIT');
      }
      return result;
    };
    selector.findByText = function (text, index) {
      return selector.find({ text: text }, index);
    };
    selector.findAllByText = function (text) {
      return selector.findAll({ text: text });
    };
    selector.findByTextContains = function (text, index) {
      return selector.find({ textContains: text }, index);
    };
    selector.findByTextRegex = function (pattern, index) {
      return selector.find({ textRegex: pattern }, index);
    };
    selector.findByDesc = function (desc, index) {
      return selector.find({ desc: desc }, index);
    };
    selector.findByDescContains = function (desc, index) {
      return selector.find({ descContains: desc }, index);
    };
    selector.findByDescRegex = function (pattern, index) {
      return selector.find({ descRegex: pattern }, index);
    };
    selector.waitForText = function (text, timeoutMs, intervalMs) {
      return selector.waitFor({ text: text }, timeoutMs, intervalMs);
    };
    selector.waitForId = function (id, timeoutMs, intervalMs) {
      return selector.waitFor({ id: id }, timeoutMs, intervalMs);
    };
    selector.waitForTextContains = function (text, timeoutMs, intervalMs) {
      return selector.waitFor({ textContains: text }, timeoutMs, intervalMs);
    };
    selector.waitForTextRegex = function (pattern, timeoutMs, intervalMs) {
      return selector.waitFor({ textRegex: pattern }, timeoutMs, intervalMs);
    };
  }
  // ---- waitUntil ----
  global.waitUntil = function (condition, timeoutMs, intervalMs) {
    if (typeof condition !== 'function') {
      throw new Error('waitUntil 需要一个函数参数');
    }
    var timeout = (timeoutMs == null ? 10000 : Number(timeoutMs));
    var interval = (intervalMs == null ? 200 : Number(intervalMs));
    if (!isFinite(timeout) || timeout < 0) timeout = 10000;
    if (!isFinite(interval) || interval < 16) interval = 200;
    var started = Date.now();
    for (;;) {
      if (typeof shouldExit === 'function' && shouldExit()) {
        throw new Error('IZUKI_SCRIPT_EXIT');
      }
      var result = condition();
      if (result) return result;
      var elapsed = Date.now() - started;
      if (elapsed >= timeout) return null;
      sleep(Math.min(interval, timeout - elapsed));
    }
  };
  global.waitUntilGone = function (condition, timeoutMs, intervalMs) {
    if (typeof condition !== 'function') {
      throw new Error('waitUntilGone 需要一个函数参数');
    }
    return waitUntil(function () { return !condition(); }, timeoutMs, intervalMs) != null;
  };
  // ---- app / fs / notify / env / state ----
  if (typeof app !== 'undefined' && app.listRaw) {
    app.list = function () {
      try { return JSON.parse(app.listRaw()); } catch (e) { return []; }
    };
  }
  if (typeof fs !== 'undefined') {
    var __fsList = fs.list;
    fs.list = function (path) {
      var raw = __fsList(path);
      if (!raw) return null;
      try { return JSON.parse(raw); } catch (e) { return null; }
    };
  }
  if (typeof notify !== 'undefined') {
    var __notifyList = notify.list;
    notify.list = function () {
      try { return JSON.parse(__notifyList()); } catch (e) { return []; }
    };
    var __notifyLatest = notify.latest;
    notify.latest = function (pkg, timeoutMs) {
      var raw = __notifyLatest(pkg, (timeoutMs == null ? 10000 : timeoutMs));
      if (!raw) return null;
      try { return JSON.parse(raw); } catch (e) { return null; }
    };
    if (typeof notify.status === 'function') {
      var __notifyStatus = notify.status;
      notify.status = function () {
        try { return JSON.parse(__notifyStatus()); } catch (e) { return null; }
      };
    }
    if (typeof notify.progressRaw === 'function') {
      notify.progress = function (options) {
        return notify.progressRaw(JSON.stringify(options || {}));
      };
      notify.update = function (options) {
        return notify.updateRaw(JSON.stringify(options || {}));
      };
    }
    if (typeof notify.toasts === 'function') {
      var __notifyToasts = notify.toasts;
      notify.toasts = function (limit) {
        try {
          return JSON.parse(__notifyToasts(limit == null ? 20 : limit));
        } catch (e) {
          return [];
        }
      };
      if (typeof notify.toastStatus === 'function') {
        var __notifyToastStatus = notify.toastStatus;
        notify.toastStatus = function () {
          try { return JSON.parse(__notifyToastStatus()); } catch (e) { return null; }
        };
      }
      if (typeof notify.latestToastRaw === 'function') {
        notify.latestToast = function (pkg, contains, timeoutMs) {
          var options;
          if (pkg != null && typeof pkg === 'object') {
            options = pkg;
          } else {
            if (typeof contains === 'number' && timeoutMs == null) {
              timeoutMs = contains;
              contains = null;
            }
            options = {
              package: pkg,
              contains: contains,
              timeoutMs: timeoutMs
            };
          }
          var raw = notify.latestToastRaw(JSON.stringify(options || {}));
          if (!raw) return null;
          try { return JSON.parse(raw); } catch (e) { return null; }
        };
      }
    }
  }
  if (typeof events !== 'undefined' && events.listRaw) {
    events.list = function (options) {
      try {
        return JSON.parse(events.listRaw(JSON.stringify(options || {})));
      } catch (e) {
        return [];
      }
    };
    events.wait = function (options) {
      var raw = events.waitRaw(JSON.stringify(options || {}));
      if (!raw) return null;
      try { return JSON.parse(raw); } catch (e) { return null; }
    };
    events.waitIdle = function (options) {
      try {
        return JSON.parse(events.waitIdleRaw(JSON.stringify(options || {})));
      } catch (e) {
        return { ok: false, reason: String(e) };
      }
    };
    events.currentWindow = function () {
      var raw = events.currentWindowRaw();
      if (!raw) return null;
      try { return JSON.parse(raw); } catch (e) { return null; }
    };
    var __eventsStatus = events.status;
    events.status = function () {
      try { return JSON.parse(__eventsStatus()); } catch (e) { return null; }
    };
  }
  // ---- floaty 悬浮窗 ----
  if (typeof floaty !== 'undefined' && floaty.showRaw) {
    floaty.show = function (options) {
      try {
        return JSON.parse(floaty.showRaw(JSON.stringify(options || {})));
      } catch (e) {
        return { ok: false, error: String(e) };
      }
    };
    floaty.update = function (id, options) {
      return floaty.updateRaw(String(id), JSON.stringify(options || {}));
    };
    floaty.waitAction = function (id, timeoutMs) {
      var raw = floaty.waitActionRaw(String(id), timeoutMs == null ? 10000 : timeoutMs);
      if (!raw) return null;
      try { return JSON.parse(raw); } catch (e) { return null; }
    };
    var __floatyActions = floaty.actions;
    floaty.actions = function (id) {
      try { return JSON.parse(__floatyActions(String(id))); } catch (e) { return []; }
    };
    var __floatyStatus = floaty.status;
    floaty.status = function (id) {
      var raw = __floatyStatus(String(id));
      if (!raw) return null;
      try { return JSON.parse(raw); } catch (e) { return null; }
    };
  }
  // ---- 等待前台包名 / 窗口 ----
  global.waitForPackage = function (pkg, timeoutMs) {
    var target = String(pkg == null ? '' : pkg);
    var timeout = timeoutMs == null ? 10000 : Number(timeoutMs);
    if (!isFinite(timeout) || timeout < 0) timeout = 10000;
    var deadline = Date.now() + timeout;
    for (;;) {
      if (typeof app !== 'undefined' && typeof app.currentPackage === 'function' &&
          app.currentPackage() === target) {
        return true;
      }
      if (typeof events !== 'undefined' && typeof events.currentWindow === 'function') {
        var win = events.currentWindow();
        if (win && win.package === target) return true;
      }
      if (typeof shouldExit === 'function' && shouldExit()) {
        throw new Error('IZUKI_SCRIPT_EXIT');
      }
      var remaining = deadline - Date.now();
      if (remaining <= 0) return false;
      sleep(Math.min(200, remaining));
    }
  };
  global.waitForActivity = function (activity, timeoutMs) {
    var target = String(activity == null ? '' : activity);
    var timeout = timeoutMs == null ? 10000 : Number(timeoutMs);
    if (!isFinite(timeout) || timeout < 0) timeout = 10000;
    var deadline = Date.now() + timeout;
    for (;;) {
      if (typeof events !== 'undefined' && typeof events.currentWindow === 'function') {
        var win = events.currentWindow();
        if (win && win.className && String(win.className).indexOf(target) >= 0) {
          return true;
        }
      }
      if (typeof shouldExit === 'function' && shouldExit()) {
        throw new Error('IZUKI_SCRIPT_EXIT');
      }
      var remaining = deadline - Date.now();
      if (remaining <= 0) return false;
      sleep(Math.min(200, remaining));
    }
  };
  if (typeof env !== 'undefined' && env.allRaw) {
    env.get = function (key, fallback) {
      if (fallback === undefined || fallback === null) return env.getRaw(key);
      return env.getOrRaw(key, String(fallback));
    };
    env.all = function () { return JSON.parse(env.allRaw()); };
  }
  if (typeof state !== 'undefined' && state.allRaw) {
    state.get = function (key, fallback) {
      if (fallback === undefined || fallback === null) return state.getRaw(key);
      return state.getOrRaw(key, String(fallback));
    };
    state.all = function () { return JSON.parse(state.allRaw()); };
  }
  // ---- 停止 / 退出包装 ----
  // 停止脚本：Java 侧只设标志并尽快返回，绝不抛出 Java 异常（否则 QuickJS JNI 会 abort）。
  // 这里在原生调用返回后检测标志，抛出真正的 JS 异常来中断脚本执行。
  if (typeof shouldExit === 'function') {
    if (typeof sleep === 'function') {
      var __izukiSleep = sleep;
      global.sleep = function (ms) {
        __izukiSleep(ms);
        if (shouldExit()) throw new Error("IZUKI_SCRIPT_EXIT");
      };
    }
    if (typeof exit === 'function') {
      var __izukiExit = exit;
      global.exit = function () {
        __izukiExit();
        throw new Error("IZUKI_SCRIPT_EXIT");
      };
    }
    if (typeof ai !== 'undefined') {
      if (typeof ai.chat === 'function') {
        var __izukiAiChat = ai.chat;
        ai.chat = function (prompt, options) {
          var r = __izukiAiChat(prompt, options);
          if (shouldExit()) throw new Error("IZUKI_SCRIPT_EXIT");
          return r;
        };
      }
      if (typeof ai.run === 'function') {
        var __izukiAiRun = ai.run;
        ai.run = function (prompt, options) {
          var r = __izukiAiRun(prompt, options);
          if (shouldExit()) throw new Error("IZUKI_SCRIPT_EXIT");
          return r;
        };
      }
    }
  }
})(typeof globalThis !== 'undefined' ? globalThis : this);
