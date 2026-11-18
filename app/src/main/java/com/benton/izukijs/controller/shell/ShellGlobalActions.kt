package com.benton.izukijs.controller.shell

/**
 * Shell 后端可执行的系统全局动作。
 *
 * 动作编号与 `AccessibilityService.GLOBAL_ACTION_*` 对齐，但 Shell 没有等价的
 * “任意编号即按键”语义：旧实现把未映射编号直接拼成 `input keyevent <编号>`，
 * 会把「快捷设置(5)」变成拨号键、「锁屏(8)」变成数字 1。这里显式映射，
 * 无可靠命令的动作返回 null，由调用方按失败处理。
 */
internal object ShellGlobalActions {
    const val BACK = 1
    const val HOME = 2
    const val RECENTS = 3
    const val NOTIFICATIONS = 4
    const val QUICK_SETTINGS = 5
    const val POWER_DIALOG = 6
    const val SPLIT_SCREEN = 7
    const val LOCK_SCREEN = 8
    const val DISMISS_NOTIFICATION_SHADE = 12

    /**
     * 返回可执行的 Shell 命令；[displayOption] 形如 `"-d 2 "`，无目标屏时为空串。
     *
     * 仅返回当前 Shell 后端能可靠表达的动作；[SPLIT_SCREEN] 与无障碍专属动作
     * 没有稳定命令，返回 null 而不是猜测按键。
     */
    fun commandFor(action: Int, displayOption: String): String? = when (action) {
        BACK -> "input ${displayOption}keyevent 4"
        HOME -> "input ${displayOption}keyevent 3"
        RECENTS -> "input ${displayOption}keyevent 187"
        NOTIFICATIONS -> "cmd statusbar expand-notifications"
        QUICK_SETTINGS -> "cmd statusbar expand-settings"
        // 长按电源键弹出电源菜单；不能直接发 26（会变成开关屏）。
        POWER_DIALOG -> "input ${displayOption}keyevent --longpress 26"
        // KEYCODE_SLEEP：熄屏并在系统启用锁屏时上锁。
        LOCK_SCREEN -> "input keyevent 223"
        DISMISS_NOTIFICATION_SHADE -> "cmd statusbar collapse"
        else -> null
    }
}
