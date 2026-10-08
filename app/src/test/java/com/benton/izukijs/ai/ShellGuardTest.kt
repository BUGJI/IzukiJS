package com.benton.izukijs.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [ShellGuard] 的危险命令识别测试。 */
class ShellGuardTest {

    @Test
    fun flagsDestructiveCommands() {
        val dangerous = listOf(
            "rm -rf /",
            "sudo rm -rf /*",
            "rm -fr /",
            "mkfs.ext4 /dev/block/sda",
            "dd if=/dev/zero of=/dev/block/sda bs=1M",
            "reboot",
            "shutdown -h now",
            "pm uninstall com.example.app",
            "pm clear com.example.app",
            "settings put global animator_duration_scale 0",
            "mount -o remount,rw /system",
            "echo boom > /dev/block/sda",
        )
        dangerous.forEach { command ->
            assertTrue("应判为危险：$command", ShellGuard.isDangerous(command))
            assertNotNull(ShellGuard.reason(command))
        }
    }

    @Test
    fun allowsRoutineCommands() {
        val safe = listOf(
            "ls -la /sdcard",
            "pm list packages",
            "settings get global animator_duration_scale",
            "rm -rf /data/local/tmp/izuki_ui_dump.xml",
            "uiautomator dump /data/local/tmp/dump.xml",
            "dumpsys window | grep mCurrentFocus",
            "cat /proc/meminfo",
        )
        safe.forEach { command ->
            assertFalse("不应判为危险：$command", ShellGuard.isDangerous(command))
            assertNull(ShellGuard.reason(command))
        }
    }

    @Test
    fun blankIsSafe() {
        assertFalse(ShellGuard.isDangerous(""))
        assertFalse(ShellGuard.isDangerous("   "))
    }
}
