package com.diplay.carlife.core

import java.io.ByteArrayOutputStream

/**
 * Minimal protobuf 2 wire-format codec covering exactly the field shapes used by
 * the CarLife message set: int32/uint32/uint64 varints, bool, string, double
 * (fixed64) and repeated messages (unpacked, one tag per element — proto2 default).
 *
 * Field numbers and types are taken from the Baidu CarlifeXxxProto.java sources;
 * negative int32 values would be 10-byte sign-extended varints, which never occur
 * in the CarLife message set.
 */
class ProtoWriter {
    private val out = ByteArrayOutputStream(64)

    fun writeVarintField(field: Int, value: Long): ProtoWriter = apply {
        tag(field, 0)
        varint(value)
    }

    fun writeInt32(field: Int, value: Int) = writeVarintField(field, value.toLong())

    fun writeUint32(field: Int, value: Long) = writeVarintField(field, value)

    fun writeUint64(field: Int, value: Long) = writeVarintField(field, value)

    fun writeBool(field: Int, value: Boolean) = writeVarintField(field, if (value) 1L else 0L)

    fun writeString(field: Int, value: String): ProtoWriter = apply {
        tag(field, 2)
        val bytes = value.toByteArray(Charsets.UTF_8)
        varint(bytes.size.toLong())
        out.write(bytes, 0, bytes.size)
    }

    fun writeBytes(field: Int, value: ByteArray): ProtoWriter = apply {
        tag(field, 2)
        varint(value.size.toLong())
        out.write(value, 0, value.size)
    }

    fun writeDouble(field: Int, value: Double): ProtoWriter = apply {
        tag(field, 1)
        val bits = java.lang.Double.doubleToLongBits(value)
        var b = 8
        while (b-- > 0) {
            out.write(((bits shr (8 * b)) and 0xFF).toInt())
        }
    }

    /** Repeated message: proto2 unpacked encoding — one tag per element. */
    fun writeMessage(field: Int, body: ByteArray): ProtoWriter = apply {
        tag(field, 2)
        varint(body.size.toLong())
        out.write(body, 0, body.size)
    }

    private fun tag(field: Int, wireType: Int) {
        varint(((field.toLong() shl 3) or wireType.toLong()))
    }

    private fun varint(v: Long) {
        var value = v
        while (true) {
            val b = (value and 0x7F).toInt()
            value = value ushr 7
            if (value == 0L) {
                out.write(b)
                return
            }
            out.write(b or 0x80)
        }
    }

    fun toByteArray(): ByteArray = out.toByteArray()
}

class ProtoReader(private val buf: ByteArray, private val start: Int = 0, private val end: Int = buf.size) {
    private var pos = start

    /** Current read offset (diagnostics). */
    val position: Int get() = pos

    fun isAtEnd(): Boolean = pos >= end

    /** Returns the next field number, or 0 at end of input. */
    fun nextField(): Int {
        if (isAtEnd()) return 0
        val key = readVarint()
        lastWireType = (key and 0x7).toInt()
        return (key ushr 3).toInt()
    }

    var lastWireType: Int = 0
        private set

    fun readInt32(): Int = readVarint().toInt()

    fun readUint32(): Long = readVarint()

    fun readUint64(): Long = readVarint()

    fun readBool(): Boolean = readVarint() != 0L

    fun readString(): String {
        val len = readVarint().toInt()
        checkLengthDelimited(len)
        val s = String(buf, pos, len, Charsets.UTF_8)
        pos += len
        return s
    }

    fun readBytes(): ByteArray {
        val len = readVarint().toInt()
        checkLengthDelimited(len)
        val copy = buf.copyOfRange(pos, pos + len)
        pos += len
        return copy
    }

    /** A truncated or hostile length must fail loudly, not silently zero-fill. */
    private fun checkLengthDelimited(len: Int) {
        if (len < 0 || len > end - pos) {
            throw ProtocolException(
                "length-delimited field overruns buffer: len=$len remaining=${end - pos}",
            )
        }
    }

    fun readDouble(): Double {
        var bits = 0L
        for (i in 0 until 8) {
            bits = (bits shl 8) or (buf[pos + i].toLong() and 0xFF)
        }
        pos += 8
        return java.lang.Double.longBitsToDouble(bits)
    }

    /** Skips a field the reader does not care about, based on [lastWireType]. */
    fun skip() {
        when (lastWireType) {
            0 -> readVarint()
            1 -> pos += 8
            2 -> {
                // Not `pos += readVarint()`: the property is read before the RHS
                // runs, and readVarint() itself advances pos — that double-counts
                // the length byte and desyncs the stream.
                val len = readVarint().toInt()
                pos += len
            }
            5 -> pos += 4
            else -> throw ProtocolException("unsupported wire type $lastWireType")
        }
    }

    private fun readVarint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            check(pos < end) { "varint runs past end of buffer" }
            val b = buf[pos++].toInt()
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
            if (shift >= 64) throw ProtocolException("varint too long")
        }
    }
}

class ProtocolException(message: String) : RuntimeException(message)
