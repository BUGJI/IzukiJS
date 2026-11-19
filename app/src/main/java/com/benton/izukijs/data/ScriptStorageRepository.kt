package com.benton.izukijs.data

import android.content.Context
import android.content.SharedPreferences
import com.benton.izukijs.core.prefs.PrefsStateStore
import java.io.File
import kotlinx.coroutines.flow.StateFlow

/** 脚本存储位置。`rootPath` 为空时使用应用私有目录 `filesDir`。 */
data class ScriptStorageSettings(
    val rootPath: String? = null,
) {
    val isDefault: Boolean get() = rootPath.isNullOrBlank()
}

/** 切换脚本根目录的结果；失败原因直接用于界面提示。 */
sealed interface ScriptStorageSwitchResult {
    data object Unchanged : ScriptStorageSwitchResult
    data object Running : ScriptStorageSwitchResult
    data object InvalidPath : ScriptStorageSwitchResult
    data object NestedRoot : ScriptStorageSwitchResult
    data class NotWritable(val path: String) : ScriptStorageSwitchResult
    data class Success(
        val rootPath: String,
        val copied: Int,
        val skipped: Int,
        val failed: Int,
    ) : ScriptStorageSwitchResult
}

/**
 * 脚本根目录偏好。默认与应用私有目录保持一致，避免已有安装升级后脚本“消失”。
 *
 * 根目录下固定使用 `scripts/` 与 `projects/` 子目录；切换目录只改变这两个子目录的
 * 位置，脚本历史仍留在应用私有目录。
 */
class ScriptStorageRepository(private val appContext: Context) :
    PrefsStateStore<ScriptStorageSettings>(
        context = appContext,
        prefsName = PREFS,
        defaultValue = ScriptStorageSettings(),
    ) {

    val settings: StateFlow<ScriptStorageSettings> get() = state

    fun root(): File = ScriptStoragePaths.resolveRoot(appContext.filesDir, current().rootPath)

    fun scriptsDir(): File = ScriptStoragePaths.scriptsDir(root())

    fun projectsDir(): File = ScriptStoragePaths.projectsDir(root())

    fun saveRoot(path: String?) {
        save(ScriptStorageSettings(rootPath = path?.trim()?.ifBlank { null }))
    }

    override fun read(): ScriptStorageSettings = ScriptStorageSettings(
        rootPath = prefs.getString(KEY_ROOT_PATH, null)?.trim()?.ifBlank { null },
    )

    override fun write(editor: SharedPreferences.Editor, value: ScriptStorageSettings) {
        val path = value.rootPath?.trim().orEmpty()
        if (path.isEmpty()) {
            editor.remove(KEY_ROOT_PATH)
        } else {
            editor.putString(KEY_ROOT_PATH, path)
        }
    }

    private companion object {
        const val PREFS = "izukijs_script_storage"
        const val KEY_ROOT_PATH = "root_path"
    }
}
