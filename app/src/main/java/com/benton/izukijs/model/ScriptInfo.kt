package com.benton.izukijs.model

import androidx.compose.runtime.Immutable
import java.io.File

/**
 * 脚本文件的元信息。
 *
 * 标记为 [Immutable]：字段均为只读值（[File] 仅作路径句柄），
 * 便于 Compose 跳过内容未变化的列表行重组。
 */
@Immutable
data class ScriptInfo(
    val id: String,
    val name: String,
    val file: File,
    val updatedAt: Long,
)
