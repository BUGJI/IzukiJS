// 基础操作示例：返回桌面 → 清理垃圾 → 返回上一级

var w = device.width();
var h = device.height();

// 1. 返回桌面：底部正中间上滑，100ms
function backToHome() {
  swipe(w / 2, h * 0.99, w / 2, h * 0.55, 100);
  sleep(800);
}

// 2. 清理垃圾：底部正中间上滑并停顿 1 秒（打开最近任务），再点击中下位置
function cleanJunk() {
  swipe(w / 2, h * 0.99, w / 2, h * 0.6, 1000);
  sleep(1000);
  click(w / 2, h * 0.82);
  sleep(800);
}

// 3. 返回上一级：屏幕右侧滑到中间，100ms
function goBack() {
  swipe(w * 0.99, h * 0.5, w * 0.5, h * 0.5, 100);
  sleep(500);
}

// 入口：按顺序执行一遍
function main() {
  backToHome();
  cleanJunk();
  goBack();
}

main();
