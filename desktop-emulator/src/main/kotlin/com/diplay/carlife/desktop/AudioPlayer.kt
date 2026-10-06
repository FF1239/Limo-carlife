package com.diplay.carlife.desktop

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine

/**
 * PCM playback for one CarLife audio channel (media / TTS / VR). Sample rate
 * and channel count arrive in the phone's INIT message; 16-bit little-endian
 * is the on-wire format and matches Java's signed 16-bit little-endian PCM.
 */
class AudioPlayer(private val name: String) {
    private var line: SourceDataLine? = null

    @Volatile
    var playing = false
        private set

    fun init(sampleRate: Int, channelConfig: Int, sampleFormat: Int) {
        release()
        try {
            val channels = if (channelConfig == 1) 1 else 2
            val bits = if (sampleFormat == 8) 8 else 16
            val format = AudioFormat(sampleRate.toFloat(), bits, channels, true, false)
            val l = AudioSystem.getSourceDataLine(format)
            l.open(format, 64 * 1024)
            l.start()
            line = l
            playing = true
            println("[audio] $name init ${sampleRate}Hz ${channels}ch ${bits}bit")
        } catch (e: Exception) {
            println("[audio] $name init failed: $e")
        }
    }

    fun play(pcm: ByteArray) {
        val l = line ?: return
        try {
            l.write(pcm, 0, pcm.size)
        } catch (e: Exception) {
            playing = false
        }
    }

    fun release() {
        line?.let {
            runCatching { it.drain() }
            runCatching { it.stop() }
            runCatching { it.close() }
        }
        line = null
        playing = false
    }
}
