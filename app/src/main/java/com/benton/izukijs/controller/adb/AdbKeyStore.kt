package com.benton.izukijs.controller.adb

import android.content.Context
import android.util.Log
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

    @Synchronized
    fun hasIdentity(): Boolean = cached != null || identityFile.exists()

    @Synchronized
    fun loadOrCreate(): Identity {
        cached?.let { return it }
        readFromDisk()?.let {
            cached = it
            return it
        }
        return generate().also { identity ->
            cached = identity
            writeToDisk(identity)
        }
    }

    @Synchronized
    fun clear() {
        cached = null
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
            Log.w(TAG, "读取无线调试身份失败，将重新生成", it)
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
        runCatching { identityFile.writeText(SecretCipher.encrypt(pem), Charsets.UTF_8) }
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
