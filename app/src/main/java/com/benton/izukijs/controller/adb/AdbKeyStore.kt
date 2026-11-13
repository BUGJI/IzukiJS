package com.benton.izukijs.controller.adb

import android.content.Context
import android.util.Log
import com.benton.izukijs.core.storage.AtomicFile
import com.benton.izukijs.security.SecretCipher
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.Date

/**
 * 无线 ADB 的客户端身份（RSA 私钥 + 自签证书）。
 *
 * 配对成功后系统会把证书公钥加入 adbd 的授权列表，私钥只保存在应用私有目录。
 * 文件位于 noBackupFilesDir，且整段 PEM 用 [SecretCipher] 加密，避免被系统备份带出。
 */
class AdbKeyStore(private val context: Context) {

    data class Identity(
        val privateKey: PrivateKey,
        val certificate: X509Certificate,
    )

    private val identityFile = File(context.noBackupFilesDir, FILE_NAME)

    @Volatile
    private var cached: Identity? = null

    /** 磁盘上的身份无法解密 / 解析时置位；此时应重新走配对，而不是静默换新密钥。 */
    @Volatile
    private var corrupt = false

    @Synchronized
    fun hasIdentity(): Boolean {
        cached?.let { return true }
        if (!identityFile.exists() || corrupt) return false
        // 首次调用时验证一次可解密性，避免损坏的文件让连接反复失败而不提示重新配对。
        val loaded = readFromDisk()
        if (loaded == null) {
            corrupt = true
            return false
        }
        cached = loaded
        return true
    }

    @Synchronized
    fun loadOrCreate(): Identity {
        cached?.let { return it }
        readFromDisk()?.let {
            cached = it
            corrupt = false
            return it
        }
        if (identityFile.exists()) {
            // 保留损坏文件用于排查，但必须生成新身份；旧证书已无法用于配对。
            val backup = File(
                identityFile.parentFile,
                "$FILE_NAME.corrupt-${System.currentTimeMillis()}",
            )
            val moved = runCatching { identityFile.renameTo(backup) }.getOrDefault(false)
            if (moved) {
                Log.w(TAG, "无线调试身份已损坏，旧文件已备份到 ${backup.name}")
            }
        }
        corrupt = false
        return generate().also { identity ->
            cached = identity
            writeToDisk(identity)
        }
    }

    @Synchronized
    fun clear() {
        cached = null
        corrupt = false
        runCatching { identityFile.delete() }
    }

    private fun readFromDisk(): Identity? {
        if (!identityFile.exists()) return null
        return runCatching {
            val encrypted = identityFile.readText(Charsets.UTF_8)
            val pem = SecretCipher.decrypt(encrypted)
            val privateKeyPem = extractPem(pem, PRIVATE_HEADER, PRIVATE_FOOTER)
                ?: error("缺少私钥 PEM")
            val certificatePem = extractPem(pem, CERT_HEADER, CERT_FOOTER)
                ?: error("缺少证书 PEM")
            val keyBytes = Base64.getDecoder().decode(privateKeyPem)
            val privateKey = KeyFactory.getInstance("RSA")
                .generatePrivate(PKCS8EncodedKeySpec(keyBytes))
            val certificateBytes = Base64.getDecoder().decode(certificatePem)
            val certificate = CertificateFactory.getInstance("X.509")
                .generateCertificate(certificateBytes.inputStream()) as X509Certificate
            Identity(privateKey, certificate)
        }.onFailure {
            Log.w(TAG, "读取无线调试身份失败，需要重新配对", it)
        }.getOrNull()
    }

    private fun generate(): Identity {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(KEY_SIZE_BITS, SecureRandom())
        val keyPair = generator.generateKeyPair()
        val certificate = selfSignedCertificate(keyPair)
        return Identity(keyPair.private, certificate)
    }

    private fun selfSignedCertificate(keyPair: KeyPair): X509Certificate {
        val now = System.currentTimeMillis()
        val notBefore = Date(now - DAY_MS)
        val notAfter = Date(now + VALIDITY_DAYS * DAY_MS)
        val subject = "CN=Izuki JS Wireless ADB"
        val name = org.bouncycastle.asn1.x500.X500Name(subject)
        val builder = JcaX509v3CertificateBuilder(
            name,
            BigInteger(64, SecureRandom()).abs(),
            notBefore,
            notAfter,
            name,
            keyPair.public,
        )
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
        return JcaX509CertificateConverter().getCertificate(builder.build(signer))
    }

    private fun writeToDisk(identity: Identity) {
        val pem = buildString {
            append(PRIVATE_HEADER).append('\n')
            append(Base64.getEncoder().encodeToString(identity.privateKey.encoded)).append('\n')
            append(PRIVATE_FOOTER).append('\n')
            append(CERT_HEADER).append('\n')
            append(Base64.getEncoder().encodeToString(identity.certificate.encoded)).append('\n')
            append(CERT_FOOTER).append('\n')
        }
        runCatching { AtomicFile.writeText(identityFile, SecretCipher.encrypt(pem)) }
            .onFailure { Log.w(TAG, "写入无线调试身份失败", it) }
    }

    private fun extractPem(text: String, header: String, footer: String): String? {
        val start = text.indexOf(header)
        if (start < 0) return null
        val end = text.indexOf(footer, start + header.length)
        if (end < 0) return null
        return text.substring(start + header.length, end).filterNot { it.isWhitespace() }
    }

    private companion object {
        const val TAG = "AdbKeyStore"
        const val FILE_NAME = "adb_identity.enc"
        const val KEY_SIZE_BITS = 2048
        const val VALIDITY_DAYS = 3650L
        const val DAY_MS = 24L * 60L * 60L * 1000L
        const val PRIVATE_HEADER = "-----BEGIN PRIVATE KEY-----"
        const val PRIVATE_FOOTER = "-----END PRIVATE KEY-----"
        const val CERT_HEADER = "-----BEGIN CERTIFICATE-----"
        const val CERT_FOOTER = "-----END CERTIFICATE-----"
    }
}
