package com.diplay.carlife.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameGoldenTest {

    @Test
    fun `protocol version protobuf golden`() {
        // CarlifeProtocolVersion{majorVersion=1, minorVersion=0}: field1 varint, field2 varint
        assertArrayEquals(byteArrayOf(0x08, 0x01, 0x10, 0x00), CarLifeMessages.ProtocolVersion(1, 0).encode())
    }

    @Test
    fun `authen request protobuf golden`() {
        // field1 string: tag 0x0A, len 13, "CarLife_1.0.0"
        val expected = byteArrayOf(0x0A, 0x0D) + "CarLife_1.0.0".toByteArray(Charsets.UTF_8)
        assertArrayEquals(expected, CarLifeMessages.AuthenRequest("CarLife_1.0.0").encode())
    }

    @Test
    fun `video encoder info protobuf golden`() {
        // 768 = 0x300 -> varint 0x80 0x06; 480 = 0x1E0 -> varint 0xE0 0x03; 30 = 0x1E
        assertArrayEquals(
            byteArrayOf(0x08, 0x80.toByte(), 0x06, 0x10, 0xE0.toByte(), 0x03, 0x18, 0x1E),
            CarLifeMessages.VideoEncoderInfo(768, 480, 30).encode(),
        )
    }

    @Test
    fun `touch action protobuf golden`() {
        // action=0, x=100, y=200: 08 00 | 10 64 | 18 C8 01 (200 -> two-byte varint)
        assertArrayEquals(
            byteArrayOf(0x08, 0x00, 0x10, 0x64, 0x18, 0xC8.toByte(), 0x01),
            CarLifeMessages.TouchAction(0, 100, 200).encode(),
        )
    }

    @Test
    fun `cmd frame golden - version negotiation`() {
        val payload = byteArrayOf(0x08, 0x01, 0x10, 0x00)
        val frame = CarLifeFrame.pack(CarLife.CHANNEL_CMD, CarLife.Cmd.HU_PROTOCOL_VERSION.toLong(), payload)
        val expected = byteArrayOf(
            // transport: channel=1, msgLen=8+4=12
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x0C,
            // channel header: payloadLen=4 (u16), reserved=0, serviceType=0x00018001
            0x00, 0x04, 0x00, 0x00, 0x00, 0x01, 0x80.toByte(), 0x01,
            // protobuf
            0x08, 0x01, 0x10, 0x00,
        )
        assertArrayEquals(expected, frame)
    }

    @Test
    fun `stream frame golden - video data with timestamp`() {
        val payload = byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x04)
        val frame = CarLifeFrame.pack(
            CarLife.CHANNEL_VIDEO,
            CarLife.Stream.VIDEO_DATA.toLong(),
            payload,
            timestamp = 0x11223344L,
        )
        val expected = byteArrayOf(
            // transport: channel=2, msgLen=12+5=17
            0x00, 0x00, 0x00, 0x02, 0x00, 0x00, 0x00, 0x11,
            // channel header: payloadLen=5 (u32), timestamp, serviceType=0x00020001
            0x00, 0x00, 0x00, 0x05, 0x11, 0x22, 0x33, 0x44, 0x00, 0x02, 0x00, 0x01,
            0x00, 0x01, 0x02, 0x03, 0x04,
        )
        assertArrayEquals(expected, frame)
    }

    @Test
    fun `packSplit equals pack concatenation`() {
        val payload = byteArrayOf(1, 2, 3)
        val packed = CarLifeFrame.pack(CarLife.CHANNEL_TOUCH, CarLife.Stream.TOUCH_ACTION.toLong(), payload)
        val (head, body) = CarLifeFrame.packSplit(CarLife.CHANNEL_TOUCH, CarLife.Stream.TOUCH_ACTION.toLong(), payload)
        assertArrayEquals(packed, head + body)
    }

    @Test
    fun `splitter handles byte-by-byte feeding and multiple frames`() {
        val f1 = CarLifeFrame.pack(CarLife.CHANNEL_CMD, CarLife.Cmd.HU_PROTOCOL_VERSION.toLong(), byteArrayOf(0x08, 0x01, 0x10, 0x00))
        val f2 = CarLifeFrame.pack(CarLife.CHANNEL_VIDEO, CarLife.Stream.VIDEO_DATA.toLong(), byteArrayOf(9, 8, 7), 77L)
        val stream = f1 + f2

        val splitter = CarLifeFrame.Splitter()
        val out = ArrayList<CarLifeFrame.Parsed>()
        var i = 0
        while (i < stream.size) {
            out.addAll(splitter.feed(stream, i, 1))
            i += 1
        }

        assertEquals(2, out.size)
        assertEquals(CarLife.CHANNEL_CMD, out[0].channel)
        assertEquals(CarLife.Cmd.HU_PROTOCOL_VERSION.toLong(), out[0].serviceType)
        assertArrayEquals(byteArrayOf(0x08, 0x01, 0x10, 0x00), out[0].payload)
        assertEquals(CarLife.CHANNEL_VIDEO, out[1].channel)
        assertEquals(CarLife.Stream.VIDEO_DATA.toLong(), out[1].serviceType)
        assertEquals(77L, out[1].timestamp)
        assertArrayEquals(byteArrayOf(9, 8, 7), out[1].payload)
    }

    @Test
    fun `splitter rejects invalid channel`() {
        val splitter = CarLifeFrame.Splitter()
        var thrown: ProtocolException? = null
        try {
            splitter.feed(byteArrayOf(0, 0, 0, 9, 0, 0, 0, 8, 0, 0, 0, 0, 0, 0, 0, 0))
        } catch (e: ProtocolException) {
            thrown = e
        }
        assertTrue(thrown != null)
    }

    @Test
    fun `device info round trip`() {
        val info = CarLifeMessages.DeviceInfo(
            os = "C12346", model = "JY-2016", brand = "carlife", sdkInt = 28, serial = "ABC123",
        )
        val decoded = CarLifeMessages.DeviceInfo.decode(info.encode())
        assertEquals(info, decoded)
    }

    @Test
    fun `feature config list round trip with repeated fields`() {
        val list = CarLifeMessages.FeatureConfigList(
            listOf(
                CarLifeMessages.FeatureConfig("FOCUS_UI", 1),
                CarLifeMessages.FeatureConfig("VOICE_MIC", 2),
            ),
        )
        // decode via the ModuleStatusList-shaped parsing (cnt + repeated messages)
        val r = ProtoReader(list.encode())
        var f = r.nextField()
        var count = -1
        val items = ArrayList<CarLifeMessages.FeatureConfig>()
        while (f != 0) {
            when (f) {
                1 -> count = r.readInt32()
                2 -> items.add(CarLifeMessages.FeatureConfig.decode(r.readBytes()))
                else -> r.skip()
            }
            f = r.nextField()
        }
        assertEquals(2, count)
        assertEquals(listOf("FOCUS_UI" to 1, "VOICE_MIC" to 2), items.map { it.key to it.value })
    }

    @Test
    fun `media info decode skips unknown fields`() {
        // source(1)="media", song(2)="track", artist(3)="a", album(4)="al",
        // albumArt(5, bytes), duration(6)=100, playlistNum(7)=9, songId(8)="id", mode(9)=2
        val encoded = ProtoWriter().apply {
            writeString(1, "media")
            writeString(2, "track")
            writeString(3, "a")
            writeString(4, "al")
            writeBytes(5, byteArrayOf(1, 2, 3))
            writeInt32(6, 100)
            writeInt32(7, 9)
            writeString(8, "id")
            writeInt32(9, 2)
        }.toByteArray()
        val info = CarLifeMessages.MediaInfo.decode(encoded)
        assertEquals("media", info.source)
        assertEquals("track", info.song)
        assertEquals("a", info.artist)
        assertEquals("al", info.album)
        assertEquals(100, info.duration)
        assertEquals(2, info.mode)
    }

    @Test
    fun `audio init round trip`() {
        val init = CarLifeMessages.AudioInit(44100, 12, 2)
        assertEquals(init, CarLifeMessages.AudioInit.decode(init.encode()))
    }
}
