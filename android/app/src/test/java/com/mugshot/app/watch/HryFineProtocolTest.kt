package com.mugshot.app.watch

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HryFineProtocolTest {

    @Test
    fun encodeMatchesChecksumAndLength() {
        val frame = HryFineProtocol.encode(0x05, 0x0C, byteArrayOf(0, 0, 0x03, 0xE8.toByte()))
        assertEquals(0xDF, frame[0].toInt() and 0xFF)
        val len = ((frame[1].toInt() and 0xFF) shl 8) or (frame[2].toInt() and 0xFF)
        assertEquals(9, len)
        assertEquals(frame.size, len + 4)
        val copy = frame.copyOf().also { it[3] = 0 }
        assertEquals(HryFineProtocol.checksum(copy), frame[3].toInt() and 0xFF)
    }

    @Test
    fun encodeKeepsGroupAndCustomTypeByte() {
        val frame = HryFineProtocol.encode(0x03, 0x00, byteArrayOf(1, 2), 0x21)
        assertEquals(0x03, frame[4].toInt() and 0xFF)
        assertEquals(0x21, frame[5].toInt() and 0xFF)
        assertEquals(0x00, frame[6].toInt() and 0xFF)
    }

    @Test
    fun measureCommandMatchesOfficialFrame() {
        // 官方 App 打开心率测量发出的帧：DF 00 06 F7 02 01 0D 00 01 01
        val frame = HryFineProtocol.encode(0x02, 0x0D, byteArrayOf(0x01), 0x01)
        assertArrayEquals(
            byteArrayOf(0xDF.toByte(), 0x00, 0x06, 0xF7.toByte(), 0x02, 0x01, 0x0D, 0x00, 0x01, 0x01),
            frame,
        )
    }

    @Test
    fun parseRoundTripAndRejectBadHeader() {
        val raw = HryFineProtocol.encode(0x09, 0x00, ByteArray(0))
        val frame = HryFineProtocol.parse(raw)
        assertNotNull(frame)
        assertFalse(frame!!.isAck)
        assertEquals(0x09, frame.group)
        assertEquals(0x00, frame.sub)
        assertTrue(frame.checksumOk)
        assertEquals(0, frame.payload.size)
        assertNull(HryFineProtocol.parse(byteArrayOf(0x00, 0x00, 0x00, 0x00)))
    }

    @Test
    fun checksumDetectsCorruption() {
        val raw = HryFineProtocol.encode(0xF3, 0x00, byteArrayOf(1, 2, 3))
        raw[6] = (raw[6].toInt() xor 0xFF).toByte()
        val frame = HryFineProtocol.parse(raw)
        assertNotNull(frame)
        assertFalse(frame!!.checksumOk)
    }

    @Test
    fun ackCarriesGroupAndSub() {
        val data = HryFineProtocol.parse(HryFineProtocol.encode(0x05, 0x0C, ByteArray(4)))!!
        val ack = HryFineProtocol.ack(data)
        assertEquals(0xFD, ack[0].toInt() and 0xFF)
        assertEquals(0x05, ack[4].toInt() and 0xFF)
        assertEquals(0x0C, ack[5].toInt() and 0xFF)
        val copy = ack.copyOf().also { it[3] = 0 }
        assertEquals(HryFineProtocol.checksum(copy), ack[3].toInt() and 0xFF)
    }

    @Test
    fun reassemblerHandlesSplitAndPackedFrames() {
        val a = HryFineProtocol.encode(0x05, 0x0C, byteArrayOf(1, 2, 3, 4))
        val b = HryFineProtocol.encode(0x09, 0x09, byteArrayOf(5))
        val r = HryFineProtocol.Reassembler()
        assertTrue(r.feed(a.copyOfRange(0, 5)).isEmpty())
        val first = r.feed(a.copyOfRange(5, a.size) + b)
        assertEquals(2, first.size)
        assertArrayEquals(a, first[0])
        assertArrayEquals(b, first[1])
    }

    @Test
    fun protocolVersionFallsBackToNull() {
        assertEquals(0x10, HryFineProtocol.parseProtocolVersion("16"))
        assertEquals(2, HryFineProtocol.parseProtocolVersion("V2.1"))
        assertNull(HryFineProtocol.parseProtocolVersion("abc"))
        assertNull(HryFineProtocol.parseProtocolVersion(null))
    }

    @Test
    fun featuresFlagMapping() {
        val bytes = ByteArray(16)
        bytes[0] = 0xAA.toByte()
        bytes[15] = 0x3C
        val f = HryFineProtocol.parseFeatures(bytes)
        assertTrue(f.valid)
        assertTrue(f.sdk)
        assertTrue(f.deviceInfo)
        assertTrue(f.medicine)
        assertTrue(f.drinkWater)
        assertFalse(HryFineProtocol.parseFeatures(ByteArray(4)).valid)
    }

    @Test
    fun challengeCodeIsXorOfUserId() {
        val id = "MG123456".toByteArray(Charsets.US_ASCII)
        val expected = id.sumOf { (it.toInt() and 0xFF) xor 0xFF } and 0xFF
        assertEquals(expected, HryFineProtocol.challengeCode(id))
    }

    @Test
    fun deviceInfoSplitsLengthPrefixedStrings() {
        val firmware = "1.2.3"
        val project = "W1"
        val payload = byteArrayOf(firmware.length.toByte()) +
            firmware.toByteArray() +
            byteArrayOf(project.length.toByte()) +
            project.toByteArray()
        val info = HryFineProtocol.parseDeviceInfo(payload)
        assertNotNull(info)
        assertEquals(firmware, info!!.firmware)
        assertEquals(project, info.project)
    }

    @Test
    fun settingsParsedByKnownLengths() {
        val payload = byteArrayOf(0x03) + ByteArray(8) + byteArrayOf(0x07) + ByteArray(5)
        val out = HryFineProtocol.parseSettings(payload)
        assertEquals(8, out[0x03]!!.size)
        assertEquals(5, out[0x07]!!.size)
        assertNull(out[0x01])
    }

    @Test
    fun healthDayTotals() {
        val p = byteArrayOf(0, 0, 0x16, 0xC4.toByte(), 0, 0, 0x0F, 0x3C, 0, 0, 0x00, 0xD2.toByte())
        val h = HryFineProtocol.decodeHealth(0x0C, p)
        assertEquals(5828, h.steps)
        assertEquals(3900, h.distanceM)
        assertEquals(210, h.kcal)
    }

    @Test
    fun healthSleepAndSpo2() {
        val p = ByteArray(12)
        p[4] = 0x01; p[5] = 0xB8.toByte() // 440 light
        p[6] = 0x00; p[7] = 0x5A          // 90 deep
        p[11] = 98
        val sleep = HryFineProtocol.decodeHealth(0x09, p)
        assertEquals(440, sleep.sleepLightMin)
        assertEquals(90, sleep.sleepDeepMin)
        assertEquals(98, HryFineProtocol.decodeHealth(0x0E, p).spo2)
    }

    @Test
    fun healthHeartRateTakesLastSample() {
        val p = ByteArray(4 + 2 * 8)
        p[3] = 2
        p[4 + 7] = 66
        p[4 + 8 + 7] = 72
        assertEquals(72, HryFineProtocol.decodeHealth(0x04, p).heartRate)
    }

    @Test
    fun packedDateUsesBigEndianBitFields() {
        val cal = java.util.Calendar.getInstance().apply {
            set(2026, java.util.Calendar.MARCH, 5, 7, 8, 9)
        }
        val b = HryFineProtocol.packDateTime(cal.timeInMillis)
        val packed = ((b[0].toInt() and 0xFF) shl 24) or
            ((b[1].toInt() and 0xFF) shl 16) or
            ((b[2].toInt() and 0xFF) shl 8) or
            (b[3].toInt() and 0xFF)
        assertEquals(26, (packed ushr 26) and 0x3F)
        assertEquals(3, (packed ushr 22) and 0x0F)
        assertEquals(5, (packed ushr 17) and 0x1F)
        assertEquals(7, (packed ushr 12) and 0x1F)
        assertEquals(8, (packed ushr 6) and 0x3F)
        assertEquals(9, packed and 0x3F)
    }
}
