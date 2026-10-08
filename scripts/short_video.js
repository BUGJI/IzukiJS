// 短视频自动浏览脚本（点赞与停留分离版）
var ops = require("basic_ops");

// ==================== 全局常量 ====================
var RUN_MINUTES = 30;
var START_DELAY_SECONDS = 10;

// 负面关键词：命中后快速划过
var NEGATIVE_KEYWORDS = [
  "广告", "推广", "赞助", "合作",
  "加微信", "加v", "加vx", "威信",
  "私信", "带货", "橱窗", "小黄车", "链接在下方",
  "点赞关注", "女"
];

// 正面关键词：命中后停留观看（决定"看多久"）
var POSITIVE_KEYWORDS = [
  "教程", "教学", "学会", "oc",
  "科普", "电影", "短剧"
];

// ==================== 点赞规则（独立于停留时长） ====================
// [核心改动] 点赞只看这些词，与 POSITIVE_KEYWORDS 完全解耦。
// 命中任意一个强点赞词 → 双击点赞；但停多久由下面的 CATEGORIES 决定。
var LIKE_KEYWORDS = ["猫", "作品集", "风景", "中国","oc"];

// 高噪声词：单独出现不触发点赞，仅当同时命中强点赞词时才一并计入（用于日志）
var WEAK_LIKE_KEYWORDS = [];

// 是否点赞的判定，返回命中的点赞词数组（空数组 = 不点赞）
function detectLike(text) {
  var strong = hitsIn(text, LIKE_KEYWORDS);
  if (strong.length === 0) return [];
  return strong.concat(hitsIn(text, WEAK_LIKE_KEYWORDS));
}

// ==================== 停留时长分类（独立于点赞） ====================
// [核心改动] 这里不再有 like 类别。停留时长只由内容性质决定。
var PRIORITY = {
  negative: 100,
  positive: 10,
  none: 0
};

var CATEGORIES = [
  { name: "positive", weight: 10, wait: [60000, 90000], keywords: POSITIVE_KEYWORDS },
  { name: "negative", weight: 20, wait: [1500, 3000],   keywords: NEGATIVE_KEYWORDS },
  { name: "none",     weight: 0,  wait: [5000, 15000],  keywords: [] }
];

var CATEGORY_BY_NAME = {};
for (var _i = 0; _i < CATEGORIES.length; _i++) {
  CATEGORY_BY_NAME[CATEGORIES[_i].name] = CATEGORIES[_i];
}

var w = device.width();
var h = device.height();

// ==================== 交互区域 ====================
// 点赞与滑动都集中在屏幕右下角 2/3 处，并叠加随机抖动
var AREA_CX = w * 0.70;         // 锚点 X
var AREA_CY = h * 0.75;         // 锚点 Y
var AREA_X_JITTER = w * 0.09;   // 横向随机半径
var AREA_Y_JITTER = h * 0.05;   // 纵向随机半径

// 是否支持复杂轨迹手势（不支持时回退为 swipe / click）
var hasGesture = (typeof gesture === "function");

// ==================== 文本归一化与匹配 ====================
function normalize(text) {
  return text
    .replace(/\s+/g, "")
    .replace(/[，。！？、,.!?；;：:""''（）()【】\[\]]/g, "");
}

function hitsIn(text, keywords) {
  var found = [];
  for (var i = 0; i < keywords.length; i++) {
    if (text.indexOf(keywords[i]) >= 0) found.push(keywords[i]);
  }
  return found;
}

function matchCategory(text, cat) {
  if (cat.matcher) return cat.matcher(text);
  if (!cat.keywords || cat.keywords.length === 0) return [];
  return hitsIn(text, cat.keywords);
}

function pickCategory(text) {
  var best = CATEGORY_BY_NAME["none"];
  var bestHits = [];
  var bestPri = PRIORITY[best.name];
  for (var i = 0; i < CATEGORIES.length; i++) {
    var cat = CATEGORIES[i];
    var pri = PRIORITY[cat.name];
    if (pri < bestPri) continue;
    var hits = matchCategory(text, cat);
    if (hits.length > 0) {
      best = cat;
      bestHits = hits;
      bestPri = pri;
    }
  }
  return { category: best, hits: bestHits };
}

