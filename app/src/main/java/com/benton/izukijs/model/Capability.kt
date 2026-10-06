package com.benton.izukijs.model

/**
 * 控制能力枚举。控制抽象层的核心，用于在不同控制模式之间做能力协商与降级。
 */
enum class Capability(val displayName: String) {
    /** 绝对坐标手势注入（点击/滑动等） */
    GESTURE("手势"),

    /** 绝对坐标指针（如外部 HID 数位板） */
    ABSOLUTE_POINTER("绝对指针"),

    /** 相对位移指针（如标准 HID 鼠标） */
    RELATIVE_POINTER("相对指针"),

    /** 物理按键事件注入 */
    KEY("按键"),

    /** 文本输入 */
    TEXT("文本输入"),

    /** 屏幕截图 */
    SCREENSHOT("截图"),

    /** 读取无障碍控件树 */
    NODE_TREE("控件树"),

    /** 执行 Shell 命令 */
    SHELL("Shell"),

    /** 执行系统全局动作（返回/主页/最近任务/通知栏等） */
    GLOBAL_ACTION("全局动作"),

    /** Root 权限 */
    ROOT("Root"),
}
