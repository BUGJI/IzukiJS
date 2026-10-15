package com.benton.izukijs.model

/**
 * 能力分组：读取（获取屏幕 / 控件树）与操作（注入输入 / 执行命令）。
 * 与权限界面「读取 / 操作」维度对齐，并驱动控制优先级的两档全局设置。
 */
enum class CapabilityGroup { READ, OPERATE }

/**
 * 控制能力枚举。控制抽象层的核心，用于在不同控制模式之间做能力协商与降级。
 */
enum class Capability(val displayName: String, val group: CapabilityGroup) {
    /** 绝对坐标手势注入（点击/滑动等） */
    GESTURE("手势", CapabilityGroup.OPERATE),

    /** 绝对坐标指针（如外部 HID 数位板） */
    ABSOLUTE_POINTER("绝对指针", CapabilityGroup.OPERATE),

    /** 相对位移指针（如标准 HID 鼠标） */
    RELATIVE_POINTER("相对指针", CapabilityGroup.OPERATE),

    /** 物理按键事件注入 */
    KEY("按键", CapabilityGroup.OPERATE),

    /** 文本输入 */
    TEXT("文本输入", CapabilityGroup.OPERATE),

    /** 屏幕截图 */
    SCREENSHOT("截图", CapabilityGroup.READ),

    /** 读取无障碍控件树 */
    NODE_TREE("控件树", CapabilityGroup.READ),

    /** 执行 Shell 命令 */
    SHELL("Shell", CapabilityGroup.OPERATE),

    /** 执行系统全局动作（返回/主页/最近任务/通知栏等） */
    GLOBAL_ACTION("全局动作", CapabilityGroup.OPERATE),

    /** Root 权限 */
    ROOT("Root", CapabilityGroup.OPERATE),
}
