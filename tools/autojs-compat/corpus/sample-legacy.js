// 来源: 自写样例
// 依赖: auto, selector, device, files
// 预期: 需要兼容层（选择器对象与 device 属性）
"auto";

auto.waitFor();

var width = device.width;
toastLog("屏幕宽度: " + width);

var login = text("登录").findOne();
if (login) {
  login.click();
} else {
  log("未找到登录按钮");
}

files.write("/sdcard/izuki-compat-demo.txt", "ok");
