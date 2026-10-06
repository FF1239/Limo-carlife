package com.diplay.carlife.core

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * CarLife frame packing/parsing.
 *
 * Wire format (all integers big-endian):
 *   Transport header (8B): [u32 channelId][u32 msgLen]  where msgLen = channelHeader + payload
 *   CMD/TOUCH channel header (8B): [u16 payloadLen][u16 reserved][u32 serviceType]
 *   VIDEO/MUSIC/TTS/VR header (12B): [u32 payloadLen][u32 timestamp][u32 serviceType]
 *
 * On USB the sender issues the 8-byte transport header and the message body as two
 * separate bulk transfers (AOAHostSetup.bulkTransferOut(head, lenHead, msg, lenMsg));
 * over TCP they are written back-to-back on the stream, which is equivalent.
 */
object CarLifeFrame {

    data class Parsed(
        val channel: Int,
        val serviceType: Long,
        val timestamp: Long,
        val payload: ByteArray,
    )

    fun channelHeaderSize(channel: Int): Int =
        if (channel == CarLife.CHANNEL_CMD || channel == CarLife.CHANNEL_TOUCH) {
            CarLife.CHANNEL_HEADER_SIZE_CMD
        } else {
            CarLife.CHANNEL_HEADER_SIZE_STREAM
        }

    /** Packs one complete frame (transport header + channel header + payload). */
    fun pack(
        channel: Int,
        serviceType: Long,
        payload: ByteArray = ByteArray(0),
        timestamp: Long = 0,
    ): ByteArray {
        val headSize = channelHeaderSize(channel)
        val frame = ByteArray(CarLife.TRANSPORT_HEADER_SIZE + headSize + payload.size)
        val buf = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(channel)
        buf.putInt(headSize + payload.size)
        if (headSize == CarLife.CHANNEL_HEADER_SIZE_CMD) {
            buf.putShort(payload.size.toShort())
            buf.putShort(0)
        } else {
            buf.putInt(payload.size)
            buf.putInt(timestamp.toInt())
        }
        buf.putInt(serviceType.toInt())
        buf.put(payload)
        return frame
    }

    /** Splits [pack] output the way the USB bulk layer sends it. */
    fun packSplit(channel: Int, serviceType: Long, payload: ByteArray, timestamp: Long = 0): Pair<ByteArray, ByteArray> {
        val headSize = channelHeaderSize(channel)
        val head = ByteArray(CarLife.TRANSPORT_HEADER_SIZE)
        val body = ByteArray(headSize + payload.size)
        val hb = ByteBuffer.wrap(head).order(ByteOrder.BIG_ENDIAN)
        hb.putInt(channel)
        hb.putInt(headSize + payload.size)
        val bb = ByteBuffer.wrap(body).order(ByteOrder.BIG_ENDIAN)
        if (headSize == CarLife.CHANNEL_HEADER_SIZE_CMD) {
            bb.putShort(payload.size.toShort())
            bb.putShort(0)
        } else {
            bb.putInt(payload.size)
            bb.putInt(timestamp.toInt())
        }
        bb.putInt(serviceType.toInt())
        bb.put(payload)
        return head to body
    }

    /**
     * Incremental stream splitter: feed bytes, get complete frames out. Handles
     * partial reads across arbitrary chunk boundaries.
     */
    class Splitter {
        private var buffer = ByteArray(0)

        fun feed(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): List<Parsed> {
            val joined = ByteArray(buffer.size + length)
            System.arraycopy(buffer, 0, joined, 0, buffer.size)
            System.arraycopy(data, offset, joined, buffer.size, length)
            buffer = joined

            val frames = ArrayList<Parsed>()
            while (true) {
                if (buffer.size < CarLife.TRANSPORT_HEADER_SIZE) break
                val head = ByteBuffer.wrap(buffer, 0, CarLife.TRANSPORT_HEADER_SIZE).order(ByteOrder.BIG_ENDIAN)
                val channel = head.int
                val msgLen = head.int
                if (channel < CarLife.CHANNEL_MIN || channel > CarLife.CHANNEL_MAX ||
                    msgLen < CarLife.CHANNEL_HEADER_SIZE_CMD || msgLen > CarLife.MAX_MSG_BYTES
                ) {
                    throw ProtocolException("invalid transport header: channel=$channel msgLen=$msgLen")
                }
                if (buffer.size < CarLife.TRANSPORT_HEADER_SIZE + msgLen) break

                val body = ByteBuffer.wrap(
                    buffer,
                    CarLife.TRANSPORT_HEADER_SIZE,
                    msgLen,
                ).order(ByteOrder.BIG_ENDIAN)
                // wrap(buffer, 8, msgLen) positions at absolute index 8, which is
                // exactly where the channel header lives; reads below are absolute.
                val payloadLen: Int
                val timestamp: Long
                if (channel == CarLife.CHANNEL_CMD || channel == CarLife.CHANNEL_TOUCH) {
                    payloadLen = body.short.toInt() and 0xFFFF
                    body.short // reserved
                    timestamp = 0
                } else {
                    payloadLen = body.int
                    timestamp = body.int.toLong() and 0xFFFFFFFFL
                }
                val serviceType = body.int.toLong() and 0xFFFFFFFFL
                // body.position() is absolute in `buffer` (wrap offset = header size)
                val payloadStart = body.position()
                val payload = buffer.copyOfRange(payloadStart, payloadStart + payloadLen)

                frames.add(Parsed(channel, serviceType, timestamp, payload))

                val consumed = CarLife.TRANSPORT_HEADER_SIZE + msgLen
                buffer = if (buffer.size == consumed) ByteArray(0) else buffer.copyOfRange(consumed, buffer.size)
            }
            return frames
        }
    }
}
