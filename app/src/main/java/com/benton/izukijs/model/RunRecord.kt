package com.benton.izukijs.model

/** 一次脚本运行记录，用于运行页的「快速启动」历史。 */
data class RunRecord(val id: String, val name: String, val at: Long)
