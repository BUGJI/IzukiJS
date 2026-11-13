package com.benton.izukijs.controller.record

/** 把录制时间线转换成可直接编辑的 JS 脚本骨架。 */
object RecordingScriptExporter {

    /** 只有真正的动作事件参与等待时间计算；截图 / context / session 头不产生 sleep。 */
    private fun isTimedAction(event: RecordEvent): Boolean =
        event is UserActionEvent ||
            (RecordEvent.kindOf(event) != null && event !is AppEvent)

    fun build(reader: RecordingReader): String {
        val sb = StringBuilder()
        sb.append("// 由录制「").append(oneLine(reader.meta.name)).append("」生成\n")
        sb.append("// 坐标来自录制时的屏幕像素；控件文本命中时优先使用 selector。\n")
        if (!reader.meta.complete) {
            sb.append("// 注意：这条录制没有正常结束，尾部事件可能缺失。\n")
        }
        if (reader.meta.truncated) {
            sb.append("// 注意：录制达到帧数上限，部分画面为复用的旧帧。\n")
        }
        sb.append('\n')
        var lastT = 0L
        var lastRotation: Int? = null
        reader.events.forEach { event ->
            if (!isTimedAction(event)) {
                appendComment(sb, event)
                return@forEach
            }
            val delta = (event.timestamp - lastT).coerceAtLeast(0L)
            if (delta > 80L) {
                sb.append("sleep(").append(delta.coerceAtMost(3_000L)).append(");\n")
            }
            lastT = event.timestamp
            when (event) {
                is UserActionEvent -> {
                    val previous = lastRotation
                    if (previous != null && event.rotation != previous) {
                        sb.append("// 注意：录制中屏幕方向由 rotation=")
                            .append(previous)
                            .append(" 变为 ")
                            .append(event.rotation)
                            .append("，以下坐标仅在录制方向下有效\n")
                    }
                    lastRotation = event.rotation
                    appendUserAction(sb, event)
                }
                is ClickEvent -> sb.append("click(").append(event.x).append(", ")
                    .append(event.y).append(");\n")
                is LongClickEvent -> sb.append("longClick(").append(event.x).append(", ")
                    .append(event.y).append(", ").append(event.durationMs).append(");\n")
                is SwipeEvent -> sb.append("swipe(").append(event.x1).append(", ").append(event.y1)
                    .append(", ").append(event.x2).append(", ").append(event.y2)
                    .append(", ").append(event.durationMs).append(");\n")
                is GestureEvent -> {
                    if (event.strokes.length() > 0) {
                        sb.append("gesture(").append(event.strokes).append(");\n")
                    } else {
                        sb.append("// gesture（无轨迹数据）\n")
                    }
                }
                is InputTextEvent -> sb.append("input(").append(quote(event.text)).append(");\n")
                is PressKeyEvent -> sb.append("press(").append(event.keyCode).append(");\n")
                is GlobalActionEvent -> appendGlobalAction(sb, event.action)
                is ShellEvent -> sb.append("// shell: ").append(oneLine(event.cmd)).append('\n')
                else -> Unit
            }
        }
        return sb.toString()
    }

    private fun appendComment(sb: StringBuilder, event: RecordEvent) {
        when (event) {
            is AppEvent -> {
                if (event.packageName.isNotBlank()) {
                    sb.append("// app: ").append(oneLine(event.packageName))
                    if (event.action.isNotBlank()) sb.append(" (").append(oneLine(event.action)).append(')')
                    sb.append('\n')
                }
            }
            is ScreenshotEvent -> if (event.stale) {
                sb.append("// 画面复用上一帧：").append(oneLine(event.frame))
                    .append("（画面未变化或达到帧上限）\n")
            }
            else -> Unit
        }
    }

    private fun appendGlobalAction(sb: StringBuilder, action: Int) {
        when (action) {
            1 -> sb.append("back();\n")
            2 -> sb.append("home();\n")
            3 -> sb.append("recents();\n")
            else -> sb.append("// globalAction(").append(action).append(")\n")
        }
    }

    private fun appendUserAction(sb: StringBuilder, event: UserActionEvent) {
        val targetText = event.targets.firstOrNull { !it.text.isNullOrBlank() }?.text
        val targetId = event.targets.firstOrNull { !it.viewId.isNullOrBlank() }?.viewId
        val usedSelector = when (event.kind) {
            UserActionKind.TAP -> when {
                !targetText.isNullOrBlank() -> {
                    sb.append("selector.clickByText(").append(quote(targetText)).append(");\n")
                    true
                }

                !targetId.isNullOrBlank() -> {
                    sb.append("selector.clickById(").append(quote(targetId)).append(");\n")
                    true
                }

                else -> {
                    sb.append("click(").append(event.x).append(", ").append(event.y).append(");\n")
                    false
                }
            }

            UserActionKind.LONG_CLICK -> when {
                !targetText.isNullOrBlank() -> {
                    sb.append("selector.longClickByText(").append(quote(targetText)).append(");\n")
                    true
                }

                !targetId.isNullOrBlank() -> {
                    sb.append("selector.longClickById(").append(quote(targetId)).append(");\n")
                    true
                }

                else -> {
                    sb.append("longClick(").append(event.x).append(", ").append(event.y)
                        .append(", ").append(event.durationMs.coerceAtLeast(600L)).append(");\n")
                    false
                }
            }

            UserActionKind.SWIPE -> {
                sb.append("swipe(").append(event.x).append(", ").append(event.y)
                    .append(", ").append(event.x2).append(", ").append(event.y2)
                    .append(", ").append(event.durationMs.coerceAtLeast(200L)).append(");\n")
                false
            }

            UserActionKind.GESTURE, UserActionKind.MULTI_SWIPE -> {
                val strokes = event.strokes
                if (strokes != null && strokes.length() > 0) {
                    sb.append("gesture(").append(strokes).append(");\n")
                } else {
                    sb.append("// gesture: (").append(event.x).append(", ").append(event.y)
                        .append(") → (").append(event.x2).append(", ").append(event.y2)
                        .append(")\n")
                }
                false
            }

            UserActionKind.KEY -> {
                sb.append("// key action\n")
                false
            }
        }
        if (usedSelector) {
            sb.append("// 选择器未命中时可改用坐标: (")
                .append(event.x).append(", ").append(event.y).append(")\n")
        }
        if (event.beforeFrame != null) {
            sb.append("// frame: ").append(oneLine(event.beforeFrame))
            if (event.targets.isEmpty()) sb.append("（未命中控件，使用坐标）")
            sb.append('\n')
        }
        if (event.afterFrame != null) {
            sb.append("// afterFrame: ").append(oneLine(event.afterFrame)).append('\n')
        }
        if (event.packageName.isNotBlank()) {
            sb.append("// package: ").append(oneLine(event.packageName)).append('\n')
        }
        if (event.screenWidth > 0 && event.screenHeight > 0) {
            sb.append("// screen: ").append(event.screenWidth).append('x')
                .append(event.screenHeight).append(" rotation=").append(event.rotation)
                .append('\n')
        }
    }

    private fun oneLine(value: String): String =
        value.replace('\r', ' ').replace('\n', ' ').trim()

    private fun quote(value: String): String = buildString {
        append('"')
        value.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(char)
            }
        }
        append('"')
    }
}
