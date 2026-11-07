package com.benton.izukijs.service

/**
 * 前台服务通知 ID 集中管理。
 *
 * 通知 ID 在应用内全局唯一，不同服务复用同一 ID 会互相覆盖通知；
 * 这里统一分配，新增服务时在末尾追加，不要复用已有编号。
 */
internal object NotificationIds {
    const val SCRIPT = 1001
    const val FLOATING_WINDOW = 1002
    const val SCREEN_CAPTURE = 1003
    const val DEBUG_OVERLAY = 1004
    const val UNLOCK_RECORDING = 1005
    const val MCP_SERVER = 1006
    const val ADB_PAIRING = 4102
}
