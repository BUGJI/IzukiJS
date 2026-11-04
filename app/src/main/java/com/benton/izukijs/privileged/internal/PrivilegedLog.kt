package com.benton.izukijs.privileged.internal

import android.util.Log

internal object PrivilegedLog {
    private const val TAG = "IzukiUnlock"

    fun i(message: String) {
        Log.i(TAG, message)
    }

    fun w(message: String, throwable: Throwable? = null) {
        if (throwable == null) Log.w(TAG, message) else Log.w(TAG, message, throwable)
    }

    fun e(message: String, throwable: Throwable? = null) {
        if (throwable == null) Log.e(TAG, message) else Log.e(TAG, message, throwable)
    }
}
