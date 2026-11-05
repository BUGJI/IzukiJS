package com.benton.izukijs.core.permission

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Android 17（API 37）新增的本地网络权限。
 *
 * 没有该权限时，`NsdManager` 发现会由系统 `NsdPickerActivity` 接管（表现为「选择要连接的设备」），
 * 同时 BPF 也会拦截本地网络 TCP，因此无线调试的发现、配对、连接都需要它。
 */
object LocalNetworkPermission {

    // 直接使用字符串，避免在 minSdk 28 上触发 InlinedApi。
    const val permission: String = "android.permission.ACCESS_LOCAL_NETWORK"

    fun isRequired(sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
        sdkInt >= Build.VERSION_CODES.CINNAMON_BUN

    fun isGranted(context: Context): Boolean =
        !isRequired() || ContextCompat.checkSelfPermission(
            context,
            permission,
        ) == PackageManager.PERMISSION_GRANTED
}
