package com.benton.izukijs.ui.common

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * 语义化触感反馈封装。语义更强的 [HapticFeedbackType]（确认 / 拒绝）
 * 需要 API 30+，低版本回退到通用的长按反馈。
 */
class AppHaptics(private val feedback: HapticFeedback) {

    /** 成功 / 确认类操作。 */
    fun confirm() = feedback.performHapticFeedback(confirmType)

    /** 破坏性 / 拒绝类操作。 */
    fun reject() = feedback.performHapticFeedback(rejectType)

    /** 轻量点按（运行、复制等）。 */
    fun tap() = feedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)

    /** 开关 / 置顶等状态切换。 */
    fun toggle() = feedback.performHapticFeedback(HapticFeedbackType.LongPress)

    private companion object {
        val confirmType: HapticFeedbackType
            get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                HapticFeedbackType.Confirm
            } else {
                HapticFeedbackType.LongPress
            }

        val rejectType: HapticFeedbackType
            get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                HapticFeedbackType.Reject
            } else {
                HapticFeedbackType.LongPress
            }
    }
}

@Composable
fun rememberAppHaptics(): AppHaptics {
    val feedback = LocalHapticFeedback.current
    return remember(feedback) { AppHaptics(feedback) }
}
