package com.benton.izukijs.model

import java.io.File

/**
 * 脚本文件的元信息。
 */
data class ScriptInfo(
    val id: String,
    val name: String,
    val file: File,
    val updatedAt: Long,
)
