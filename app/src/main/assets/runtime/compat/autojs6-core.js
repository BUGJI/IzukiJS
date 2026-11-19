/*
 * Auto.js / AutoX v6 兼容门面（第一阶段：加法式 API 与对象形状适配）。
 *
 * 只在脚本声明 `// @compat autojs6` 时由 JsEngine 加载，加载顺序在 prelude.js 之后、
 * 用户脚本之前。这里不注册新的原生命名空间，也不绕过 @need / 权限；缺少原生能力时
 * 明确报错或降级，而不是静默返回假成功。
 */
(function (global) {
  'use strict';

  var root = global;

  function available(name) {
    return typeof root[name] !== 'undefined' && root[name] !== null;
  }

  function warn(message) {
    try {
      if (typeof root.warn === 'function') root.warn('[compat] ' + message);
      else if (typeof console !== 'undefined' && console.warn) console.warn('[compat] ' + message);
    } catch (ignored) {
      // 兼容层自身不能因为日志失败而中断脚本。
    }
  }

  function fail(message) {
    throw new Error('[compat] ' + message);
  }

  /**
   * 同时满足 `device.width` 与 `device.width()` 的标量包装。
   * JavaScript 的 ToPrimitive 会调用 valueOf，因此算术、比较与字符串拼接都能拿到原值。
   */
  function scalar(value) {
    var fn = function () { return value; };
    fn.valueOf = function () { return value; };
    fn.toString = function () { return String(value); };
    return fn;
  }

  // ---- device：属性写法兼容 ----
  if (available('device')) {
    var nativeDevice = root.device;
    var deviceFacade = {};

    function bindDeviceMethod(name) {
      if (typeof nativeDevice[name] === 'function') {
        deviceFacade[name] = function () {
          return nativeDevice[name].apply(nativeDevice, arguments);
        };
      }
    }

    bindDeviceMethod('width');
    bindDeviceMethod('height');
    bindDeviceMethod('screenWidth');
    bindDeviceMethod('screenHeight');
    bindDeviceMethod('rotation');
    bindDeviceMethod('model');
    bindDeviceMethod('brand');
    bindDeviceMethod('androidVersion');
    bindDeviceMethod('batteryLevel');
    [
      'vibrate', 'cancelVibration',
      'getMusicVolume', 'setMusicVolume', 'getMusicMaxVolume',
      'getNotificationVolume', 'setNotificationVolume', 'getNotificationMaxVolume',
      'getAlarmVolume', 'setAlarmVolume', 'getAlarmMaxVolume',
      'getBrightness', 'setBrightness', 'getBrightnessMode', 'setBrightnessMode'
    ].forEach(bindDeviceMethod);

    function deviceGetter(name, source) {
      Object.defineProperty(deviceFacade, name, {
        enumerable: true,
        configurable: true,
        get: function () { return scalar(source()); }
      });
    }

    deviceGetter('width', function () { return nativeDevice.width(); });
    deviceGetter('height', function () { return nativeDevice.height(); });
    deviceGetter('screenWidth', function () { return nativeDevice.screenWidth(); });
    deviceGetter('screenHeight', function () { return nativeDevice.screenHeight(); });
    deviceGetter('rotation', function () { return nativeDevice.rotation(); });
    deviceGetter('model', function () { return nativeDevice.model(); });
    deviceGetter('brand', function () { return nativeDevice.brand(); });
    deviceGetter('androidVersion', function () { return nativeDevice.androidVersion(); });
    deviceGetter('sdkInt', function () { return nativeDevice.androidVersion(); });
    deviceGetter('batteryLevel', function () { return nativeDevice.batteryLevel(); });

    try {
      root.device = deviceFacade;
    } catch (error) {
      try {
        Object.defineProperty(root, 'device', {
          value: deviceFacade,
          configurable: true,
          writable: true
        });
      } catch (fallbackError) {
        warn('device 兼容门面挂载失败：' + fallbackError);
      }
    }
  }

  function androidSdk() {
    try {
      var value = root.device && root.device.androidVersion;
      return typeof value === 'function' ? Number(value()) : Number(value);
    } catch (error) {
      return 0;
    }
  }

  // ---- 全局工具函数 ----
  if (available('toast')) {
    root.toastLog = function (message) {
      try { root.toast(message); } catch (ignored) {}
      if (typeof root.log === 'function') root.log(message);
    };
  }

  if (available('app') && typeof root.app.currentPackage === 'function') {
    root.currentPackage = function () { return root.app.currentPackage(); };
  }

  if (available('events') && typeof root.events.currentWindow === 'function') {
    root.currentActivity = function () {
      try {
        var win = root.events.currentWindow();
        return win && win.className ? win.className : null;
      } catch (error) {
        return null;
      }
    };
  }

  if (available('clipboard')) {
    root.setClip = function (text) { return root.clipboard.set(String(text == null ? '' : text)); };
    root.getClip = function () { return root.clipboard.get(); };
  }

  root.random = function (min, max) {
    if (min == null) return Math.random();
    if (max == null) { max = min; min = 0; }
    var low = Math.ceil(Number(min));
    var high = Math.floor(Number(max));
    if (!isFinite(low) || !isFinite(high)) return 0;
    if (low > high) return low;
    return Math.floor(Math.random() * (high - low + 1)) + low;
  };

  if (typeof root.exit === 'function') {
    root.stop = function (error) { return root.exit(error); };
  }

  root.isStopped = function () {
    try { return typeof root.shouldExit === 'function' ? !!root.shouldExit() : false; }
    catch (error) { return false; }
  };
  root.notStopped = function () { return !root.isStopped(); };
  root.isRunning = function () { return !root.isStopped(); };
  root.isShuttingDown = false;

  // ---- colors：Auto.js 的 32 位有符号 ARGB 常量与转换 ----
  (function () {
    function argb(a, r, g, b) {
      // Auto.js 同时支持 argb(color) / argb(r,g,b) / argb(a,r,g,b)。
      if (arguments.length <= 1) return parseColor(a);
      if (arguments.length === 3) {
        return ((Number(a) << 16) | (Number(r) << 8) | Number(b) | 0xff000000) | 0;
      }
      return ((Number(a) << 24) | (Number(r) << 16) | (Number(g) << 8) | Number(b)) | 0;
    }
    function parseColor(value) {
      if (typeof value === 'number') return value | 0;
      var text = String(value == null ? '' : value).trim().replace(/^#/, '');
      if (/^[0-9a-fA-F]{3}$/.test(text)) {
        text = text.charAt(0) + text.charAt(0) +
          text.charAt(1) + text.charAt(1) +
          text.charAt(2) + text.charAt(2);
      }
      if (/^[0-9a-fA-F]{6}$/.test(text)) {
        return (parseInt(text, 16) | 0xff000000) | 0;
      }
      if (/^[0-9a-fA-F]{8}$/.test(text)) {
        return parseInt(text, 16) | 0;
      }
      fail('无法解析颜色: ' + value);
    }
    function toString(value) {
      var hex = ((value | 0) >>> 0).toString(16).toUpperCase();
      while (hex.length < 8) hex = '0' + hex;
      return '#' + hex;
    }
    function component(value) {
      var number = Number(value);
      if (!isFinite(number)) return 0;
      return Math.max(0, Math.min(255, Math.round(number)));
    }
    function toHex(value, alphaOrLength) {
      var int = parseColor(value);
      var full = ((int >>> 0).toString(16).toUpperCase());
      while (full.length < 8) full = '0' + full;
      var mode = 0;
      if (alphaOrLength === true) mode = 8;
      else if (alphaOrLength === false) mode = 6;
      else if (typeof alphaOrLength === 'number') mode = alphaOrLength;
      if (mode === 8) return '#' + full;
      if (mode === 6) return '#' + full.substring(2);
      if (mode === 3) {
        return '#' + full.charAt(2) + full.charAt(4) + full.charAt(6);
      }
      return ((int >>> 24) & 0xff) === 255 ? '#' + full.substring(2) : '#' + full;
    }
    /** Auto.js 的 rgba(#RRGGBBAA) 会换成 ARGB；数字分量按 0..255 处理。 */
    function rgba(a, b, c, d) {
      if (arguments.length <= 1) {
        var text = String(a == null ? '' : a).trim();
        if (text.charAt(0) === '#') {
          var hex = text.substring(1);
          if (/^[0-9a-fA-F]{4}$/.test(hex)) {
            hex = hex.charAt(0) + hex.charAt(0) + hex.charAt(1) + hex.charAt(1) +
              hex.charAt(2) + hex.charAt(2) + hex.charAt(3) + hex.charAt(3);
          }
          if (/^[0-9a-fA-F]{8}$/.test(hex)) {
            return parseInt(hex.substring(6, 8) + hex.substring(0, 6), 16) | 0;
          }
        }
        return parseColor(a);
      }
      if (arguments.length === 2) {
        if (Object.prototype.toString.call(a) === '[object Array]') {
          return rgba(a[0], a[1], a[2], b);
        }
        fail('colors.rgba(rgb, alpha) 需要 [r, g, b] 数组');
      }
      if (arguments.length !== 4) {
        fail('colors.rgba 需要 1 / 2 / 4 个参数');
      }
      return argb(component(d), component(a), component(b), component(c));
    }
    root.colors = {
      BLACK: argb(255, 0, 0, 0),
      DKGRAY: argb(255, 68, 68, 68),
      DARKGRAY: argb(255, 68, 68, 68),
      GRAY: argb(255, 136, 136, 136),
      LTGRAY: argb(255, 204, 204, 204),
      LIGHTGRAY: argb(255, 204, 204, 204),
      WHITE: argb(255, 255, 255, 255),
      RED: argb(255, 255, 0, 0),
      GREEN: argb(255, 0, 255, 0),
      BLUE: argb(255, 0, 0, 255),
      YELLOW: argb(255, 255, 255, 0),
      CYAN: argb(255, 0, 255, 255),
      MAGENTA: argb(255, 255, 0, 255),
      TRANSPARENT: 0,
      argb: argb,
      rgb: function (r, g, b) { return argb(255, r, g, b); },
      rgba: rgba,
      parseColor: parseColor,
      toInt: parseColor,
      toString: toString,
      toHex: toHex,
      alpha: function (color) { return (color >> 24) & 0xff; },
      red: function (color) { return (color >> 16) & 0xff; },
      green: function (color) { return (color >> 8) & 0xff; },
      blue: function (color) { return color & 0xff; },
      equals: function (a, b) { return (a | 0) === (b | 0); }
    };
  })();

  root.requiresApi = function (api) { return androidSdk() >= Number(api); };
  root.requiresAutojsVersion = function () { return true; };

  var screenMetricsWarned = false;
  root.setScreenMetrics = function () {
    if (!screenMetricsWarned) {
      screenMetricsWarned = true;
      warn('setScreenMetrics 目前仅保留调用兼容，不做坐标缩放');
    }
    return true;
  };

  root.keys = root.keys || {
    home: 3,
    back: 4,
    menu: 82,
    volume_up: 24,
    volume_down: 25,
    power: 26,
    camera: 27,
    up: 19,
    down: 20,
    left: 21,
    right: 22,
    center: 23,
    enter: 66,
    delete: 67,
    search: 84
  };

  // ---- 定时器：在 sleep / 脚本收尾时协作式执行 ----
  // QuickJS 是单线程运行时，不能在另一个线程重新进入 JS。这里把定时器做成 JS 队列，
  // 在兼容版 sleep 与 JsEngine 的收尾循环中执行，避免跨线程调用 JSFunction。
  var nativeSleep = root.sleep;
  var timerSeq = 0;
  var timerMap = {};
  var timerCount = 0;

  function timerDelay(value) {
    var ms = Number(value);
    return isFinite(ms) && ms > 0 ? ms : 0;
  }

  function scheduleTimer(callback, delay, repeat, args) {
    var fn = callback;
    if (typeof fn === 'string') {
      var code = fn;
      fn = function () { (0, eval)(code); };
    }
    if (typeof fn !== 'function') return 0;
    var id = ++timerSeq;
    var interval = repeat ? Math.max(1, timerDelay(delay)) : 0;
    timerMap[id] = {
      id: id,
      due: Date.now() + timerDelay(delay),
      interval: interval,
      fn: fn,
      args: args || []
    };
    timerCount++;
    return id;
  }

  function clearTimer(id) {
    var key = Number(id);
    if (!timerMap[key]) return false;
    delete timerMap[key];
    timerCount--;
    return true;
  }

  function pendingTimers() {
    return timerCount;
  }

  function pumpTimers() {
    if (root.isStopped && root.isStopped()) throw new Error('IZUKI_SCRIPT_EXIT');
    var now = Date.now();
    var due = [];
    for (var key in timerMap) {
      if (Object.prototype.hasOwnProperty.call(timerMap, key) && timerMap[key].due <= now) {
        due.push(timerMap[key]);
      }
    }
    due.sort(function (a, b) { return a.due - b.due || a.id - b.id; });
    var limit = Math.min(due.length, 256);
    for (var i = 0; i < limit; i++) {
      var timer = due[i];
      if (!timerMap[timer.id]) continue;
      if (timer.interval > 0) timer.due = now + timer.interval;
      else {
        delete timerMap[timer.id];
        timerCount--;
      }
      timer.fn.apply(null, timer.args);
    }
    return due.length;
  }

  root.setTimeout = function (callback, delay) {
    return scheduleTimer(callback, delay, false, Array.prototype.slice.call(arguments, 2));
  };
  root.setInterval = function (callback, delay) {
    return scheduleTimer(callback, delay, true, Array.prototype.slice.call(arguments, 2));
  };
  root.setImmediate = function (callback) {
    return scheduleTimer(callback, 0, false, Array.prototype.slice.call(arguments, 1));
  };
  root.clearTimeout = clearTimer;
  root.clearInterval = clearTimer;
  root.clearImmediate = clearTimer;

  if (typeof nativeSleep === 'function') {
    try {
      root.sleep = function (ms) {
        var end = Date.now() + timerDelay(ms);
        for (;;) {
          pumpTimers();
          var remaining = end - Date.now();
          if (remaining <= 0) break;
          nativeSleep(Math.min(remaining, 20));
        }
        pumpTimers();
      };
    } catch (error) {
      warn('sleep 定时器门面挂载失败：' + error);
    }
  }

  root.__izukiCompatHasPending = function () { return timerCount > 0; };
  root.__izukiCompatPump = function () { pumpTimers(); return timerCount; };

  /** 距离下一个定时器到期的毫秒数；没有待执行定时器时返回 -1。 */
  root.__izukiCompatNextDelay = function () {
    var now = Date.now();
    var next = -1;
    for (var key in timerMap) {
      if (!Object.prototype.hasOwnProperty.call(timerMap, key)) continue;
      var remaining = timerMap[key].due - now;
      if (next < 0 || remaining < next) next = remaining;
    }
    return next;
  };

  // ---- 输入重载：click(text) / press(x,y,duration) ----
  function selectorClick(criteria, index) {
    if (!available('selector') || typeof root.selector.click !== 'function') {
      fail('当前运行未注册 selector；请确认无障碍已开启');
    }
    return root.selector.click(criteria, index == null ? 0 : index);
  }

  if (typeof root.click === 'function') {
    var nativeClick = root.click;
    root.click = function (x, y) {
      if (x && typeof x === 'object' && typeof x.click === 'function') return x.click();
      if (typeof x === 'string') return selectorClick({ text: x }, y);
      return nativeClick(x, y);
    };
  }

  if (typeof root.longClick === 'function') {
    var nativeLongClick = root.longClick;
    root.longClick = function (x, y, duration) {
      if (x && typeof x === 'object' && typeof x.longClick === 'function') return x.longClick();
      if (typeof x === 'string') {
        if (!available('selector') || typeof root.selector.longClick !== 'function') {
          fail('当前运行未注册 selector；请确认无障碍已开启');
        }
        return root.selector.longClick({ text: x }, y == null ? 0 : y);
      }
      return nativeLongClick(x, y, duration == null ? 600 : duration);
    };
  }

  if (typeof root.press === 'function') {
    var nativePress = root.press;
    root.press = function (a, b, c) {
      // Auto.js: press(x, y, duration)；本平台原有: press(keyCode)。
      if (typeof a === 'number' && typeof b === 'number') {
        return root.longClick(a, b, c == null ? 500 : c);
      }
      return nativePress(a);
    };
  }

  if (typeof root.gesture === 'function') {
    var nativeGesture = root.gesture;
    root.gesture = function (first) {
      if (typeof first === 'number' && arguments.length >= 2) {
        var points = [];
        for (var i = 1; i < arguments.length; i++) points.push(arguments[i]);
        var stroke = [];
        for (var j = 0; j < points.length; j++) {
          var point = points[j];
          var x = point && point[0] != null ? point[0] : point && point.x;
          var y = point && point[1] != null ? point[1] : point && point.y;
          if (x == null || y == null) continue;
          var t = points.length <= 1 ? 0 : Math.round(Number(first) * j / (points.length - 1));
          stroke.push({ x: x, y: y, t: t });
        }
        return nativeGesture([stroke]);
      }
      return nativeGesture(first);
    };
    root.gestures = function (strokes) { return nativeGesture(strokes); };
    root.gestureAsync = function () {
      var callback = arguments.length > 0 ? arguments[arguments.length - 1] : null;
      var result = root.gesture.apply(null, arguments);
      if (typeof callback === 'function') callback(result);
      return result;
    };
    root.gesturesAsync = function (strokes, callback) {
      var result = root.gestures(strokes);
      if (typeof callback === 'function') callback(result);
      return result;
    };
  }

  // ---- selector：UiSelector / UiObject 链式门面 ----
  if (available('selector') && typeof root.selector.findAll === 'function') {
    var nativeSelector = root.selector;

    function currentPackageName() {
      try {
        return typeof root.currentPackage === 'function' ? root.currentPackage() : null;
      } catch (error) {
        return null;
      }
    }

    function normalizeId(value) {
      var text = String(value == null ? '' : value);
      if (text.indexOf(':id/') >= 0 || text.indexOf('/') >= 0) return text;
      var pkg = currentPackageName();
      return pkg ? pkg + ':id/' + text : text;
    }

    function normalizeRegex(value) {
      var text = String(value == null ? '' : value);
      var match = /^\/(.*)\/([gimsuy]*)$/.exec(text);
      if (!match) return text;
      var flags = match[2] || '';
      return flags.indexOf('i') >= 0 ? '(?i)' + match[1] : match[1];
    }

    function boundsValue(first, second, third, fourth) {
      if (first && typeof first === 'object') {
        if (first.left != null) return first;
        if (first[0] != null) {
          return { left: first[0], top: first[1], right: first[2], bottom: first[3] };
        }
      }
      return { left: first, top: second, right: third, bottom: fourth };
    }

    function addValue(criteria, key, value) {
      if (value == null) return;
      criteria[key] = String(value);
    }

    function Selector(criteria, predicate) {
      this._criteria = criteria || {};
      this._predicate = typeof predicate === 'function' ? predicate : null;
    }

    Selector.prototype._all = function () {
      var list = nativeSelector.findAll(this._criteria) || [];
      if (this._predicate) list = list.filter(this._predicate);
      return list;
    };

    Selector.prototype._collection = function (list) {
      for (var i = 0; i < list.length; i++) {
        list[i] = new UiObject(list[i], this, i);
      }
      return collection(list);
    };

    Selector.prototype.filter = function (predicate) {
      if (typeof predicate !== 'function') return this;
      var previous = this._predicate;
      var combined = previous
        ? function (el) { return previous(el) && predicate(el); }
        : predicate;
      return new Selector(this._criteria, combined);
    };

    Selector.prototype.algorithm = function () { return this; };

    Selector.prototype.findOnce = function (index) {
      var list = this._all();
      var position = index == null ? 0 : Number(index);
      return list[position] ? new UiObject(list[position], this, position) : null;
    };

    Selector.prototype.find = function () {
      return this._collection(this._all());
    };

    Selector.prototype.findAll = function () {
      return this._collection(this._all());
    };

    Selector.prototype.exists = function () {
      return this._all().length > 0;
    };

    Selector.prototype.count = function () {
      return this._all().length;
    };

    Selector.prototype.waitFor = function (timeoutMs) {
      var timeout = timeoutMs == null ? 10000 : Number(timeoutMs);
      var infinite = timeout < 0;
      var deadline = infinite ? Infinity : Date.now() + Math.max(0, timeout);
      for (;;) {
        var list = this._all();
        if (list.length > 0) return new UiObject(list[0], this, 0);
        if (root.isStopped && root.isStopped()) throw new Error('IZUKI_SCRIPT_EXIT');
        if (!infinite && Date.now() >= deadline) return null;
        root.sleep(200);
      }
    };

    Selector.prototype.findOne = function (timeoutMs) {
      return this.waitFor(timeoutMs == null ? 10000 : timeoutMs);
    };

    Selector.prototype.untilFind = function () {
      var deadline = Date.now() + 10000;
      for (;;) {
        var list = this._all();
        if (list.length > 0) return this._collection(list);
        if (root.isStopped && root.isStopped()) throw new Error('IZUKI_SCRIPT_EXIT');
        if (Date.now() >= deadline) return this._collection([]);
        root.sleep(200);
      }
    };

    Selector.prototype.untilFindOne = function () { return this.waitFor(10000); };

    Selector.prototype.click = function (index) {
      var position = index == null ? 0 : Number(index);
      if (!this._predicate) {
        var ok = nativeSelector.click(this._criteria, position);
        if (ok) return true;
      }
      var el = this._all()[position];
      if (!el) return false;
      return root.click(
        (Number(el.left) + Number(el.right)) / 2,
        (Number(el.top) + Number(el.bottom)) / 2
      );
    };

    Selector.prototype.longClick = function (index) {
      var position = index == null ? 0 : Number(index);
      if (!this._predicate) {
        var ok = nativeSelector.longClick(this._criteria, position);
        if (ok) return true;
      }
      var el = this._all()[position];
      if (!el) return false;
      return root.longClick(
        (Number(el.left) + Number(el.right)) / 2,
        (Number(el.top) + Number(el.bottom)) / 2,
        600
      );
    };

    Selector.prototype.setText = function (text, index) {
      var el = this._all()[index == null ? 0 : Number(index)];
      if (!el) return false;
      if (el.id) return nativeSelector.setTextById(el.id, String(text));
      if (!root.click((Number(el.left) + Number(el.right)) / 2,
        (Number(el.top) + Number(el.bottom)) / 2)) {
        return false;
      }
      return typeof nativeInput === 'function' ? nativeInput(String(text)) : false;
    };

    Selector.prototype.scrollForward = function (index) {
      return nativeSelector.scrollForward(this._criteria, index == null ? 0 : Number(index));
    };
    Selector.prototype.scrollBackward = function (index) {
      return nativeSelector.scrollBackward(this._criteria, index == null ? 0 : Number(index));
    };

    Selector.prototype.toString = function () {
      return 'UiSelector(' + JSON.stringify(this._criteria) + ')';
    };

    function collection(list) {
      list.size = function () { return list.length; };
      list.get = function (index) { return list[index]; };
      list.each = function (callback) {
        for (var i = 0; i < list.length; i++) callback(list[i], i);
      };
      list.empty = function () { return list.length === 0; };
      list.nonEmpty = function () { return list.length > 0; };
      list.click = function (index) {
        var item = list[index == null ? 0 : index];
        return item ? item.click() : false;
      };
      list.longClick = function (index) {
        var item = list[index == null ? 0 : index];
        return item ? item.longClick() : false;
      };
      list.setText = function (index, text) {
        var item = list[index == null ? 0 : index];
        return item ? item.setText(text) : false;
      };
      return list;
    }

    function UiObject(element, selector, index) {
      this._element = element;
      this._selector = selector;
      this._index = index;
    }

    UiObject.prototype._value = function (key) {
      return this._element[key];
    };

    ['id', 'text', 'desc', 'className', 'package', 'packageName',
      'clickable', 'longClickable', 'editable', 'scrollable', 'enabled',
      'visible', 'checkable', 'checked', 'focusable', 'focused', 'selected',
      'password'].forEach(function (key) {
      UiObject.prototype[key] = function () {
        if (key === 'packageName') return this._element.package;
        return this._element[key];
      };
    });

    UiObject.prototype.bounds = function () {
      var el = this._element;
      var left = Number(el.left);
      var top = Number(el.top);
      var right = Number(el.right);
      var bottom = Number(el.bottom);
      return {
        left: left,
        top: top,
        right: right,
        bottom: bottom,
        centerX: (left + right) / 2,
        centerY: (top + bottom) / 2,
        width: right - left,
        height: bottom - top
      };
    };

    UiObject.prototype.click = function () {
      return this._selector ? this._selector.click(this._index) : false;
    };
    UiObject.prototype.longClick = function () {
      return this._selector ? this._selector.longClick(this._index) : false;
    };
    UiObject.prototype.setText = function (text) {
      return this._selector ? this._selector.setText(text, this._index) : false;
    };
    UiObject.prototype.scrollForward = function () {
      return this._selector ? this._selector.scrollForward(this._index) : false;
    };
    UiObject.prototype.scrollBackward = function () {
      return this._selector ? this._selector.scrollBackward(this._index) : false;
    };
    UiObject.prototype.exists = function () { return true; };
    UiObject.prototype.refresh = function () { return this; };
    UiObject.prototype.isStale = function () { return false; };

    ['parent', 'child', 'children', 'find', 'findOne', 'findOnce'].forEach(function (name) {
      UiObject.prototype[name] = function () {
        fail('UiObject.' + name + ' 需要节点句柄，当前兼容层尚未实现');
      };
    });

    UiObject.prototype.toString = function () {
      return 'UiObject(' + JSON.stringify(this._element) + ')';
    };

    function selectorBuilder(criteria) { return new Selector(criteria); }

    root.text = function (value) { var c = {}; addValue(c, 'text', value); return selectorBuilder(c); };
    root.textContains = function (value) { var c = {}; addValue(c, 'textContains', value); return selectorBuilder(c); };
    root.textStartsWith = function (value) { var c = {}; addValue(c, 'textStartsWith', value); return selectorBuilder(c); };
    root.textEndsWith = function (value) { var c = {}; addValue(c, 'textEndsWith', value); return selectorBuilder(c); };
    root.textMatches = function (value) { var c = {}; addValue(c, 'textRegex', normalizeRegex(value)); return selectorBuilder(c); };

    root.desc = function (value) { var c = {}; addValue(c, 'desc', value); return selectorBuilder(c); };
    root.descContains = function (value) { var c = {}; addValue(c, 'descContains', value); return selectorBuilder(c); };
    root.descStartsWith = function (value) { var c = {}; addValue(c, 'descStartsWith', value); return selectorBuilder(c); };
    root.descEndsWith = function (value) { var c = {}; addValue(c, 'descEndsWith', value); return selectorBuilder(c); };
    root.descMatches = function (value) { var c = {}; addValue(c, 'descRegex', normalizeRegex(value)); return selectorBuilder(c); };

    root.id = function (value) { var c = {}; addValue(c, 'id', normalizeId(value)); return selectorBuilder(c); };
    root.idContains = function (value) { var c = {}; addValue(c, 'idContains', String(value)); return selectorBuilder(c); };
    root.idStartsWith = function (value) { var c = {}; addValue(c, 'idStartsWith', normalizeId(value)); return selectorBuilder(c); };
    root.idEndsWith = function (value) { var c = {}; addValue(c, 'idEndsWith', String(value)); return selectorBuilder(c); };
    root.idMatches = function (value) { var c = {}; addValue(c, 'idRegex', normalizeRegex(value)); return selectorBuilder(c); };

    root.className = function (value) { var c = {}; addValue(c, 'className', value); return selectorBuilder(c); };
    root.classNameContains = function (value) { var c = {}; addValue(c, 'classNameContains', value); return selectorBuilder(c); };
    root.classNameStartsWith = function (value) { var c = {}; addValue(c, 'classNameStartsWith', value); return selectorBuilder(c); };
    root.classNameEndsWith = function (value) { var c = {}; addValue(c, 'classNameEndsWith', value); return selectorBuilder(c); };
    root.classNameMatches = function (value) { var c = {}; addValue(c, 'classNameRegex', normalizeRegex(value)); return selectorBuilder(c); };

    root.packageName = function (value) { var c = {}; addValue(c, 'package', value); return selectorBuilder(c); };
    root.packageNameContains = function (value) { var c = {}; addValue(c, 'packageContains', value); return selectorBuilder(c); };
    root.packageNameStartsWith = function (value) { var c = {}; addValue(c, 'packageStartsWith', value); return selectorBuilder(c); };
    root.packageNameEndsWith = function (value) { var c = {}; addValue(c, 'packageEndsWith', value); return selectorBuilder(c); };
    root.packageNameMatches = function (value) { var c = {}; addValue(c, 'packageNameRegex', normalizeRegex(value)); return selectorBuilder(c); };

    function booleanBuilder(key, value) {
      var c = {};
      c[key] = value == null ? true : !!value;
      return selectorBuilder(c);
    }

    root.clickable = function (value) { return booleanBuilder('clickable', value); };
    root.longClickable = function (value) { return booleanBuilder('longClickable', value); };
    root.editable = function (value) { return booleanBuilder('editable', value); };
    root.scrollable = function (value) { return booleanBuilder('scrollable', value); };
    root.enabled = function (value) { return booleanBuilder('enabled', value); };
    root.visibleToUser = function (value) { return booleanBuilder('visible', value); };
    root.checkable = function (value) { return booleanBuilder('checkable', value); };
    root.checked = function (value) { return booleanBuilder('checked', value); };
    root.focusable = function (value) { return booleanBuilder('focusable', value); };
    root.focused = function (value) { return booleanBuilder('focused', value); };
    root.selected = function (value) { return booleanBuilder('selected', value); };
    root.password = function (value) { return booleanBuilder('password', value); };

    root.bounds = function (a, b, c, d) {
      return selectorBuilder({ bounds: boundsValue(a, b, c, d) });
    };
    root.boundsInside = function (a, b, c, d) {
      return selectorBuilder({ boundsInside: boundsValue(a, b, c, d) });
    };
    root.boundsContains = function (a, b, c, d) {
      return selectorBuilder({ boundsContains: boundsValue(a, b, c, d) });
    };

    // 让 selector() 可调用，同时保留 selector.find / waitFor 等现有方法。
    var compatSelector = function () { return new Selector(); };
    var copied = {};
    for (var selectorKey in nativeSelector) {
      if (typeof nativeSelector[selectorKey] === 'function') {
        copied[selectorKey] = (function (name) {
          return function () { return nativeSelector[name].apply(nativeSelector, arguments); };
        })(selectorKey);
      }
    }
    [
      'find', 'findAll', 'nth', 'exists', 'count', 'click', 'longClick',
      'parse', 'explain', 'codegen', 'waitFor', 'waitGone', 'scrollIntoView',
      'dumpRaw', 'windowsRaw',
      'scrollForward', 'scrollBackward', 'findByText', 'findAllByText',
      'findByTextContains', 'findByTextRegex', 'findByDesc', 'findByDescContains',
      'findByDescRegex', 'waitForText', 'waitForId', 'waitForTextContains',
      'waitForTextRegex', 'available', 'setTextById', 'getTextById',
      'getTextByText', 'boundsById', 'existsById', 'existsByText', 'existsByDesc',
      'clickById', 'clickByText', 'clickByDesc', 'longClickById',
      'longClickByText', 'longClickByDesc', 'countByText'
    ].forEach(function (name) {
      if (typeof nativeSelector[name] === 'function') {
        compatSelector[name] = copied[name] || function () {
          return nativeSelector[name].apply(nativeSelector, arguments);
        };
      }
    });
    try {
      root.selector = compatSelector;
    } catch (error) {
      warn('selector 兼容门面挂载失败：' + error);
    }
  }

  // ---- 聚焦文本：Auto.js 的 input 追加、setText 替换 ----
  if (available('selector') && typeof root.selector.findAll === 'function') {
    var nativeInput = root.input;
    var compatInput = function (text) {
      var value = String(text == null ? '' : text);
      try {
        var focused = root.selector.findAll({ editable: true, focused: true });
        var target = focused && focused.length ? focused[0] : null;
        if (!target) {
          var editables = root.selector.findAll({ editable: true });
          target = editables && editables.length ? editables[0] : null;
        }
        if (!target) return nativeInput(value);
        var current = target.text == null ? '' : String(target.text);
        var next = current + value;
        if (target.id) return root.selector.setTextById(target.id, next);
        if (root.click((target.left + target.right) / 2, (target.top + target.bottom) / 2)) {
          return nativeInput(next);
        }
        return nativeInput(value);
      } catch (error) {
        return nativeInput(value);
      }
    };
    for (var inputKey in nativeInput) compatInput[inputKey] = nativeInput[inputKey];
    ['notifications', 'quickSettings', 'powerDialog', 'splitScreen', 'lockScreen',
      'dismissNotifications', 'inputWithMethod', 'inputInfo'].forEach(function (name) {
      if (typeof nativeInput[name] === 'function') {
        compatInput[name] = function () { return nativeInput[name].apply(nativeInput, arguments); };
      }
    });
    try {
      root.input = compatInput;
    } catch (error) {
      warn('input 兼容门面挂载失败：' + error);
    }

    root.setText = function (text) {
      var value = String(text == null ? '' : text);
      try {
        var focused = root.selector.findAll({ editable: true, focused: true });
        var target = focused && focused.length ? focused[0] : null;
        if (!target) {
          var editables = root.selector.findAll({ editable: true });
          target = editables && editables.length ? editables[0] : null;
        }
        if (!target) return false;
        if (target.id) return root.selector.setTextById(target.id, value);
        if (!root.click((target.left + target.right) / 2, (target.top + target.bottom) / 2)) {
          return false;
        }
        return nativeInput(value);
      } catch (error) {
        return false;
      }
    };
  }

  // ---- images：Auto.js Image 对象与旧式 findImage / findColor ----
  if (available('images')) {
    var nativeImages = root.images;
    var nativeFindImage = nativeImages.findImage;
    var nativeFindColor = nativeImages.findColor;
    var regionWarned = false;

    function imagePath(image) {
      if (!image) return null;
      if (typeof image === 'string') return image;
      if (image.__image && image.path) return String(image.path);
      return null;
    }

    function imageObject(path) {
      if (!path) return null;
      var size = null;
      try {
        var rawSize = nativeImages.sizeRaw(String(path));
        if (rawSize) size = JSON.parse(rawSize);
      } catch (error) {
        size = null;
      }
      var image = {
        __image: true,
        path: String(path),
        width: size ? Number(size.width) : 0,
        height: size ? Number(size.height) : 0,
        getPath: function () { return this.path; },
        getWidth: function () { return this.width; },
        getHeight: function () { return this.height; },
        save: function (target, quality) {
          return root.images.save(this, target, quality);
        },
        clone: function () { return imageObject(this.path); },
        recycle: function () { return true; },
        getBitmap: function () {
          fail('Image.getBitmap 需要 Java 桥，当前兼容层不提供');
        }
      };
      return image;
    }

    nativeImages.read = function (path) {
      if (path && path.__image) return path;
      var source = imagePath(path);
      if (!source) return null;
      if (available('fs') && typeof root.fs.exists === 'function' && !root.fs.exists(source)) {
        return null;
      }
      return imageObject(source);
    };

    nativeImages.save = function (image, path, quality) {
      var source = imagePath(image);
      if (!source || !path) return false;
      return nativeImages.saveRaw(
        source,
        String(path),
        quality == null ? 100 : Number(quality)
      );
    };

    nativeImages.pixel = function (image, x, y) {
      var source = imagePath(image);
      if (!source) return 0;
      var value = nativeImages.pixelRaw(source, Number(x), Number(y));
      if (!value) return 0;
      // Auto.js 返回 Java int（32 位有符号），与 colors.* 常量可直接比较。
      return parseInt(String(value).substring(1), 16) | 0;
    };

    nativeImages.getSize = function (image) {
      var source = imagePath(image);
      if (!source) return { width: 0, height: 0 };
      var raw = nativeImages.sizeRaw(source);
      if (!raw) return { width: 0, height: 0 };
      try {
        var size = JSON.parse(raw);
        return { width: Number(size.width), height: Number(size.height) };
      } catch (error) {
        return { width: 0, height: 0 };
      }
    };

    nativeImages.clip = function (image, x, y, width, height) {
      var source = imagePath(image);
      if (!source) return null;
      var path = nativeImages.clipToCacheRaw(
        source,
        Number(x),
        Number(y),
        Number(width),
        Number(height)
      );
      return path ? imageObject(path) : null;
    };

    nativeImages.resize = function (image, width, height) {
      var source = imagePath(image);
      if (!source) return null;
      var path = nativeImages.resizeToCacheRaw(source, Number(width), Number(height));
      return path ? imageObject(path) : null;
    };

    nativeImages.findImage = function (first, second, third) {
      // 目标平台原生写法：findImage(templatePath, threshold, region)
      if (typeof first === 'string' && (second == null || typeof second === 'number')) {
        return nativeFindImage(first, second, third);
      }
      var screen = imagePath(first);
      var template = imagePath(second);
      if (!screen || !template) return null;
      var options = third && typeof third === 'object' ? third : {};
      var threshold = third == null
        ? 0.8
        : (typeof third === 'number' ? Number(third) : Number(options.threshold));
      if (!isFinite(threshold)) threshold = 0.8;
      if (options.region && !regionWarned) {
        regionWarned = true;
        warn('images.findImage 的 region 暂不限制到图片内，按整张图片搜索');
      }
      var raw = nativeImages.findImageInRaw(screen, template, threshold);
      if (!raw) return null;
      var parts = String(raw).split(',');
      return {
        x: parseInt(parts[0], 10),
        y: parseInt(parts[1], 10),
        confidence: parseFloat(parts[2])
      };
    };

    nativeImages.findImageInRegion = function (image, template, region, options) {
      var merged = {};
      if (options && typeof options === 'object') {
        for (var key in options) merged[key] = options[key];
      }
      merged.region = region;
      return nativeImages.findImage(image, template, merged);
    };

    nativeImages.findColor = function (first, second, third) {
      // 目标平台原生写法：findColor("#ff0000", threshold, region)
      if (typeof first === 'string' && first.charAt(0) === '#') {
        return nativeFindColor(first, second, third);
      }
      var screen = imagePath(first);
      if (!screen || second == null) return null;
      var options = third && typeof third === 'object' ? third : {};
      var threshold = third == null
        ? 4
        : (typeof third === 'number' ? Number(third) : Number(options.threshold));
      if (!isFinite(threshold)) threshold = 4;
      return nativeImages.findColorInRaw(screen, String(second), threshold);
    };

    nativeImages.captureScreen = function () {
      var image = root.captureScreen("");
      return image && image.__image ? image : imageObject(image);
    };

    root.requestScreenCapture = function () {
      try {
        if (available('permissions') && root.permissions.screenCapture()) return true;
      } catch (error) {
        // 权限查询失败按未授权处理。
      }
      // 兼容层不主动发起录屏授权，避免旧脚本在用户不知情时弹授权框。
      warn('兼容层不会发起录屏授权；请先在「权限与能力」中授权屏幕捕获或开启可用的控制后端');
      return false;
    };

    root.requestScreenCaptureAsync = function (options, callback) {
      var legacy = typeof options === 'function'
        ? options
        : (typeof callback === 'function' ? callback : null);
      var granted = root.requestScreenCapture();
      if (legacy) legacy(granted);
      if (typeof Promise === 'function') {
        return Promise.resolve(granted);
      }
      return granted;
    };
    nativeImages.requestScreenCaptureAsync = root.requestScreenCaptureAsync;

    if (typeof root.captureScreen === 'function') {
      try {
        var nativeCaptureScreen = root.captureScreen;
        root.captureScreen = function () {
          var result = nativeCaptureScreen.apply(null, arguments);
          if (!result) return null;
          return imageObject(result);
        };
      } catch (error) {
        warn('captureScreen 图片门面挂载失败：' + error);
      }
    }
  }

  function screenPoint() {
    var width = available('device') ? Number(root.device.width()) : 1080;
    var height = available('device') ? Number(root.device.height()) : 1920;
    return { width: width, height: height };
  }

  function scrollOnce(down, target) {
    if (typeof root.swipe !== 'function') return false;
    var rect = null;
    if (target && typeof target.bounds === 'function') {
      try { rect = target.bounds(); } catch (ignored) { rect = null; }
    }
    var cx;
    var top;
    var bottom;
    if (rect) {
      cx = (rect.left + rect.right) / 2;
      top = rect.top + (rect.bottom - rect.top) * 0.25;
      bottom = rect.top + (rect.bottom - rect.top) * 0.75;
    } else {
      var screen = screenPoint();
      cx = screen.width / 2;
      top = screen.height * 0.25;
      bottom = screen.height * 0.75;
    }
    return down ? root.swipe(cx, bottom, cx, top, 300) : root.swipe(cx, top, cx, bottom, 300);
  }

  root.scrollDown = function (n) {
    var times = typeof n === 'number' ? n : 1;
    var ok = false;
    for (var i = 0; i < times; i++) ok = scrollOnce(true, n);
    return ok;
  };
  root.scrollUp = function (n) {
    var times = typeof n === 'number' ? n : 1;
    var ok = false;
    for (var i = 0; i < times; i++) ok = scrollOnce(false, n);
    return ok;
  };

  // ---- 全局动作别名 ----
  if (available('input')) {
    ['notifications', 'quickSettings', 'powerDialog', 'splitScreen', 'lockScreen', 'dismissNotifications']
      .forEach(function (name) {
        if (typeof root.input[name] === 'function') {
          root[name] = function () { return root.input[name].apply(root.input, arguments); };
        }
      });
    root.dismissNotificationShade = root.dismissNotifications;
  }

  // ---- files：Path / IO 对象形状 ----
  var filesApi = null;
  if (available('fs')) {
    var fs = root.fs;

    function joinPath() {
      var parts = [];
      for (var i = 0; i < arguments.length; i++) {
        var part = arguments[i];
        if (part == null || String(part).length === 0) continue;
        parts.push(String(part));
      }
      var joined = parts.join('/').replace(/\/+/g, '/');
      if (/^[A-Za-z]:\//.test(joined)) return joined.charAt(0) + ':/' + joined.substring(3);
      return joined;
    }

    function baseName(path) {
      var value = String(path == null ? '' : path).replace(/\/+$/, '');
      var index = value.lastIndexOf('/');
      return index < 0 ? value : value.substring(index + 1);
    }

    function extensionOf(path) {
      var name = baseName(path);
      var index = name.lastIndexOf('.');
      return index <= 0 ? '' : name.substring(index + 1);
    }

    function openFile(path, mode) {
      var target = String(path);
      var option = mode == null ? 'r' : String(mode);
      var readOnly = option.indexOf('r') >= 0 &&
        option.indexOf('w') < 0 && option.indexOf('a') < 0;
      if (readOnly && !fs.exists(target)) fail('文件不存在：' + target);
      var content = readOnly || option.indexOf('a') >= 0 ? (fs.readText(target) || '') : '';
      var position = 0;

      var handle = {
        read: function (size) {
          if (size == null) {
            var rest = content.substring(position);
            position = content.length;
            return rest;
          }
          var end = Math.min(content.length, position + Number(size));
          var chunk = content.substring(position, end);
          position = end;
          return chunk;
        },
        readline: function () {
          if (position >= content.length) return null;
          var index = content.indexOf('\n', position);
          if (index < 0) {
            var tail = content.substring(position);
            position = content.length;
            return tail;
          }
          var line = content.substring(position, index);
          position = index + 1;
          return line;
        },
        readlines: function () {
          var rest = content.substring(position);
          position = content.length;
          return rest.length ? rest.split('\n') : [];
        },
        write: function (text) {
          if (readOnly) fail('文件以只读模式打开：' + target);
          content += String(text == null ? '' : text);
          return true;
        },
        writeline: function (text) {
          return handle.write((text == null ? '' : text) + '\n');
        },
        flush: function () {
          if (!readOnly) fs.writeText(target, content, false);
          return true;
        },
        close: function () { return handle.flush(); },
        getPath: function () { return target; }
      };
      return handle;
    }

    function removeDirRecursive(path) {
      var target = String(path == null ? '' : path);
      if (!fs.exists(target)) return false;
      var raw = null;
      try { raw = fs.list(target); } catch (error) { raw = null; }
      var entries = [];
      if (raw) {
        try { entries = JSON.parse(raw) || []; } catch (error) { entries = []; }
      }
      for (var i = 0; i < entries.length; i++) {
        var entry = entries[i];
        if (!entry || !entry.path) continue;
        if (entry.dir) removeDirRecursive(entry.path);
        else fs.delete(String(entry.path));
      }
      return fs.delete(target);
    }

    filesApi = {
      read: function (path) { return fs.readText(String(path)); },
      write: function (path, text) { return fs.writeText(String(path), String(text), false); },
      createWithDirs: function (path) {
        var target = String(path == null ? '' : path);
        if (!target) return false;
        if (fs.exists(target)) return true;
        var slash = target.lastIndexOf('/');
        if (slash > 0) fs.mkdirs(target.substring(0, slash));
        return fs.writeText(target, '', false);
      },
      append: function (path, text) { return fs.writeText(String(path), String(text), true); },
      exists: function (path) { return fs.exists(String(path)); },
      isFile: function (path) { return fs.exists(String(path)) && !fs.isDirectory(String(path)); },
      isDir: function (path) { return fs.isDirectory(String(path)); },
      ensureDir: function (path) { return fs.mkdirs(String(path)); },
      remove: function (path) { return fs.delete(String(path)); },
      removeDir: removeDirRecursive,
      copy: function (src, dst) { return fs.copy(String(src), String(dst)); },
      move: function (src, dst) { return fs.move(String(src), String(dst)); },
      rename: function (src, dst) { return fs.move(String(src), String(dst)); },
      listDir: function (path, filter) {
        var list = fs.list(String(path));
        if (!list || !list.length) return [];
        var names = list.map(function (entry) { return entry.name; });
        return typeof filter === 'function' ? names.filter(filter) : names;
      },
      join: joinPath,
      path: function (path) { return String(path == null ? '' : path); },
      cwd: function () { return '/sdcard'; },
      // Auto.js 的 sdcard 路径；Android 上 /sdcard 是指向实际存储的符号链接。
      getSdcardPath: function () { return '/sdcard'; },
      getName: baseName,
      getNameWithoutExtension: function (path) {
        var name = baseName(path);
        var index = name.lastIndexOf('.');
        return index <= 0 ? name : name.substring(0, index);
      },
      getExtension: extensionOf,
      open: openFile
    };
    root.files = filesApi;
  }

  // ---- storages：命名键值存储 ----
  if (available('storages') && typeof root.storages.getRaw === 'function') {
    root.storages.create = function (name) {
      var store = String(name == null ? 'default' : name);
      return {
        get: function (key, defaultValue) {
          var raw = root.storages.getRaw(store, String(key));
          if (raw == null) return defaultValue;
          try { return JSON.parse(raw); } catch (error) { return raw; }
        },
        put: function (key, value) {
          var raw = JSON.stringify(value);
          if (raw == null) return false;
          return root.storages.putRaw(store, String(key), raw);
        },
        remove: function (key) { return root.storages.removeRaw(store, String(key)); },
        contains: function (key) { return root.storages.containsRaw(store, String(key)); },
        clear: function () { return root.storages.clearRaw(store); },
        keys: function () {
          var raw = root.storages.keysRaw(store);
          try { return JSON.parse(raw); } catch (error) { return []; }
        }
      };
    };
  }

  // ---- events.on：用 100ms 轮询桥接无障碍事件缓冲 ----
  if (available('events') && typeof root.events.list === 'function') {
    var eventListeners = {};
    var eventCursor = null;
    var eventInterval = null;

    function eventTypesFor(name) {
      if (name === 'key') return ['key_down', 'key_up'];
      if (name === 'key_down' || name === 'key_up') return [name];
      if (name === 'notification' || name === 'toast') return [name];
      if (name === 'window_state' || name === 'window_content') return [name];
      return [String(name)];
    }

    function eventListenerCount() {
      var total = 0;
      for (var name in eventListeners) {
        if (Object.prototype.hasOwnProperty.call(eventListeners, name)) {
          total += eventListeners[name].length;
        }
      }
      return total;
    }

    function ensureEventCursor() {
      if (eventCursor != null) return;
      try {
        var status = root.events.status();
        eventCursor = status && status.seq != null ? Number(status.seq) : 0;
      } catch (error) {
        eventCursor = 0;
      }
    }

    function eventPayload(event) {
      var payload = {
        type: event.type,
        seq: event.seq,
        packageName: event.package,
        className: event.className,
        text: event.text,
        contentDescription: event.desc,
        windowId: event.windowId,
        time: event.time,
        keyCode: event.keyCode,
        getAction: function () { return this.type === 'key_up' ? 1 : 0; },
        getKeyCode: function () { return this.keyCode; },
        getPackageName: function () { return this.packageName; },
        getClassName: function () { return this.className; },
        getText: function () { return this.text; }
      };
      return payload;
    }

    function dispatchEvent(event) {
      var names = [event.type];
      if (event.type === 'key_down' || event.type === 'key_up') names.push('key');
      for (var n = 0; n < names.length; n++) {
        var list = eventListeners[names[n]];
        if (!list || !list.length) continue;
        var snapshot = list.slice();
        for (var i = 0; i < snapshot.length; i++) {
          var entry = snapshot[i];
          if (entry.once) {
            var index = list.indexOf(entry);
            if (index >= 0) list.splice(index, 1);
          }
          var payload = eventPayload(event);
          if (event.type === 'key_down' || event.type === 'key_up') {
            entry.callback(payload.keyCode, payload);
          } else {
            entry.callback(payload);
          }
        }
      }
    }

    function dispatchEvents() {
      if (eventListenerCount() === 0) return;
      ensureEventCursor();
      var types = [];
      for (var name in eventListeners) {
        if (!Object.prototype.hasOwnProperty.call(eventListeners, name)) continue;
        var mapped = eventTypesFor(name);
        for (var i = 0; i < mapped.length; i++) {
          if (types.indexOf(mapped[i]) < 0) types.push(mapped[i]);
        }
      }
      if (types.length === 0) return;
      var list = root.events.list({
        sinceSeq: eventCursor,
        types: types,
        limit: 50
      }) || [];
      var ordered = list.slice().reverse();
      for (var j = 0; j < ordered.length; j++) {
        var event = ordered[j];
        if (event.seq != null) eventCursor = Math.max(eventCursor, Number(event.seq));
        dispatchEvent(event);
      }
    }

    function ensureEventPolling() {
      if (eventInterval != null) return;
      eventInterval = root.setInterval(dispatchEvents, 100);
    }

    function stopEventPollingIfIdle() {
      if (eventInterval != null && eventListenerCount() === 0) {
        root.clearInterval(eventInterval);
        eventInterval = null;
      }
    }

    root.events.on = function (name, callback) {
      if (typeof callback !== 'function') return callback;
      if (eventListenerCount() === 0) ensureEventCursor();
      var key = String(name);
      if (!eventListeners[key]) eventListeners[key] = [];
      eventListeners[key].push({ callback: callback, once: false });
      ensureEventPolling();
      return callback;
    };

    root.events.once = function (name, callback) {
      if (typeof callback !== 'function') return callback;
      if (eventListenerCount() === 0) ensureEventCursor();
      var key = String(name);
      if (!eventListeners[key]) eventListeners[key] = [];
      eventListeners[key].push({ callback: callback, once: true });
      ensureEventPolling();
      return callback;
    };

    root.events.removeListener = function (name, callback) {
      var key = String(name);
      var list = eventListeners[key];
      if (!list) return false;
      if (typeof callback !== 'function') {
        delete eventListeners[key];
      } else {
        for (var i = list.length - 1; i >= 0; i--) {
          if (list[i].callback === callback) list.splice(i, 1);
        }
        if (!list.length) delete eventListeners[key];
      }
      stopEventPollingIfIdle();
      return true;
    };

    root.events.removeAllListeners = function (name) {
      if (name == null) {
        eventListeners = {};
      } else {
        delete eventListeners[String(name)];
      }
      stopEventPollingIfIdle();
      return true;
    };

    function keyCodeOf(key) {
      if (typeof key === 'number') return key;
      var name = String(key == null ? '' : key).toLowerCase();
      var table = root.keys || {};
      if (typeof table[name] === 'number') return table[name];
      var numeric = Number(name);
      return isFinite(numeric) ? numeric : null;
    }

    function onKeyEvent(type, key, callback) {
      if (typeof key === 'function' && callback == null) {
        callback = key;
        key = null;
      }
      if (typeof callback !== 'function') return callback;
      var target = key == null ? null : keyCodeOf(key);
      if (key != null && target == null) {
        warn('未知按键名: ' + key);
        return callback;
      }
      return root.events.on(type, function (code, event) {
        if (target == null || code === target) callback(event);
      });
    }

    root.events.observeKey = function () {
      if (!available('permissions') || !root.permissions.accessibility()) {
        warn('events.observeKey 需要开启无障碍服务');
        return false;
      }
      return true;
    };
    root.events.observeNotification = function () { return root.events.observeKey(); };
    root.events.observeToast = function () { return root.events.observeKey(); };
    root.events.onKeyDown = function (key, callback) {
      return onKeyEvent('key_down', key, callback);
    };
    root.events.onKeyUp = function (key, callback) {
      return onKeyEvent('key_up', key, callback);
    };
    root.events.onNotification = function (callback) {
      return root.events.on('notification', callback);
    };
    root.events.onToast = function (callback) { return root.events.on('toast', callback); };
    root.events.setTouchEventTimeout = function () { return true; };
    root.events.observeTouch = function () {
      warn('events.observeTouch 需要原始触摸事件，当前无障碍后端不提供');
      return false;
    };
  }

  // ---- dialogs：阻塞对话框 ----
  if (available('dialogs') && typeof root.dialogs.showRaw === 'function') {
    function showDialog(type, options, callback) {
      var payload = options && typeof options === 'object' ? options : {};
      var timeout = payload.timeoutMs == null ? 0 : Number(payload.timeoutMs);
      var raw = root.dialogs.showRaw(type, JSON.stringify(payload), timeout);
      var result;
      try { result = JSON.parse(raw); } catch (error) { result = { ok: false, action: 'error' }; }
      var value;
      if (!result || !result.ok) {
        value = null;
      } else if (type === 'confirm') {
        value = result.action === 'positive';
      } else if (type === 'prompt') {
        value = result.action === 'positive' ? (result.text == null ? '' : String(result.text)) : null;
      } else if (type === 'select') {
        value = result.action === 'positive' ? result.index : null;
      } else if (type === 'multiChoice') {
        value = result.action === 'positive' ? result.indices : null;
      } else {
        value = result.action === 'positive' ? (result.text == null ? true : result.text) : null;
      }
      if (typeof callback === 'function') callback(value);
      return value;
    }

    root.dialogs.alert = function (title, message, callback) {
      return showDialog('alert', { title: title, message: message }, callback);
    };
    root.dialogs.confirm = function (title, message, callback) {
      return showDialog('confirm', { title: title, message: message }, callback);
    };
    root.dialogs.prompt = function (title, message, defaultValue, callback) {
      if (typeof defaultValue === 'function') {
        callback = defaultValue;
        defaultValue = '';
      }
      return showDialog(
        'prompt',
        { title: title, message: message, defaultValue: defaultValue == null ? '' : String(defaultValue) },
        callback
      );
    };
    root.dialogs.select = function (title, items, callback) {
      return showDialog('select', { title: title, items: items || [] }, callback);
    };
    root.dialogs.multiChoice = function (title, items, selectedIndices, callback) {
      if (typeof selectedIndices === 'function') {
        callback = selectedIndices;
        selectedIndices = [];
      }
      return showDialog(
        'multiChoice',
        { title: title, items: items || [], selectedIndices: selectedIndices || [] },
        callback
      );
    };
    root.dialogs.singleChoice = function (title, items, index, callback) {
      if (typeof index === 'function') {
        callback = index;
        index = null;
      }
      return showDialog('select', { title: title, items: items || [] }, callback);
    };
    root.dialogs.rawInput = function (title, prefill, callback) {
      if (typeof prefill === 'function') {
        callback = prefill;
        prefill = '';
      }
      return showDialog(
        'prompt',
        { title: title, defaultValue: prefill == null ? '' : String(prefill) },
        callback
      );
    };
    root.dialogs.input = function (title, prefill, callback) {
      if (typeof prefill === 'function') {
        callback = prefill;
        prefill = '';
      }
      var text = root.dialogs.rawInput(title, prefill);
      if (text == null) {
        if (typeof callback === 'function') callback(null);
        return null;
      }
      var value = text;
      try { value = (0, eval)(String(text)); } catch (error) { value = text; }
      if (typeof callback === 'function') callback(value);
      return value;
    };

    root.alert = root.dialogs.alert;
    root.confirm = root.dialogs.confirm;
    root.prompt = root.dialogs.prompt;
    root.select = root.dialogs.select;
    root.multiChoice = root.dialogs.multiChoice;
  }

  // ---- http：把当前结果对象包装成 Auto.js 的 response 形状 ----
  if (available('http') && typeof root.http.request === 'function') {
    var nativeHttpRequest = root.http.request;

    function wrapHttpResponse(response) {
      if (!response || typeof response !== 'object') return response;
      if (response.body && typeof response.body.string === 'function') return response;
      var text = response.body == null ? '' : String(response.body);
      response.statusCode = response.status;
      response.statusMessage = response.statusText || '';
      response.body = {
        string: function () { return text; },
        json: function () { return JSON.parse(text); },
        contentType: function () {
          var headers = response.headers || {};
          return headers['Content-Type'] || headers['content-type'] || '';
        },
        bytes: function () { fail('兼容层暂不支持 body.bytes()'); }
      };
      return response;
    }

    root.http.request = function (options) {
      return wrapHttpResponse(nativeHttpRequest(options));
    };

    function withCallback(fn) {
      return function () {
        var args = Array.prototype.slice.call(arguments);
        var callback = typeof args[args.length - 1] === 'function' ? args.pop() : null;
        var result = fn.apply(root.http, args);
        if (callback) callback(result);
        return result;
      };
    }

    if (typeof root.http.get === 'function') root.http.get = withCallback(root.http.get);
    if (typeof root.http.post === 'function') root.http.post = withCallback(root.http.post);
    if (typeof root.http.postJson === 'function') {
      root.http.postJson = withCallback(root.http.postJson);
    }
  }

  // ---- auto：无障碍服务门面 ----
  if (available('permissions') && typeof root.permissions.accessibility === 'function') {
    function accessibilityReady() {
      try { return !!root.permissions.accessibility(); } catch (error) { return false; }
    }

    function selectorMethod(name) {
      return available('selector') && typeof root.selector[name] === 'function';
    }

    /** 把 windowsRaw() 的数组包装成 Auto.js 的 AccessibilityWindowInfo 形状。 */
    function windowList() {
      if (!accessibilityReady()) return [];
      if (!selectorMethod('windowsRaw')) return [];
      var raw;
      try {
        raw = root.selector.windowsRaw();
      } catch (error) {
        return [];
      }
      var parsed;
      try { parsed = JSON.parse(raw || '[]'); } catch (error) { return []; }
      if (!parsed || !parsed.length) return [];
      return parsed.map(function (item) {
        var bounds = item.bounds;
        return {
          getType: function () { return item.type; },
          isActive: function () { return !!item.active; },
          isFocused: function () { return !!item.focused; },
          isAccessibilityFocused: function () { return !!item.accessibilityFocused; },
          getLayer: function () { return item.layer; },
          getId: function () { return item.id; },
          getPackageName: function () { return item.packageName; },
          getBounds: function () {
            return bounds == null ? null : {
              left: bounds[0], top: bounds[1], right: bounds[2], bottom: bounds[3]
            };
          },
          // 节点句柄尚未实现；窗口的 package / bounds 已由兼容层填好。
          getRoot: function () { return null; }
        };
      });
    }

    function treeLines(node, depth, lines) {
      if (!node) return;
      var indent = new Array(depth + 1).join('  ');
      var bounds = node.bounds;
      var rect = bounds
        ? '[' + bounds.left + ',' + bounds.top + '][' + bounds.right + ',' + bounds.bottom + ']'
        : '[]';
      var parts = [indent + (node.className || 'node')];
      if (node.id) parts.push('id=' + node.id);
      if (node.text != null) parts.push('text=' + JSON.stringify(String(node.text)));
      if (node.desc != null) parts.push('desc=' + JSON.stringify(String(node.desc)));
      parts.push('bounds=' + rect);
      if (node.clickable) parts.push('clickable=true');
      if (node.scrollable) parts.push('scrollable=true');
      if (node.editable) parts.push('editable=true');
      lines.push(parts.join(' '));
      var children = node.children || [];
      for (var i = 0; i < children.length; i++) treeLines(children[i], depth + 1, lines);
    }

    function xmlEscape(value) {
      return String(value)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;');
    }

    function treeXml(node, depth, lines) {
      if (!node) return;
      var indent = new Array(depth + 1).join('  ');
      var attrs = ['class="' + xmlEscape(node.className || '') + '"'];
      if (node.id) attrs.push('resource-id="' + xmlEscape(node.id) + '"');
      if (node.packageName) attrs.push('package="' + xmlEscape(node.packageName) + '"');
      if (node.text != null) attrs.push('text="' + xmlEscape(node.text) + '"');
      if (node.desc != null) attrs.push('content-desc="' + xmlEscape(node.desc) + '"');
      if (node.bounds) {
        attrs.push('bounds="[' + node.bounds.left + ',' + node.bounds.top + '][' +
          node.bounds.right + ',' + node.bounds.bottom + ']"');
      }
      attrs.push('clickable="' + !!node.clickable + '"');
      attrs.push('enabled="' + !!node.enabled + '"');
      var children = node.children || [];
      if (!children.length) {
        lines.push(indent + '<node ' + attrs.join(' ') + ' />');
        return;
      }
      lines.push(indent + '<node ' + attrs.join(' ') + '>');
      for (var i = 0; i < children.length; i++) treeXml(children[i], depth + 1, lines);
      lines.push(indent + '</node>');
    }

    var autoFacade = function () {
      if (accessibilityReady()) return true;
      fail('需要开启无障碍服务；请在「权限与能力」中授权后重试');
    };
    autoFacade.waitFor = function (timeoutMs) {
      var timeout = timeoutMs == null ? 10000 : Number(timeoutMs);
      var infinite = timeout < 0;
      var deadline = infinite ? Infinity : Date.now() + timeout;
      for (;;) {
        if (accessibilityReady()) return true;
        if (root.isStopped()) throw new Error('IZUKI_SCRIPT_EXIT');
        if (!infinite && Date.now() >= deadline) return false;
        root.sleep(200);
      }
    };
    autoFacade.setMode = function () { return true; };
    autoFacade.setFlags = function () { return true; };
    autoFacade.isRunning = accessibilityReady;
    // 兼容层不修改系统无障碍开关，避免旧脚本静默改权限。
    autoFacade.enable = function () {
      fail('兼容层不会自动开启无障碍；请在系统设置中授权后重试');
    };
    autoFacade.disable = function () {
      fail('兼容层不会自动关闭无障碍；请在系统设置中操作');
    };
    autoFacade.explain = function (target) {
      if (!selectorMethod('explain')) {
        fail('auto.explain 需要 selector 能力；请确认无障碍已开启，且未通过 @need 关闭 selector');
      }
      var criteria = target;
      if (target && typeof target === 'object' && target._criteria) {
        criteria = target._criteria;
      }
      return root.selector.explain(criteria);
    };

    var serviceFacade = {
      __compat: true,
      getWindows: windowList,
      getRootInActiveWindow: function () {
        fail('auto.service.getRootInActiveWindow 需要节点句柄；请改用 auto.dump 读取控件树');
      }
    };

    // service 与 windows 都做成 getter：无障碍可能在中途才授权。
    Object.defineProperty(autoFacade, 'service', {
      enumerable: true,
      get: function () { return accessibilityReady() ? serviceFacade : null; }
    });
    Object.defineProperty(autoFacade, 'windows', {
      enumerable: true,
      get: windowList
    });

    autoFacade.root = null;
    autoFacade.rootInActiveWindow = null;
    autoFacade.windowRoots = null;

    /** Auto.js 的控件树 dump；格式转换在 JS 侧完成，原生只负责取树。 */
    autoFacade.dump = function (options) {
      var opts = options && typeof options === 'object' ? options : {};
      var format = opts.format == null ? 'json' : String(opts.format).toLowerCase();
      if (!selectorMethod('dumpRaw')) {
        fail('auto.dump 需要 selector 能力；请确认无障碍已开启，且未通过 @need 关闭 selector');
      }
      var raw = root.selector.dumpRaw(JSON.stringify({
        maxDepth: opts.maxDepth,
        maxNodes: opts.maxNodes,
        visibleOnly: opts.visibleOnly,
        properties: opts.properties
      }));
      var parsed;
      try {
        parsed = JSON.parse(raw);
      } catch (error) {
        fail('auto.dump 返回无法解析: ' + error);
      }
      if (!parsed || parsed.ok === false) {
        fail('auto.dump 失败: ' + (parsed && parsed.error ? parsed.error : '未知原因'));
      }
      if (format === 'json') return JSON.stringify(parsed.tree);
      if (format === 'text') {
        var lines = [];
        treeLines(parsed.tree, 0, lines);
        return lines.join('\n');
      }
      if (format === 'xml') {
        var xmlLines = ['<?xml version="1.0" encoding="UTF-8"?>'];
        treeXml(parsed.tree, 0, xmlLines);
        return xmlLines.join('\n');
      }
      fail('不支持的 auto.dump 格式: ' + format);
    };

    root.auto = autoFacade;
  }

  // ---- sensors：把原生事件缓冲轮询成 Auto.js 的 change 回调 ----
  if (available('sensors') && typeof root.sensors.registerRaw === 'function') {
    var sensorEmitters = {};
    var sensorRoot = {};
    var sensorInterval = null;

    function sensorRootListeners(name) {
      if (!sensorRoot[name]) sensorRoot[name] = [];
      return sensorRoot[name];
    }

    function sensorEmit(name, args) {
      var list = sensorRootListeners(name).slice();
      for (var i = 0; i < list.length; i++) list[i].apply(null, args);
    }

    function sensorPayload(item) {
      var values = item.values || [];
      return {
        values: values,
        accuracy: item.accuracy,
        timestamp: item.timestamp,
        sensor: { getName: function () { return item.name; } },
        getValues: function () { return values.slice(); },
        toString: function () {
          return 'SensorEvent(' + item.name + ': ' + values.join(', ') + ')';
        }
      };
    }

    function pollSensors() {
      var raw;
      try { raw = root.sensors.pollRaw(); } catch (error) { return; }
      var list;
      try { list = JSON.parse(raw || '[]'); } catch (error) { return; }
      for (var i = 0; i < list.length; i++) {
        var item = list[i];
        if (!item || !item.id) continue;
        var emitter = sensorEmitters[item.id];
        if (item.kind === 'accuracy') {
          if (emitter) emitter._emitAccuracy(item.accuracy);
          continue;
        }
        var payload = sensorPayload(item);
        var args = [payload].concat(item.values || []);
        if (emitter) emitter._emitChange(args);
        sensorEmit('sensor_event', args);
      }
    }

    function ensureSensorPolling() {
      if (sensorInterval != null) return;
      sensorInterval = root.setInterval(pollSensors, 50);
    }

    function stopSensorPollingIfIdle() {
      if (sensorInterval != null && Object.keys(sensorEmitters).length === 0) {
        root.clearInterval(sensorInterval);
        sensorInterval = null;
      }
    }

    function SensorEmitter(id, name) {
      this._id = id;
      this._name = name;
      this._change = [];
      this._accuracy = [];
    }
    SensorEmitter.prototype._add = function (event, callback, once) {
      if (typeof callback !== 'function') return this;
      var list = event === 'accuracy_change' ? this._accuracy : this._change;
      if (once) {
        var wrapper = function () {
          var index = list.indexOf(wrapper);
          if (index >= 0) list.splice(index, 1);
          callback.apply(null, arguments);
        };
        list.push(wrapper);
      } else {
        list.push(callback);
      }
      return this;
    };
    SensorEmitter.prototype.on = function (event, callback) {
      return this._add(event, callback, false);
    };
    SensorEmitter.prototype.once = function (event, callback) {
      return this._add(event, callback, true);
    };
    SensorEmitter.prototype.removeListener = function (event, callback) {
      var list = event === 'accuracy_change' ? this._accuracy : this._change;
      for (var i = list.length - 1; i >= 0; i--) {
        if (list[i] === callback) list.splice(i, 1);
      }
      return this;
    };
    SensorEmitter.prototype._emitChange = function (args) {
      var list = this._change.slice();
      for (var i = 0; i < list.length; i++) list[i].apply(null, args);
    };
    SensorEmitter.prototype._emitAccuracy = function (accuracy) {
      var list = this._accuracy.slice();
      for (var i = 0; i < list.length; i++) list[i](accuracy);
    };
    SensorEmitter.prototype.unregister = function () {
      if (this._id && sensorEmitters[this._id]) {
        try { root.sensors.unregisterRaw(this._id); } catch (error) { /* 已注销 */ }
        delete sensorEmitters[this._id];
      }
      stopSensorPollingIfIdle();
      return true;
    };

    function noopSensorEmitter(name) {
      var emitter = new SensorEmitter(null, name);
      emitter.unregister = function () { return false; };
      return emitter;
    }

    root.sensors.delay = root.sensors.delay || {
      normal: 3,
      ui: 2,
      game: 1,
      fastest: 0
    };
    if (root.sensors.ignoresUnsupportedSensor == null) {
      root.sensors.ignoresUnsupportedSensor = false;
    }
    root.sensors.register = function (name, delay, callback) {
      var sensorName = String(name == null ? '' : name);
      if (typeof delay === 'function') {
        callback = delay;
        delay = null;
      }
      var sampling = delay == null ? root.sensors.delay.normal : Number(delay);
      if (!isFinite(sampling)) sampling = root.sensors.delay.normal;
      var result;
      try {
        result = JSON.parse(root.sensors.registerRaw(sensorName, sampling));
      } catch (error) {
        result = { ok: false, error: String(error) };
      }
      if (!result || result.ok === false) {
        var reason = result && result.error ? result.error : '不支持的传感器';
        if (root.sensors.ignoresUnsupportedSensor) {
          sensorEmit('unsupported_sensor', [sensorName, result && result.type]);
          return noopSensorEmitter(sensorName);
        }
        warn('sensors.register 失败: ' + reason);
        return null;
      }
      var emitter = new SensorEmitter(result.id, result.name || sensorName);
      sensorEmitters[result.id] = emitter;
      ensureSensorPolling();
      if (typeof callback === 'function') emitter.on('change', callback);
      return emitter;
    };
    root.sensors.unregister = function (emitter) {
      if (emitter && typeof emitter.unregister === 'function') return emitter.unregister();
      return false;
    };
    root.sensors.unregisterAll = function () {
      var ids = Object.keys(sensorEmitters);
      for (var i = 0; i < ids.length; i++) {
        var emitter = sensorEmitters[ids[i]];
        if (emitter) emitter.unregister();
      }
      try { root.sensors.unregisterAllRaw(); } catch (error) { /* 已注销 */ }
      stopSensorPollingIfIdle();
      return true;
    };
    ['on', 'once', 'removeListener'].forEach(function (method) {
      root.sensors[method] = function (event, callback) {
        var list = sensorRootListeners(String(event));
        if (method === 'removeListener') {
          for (var i = list.length - 1; i >= 0; i--) {
            if (list[i] === callback) list.splice(i, 1);
          }
          return root.sensors;
        }
        if (typeof callback !== 'function') return root.sensors;
        if (method === 'once') {
          var wrapper = function () {
            var index = list.indexOf(wrapper);
            if (index >= 0) list.splice(index, 1);
            callback.apply(null, arguments);
          };
          list.push(wrapper);
        } else {
          list.push(callback);
        }
        return root.sensors;
      };
    });
  }

  // ---- automator：把常用全局动作收成一个命名空间 ----
  var automatorApi = {};
  ['click', 'longClick', 'swipe', 'press', 'gesture', 'gestures',
    'back', 'home', 'recents', 'notifications', 'quickSettings',
    'powerDialog', 'splitScreen', 'scrollUp', 'scrollDown'].forEach(function (name) {
    if (typeof root[name] === 'function') {
      automatorApi[name] = function () { return root[name].apply(root, arguments); };
    }
  });
  if (typeof root.captureScreen === 'function') {
    automatorApi.captureScreen = function () {
      return root.captureScreen.apply(root, arguments);
    };
  }
  root.automator = automatorApi;

  // ---- require：内建名映射到全局对象 ----
  if (typeof root.require === 'function') {
    var nativeRequire = root.require;
    var builtinNames = [
      'images', 'device', 'app', 'http', 'events', 'floaty', 'selector',
      'fs', 'files', 'clipboard', 'ocr', 'notify', 'pipeline', 'virtualScreen',
      'automator', 'storages', 'media', 'sensors', 'colors'
    ];
    try {
      root.require = function (name) {
        var key = String(name);
        if (builtinNames.indexOf(key) >= 0 && typeof root[key] !== 'undefined') {
          return root[key];
        }
        return nativeRequire.apply(this, arguments);
      };
    } catch (error) {
      warn('require 兼容门面挂载失败：' + error);
    }
  }
})(typeof globalThis !== 'undefined' ? globalThis : this);
