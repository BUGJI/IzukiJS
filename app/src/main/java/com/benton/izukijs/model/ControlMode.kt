package com.benton.izukijs.model

/**
 * 控制后端。除无障碍 / HID 外，其余后端都通过 Shell 命令实现输入与截图。
 */
enum class ControlMode(val displayName: String, val description: String) {
    ACCESSIBILITY(
        displayName = "无障碍",
        description = "通过无障碍服务注入手势、读取控件树。无需额外权限，兼容性最好。"
    ),
    SHIZUKU(
        displayName = "Shizuku",
        description = "通过 Shizuku 以 ADB 权限注入输入、截图、执行 Shell。功能最强，需常驻 Shizuku。"
    ),
    ADB(
        displayName = "无线调试",
        description = "通过系统无线调试配对，以 ADB 权限注入输入、截图并执行 Shell，无需安装 Shizuku。"
    ),
    HID(
        displayName = "蓝牙 HID",
        description = "通过外部 HID 硬件（数位板）注入输入，等同真实硬件，最难被检测。"
    ),
    ROOT(
        displayName = "Root",
        description = "通过 Root 权限直接调用系统能力。"
    ),
}
