package com.diplay.carlife.desktop

import java.io.File

/**
 * Sets up the six `adb forward tcp:<port> tcp:<port>` rules so the desktop
 * client can reach the phone's CarLife servers over the USB cable. Mirrors the
 * original head-unit ADB connect mode.
 */
object AdbForwarder {
    private val adbCandidates = listOf(
        System.getenv("ANDROID_HOME")?.let { "$it\\platform-tools\\adb.exe" },
        "D:\\Android\\Sdk\\platform-tools\\adb.exe",
        "C:\\Android\\platform-tools\\adb.exe",
        "adb",
    ).filterNotNull()

    private fun adb(): String = adbCandidates.firstOrNull { File(it).exists() || it == "adb" }
        ?: error("adb not found — install Android platform-tools")

    val ports = intArrayOf(7240, 8240, 9240, 9241, 9242, 9340)

    fun devices(): String {
        val p = ProcessBuilder(adb(), "devices").redirectErrorStream(true).start()
        return p.inputStream.bufferedReader().readText()
    }

    fun firstSerial(): String? =
        devices().lineSequence().drop(1).mapNotNull { line ->
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size >= 2 && parts[1] == "device") parts[0] else null
        }.firstOrNull()

    fun forwardAll(serial: String) {
        val adb = adb()
        for (port in ports) {
            val p = ProcessBuilder(adb, "-s", serial, "forward", "tcp:$port", "tcp:$port")
                .redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText().trim()
            val code = p.waitFor()
            if (code != 0) error("adb forward $port failed: $out")
            println("[adb] forward tcp:$port → tcp:$port OK")
        }
    }

    fun removeForwards(serial: String) {
        val adb = adb()
        for (port in ports) {
            ProcessBuilder(adb, "-s", serial, "forward", "--remove", "tcp:$port")
                .redirectErrorStream(true).start().waitFor()
        }
    }
}
