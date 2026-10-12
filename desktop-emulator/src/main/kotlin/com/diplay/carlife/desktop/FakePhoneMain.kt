package com.diplay.carlife.desktop

import com.diplay.carlife.core.CarLifeMessages

/**
 * Fake CarLife phone (MD role) for desktop end-to-end testing of the real
 * Android head-unit app: listens on the six MD ports, answers the handshake,
 * and PRINTS every touch/hard-key frame the head unit sends. Point the head
 * unit at this PC's LAN IP (manual IP or the "host" intent extra).
 *
 * Touch events arriving here prove the head unit's touch pipeline
 * (MotionEvent → coordinate mapping → TOUCH channel framing) is correct.
 */
private fun log(line: String) {
    println(line)
    System.out.flush() // redirected stdout is block-buffered; flush every line
}

fun main() {
    val host = FakePhone(
        deviceInfo = CarLifeMessages.DeviceInfo(
            os = "android",
            model = "FakePhone-PC",
            brand = "DiPlay",
            manufacturer = "DiPlay",
        ),
        listener = object : FakePhone.Listener {
            override fun onEvent(line: String) = log("[MD] $line")
            override fun onVideoStarted(width: Int, height: Int, frameRate: Int) =
                log("[MD] video started ${width}x${height}@$frameRate (no real stream in this harness)")
            override fun onAudioStarted(sampleRate: Int, channels: Int) =
                log("[MD] audio started $sampleRate Hz x$channels")
            override fun onTouch(action: Int, x: Int, y: Int) {
                val name = when (action) {
                    0 -> "DOWN"
                    1 -> "UP"
                    2 -> "MOVE"
                    else -> "?$action"
                }
                log("[TOUCH] $name $x,$y")
            }
            override fun onHardKey(code: Int) = log("[HARDKEY] code=$code")
            override fun onClientConnected(channel: Int) = log("[MD] channel $channel connected")
            override fun onAllConnected() = log("[MD] all 6 channels connected — session ready")
        },
    )
    host.start()
    log("FakePhone listening on CMD 7240 / VIDEO 8240 / MUSIC 9240 / TTS 9241 / VR 9242 / TOUCH 9340")
    log("Point the head unit (manual IP) at this PC, then touch its screen — events print below.")
    while (true) Thread.sleep(1_000)
}
