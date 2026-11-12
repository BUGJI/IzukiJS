package com.benton.izukijs.controller.record

import android.graphics.Bitmap

/**
 * 截图 / 应用启动的录制观察点。
 *
 * 由容器持有一个实例，注入到 [com.benton.izukijs.runtime.api.DeviceApiBundle] 与
 * [RecordingSession]，避免使用进程级可变单例。录制同一时间只允许一个会话。
 */
class RecordingHooks {
    /**
     * 截屏观察点：bitmap、采集开始墙钟时间、耗时。
     *
     * 录制器据此写入 `screencap` 事件；`atMs` 必须是采集开始时间，
     * 而不是写盘时间，否则回放时无法与动作对齐。
     */
    @Volatile
    var captureObserver: ((Bitmap?, Long, Long) -> Unit)? = null

    @Volatile
    var appObserver: ((String, String, Boolean) -> Unit)? = null
}
