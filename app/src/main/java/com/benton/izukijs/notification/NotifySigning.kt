package com.benton.izukijs.notification

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** 渠道签名与表单编码工具；与 SmsForwarder 的取值方式保持一致。 */
internal object NotifySigning {

    fun hmacSha256Base64(key: String, data: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return base64(mac.doFinal(data.toByteArray(StandardCharsets.UTF_8)))
    }

    fun base64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8")

    fun md5Hex(value: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(value.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** 把表单字段编码为 `a=1&b=2`，空值会被跳过。 */
    fun formBody(fields: Map<String, String>): String = fields.entries
        .filter { it.value.isNotEmpty() }
        .joinToString("&") { "${urlEncode(it.key)}=${urlEncode(it.value)}" }
}
