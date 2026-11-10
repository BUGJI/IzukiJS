package com.benton.izukijs.core

import android.content.Context

/**
 * 与 `AndroidManifest.xml` 中 `${applicationId}.fileprovider` 保持一致的 authority。
 * 集中一处，避免分享 / 安装 APK / 导入导出各自硬编码字符串。
 */
fun fileProviderAuthority(context: Context): String = "${context.packageName}.fileprovider"
