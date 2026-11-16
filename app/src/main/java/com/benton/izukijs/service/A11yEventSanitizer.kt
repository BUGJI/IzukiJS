package com.benton.izukijs.service

/**
 * 无障碍事件内容脱敏规则。
 *
 * 密码框的文字变化只保留「发生过输入」这一事实，不记录用户实际键入的字符；
 * 抽成纯函数便于 JVM 单测覆盖，也避免服务层遗漏新的敏感事件类型。
 */
internal fun shouldRedactA11yText(eventType: String?, isPassword: Boolean): Boolean =
    eventType == "text_changed" && isPassword
