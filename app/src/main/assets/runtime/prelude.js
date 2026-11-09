(function (global) {
  var __modules = {};
  var __loading = {};
  var __builtins = {};
  global.require = function (name) {
    var key = String(name);
    if (Object.prototype.hasOwnProperty.call(__builtins, key)) return __builtins[key];
    if (Object.prototype.hasOwnProperty.call(__modules, key)) return __modules[key].exports;
    if (__loading[key]) throw new Error("检测到循环依赖: " + key);
    var src = (typeof modules !== 'undefined' && modules.source) ? modules.source(key) : null;
    if (src == null) throw new Error("找不到脚本模块: " + key);
    __loading[key] = true;
    var mod = { exports: {} };
    __modules[key] = mod;
    try {
      var factory;
      try {
        factory = new Function("module", "exports", "require", src);
      } catch (e) {
        factory = function (module, exports, require) { eval(src); };
      }
      factory(mod, mod.exports, global.require);
    } finally {
      delete __loading[key];
    }
    return mod.exports;
  };
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
    virtualScreen.capture = function (path, source) {
      return virtualScreen.captureRaw(
        path == null ? "" : String(path),
        source == null ? "virtual" : String(source)
      );
    };
  }
  if (typeof captureScreenSource === 'function') {
    var __izukiCaptureScreen = captureScreen;
    global.captureScreen = function (path, source) {
      var target = (path == null ? "" : String(path));
      if (source == null) return __izukiCaptureScreen(target);
      return captureScreenSource(target, String(source));
    };
  }
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
  if (typeof ocr !== 'undefined') {
    ocr.find = function (text) {
      var raw = ocr.findRaw(text);
      if (!raw) return null;
      var p = raw.split(',');
      return {
        x: parseInt(p[0], 10),
        y: parseInt(p[1], 10),
        width: parseInt(p[2], 10),
        height: parseInt(p[3], 10)
      };
    };
  }
  if (typeof selector !== 'undefined' && selector.findRaw) {
    var __selCriteria = function (criteria) {
      if (criteria == null) return {};
      if (typeof criteria === 'string') return { text: criteria };
      return criteria;
    };
    var __selJson = function (criteria) {
      return JSON.stringify(__selCriteria(criteria));
    };
    var __selParse = function (raw) {
      if (!raw) return null;
      try { return JSON.parse(raw); } catch (e) { return null; }
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
    if (typeof notify.progressRaw === 'function') {
      notify.progress = function (options) {
        return notify.progressRaw(JSON.stringify(options || {}));
      };
      notify.update = function (options) {
        return notify.updateRaw(JSON.stringify(options || {}));
      };
    }
  }
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
