package com.diplay.carlife.desktop

import com.diplay.carlife.core.CarLifeSession

/**
 * Desktop "fake head unit" entry point for testing the CarLife channel with a
 * real phone (the user provides the phone; this program is the head unit — a
 * 2025 Accord mid-trim 12.3" 1920x720 form).
 *
 * Usage:
 *   adb mode : gradlew :desktop-emulator:run --args="adb"                (phone over USB, USB debugging on)
 *   wifi mode: gradlew :desktop-emulator:run --args="wifi 192.168.x.x"   (phone in CarLife wireless wait)
 */
fun main(args: Array<String>) {
    val mode = args.firstOrNull() ?: "adb"
    require(mode == "adb" || mode == "wifi") { "mode must be 'adb' or 'wifi <ip>'" }

    val target = when (mode) {
        "adb" -> {
            val serial = AdbForwarder.firstSerial()
                ?: error("no adb device — plug the phone in with USB debugging enabled, check: adb devices")
            println("[adb] phone: $serial — setting up port forwards…")
            AdbForwarder.forwardAll(serial)
            "127.0.0.1"
        }
        else -> args.getOrNull(1) ?: error("wifi mode needs the phone's IP: wifi 192.168.x.x")
    }

    println("[hu] DiPlay-CarLife fake head unit — target $target (Accord 12.3\" 1920x720)")

    val window = AccordHeadUnitWindow()
    val pipe = window.pipe

    var session: CarLifeSession? = null
    val client = CarLifeTcpClient(target, listener = object : CarLifeTcpClient.Listener {
        override fun onChannelConnected(channel: Int) {
            println("[tcp] channel $channel connected")
        }

        override fun onChannelClosed(channel: Int, reason: String) {
            println("[tcp] channel $channel closed: $reason")
            window.status("通道 $channel 断开: $reason")
        }

        override fun onBytes(channel: Int, data: ByteArray, offset: Int, length: Int) {
            session?.onBytes(data, offset, length)
        }

        override fun onAllConnected() {
            println("[tcp] all 6 channels up — starting handshake")
            window.status("6 通道已连接 — 发起握手")
            session?.startHandshake()
        }
    })

    session = CarLifeSession(client, pipe).also { pipe.session = it }

    window.show()
    window.installTouchInput()
    window.installHardKeys()
    window.status("连接 $target 中(手机端需处于 CarLife 连接等待)…")

    client.start()
    Thread.currentThread().join()
}
