# 性能基线

项目内置了一套轻量性能采集器 `PerfMetrics`，用于对比优化前后的运行速度与界面流畅性。
默认关闭，开启后每 30 秒向 logcat 输出一个统计窗口，输出后清空窗口，便于“一次只观察一个场景”。

## 开启

1. 打开 **设置 → 运行 → 性能基线**，启用「启用性能基线采集」。
2. 保持悬浮窗开关与脚本运行方式不变，按下面的场景操作。
3. 采集完成后关闭开关。

也可以在开启状态下点击「立即输出本次统计」，手动结束当前窗口。

## 查看

```bash
adb logcat -c
adb logcat -s IzukiPerf
```

每次输出类似：

```text
=== report session=30s device=... sdk=... slowFrame=17.7ms frames=1800 jank=42(2.3%) java=...MB native=...MB
time ui.frame n=1800 avg=6.1ms p50=5.8ms p95=11.2ms p99=24.5ms max=38.1ms
time log.emit n=120 avg=0.3ms p50=0.2ms p95=0.9ms p99=1.4ms max=2.1ms
time floating.logs.render n=118 avg=1.4ms p50=1.1ms p95=3.8ms p99=6.2ms max=8.9ms
time capture.screen n=20 avg=12.4ms p50=8.1ms p95=31.2ms p99=45.0ms max=62.1ms
time image.findColor n=20 avg=9.8ms p50=7.5ms p95=20.1ms p99=25.4ms max=30.2ms
count capture.screen.cached=3
count capture.screen.fallback=0
gauge log.buffer=200
```

指标含义：

| 指标 | 含义 |
|---|---|
| `ui.frame` | 主窗口每帧总耗时；`jank` 为超过当前刷新率一帧的帧数占比 |
| `log.emit` | `LogBus` 合并后刷新给 UI 的耗时 |
| `log.append` | 单条日志写入（含 logcat、落盘调度、运行归档）的耗时 |
| `floating.logs.render` | 悬浮窗日志面板一次绘制的耗时 |
| `console.filter` | 日志页按级别 / 关键字过滤的耗时 |
| `capture.screen` | 主屏截图（持续录屏）耗时 |
| `capture.screen.cached` | 没有新帧、直接使用上一帧缓存的次数 |
| `capture.screen.fallback` | 持续录屏不可用、回退到其它截图后端的次数 |
| `capture.virtual` | 虚拟屏取帧耗时 |
| `image.findImage` / `image.findColor` / `image.findColorRegions` | 找图 / 找色 / 区域找色整体耗时 |
| `image.matchTemplate` / `image.findColorScan` | OpenCV 模板匹配 / 像素扫描耗时 |
| `overlay.hideWait` | 截图前等待悬浮层隐藏的耗时 |
| `script.prepare` | 脚本启动的参数解析 / 历史记录 / 通知与悬浮窗拉起耗时（后台线程） |
| `opencv.prewarm` / `ocr.prewarm` | 脚本启动时视觉能力的后台预热耗时 |
| `ocr.recognize` | OCR 识别耗时 |
| `preview.draw` | 虚拟屏预览绘制耗时 |
| `preview.skip` | 虚拟屏无新帧、跳过绘制的次数 |

## 对比场景

同一台设备、同一刷新率、同一构建类型下，分别记录以下场景各 30 秒：

1. **空闲首页**：停留在运行页，不运行脚本。
2. **日志页**：停留在日志页，脚本持续输出日志。
3. **悬浮窗日志**：开启悬浮窗，运行输出日志的脚本，保持悬浮窗展开。
4. **找色循环**：运行局部找色脚本，观察 `capture.screen` / `image.findColor`。
5. **虚拟屏预览**：后台脚本 + 虚拟屏页面预览 / 画中画。

日志脚本示例：

```js
for (let i = 0; i < 1500; i++) {
  log("tick " + i);
  sleep(20);
}
```

找色脚本示例（把坐标区域换成实际界面）：

```js
const region = { x: 0, y: 0, width: 540, height: 300 };
const started = Date.now();
let hits = 0;
for (let i = 0; i < 200; i++) {
  if (images.findColor("#FFFFFF", 4, region)) hits++;
}
log("findColor 200 次: " + (Date.now() - started) + "ms, hits=" + hits);
```

## 系统级辅助

```bash
# 帧统计（需要先操作一段时间）
adb shell dumpsys gfxinfo com.benton.izukijs framestats

# 重置统计
adb shell dumpsys gfxinfo com.benton.izukijs reset
```

对比时优先看 `jank` 占比、`ui.frame` 的 p95/p99，以及对应场景的模块耗时；单次 max 容易受系统调度偶发影响。
