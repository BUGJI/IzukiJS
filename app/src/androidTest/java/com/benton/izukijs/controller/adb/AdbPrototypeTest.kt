package com.benton.izukijs.controller.adb

import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.benton.izukijs.core.log.LogBus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P0 原型验证，不进入正式 UI。
 *
 * 用法：
 * ```
 * adb shell am instrument -w \
 *   -e class com.benton.izukijs.controller.adb.AdbPrototypeTest \
 *   -e pairPort <系统配对对话框端口> \
 *   -e pairCode <6 位配对码> \
 *   com.benton.izukijs.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 *
 * 已有配对身份时可只跑重连：
 * ```
 * adb shell am instrument -w -e class ...AdbPrototypeTest#reconnectAndRun \
 *   com.benton.izukijs.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 */
@RunWith(AndroidJUnit4::class)
class AdbPrototypeTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val args = InstrumentationRegistry.getArguments()
    private val logBus = LogBus()

    @Test
    fun keyStoreGeneratesAndReloadsIdentity() {
        val store = AdbKeyStore(context)
        val first = store.loadOrCreate()
        Log.i(TAG, "key alg=${first.privateKey.algorithm} cert=${first.certificate.subjectX500Principal.name}")
        assertTrue("私钥不是 RSA", first.privateKey.algorithm == "RSA")
        assertTrue("证书主题异常", first.certificate.subjectX500Principal.name.contains("Izuki"))

        val second = AdbKeyStore(context).loadOrCreate()
        assertEquals("证书未持久化", first.certificate, second.certificate)
        assertEquals("私钥未持久化", first.privateKey, second.privateKey)
    }

    @Test
    fun pairConnectAndRun() {
        assumeTrue("无线调试需要 Android 11+", Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
        val pairPort = args.getString("pairPort")?.toIntOrNull() ?: 0
        val pairingCode = args.getString("pairCode").orEmpty()
        val connectPort = args.getString("connectPort")?.toIntOrNull() ?: 0
        assumeTrue("需要 -e pairPort 和 -e pairCode", pairPort > 0 && pairingCode.isNotBlank())

        val keyStore = AdbKeyStore(context)
        val manager = AdbConnectionManager(context, keyStore, logBus)
        val host = args.getString("pairHost")?.takeIf { it.isNotBlank() }
            ?: AdbNetworkUtil.preferredIpv4Address()
            ?: error("找不到本机 IPv4 地址")

        runBlocking {
            Log.i(TAG, "pair host=$host port=$pairPort connectPort=$connectPort")
            var connected = manager.pairAndConnect(host, pairPort, pairingCode)
            if (!connected && connectPort > 0) {
                Log.i(TAG, "mDNS 连接失败，改用配对页显示的连接端口 $connectPort")
                connected = manager.connectManual(host, connectPort)
            }
            assertTrue("配对或连接失败", connected)
            runAssertions(manager)
            manager.disconnectConnection()
        }
    }

    @Test
    fun manualConnectAndRun() {
        assumeTrue("无线调试需要 Android 11+", Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
        val connectPort = args.getString("connectPort")?.toIntOrNull() ?: 0
        assumeTrue("需要 -e connectPort", connectPort > 0)
        val keyStore = AdbKeyStore(context)
        assumeTrue("尚未配对，先运行 pairConnectAndRun", keyStore.hasIdentity())

        val host = args.getString("pairHost")?.takeIf { it.isNotBlank() }
            ?: AdbNetworkUtil.preferredIpv4Address()
            ?: error("找不到本机 IPv4 地址")
        val manager = AdbConnectionManager(context, keyStore, logBus)
        runBlocking {
            assertTrue("手动连接失败", manager.connectManual(host, connectPort))
            runAssertions(manager)
            manager.disconnectConnection()
        }
    }

    @Test
    fun reconnectAndRun() {
        assumeTrue("无线调试需要 Android 11+", Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
        val keyStore = AdbKeyStore(context)
        assumeTrue("尚未配对，先运行 pairConnectAndRun", keyStore.hasIdentity())

        val connectPort = args.getString("connectPort")?.toIntOrNull() ?: 0
        val host = args.getString("pairHost")?.takeIf { it.isNotBlank() }
            ?: AdbNetworkUtil.preferredIpv4Address()
            ?: error("找不到本机 IPv4 地址")
        val manager = AdbConnectionManager(context, keyStore, logBus)
        runBlocking {
            var connected = manager.connect()
            if (!connected && connectPort > 0) {
                Log.i(TAG, "mDNS 重连失败，改用手动端口 $connectPort")
                connected = manager.connectManual(host, connectPort)
            }
            assertTrue("重连失败", connected)
            runAssertions(manager)
            manager.disconnectConnection()
        }
    }

    private suspend fun runAssertions(manager: AdbConnectionManager) {
        val shell = AdbShell(manager, logBus)

        val id = shell.run("id")
        Log.i(TAG, "id exit=${id?.exitCode} out=${id?.stdout}")
        assertTrue("id 输出缺少 shell uid", id?.stdout?.contains("uid=2000") == true)

        val failed = shell.run("false")
        assertEquals("退出码解析错误", 1, failed?.exitCode)

        val screenshot = shell.screenshotPng(null)
        Log.i(TAG, "screenshot bytes=${screenshot?.size}")
        assertTrue("截图为空或不是 PNG", screenshot != null && screenshot.size > 8)

        val dump = shell.run("uiautomator dump /data/local/tmp/izuki_p0_ui.xml && cat /data/local/tmp/izuki_p0_ui.xml && rm -f /data/local/tmp/izuki_p0_ui.xml")
        Log.i(TAG, "dump exit=${dump?.exitCode} size=${dump?.stdout?.length}")
        assertTrue("uiautomator dump 失败", dump?.stdout?.contains("<hierarchy") == true)
    }

    private companion object {
        const val TAG = "AdbPrototypeTest"
    }
}
