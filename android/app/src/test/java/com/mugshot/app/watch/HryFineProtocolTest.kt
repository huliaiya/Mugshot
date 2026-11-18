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
    fun sleepRejectsPlaceholderFill() {
        val p = ByteArray(12) { 0xFF.toByte() }
        val base = HryFineProtocol.Health(sleepLightMin = 300, sleepDeepMin = 60)
        val s = HryFineProtocol.decodeHealth(0x09, p, base)
        assertEquals(300, s.sleepLightMin)
        assertEquals(60, s.sleepDeepMin)
    }

    @Test
    fun sleepRejectsZeroTotal() {
        // 全 0 只说明当天还没睡够数据，不应把已有睡眠覆盖成 0
        val p = ByteArray(12)
        val base = HryFineProtocol.Health(sleepLightMin = 300, sleepDeepMin = 60)
        val s = HryFineProtocol.decodeHealth(0x09, p, base)
        assertEquals(300, s.sleepLightMin)
        assertEquals(60, s.sleepDeepMin)
    }

    @Test
    fun spo2DecodesCountPrefixedRecords() {
        // 与心率同构：条数在 p[3]，其后每条 8 字节，数值在末字节。
        // 首条为空（低于生理区间），应跳到第二条有效值
        val p = ByteArray(20)
        p[3] = 2
        p[4 + 7] = 0
        p[4 + 8 + 7] = 97
        assertEquals(97, HryFineProtocol.decodeHealth(0x0E, p).spo2)
    }

    @Test
    fun spo2UsesOfficialSingleReadingLayout() {
        // 官方布局：[0..1] 日期、[4..7] 时间戳、[11] 血氧值
        val p = ByteArray(12)
        p[11] = 95
        assertEquals(95, HryFineProtocol.decodeHealth(0x0E, p).spo2)
    }

    @Test
    fun spo2RejectsPlaceholderValue() {
        val p = ByteArray(12)
        p[11] = 0
        val base = HryFineProtocol.Health(spo2 = 98)
        assertEquals(98, HryFineProtocol.decodeHealth(0x0E, p, base).spo2)
    }

    @Test
    fun spo2MeasurementResultAlsoAcceptedOnSub1C() {
        val p = ByteArray(12)
        p[11] = 98
        assertEquals(98, HryFineProtocol.decodeHealth(0x1C, p).spo2)
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

    @Test
    fun dayTotalsDecodeLittleEndian() {
        // 05/0C：步 5820 = 0x16BC、距 3860 = 0x0F14、卡 215 = 0x00D7（小端）
        val p = ByteArray(12).apply {
            this[0] = 0xBC.toByte(); this[1] = 0x16
            this[4] = 0x14; this[5] = 0x0F
            this[8] = 0xD7.toByte()
        }
        val h = HryFineProtocol.decodeHealth(0x0C, p)
        assertEquals(5820, h.steps)
        assertEquals(3860, h.distanceM)
        assertEquals(215, h.kcal)
    }

    @Test
    fun hourlyBucketsSumStepsDistanceAndKcal() {
        // 05/02：2 字节日期 + 24 组 8 字节（步 u16、距 u16、卡 u32，小端）
        val p = ByteArray(2 + 24 * 8)
        fun put(at: Int, steps: Int, dist: Int, kcal: Int) {
            p[at] = (steps and 0xFF).toByte()
            p[at + 1] = ((steps shr 8) and 0xFF).toByte()
            p[at + 2] = (dist and 0xFF).toByte()
            p[at + 3] = ((dist shr 8) and 0xFF).toByte()
            p[at + 4] = (kcal and 0xFF).toByte()
            p[at + 5] = ((kcal shr 8) and 0xFF).toByte()
            p[at + 6] = ((kcal shr 16) and 0xFF).toByte()
            p[at + 7] = ((kcal shr 24) and 0xFF).toByte()
        }
        put(2, 120, 84, 5)
        put(2 + 8, 300, 210, 12)
        // 0xFFFF 表示无数据，应跳过
        p[2 + 16] = 0xFF.toByte()
        p[2 + 17] = 0xFF.toByte()
        val h = HryFineProtocol.decodeHealth(0x02, p)
        assertEquals(420, h.steps)
        assertEquals(294, h.distanceM)
        assertEquals(17, h.kcal)
        assertEquals(420, h.hourlySteps)
    }

    @Test
    fun hourlyCorruptBucketIsDropped() {
        // 单小时 30000 步是字节序错位读出的脏值，整桶丢弃，不污染其它小时
        val p = ByteArray(2 + 24 * 8)
        fun put(at: Int, steps: Int, dist: Int, kcal: Int) {
            p[at] = (steps and 0xFF).toByte()
            p[at + 1] = ((steps shr 8) and 0xFF).toByte()
            p[at + 2] = (dist and 0xFF).toByte()
            p[at + 3] = ((dist shr 8) and 0xFF).toByte()
            p[at + 4] = (kcal and 0xFF).toByte()
        }
        put(2, 120, 84, 5)
        put(2 + 8, 30000, 999, 500)
        put(2 + 16, 300, 210, 12)
        val h = HryFineProtocol.decodeHealth(0x02, p)
        assertEquals(420, h.steps)
        assertEquals(294, h.distanceM)
        assertEquals(17, h.kcal)
    }

    @Test
    fun freshDayTotalsReplaceStaleSteps() {
        // 每轮同步都应覆盖上一轮的旧值：旧值 111 步不应挡住新的 5820 步
        val p = ByteArray(12).apply {
            this[0] = 0xBC.toByte(); this[1] = 0x16
            this[4] = 0x14; this[5] = 0x0F
            this[8] = 0xD7.toByte()
        }
        val base = HryFineProtocol.Health(steps = 111, distanceM = 222, kcal = 33)
        val h = HryFineProtocol.decodeHealth(0x0C, p, base)
        assertEquals(5820, h.steps)
        assertEquals(3860, h.distanceM)
        assertEquals(215, h.kcal)
    }

    @Test
    fun emptyHourlyBucketsAreIgnored() {
        // 全天无数据（全 0xFFFF）时应返回 null，交由上层保留上一轮的步数
        val p = ByteArray(2 + 24 * 8) { 0xFF.toByte() }
        assertNull(HryFineProtocol.decodeHealth(0x02, p).hourlySteps)
    }

    @Test
    fun dayTotalsRejectOutOfRangeValues() {
        // 全 0xFF：小端/大端都超上限，保持原值不变
        val p = ByteArray(12) { 0xFF.toByte() }
        val base = HryFineProtocol.Health(steps = 11, distanceM = 22, kcal = 33)
        val h = HryFineProtocol.decodeHealth(0x0C, p, base)
        assertEquals(11, h.steps)
        assertEquals(22, h.distanceM)
        assertEquals(33, h.kcal)
    }

    @Test
    fun todayPacketWithDatePrefixDecodesTotals() {
        // 05/0A：2 字节日期前缀 + 步/距/卡总计，以前整包被丢弃导致步数不更新
        val p = ByteArray(14).apply {
            this[0] = 0x11
            this[1] = 0x0B
            this[2] = 0xBC.toByte(); this[3] = 0x16
            this[6] = 0x14; this[7] = 0x0F
            this[10] = 0xD7.toByte()
        }
        val h = HryFineProtocol.decodeHealth(0x0A, p)
        assertEquals(5820, h.steps)
        assertEquals(3860, h.distanceM)
        assertEquals(215, h.kcal)
    }

    @Test
    fun todayPacketWithoutPrefixDecodesTotals() {
        val p = ByteArray(12).apply {
            this[0] = 0xBC.toByte(); this[1] = 0x16
            this[4] = 0x14; this[5] = 0x0F
            this[8] = 0xD7.toByte()
        }
        val h = HryFineProtocol.decodeHealth(0x0A, p)
        assertEquals(5820, h.steps)
        assertEquals(3860, h.distanceM)
        assertEquals(215, h.kcal)
    }

    @Test
    fun todayPacketWithHourlyBucketsSumsHours() {
        // 05/0A 也可能是整包逐时数据：2 字节日期 + 24 组 8 字节
        val p = ByteArray(2 + 24 * 8)
        fun put(at: Int, steps: Int, dist: Int, kcal: Int) {
            p[at] = (steps and 0xFF).toByte()
            p[at + 1] = ((steps shr 8) and 0xFF).toByte()
            p[at + 2] = (dist and 0xFF).toByte()
            p[at + 3] = ((dist shr 8) and 0xFF).toByte()
            p[at + 4] = (kcal and 0xFF).toByte()
        }
        put(2, 120, 84, 5)
        put(2 + 8, 300, 210, 12)
        val h = HryFineProtocol.decodeHealth(0x0A, p)
        assertEquals(420, h.steps)
        assertEquals(294, h.distanceM)
        assertEquals(17, h.kcal)
        assertEquals(420, h.hourlySteps)
    }

    @Test
    fun todayPacketKeepsExistingValuesWhenUnparsable() {
        // 全 0xFF 无法识别时不应清空已有数据
        val p = ByteArray(12) { 0xFF.toByte() }
        val base = HryFineProtocol.Health(steps = 11, distanceM = 22, kcal = 33)
        val h = HryFineProtocol.decodeHealth(0x0A, p, base)
        assertEquals(11, h.steps)
        assertEquals(22, h.distanceM)
        assertEquals(33, h.kcal)
    }

    @Test
    fun dayTotalWithDatePrefixDecodesTotals() {
        // 05/0C：2 字节日期前缀 + 步/距/卡总计；偏移 0 读出的步距比不自洽，应落到偏移 2
        val p = ByteArray(14).apply {
            this[0] = 0x11
            this[1] = 0x0B
            this[2] = 0xBC.toByte(); this[3] = 0x16
            this[6] = 0x14; this[7] = 0x0F
            this[10] = 0xD7.toByte()
        }
        val h = HryFineProtocol.decodeHealth(0x0C, p)
        assertEquals(5820, h.steps)
        assertEquals(3860, h.distanceM)
        assertEquals(215, h.kcal)
    }

    @Test
    fun dayTotalsDecodeU16Triple() {
        // 只按 u16 三项上报的固件：步 5820、距 3860、卡 215（小端）
        val p = ByteArray(6).apply {
            this[0] = 0xBC.toByte(); this[1] = 0x16
            this[2] = 0x14; this[3] = 0x0F
            this[4] = 0xD7.toByte()
        }
        val h = HryFineProtocol.decodeHealth(0x0C, p)
        assertEquals(5820, h.steps)
        assertEquals(3860, h.distanceM)
        assertEquals(215, h.kcal)
    }

    @Test
    fun dayTotalsRejectSentinelFill() {
        // 0xFFFF 是「无数据」填充，不能当成 65535 步
        val p = ByteArray(6) { 0xFF.toByte() }
        val base = HryFineProtocol.Health(steps = 11, distanceM = 22, kcal = 33)
        val h = HryFineProtocol.decodeHealth(0x0C, p, base)
        assertEquals(11, h.steps)
        assertEquals(22, h.distanceM)
        assertEquals(33, h.kcal)
    }

    @Test
    fun dayTotalsTooShortKeepsExistingValues() {
        val p = ByteArray(3)
        val base = HryFineProtocol.Health(steps = 11, distanceM = 22, kcal = 33)
        val h = HryFineProtocol.decodeHealth(0x0C, p, base)
        assertEquals(11, h.steps)
    }

    @Test
    fun hourlyPartialBucketsAreSummed() {
        // 部分固件只回到当前小时：2 字节日期 + 10 组 8 字节，不再要求满 24 组
        val p = ByteArray(2 + 10 * 8)
        fun put(at: Int, steps: Int, dist: Int, kcal: Int) {
            p[at] = (steps and 0xFF).toByte()
            p[at + 1] = ((steps shr 8) and 0xFF).toByte()
            p[at + 2] = (dist and 0xFF).toByte()
            p[at + 3] = ((dist shr 8) and 0xFF).toByte()
            p[at + 4] = (kcal and 0xFF).toByte()
        }
        put(2, 120, 84, 5)
        put(2 + 8, 300, 210, 12)
        val h = HryFineProtocol.decodeHealth(0x02, p)
        assertEquals(420, h.steps)
        assertEquals(294, h.distanceM)
        assertEquals(17, h.kcal)
    }

    @Test
    fun todayPacketWithPartialBucketsSumsHours() {
        // 05/0A 逐时整包也可能是部分桶：桶数 >= 6 仍按逐时汇总
        val p = ByteArray(2 + 8 * 8)
        fun put(at: Int, steps: Int, dist: Int, kcal: Int) {
            p[at] = (steps and 0xFF).toByte()
            p[at + 1] = ((steps shr 8) and 0xFF).toByte()
            p[at + 2] = (dist and 0xFF).toByte()
            p[at + 3] = ((dist shr 8) and 0xFF).toByte()
            p[at + 4] = (kcal and 0xFF).toByte()
        }
        put(2, 120, 84, 5)
        put(2 + 8, 300, 210, 12)
        val h = HryFineProtocol.decodeHealth(0x0A, p)
        assertEquals(420, h.steps)
        assertEquals(294, h.distanceM)
        assertEquals(17, h.kcal)
        assertEquals(420, h.hourlySteps)
    }

    @Test
    fun realtimeStepSubscribeFrameMatchesOfficial() {
        // 官方打开实时步数：DF 00 06 F3 05 01 06 00 01 01
        val frame = HryFineProtocol.encode(HryFineProtocol.GROUP_SPORTS, HryFineProtocol.SUB_HEALTH_REALTIME_STEP, byteArrayOf(0x01))
        assertArrayEquals(
            byteArrayOf(0xDF.toByte(), 0x00, 0x06, 0xF3.toByte(), 0x05, 0x01, 0x06, 0x00, 0x01, 0x01),
            frame,
        )
    }

    @Test
    fun sleepUsesOfficialBigEndianLayout() {
        // 官方布局：[2..3] 入睡、[4..5] 浅睡、[6..7] 深睡、[8..9] 起床、[10..11] 清醒（大端）
        val p = ByteArray(12)
        p[2] = 0x05; p[3] = 0xA0.toByte() // 入睡 1440 分钟
        p[4] = 0x01; p[5] = 0x7C.toByte() // 浅睡 380
        p[6] = 0x00; p[7] = 0x5F             // 深睡 95
        p[8] = 0x08; p[9] = 0x98.toByte() // 起床 2200 分钟
        p[10] = 0x00; p[11] = 0x14           // 清醒 20
        val s = HryFineProtocol.decodeHealth(0x09, p)
        assertEquals(380, s.sleepLightMin)
        assertEquals(95, s.sleepDeepMin)
        assertEquals(20, s.sleepAwakeMin)
    }

    @Test
    fun dayTotalsDecodeBigEndian() {
        // 官方 0x0C：[0..3] 步、[4..7] 距、[8..11] 卡，全部大端
        val p = ByteArray(12).apply {
            this[2] = 0x16; this[3] = 0xBC.toByte() // 5820
            this[6] = 0x0F; this[7] = 0x14          // 3860
            this[11] = 0xD7.toByte()                // 215
        }
        val h = HryFineProtocol.decodeHealth(0x0C, p)
        assertEquals(5820, h.steps)
        assertEquals(3860, h.distanceM)
        assertEquals(215, h.kcal)
    }

    @Test
    fun hourlyBucketsDecodeBigEndian() {
        // 官方 0x02：2 字节日期 + 每小时 {步 BE16, 距 BE16, 卡 BE32}
        val p = ByteArray(2 + 24 * 8)
        fun put(at: Int, steps: Int, dist: Int, kcal: Int) {
            p[at] = ((steps shr 8) and 0xFF).toByte()
            p[at + 1] = (steps and 0xFF).toByte()
            p[at + 2] = ((dist shr 8) and 0xFF).toByte()
            p[at + 3] = (dist and 0xFF).toByte()
            p[at + 4] = ((kcal shr 24) and 0xFF).toByte()
            p[at + 5] = ((kcal shr 16) and 0xFF).toByte()
            p[at + 6] = ((kcal shr 8) and 0xFF).toByte()
            p[at + 7] = (kcal and 0xFF).toByte()
        }
        put(2, 120, 84, 5)
        put(2 + 8, 300, 210, 12)
        p[2 + 16] = 0xFF.toByte()
        p[2 + 17] = 0xFF.toByte()
        val h = HryFineProtocol.decodeHealth(0x02, p)
        assertEquals(420, h.steps)
        assertEquals(294, h.distanceM)
        assertEquals(17, h.kcal)
    }

    @Test
    fun heartRateSkipsOutOfRangeSample() {
        // 最后一条是未测出的 0，应回退到上一条有效值
        val p = ByteArray(4 + 2 * 8)
        p[3] = 2
        p[4 + 7] = 72
        p[4 + 8 + 7] = 0
        assertEquals(72, HryFineProtocol.decodeHealth(0x04, p).heartRate)
    }

    @Test
    fun bloodPressureSkipsOutOfRangeSample() {
        val p = ByteArray(4 + 2 * 8)
        p[3] = 2
        p[4 + 6] = 118; p[4 + 7] = 76
        p[4 + 8 + 6] = 0; p[4 + 8 + 7] = 0
        val h = HryFineProtocol.decodeHealth(0x05, p)
        assertEquals(118, h.bpSys)
        assertEquals(76, h.bpDia)
    }

    @Test
    fun kcalFieldIsCaloriesAndConvertedToKilocalories() {
        // 官方 0x0C 回的是「卡」：8685 步 / 5385 米 / 129240 卡，应展示 129 千卡
        val p = ByteArray(12).apply {
            this[2] = 0x21; this[3] = 0xED.toByte() // 8685
            this[6] = 0x15; this[7] = 0x09          // 5385
            this[10] = 0x01; this[11] = 0x8D.toByte() // 129.24 千卡 → 取整前 129240 卡的高位在 [8..11]
        }
        // 129240 = 0x0001F8D8
        p[8] = 0x00; p[9] = 0x01; p[10] = 0xF8.toByte(); p[11] = 0xD8.toByte()
        val h = HryFineProtocol.decodeHealth(0x0C, p)
        assertEquals(8685, h.steps)
        assertEquals(5385, h.distanceM)
        assertEquals(129, h.kcal)
    }

    @Test
    fun kcalAlreadyInKilocaloriesIsKept() {
        // 少数固件直接给千卡（215），不能再次除以 1000
        val p = ByteArray(12).apply {
            this[2] = 0x16; this[3] = 0xBC.toByte()
            this[6] = 0x0F; this[7] = 0x14
            this[11] = 0xD7.toByte()
        }
        assertEquals(215, HryFineProtocol.decodeHealth(0x0C, p).kcal)
    }

    @Test
    fun hourlyKcalAlsoConvertsFromCalories() {
        val p = ByteArray(2 + 24 * 8)
        fun put(at: Int, steps: Int, dist: Int, kcal: Int) {
            p[at] = ((steps shr 8) and 0xFF).toByte()
            p[at + 1] = (steps and 0xFF).toByte()
            p[at + 2] = ((dist shr 8) and 0xFF).toByte()
            p[at + 3] = (dist and 0xFF).toByte()
            p[at + 4] = ((kcal shr 24) and 0xFF).toByte()
            p[at + 5] = ((kcal shr 16) and 0xFF).toByte()
            p[at + 6] = ((kcal shr 8) and 0xFF).toByte()
            p[at + 7] = (kcal and 0xFF).toByte()
        }
        put(2, 120, 84, 25840)
        put(2 + 8, 300, 210, 60000)
        val h = HryFineProtocol.decodeHealth(0x02, p)
        assertEquals(420, h.steps)
        assertEquals(86, h.kcal)
    }

    @Test
    fun sleepKeepsTotalWhenAwakeFieldIsInvalid() {
        // 清醒字段损坏不应连带丢掉睡眠时长
        val p = ByteArray(12)
        p[4] = 0x01; p[5] = 0x7C.toByte() // 浅睡 380
        p[6] = 0x00; p[7] = 0x5F          // 深睡 95
        p[10] = 0xFF.toByte(); p[11] = 0xFF.toByte()
        val s = HryFineProtocol.decodeHealth(0x09, p)
        assertEquals(380, s.sleepLightMin)
        assertEquals(95, s.sleepDeepMin)
        // 清醒字段无效时保持「未知」，不写 0 也不写 -1
        assertNull(s.sleepAwakeMin)
    }

    @Test
    fun sleepFallsBackToWindowWhenStagesAreZero() {
        // 手表只回入睡 23:00(1380) 与起床 08:00(480)，跨零点求时长
        val p = ByteArray(12)
        p[2] = 0x05; p[3] = 0x64.toByte() // 1380
        p[8] = 0x01; p[9] = 0xE0.toByte() // 480
        val s = HryFineProtocol.decodeHealth(0x09, p)
        assertEquals(540, s.sleepLightMin)
        assertEquals(0, s.sleepDeepMin)
    }

    @Test
    fun sleepAllZeroStaysEmpty() {
        val base = HryFineProtocol.Health(sleepLightMin = 300, sleepDeepMin = 60)
        val s = HryFineProtocol.decodeHealth(0x09, ByteArray(12), base)
        assertEquals(300, s.sleepLightMin)
        assertEquals(60, s.sleepDeepMin)
    }
    @Test
    fun sleepRejectsInconsistentStages() {
        // 浅睡 1320 明显是把「入睡分钟」读到了浅睡字段：三段之和必须接近入睡-起床跨度
        val p = ByteArray(12)
        p[2] = 0x05; p[3] = 0x28.toByte() // 入睡 1320
        p[4] = 0x05; p[5] = 0x28.toByte() // 浅睡 1320（错位）
        p[6] = 0x00; p[7] = 0x1E             // 深睡 30
        p[8] = 0x01; p[9] = 0xE0.toByte() // 起床 480
        val s = HryFineProtocol.decodeHealth(0x09, p)
        assertNull(s.sleepLightMin)
        assertNull(s.sleepDeepMin)
    }

    @Test
    fun sleepRejectsRecordOlderThanYesterday() {
        val p = sleepRecord(dateHi = 0x35, dateLo = 0x71, light = 380, deep = 95, awake = 20)
        val s = HryFineProtocol.decodeHealth(0x09, p, HryFineProtocol.Health(), 20261118)
        assertNull(s.sleepLightMin)
    }

    @Test
    fun sleepAcceptsYesterdayRecord() {
        // 昨夜的记录可能按入睡当天打日期，昨天及以后都接受
        val p = sleepRecord(dateHi = 0x35, dateLo = 0x71, light = 380, deep = 95, awake = 20)
        val s = HryFineProtocol.decodeHealth(0x09, p, HryFineProtocol.Health(), 20261117)
        assertEquals(380, s.sleepLightMin)
        assertEquals(95, s.sleepDeepMin)
    }

    @Test
    fun dateKeyDecodesCompressedDate() {
        val p = byteArrayOf(0x35, 0x72) // 2026-11-18
        assertEquals(20261118, HryFineProtocol.dateKey(p))
        assertNull(HryFineProtocol.dateKey(ByteArray(2)))
    }

    @Test
    fun parseSettingsSkipsFiveBytePrefix() {
        val payload = ByteArray(5) { 0x00 } +
            byteArrayOf(0x05, 0x01, 0x05, 0x28, 0x01, 0xE0.toByte()) +
            byteArrayOf(0x03, 1, 2, 3, 4, 5, 6, 7, 8)
        val m = HryFineProtocol.parseSettings(payload)
        assertEquals(5, m[0x05]?.size)
        assertEquals(8, m[0x03]?.size)
    }

    @Test
    fun sleepWindowEncodesOfficialFrame() {
        val w = HryFineProtocol.parseSleepWindow(
            HryFineProtocol.encodeSleepWindow(true, 22 * 60, 8 * 60)
        )
        assertEquals(true, w?.enabled)
        assertEquals(1320, w?.startMin)
        assertEquals(480, w?.endMin)
        assertEquals("开关=1 窗口=22:00-08:00", w?.describe())
        assertNull(HryFineProtocol.parseSleepWindow(null))
    }

    private fun sleepRecord(dateHi: Int, dateLo: Int, light: Int, deep: Int, awake: Int): ByteArray {
        val p = ByteArray(12)
        p[0] = dateHi.toByte(); p[1] = dateLo.toByte()
        p[2] = 0x05; p[3] = 0x28.toByte() // 入睡 22:00
        p[4] = (light shr 8).toByte(); p[5] = (light and 0xFF).toByte()
        p[6] = (deep shr 8).toByte(); p[7] = (deep and 0xFF).toByte()
        p[8] = 0x01; p[9] = 0xE0.toByte() // 起床 08:00
        p[10] = (awake shr 8).toByte(); p[11] = (awake and 0xFF).toByte()
        return p
    }

}
