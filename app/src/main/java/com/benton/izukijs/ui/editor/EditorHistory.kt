package com.benton.izukijs.ui.editor

/** 一次可撤销的编辑快照；与 Compose 的 TextFieldValue 解耦，便于单测。 */
data class EditorTextState(
    val text: String,
    val selectionStart: Int,
    val selectionEnd: Int,
)

/**
 * 编辑器的撤销 / 重做栈。
 *
 * 只处理纯数据，不依赖 Compose、Android 或 ViewModel；输入合并窗口与栈上限可注入，
 * 便于在 JVM 单测里稳定验证。
 */
class EditorHistory(
    private val coalesceMs: Long = DEFAULT_COALESCE_MS,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val undoStack = ArrayDeque<EditorTextState>()
    private val redoStack = ArrayDeque<EditorTextState>()
    private var lastEditAt = 0L

    val canUndo: Boolean get() = undoStack.isNotEmpty()

    val canRedo: Boolean get() = redoStack.isNotEmpty()

    fun clear() {
        undoStack.clear()
        redoStack.clear()
        lastEditAt = 0L
    }

    /**
     * 记录一次编辑前的快照。
     *
     * 短时间内的连续输入合并为一步；[force] 用于补全、替换等程序化编辑。
     */
    fun record(previous: EditorTextState, force: Boolean) {
        val now = clock()
        val coalesce = !force && now - lastEditAt < coalesceMs && undoStack.isNotEmpty()
        if (!coalesce) {
            undoStack.addLast(previous)
            while (undoStack.size > maxEntries) undoStack.removeFirst()
        }
        redoStack.clear()
        lastEditAt = now
    }

    /** 程序化编辑后重置合并窗口，避免紧随其后的输入被并入同一步撤销。 */
    fun resetCoalescing() {
        lastEditAt = 0L
    }

    fun undo(current: EditorTextState): EditorTextState? {
        val previous = undoStack.removeLastOrNull() ?: return null
        redoStack.addLast(current)
        lastEditAt = 0L
        return previous
    }

    fun redo(current: EditorTextState): EditorTextState? {
        val next = redoStack.removeLastOrNull() ?: return null
        undoStack.addLast(current)
        lastEditAt = 0L
        return next
    }

    companion object {
        const val DEFAULT_COALESCE_MS = 500L
        const val DEFAULT_MAX_ENTRIES = 200
    }
}
