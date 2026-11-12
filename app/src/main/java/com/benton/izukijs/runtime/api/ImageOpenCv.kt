package com.benton.izukijs.runtime.api

import com.benton.izukijs.core.log.LogBus
import com.benton.izukijs.core.perf.PerfMetrics
import org.opencv.android.OpenCVLoader

/** OpenCV 加载状态的进程级入口，供 `images` 的识别 / 找图 / 找色共用。 */
internal object ImageOpenCv {

    private val lock = Any()

    @Volatile
    private var ready = false

    @Volatile
    private var prewarmStarted = false

    fun ensure(logBus: LogBus): Boolean {
        val loaded = load()
        if (!loaded) logBus.error("OpenCV 初始化失败")
        return loaded
    }

    /** 后台预热 OpenCV，避免脚本第一次找图时同步加载 native 库。 */
    fun prewarm() {
        if (ready || prewarmStarted) return
        synchronized(lock) {
            if (ready || prewarmStarted) return
            prewarmStarted = true
        }
        Thread(
            {
                val loaded = PerfMetrics.measure("opencv.prewarm") { load() }
                if (!loaded) {
                    // 允许下一次调用重新预热，而不是永久停留在失败状态。
                    synchronized(lock) { prewarmStarted = false }
                }
            },
            "izuki-open-cv-prewarm",
        ).apply { isDaemon = true }.start()
    }

    private fun load(): Boolean {
        if (ready) return true
        synchronized(lock) {
            if (ready) return true
            ready = runCatching { OpenCVLoader.initLocal() }.getOrDefault(false)
            return ready
        }
    }
}