// ==================== 独立方法 ====================
// 在右下角锚点附近取一个随机点
function areaPoint() {
  return {
    x: randInt(AREA_CX - AREA_X_JITTER, AREA_CX + AREA_X_JITTER),
    y: randInt(AREA_CY - AREA_Y_JITTER, AREA_CY + AREA_Y_JITTER)
  };
}

// 单击（优先 gesture，不支持时回退 click）
function tap(x, y, dur) {
  if (hasGesture) {
    gesture([[{ x: x, y: y, t: 0 }, { x: x, y: y, t: dur }]]);
  } else {
    click(x, y);
  }
}

// 向上滑动刷下一个视频：右下角区域起手，短行程快滑，整条轨迹留在下半屏
function swipeUp() {
  var from = areaPoint();
  var toY = from.y - randInt(h * 0.15, h * 0.25);
  var toX = from.x + randInt(-w * 0.02, w * 0.02);
  var midX = (from.x + toX) / 2 + randInt(-w * 0.03, w * 0.03);
  var midY = (from.y + toY) / 2;
  var dur = randInt(200, 350);

  if (hasGesture) {
    gesture([[
      { x: from.x, y: from.y, t: 0 },
      { x: midX, y: midY, t: Math.round(dur / 2) },
      { x: toX, y: toY, t: dur }
    ]]);
  } else {
    // 回退：普通直线滑动
    swipe(from.x, from.y, toX, toY, dur);
  }
}

// 双击右下角区域点赞
function doubleTap() {
  var p = areaPoint();
  tap(p.x, p.y, randInt(10, 20));
  sleep(randInt(60, 140));
  tap(p.x, p.y, randInt(10, 20));
}

// ==================== 工具函数 ====================
function randInt(min, max) {
  min = Math.ceil(min);
  max = Math.floor(max);
  return Math.floor(Math.random() * (max - min + 1)) + min;
}

function sleepInterruptible(totalMs, deadline) {
  var end = Date.now() + totalMs;
  while (Date.now() < end) {
    if (deadline && Date.now() >= deadline) return false;
    var remain = end - Date.now();
    sleep(remain > 500 ? 500 : remain);
  }
  return true;
}

// ==================== 主流程 ====================
function main() {
  var deadlineMs = RUN_MINUTES * 60 * 1000;
  var watched = 0;

  log("请在 " + START_DELAY_SECONDS + " 秒内打开目标 APP...");
  toast("请在 " + START_DELAY_SECONDS + " 秒内打开目标 APP");
  sleep(START_DELAY_SECONDS * 1000);

  var startTime = Date.now();
  var deadline = startTime + deadlineMs;

  log("开始运行，计划 " + RUN_MINUTES + " 分钟");
  toast("开始运行，计划 " + RUN_MINUTES + " 分钟");

  while (Date.now() < deadline) {
    swipeUp();
    sleep(600);
    watched++;

    var text = "";
    try {
      text = normalize(ocr.recognize() || "");
    } catch (e) {
      warn("OCR 识别失败：" + e);
    }

    // ---- 维度 1：停多久 ----
    var picked = pickCategory(text);
    var cat = picked.category;

    // 负面一票否决
    var negHits = hitsIn(text, NEGATIVE_KEYWORDS);
    if (negHits.length > 0) {
      cat = CATEGORY_BY_NAME["negative"];
      picked = { category: cat, hits: negHits };
    }

    // ---- 维度 2：点不点赞（完全独立） ----
    var likeHits = detectLike(text);
    var willLike = (likeHits.length > 0) && cat.name !== "negative";
    // [优化] 广告即使命中"猫"也不点赞；想连广告也点赞就把 && 后面删掉
    if (willLike) {
      doubleTap();
    }

    var waitMs = randInt(cat.wait[0], cat.wait[1]);

    log("第 " + watched + " 个视频" +
        " | 停留分类=" + cat.name +
        " | 停留命中=" + picked.hits.join("、") +
        (willLike ? " | 已点赞(" + likeHits.join("、") + ")" : "") +
        " | 等待 " + waitMs + "ms" +
        " | 剩余 " + Math.max(0, Math.round((deadline - Date.now()) / 1000)) + "s");

    if (!sleepInterruptible(waitMs, deadline)) break;
  }

  ops.backToHome();
  log("运行时间到，共浏览 " + watched + " 个视频，已返回桌面");
  exit();
}

main();