package com.benton.izukijs.privileged.internal

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.view.Surface
import java.lang.reflect.Method

/**
 * Shizuku UserService 内的虚拟屏实现。
 *
 * App 侧传入自己的 ImageReader Surface，帧直接落到 App 进程；服务进程只负责创建/销毁
 * 虚拟屏，以及把目标应用拉到这块屏上。
 */
internal class PrivilegedVirtualDisplay(
    private val context: Context,
) {

    @Volatile
    private var virtualDisplay: VirtualDisplay? = null

    @Volatile
    private var currentDisplayId: Int = DISPLAY_NONE

    /** 最近一次 create 失败的真实原因，供 App 侧透传。 */
    @Volatile
    var lastError: String? = null
        private set

    @Volatile
    private var displayManager: DisplayManager? = null

    @SuppressLint("WrongConstant")
    @Synchronized
    fun create(width: Int, height: Int, dpi: Int, surface: Surface): Int {
        destroy()
        lastError = null
        val manager = obtainDisplayManager()
            ?: return fail("DisplayManager unavailable")
        var primaryError: String? = null
        val attempts = listOf(
            "preferred" to buildPreferredDisplayFlags(),
            "legacy" to buildLegacyDisplayFlags(),
        )
        for ((label, flags) in attempts) {
            val outcome = runCatching {
                manager.createVirtualDisplay(VD_NAME, width, height, dpi, surface, flags)
            }
            val display = outcome.getOrNull()
            if (display != null) {
                virtualDisplay = display
                currentDisplayId = display.display.displayId
                applyDisplayTweaks(currentDisplayId)
                PrivilegedLog.i(
                    "Virtual display created: id=$currentDisplayId ${width}x$height@$dpi " +
                        "flags=0x${flags.toString(16)} ($label)",
                )
                return currentDisplayId
            }
            val throwable = outcome.exceptionOrNull()
            val message = throwable?.let {
                "${it.javaClass.simpleName}: ${it.message ?: it.javaClass.name}"
            } ?: "createVirtualDisplay returned null"
            PrivilegedLog.w(
                "createVirtualDisplay($label flags=0x${flags.toString(16)}) failed: $message",
                throwable,
            )
            if (primaryError == null) primaryError = message
        }
        return fail(primaryError ?: "createVirtualDisplay failed")
    }

    fun displayId(): Int = currentDisplayId

    /** 虚拟屏上最靠前的任务包名；读取失败返回 null。 */
    fun currentPackage(): String? {
        val displayId = currentDisplayId
        if (displayId == DISPLAY_NONE) return null
        return runCatching {
            val am = context.getSystemService(ActivityManager::class.java) ?: return null
            @Suppress("DEPRECATION")
            val tasks = am.getRunningTasks(100)
            tasks.asSequence()
                .filter { taskDisplayId(it) == displayId }
                .mapNotNull { it.topActivity?.packageName ?: it.baseActivity?.packageName }
                .firstOrNull()
        }.onFailure {
            PrivilegedLog.w("currentPackage failed: ${it.message}")
        }.getOrNull()
    }

    @Synchronized
    fun launch(intent: Intent, forceStopIfElsewhere: Boolean): Boolean {
        val displayId = currentDisplayId
        if (displayId == DISPLAY_NONE) {
            PrivilegedLog.w("launch: no active virtual display")
            return false
        }
        val packageName = intent.component?.packageName
            ?: intent.resolveActivity(context.packageManager)?.packageName
            ?: return failBoolean("launch: cannot resolve package")
        val onDisplay = isPackageOnDisplay(packageName, displayId)
        if (forceStopIfElsewhere && !onDisplay) {
            if (forceStop(packageName)) {
                PrivilegedLog.i("launch: force-stopped $packageName before moving to display $displayId")
            }
        }
        val options = ActivityOptions.makeBasic().apply {
            launchDisplayId = displayId
        }
        val started = startActivity(intent, options.toBundle())
        if (!started) {
            PrivilegedLog.w("launch: ActivityManager failed for $packageName, fallback to am")
            return false
        }
        if (!awaitPackageOnDisplay(packageName, displayId)) {
            PrivilegedLog.w("launch: $packageName not observed on display $displayId after launch")
        }
        return true
    }

    private fun awaitPackageOnDisplay(
        packageName: String,
        displayId: Int,
        timeoutMs: Long = APP_PIN_TIMEOUT_MS,
    ): Boolean {
        val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            if (isPackageOnDisplay(packageName, displayId)) return true
            runCatching { Thread.sleep(APP_PIN_POLL_MS) }
        }
        return isPackageOnDisplay(packageName, displayId)
    }

    @Synchronized
    fun destroy() {
        virtualDisplay?.let { runCatching { it.release() } }
        virtualDisplay = null
        currentDisplayId = DISPLAY_NONE
    }

    private fun applyDisplayTweaks(displayId: Int) {
        val wm = HiddenWindowManager.create() ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            wm.setDisplayImePolicy(displayId, DISPLAY_IME_POLICY_HIDE)
        }
        wm.freezeRotation(displayId, Surface.ROTATION_0)
    }

    private fun startActivity(intent: Intent, options: Bundle): Boolean = runCatching {
        val am = context.getSystemService(ActivityManager::class.java) ?: return false
        val method: Method = ActivityManager::class.java.getMethod(
            "startActivity",
            Intent::class.java,
            Bundle::class.java,
        )
        val result = method.invoke(am, intent, options) as? Int ?: return false
        result >= 0
    }.onFailure {
        PrivilegedLog.w("ActivityManager.startActivity failed: ${it.message}", it)
    }.getOrDefault(false)

    private fun forceStop(packageName: String): Boolean = runCatching {
        val am = context.getSystemService(ActivityManager::class.java) ?: return false
        val method: Method = ActivityManager::class.java.getMethod(
            "forceStopPackage",
            String::class.java,
        )
        method.invoke(am, packageName)
        true
    }.onFailure {
        PrivilegedLog.w("forceStopPackage($packageName) failed: ${it.message}", it)
    }.getOrDefault(false)

    /**
     * 目标包是否有任务在指定屏。读不到 displayId 时宽松返回 true，避免误强停。
     * API 29 以下没有该字段，也按 true 处理。
     */
    private fun isPackageOnDisplay(packageName: String, displayId: Int): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        return runCatching {
            val am = context.getSystemService(ActivityManager::class.java) ?: return true
            @Suppress("DEPRECATION")
            val tasks = am.getRunningTasks(100)
            val matching = tasks.filter {
                it.topActivity?.packageName == packageName ||
                    it.baseActivity?.packageName == packageName
            }
            if (matching.isEmpty()) return false
            matching.any { task -> taskDisplayId(task)?.let { it == displayId } ?: true }
        }.onFailure {
            PrivilegedLog.w("isPackageOnDisplay failed: ${it.message}")
        }.getOrDefault(true)
    }

    private fun taskDisplayId(task: ActivityManager.RunningTaskInfo): Int? {
        var cls: Class<*>? = task.javaClass
        while (true) {
            val current = cls ?: return null
            val field = runCatching {
                current.getDeclaredField("displayId").apply { isAccessible = true }
            }.getOrNull()
            if (field != null) {
                return runCatching { field.getInt(task) }.getOrNull()
            }
            cls = current.superclass
        }
    }

    /**
     * 与已验证可用的后台虚拟屏配置对齐：不设 FLAG_PUBLIC，且不与
     * FLAG_OWN_DISPLAY_GROUP 同时使用互斥的 FLAG_DEVICE_DISPLAY_GROUP。
     */
    private fun buildPreferredDisplayFlags(): Int {
        var flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
            FLAG_SUPPORTS_TOUCH or
            FLAG_DESTROY_CONTENT_ON_REMOVAL
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            flags = flags or
                FLAG_TRUSTED or
                FLAG_OWN_DISPLAY_GROUP or
                FLAG_ALWAYS_UNLOCKED or
                FLAG_TOUCH_FEEDBACK_DISABLED
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                flags = flags or
                    FLAG_OWN_FOCUS or
                    FLAG_STEAL_TOP_FOCUS_DISABLED
            }
        }
        return flags
    }

    /** 兼容旧 ROM 的回退组合；仍使用按 uid 修正后的 DisplayManager。 */
    private fun buildLegacyDisplayFlags(): Int {
        var flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
            FLAG_SUPPORTS_TOUCH or
            FLAG_DESTROY_CONTENT_ON_REMOVAL
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            flags = flags or
                FLAG_TRUSTED or
                FLAG_OWN_DISPLAY_GROUP or
                FLAG_ALWAYS_UNLOCKED or
                FLAG_TOUCH_FEEDBACK_DISABLED
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                flags = flags or
                    FLAG_OWN_FOCUS or
                    FLAG_STEAL_TOP_FOCUS_DISABLED
            }
        }
        return flags
    }

    private fun obtainDisplayManager(): DisplayManager? {
        displayManager?.let { return it }
        val uid = Process.myUid()
        val packages = runCatching { context.packageManager.getPackagesForUid(uid) }
            .getOrNull()
            ?.toList()
        val packageName = DisplayPackageResolver.resolve(uid, packages, context.packageName)
        val manager = if (packageName == context.packageName) {
            context.getSystemService(DisplayManager::class.java)
        } else {
            createPackageDisplayManager(packageName) ?: createWrappedDisplayManager(packageName)
        } ?: context.getSystemService(DisplayManager::class.java)
        displayManager = manager
        if (manager != null) {
            PrivilegedLog.i("DisplayManager package=$packageName uid=$uid")
        }
        return manager
    }

    private fun createPackageDisplayManager(packageName: String): DisplayManager? = runCatching {
        context.createPackageContext(packageName, 0)
            .getSystemService(DisplayManager::class.java)
    }.onFailure {
        PrivilegedLog.w("createPackageContext($packageName) failed: ${it.message}")
    }.getOrNull()

    private fun createWrappedDisplayManager(packageName: String): DisplayManager? = runCatching {
        val wrapped = object : ContextWrapper(context) {
            override fun getPackageName(): String = packageName

            override fun getOpPackageName(): String = packageName
        }
        val constructor = DisplayManager::class.java.getDeclaredConstructor(Context::class.java)
        constructor.isAccessible = true
        constructor.newInstance(wrapped) as DisplayManager
    }.onFailure {
        PrivilegedLog.w("wrapped DisplayManager($packageName) failed: ${it.message}")
    }.getOrNull()

    private fun fail(message: String): Int {
        lastError = message
        PrivilegedLog.e(message)
        return DISPLAY_NONE
    }

    private fun failBoolean(message: String): Boolean {
        PrivilegedLog.e(message)
        return false
    }

    private companion object {
        const val VD_NAME = "IzukiVirtualDisplay"
        const val DISPLAY_NONE = -1
        const val APP_PIN_TIMEOUT_MS = 2_000L
        const val APP_PIN_POLL_MS = 100L

        const val FLAG_SUPPORTS_TOUCH = 1 shl 6
        const val FLAG_DESTROY_CONTENT_ON_REMOVAL = 1 shl 8
        const val FLAG_TRUSTED = 1 shl 10
        const val FLAG_OWN_DISPLAY_GROUP = 1 shl 11
        const val FLAG_ALWAYS_UNLOCKED = 1 shl 12
        const val FLAG_TOUCH_FEEDBACK_DISABLED = 1 shl 13
        const val FLAG_OWN_FOCUS = 1 shl 14
        const val FLAG_STEAL_TOP_FOCUS_DISABLED = 1 shl 16

        /** WindowManager.DISPLAY_IME_POLICY_HIDE。 */
        const val DISPLAY_IME_POLICY_HIDE = 2
    }
}
