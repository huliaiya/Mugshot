package com.mugshot.app.watch

/**
 * HryFine（联合动力）手表 BLE 协议编解码。
 *
 * 帧格式：[DF, lenHi, lenLo, sum8, group, type, sub, plenHi, plenLo, payload...]
 *  - len = payload.size + 5，整帧长度 = len + 4
 *  - sum8 为整帧逐字节求和（校验位先置 0）低 8 位
 *  - group 为命令分组（0x05 健康、0x09 设置、0x03 绑定等）
 * ACK：[FD, 00, 05, sum8, group, sub, 00, 00, 01]
 *
 * 纯函数实现，便于单元测试；健康数据字段布局参考社区抓包实现。
 */
object HryFineProtocol {
    const val SERVICE_UUID = "6e400001-b5a3-f393-e0a9-e50e24dcca9f"
    const val WRITE_UUID = "6e400002-b5a3-f393-e0a9-e50e24dcca9f"
    const val NOTIFY_UUID = "6e400003-b5a3-f393-e0a9-e50e24dcca9f"

    const val BATTERY_SERVICE_UUID = "0000180f-0000-1000-8000-00805f9b34fb"
    const val BATTERY_CHAR_UUID = "00002a19-0000-1000-8000-00805f9b34fb"
    const val DIS_SERVICE_UUID = "0000180a-0000-1000-8000-00805f9b34fb"
    const val CHAR_MODEL = "00002a25-0000-1000-8000-00805f9b34fb"
    const val CHAR_FIRMWARE = "00002a26-0000-1000-8000-00805f9b34fb"
    const val CHAR_PROTOCOL = "00002a27-0000-1000-8000-00805f9b34fb"
    const val CHAR_FEATURES = "00002a28-0000-1000-8000-00805f9b34fb"

    // 命令分组（group）
    const val GROUP_GENERAL = 0x02
    const val GROUP_SPORTS = 0x05
    const val GROUP_REQUEST = 0x0A
    const val GROUP_BAND_INFO = 0x13
    const val GROUP_BIND = 0x03
    const val GROUP_UNBIND = 0x04
    const val GROUP_BUTTONS = 0x0C
    const val GROUP_SETTINGS = 0x09
    const val GROUP_DEVICE_INFO = 0xF3
    const val GROUP_CHALLENGE = 0xF0
    const val GROUP_CHALLENGE_CONFIRM = 0xF1

    // 子命令
    const val SUB_TIME_SYNC = 0x01
    const val SUB_BIND = 0x00
    const val SUB_PAIRING = 0x0A
    const val SUB_SETTINGS_QUERY = 0x00
    const val SUB_SETTINGS_REPLY = 0x09
    const val SUB_HEALTH_SYNC = 0x01
    const val SUB_HEALTH_HOURLY = 0x02
    const val SUB_HEALTH_END = 0x08
    const val SUB_HEALTH_TODAY = 0x0A
    const val SUB_HEALTH_TODAY_TOTAL = 0x0C
    const val SUB_BIND_ACCEPT = 0x08
    const val SUB_BIND_REJECT = 0x09
    const val SUB_MEASURE_HEART = 0x0D
    const val SUB_MEASURE_BP = 0x0E
    const val SUB_MEASURE_SPO2 = 0x1C

    private const val HEADER_DATA = 0xDF
    private const val HEADER_ACK = 0xFD

    /** 睡眠三元组（浅睡、深睡、清醒）的候选偏移，最后一项 -1 表示该布局没有清醒字段 */
    private val SLEEP_LAYOUTS = arrayOf(
        intArrayOf(4, 6, 10),
        intArrayOf(2, 4, 8),
        intArrayOf(0, 2, 6),
        intArrayOf(6, 8, 12),
        intArrayOf(4, 6, -1),
        intArrayOf(2, 4, -1),
    )

    data class Frame(
        val group: Int,
        val type: Int,
        val sub: Int,
        val payload: ByteArray,
        val checksumOk: Boolean,
        val isAck: Boolean,
        val status: Int,
        val raw: ByteArray,
    )

    /** 拼包时校验位先置 0，再对整帧求和取低 8 位 */
    fun checksum(bytes: ByteArray): Int = bytes.sumOf { it.toInt() and 0xFF } and 0xFF

    /** 构造数据帧：group 为命令分组，type 缺省 0x01 */
    fun encode(group: Int, sub: Int, payload: ByteArray = ByteArray(0), type: Int = 0x01): ByteArray {
        val len = payload.size + 5
        val out = ByteArray(9 + payload.size)
        out[0] = HEADER_DATA.toByte()
        out[1] = ((len shr 8) and 0xFF).toByte()
        out[2] = (len and 0xFF).toByte()
        out[3] = 0
        out[4] = (group and 0xFF).toByte()
        out[5] = (type and 0xFF).toByte()
        out[6] = (sub and 0xFF).toByte()
        out[7] = ((payload.size shr 8) and 0xFF).toByte()
        out[8] = (payload.size and 0xFF).toByte()
        payload.copyInto(out, 9)
        out[3] = checksum(out).toByte()
        return out
    }

    /** 对收到的数据帧回 ACK，否则表端会重发或断连 */
    fun ack(frame: Frame): ByteArray {
        val out = byteArrayOf(
            HEADER_ACK.toByte(), 0x00, 0x05, 0x00,
            frame.group.toByte(), frame.sub.toByte(), 0x00, 0x00, 0x01,
        )
        out[3] = checksum(out).toByte()
        return out
    }

    fun parse(raw: ByteArray): Frame? {
        if (raw.size < 9) return null
        val copy = raw.copyOf()
        copy[3] = 0
        val ok = checksum(copy) == (raw[3].toInt() and 0xFF)
        return when (raw[0].toInt() and 0xFF) {
            HEADER_DATA -> {
                val plen = ((raw[7].toInt() and 0xFF) shl 8) or (raw[8].toInt() and 0xFF)
                val end = minOf(raw.size, 9 + plen)
                Frame(
                    group = raw[4].toInt() and 0xFF,
                    type = raw[5].toInt() and 0xFF,
                    sub = raw[6].toInt() and 0xFF,
                    payload = raw.copyOfRange(9, end),
                    checksumOk = ok,
                    isAck = false,
                    status = 0,
                    raw = raw,
                )
            }
            HEADER_ACK -> Frame(
                group = raw[4].toInt() and 0xFF,
                type = 0,
                sub = raw[5].toInt() and 0xFF,
                payload = raw.copyOfRange(8, raw.size),
                checksumOk = ok,
                isAck = true,
                status = ((raw[6].toInt() and 0xFF) shl 8) or (raw[7].toInt() and 0xFF),
                raw = raw,
            )
            else -> null
        }
    }

    /** 按 lenHi/lenLo 重组 MTU 分片，避免一次 notify 里带多帧时被截断 */
    class Reassembler {
        private var buffer = ByteArray(0)
        private var expected = 0

        fun feed(chunk: ByteArray): List<ByteArray> {
            if (chunk.isEmpty()) return emptyList()
            if (expected == 0) {
                expected = frameLength(chunk) ?: return emptyList()
                buffer = chunk.copyOf()
            } else {
                buffer += chunk
            }
            val frames = mutableListOf<ByteArray>()
            while (expected > 0 && buffer.size >= expected) {
                frames += buffer.copyOfRange(0, expected)
                val rest = buffer.copyOfRange(expected, buffer.size)
                buffer = ByteArray(0)
                expected = 0
                if (rest.isNotEmpty()) {
                    val len = frameLength(rest)
                    if (len == null) break else {
                        expected = len
                        buffer = rest
                    }
                }
            }
            return frames
        }

        fun reset() {
            buffer = ByteArray(0)
            expected = 0
        }

        private fun frameLength(b: ByteArray): Int? {
            if (b.size < 3) return null
            val header = b[0].toInt() and 0xFF
            if (header != HEADER_DATA && header != HEADER_ACK) return null
            val len = ((b[1].toInt() and 0xFF) shl 8) or (b[2].toInt() and 0xFF)
            return len + 4
        }
    }

    /** 协议版本文本（"10000" / "V2.1" / "2.0"）→ 帧第 5 字节；无法识别返回 null */
    fun parseProtocolVersion(raw: String?): Int? {
        val digits = (raw ?: return null).trim().removePrefix("v").removePrefix("V").substringBefore('.').trim()
        return digits.toIntOrNull()?.let { it and 0xFF }
    }

    /** 时间同步载荷：年-2000(6) 月(4) 日(5) 时(5) 分(6) 秒(6) */
    fun packDateTime(epochMs: Long): ByteArray {
        val c = java.util.Calendar.getInstance().apply { timeInMillis = epochMs }
        val packed = (((c.get(java.util.Calendar.YEAR) - 2000) and 0x3F) shl 26) or
            ((c.get(java.util.Calendar.MONTH) + 1) shl 22) or
            (c.get(java.util.Calendar.DAY_OF_MONTH) shl 17) or
            (c.get(java.util.Calendar.HOUR_OF_DAY) shl 12) or
            (c.get(java.util.Calendar.MINUTE) shl 6) or
            c.get(java.util.Calendar.SECOND)
        return byteArrayOf(
            ((packed ushr 24) and 0xFF).toByte(),
            ((packed ushr 16) and 0xFF).toByte(),
            ((packed ushr 8) and 0xFF).toByte(),
            (packed and 0xFF).toByte(),
        )
    }

    data class Features(val valid: Boolean, val sdk: Boolean, val deviceInfo: Boolean, val medicine: Boolean, val drinkWater: Boolean)

    /** 2A28：长度 >15 且首字节 AA 时，第 15 字节为能力开关 */
    fun parseFeatures(bytes: ByteArray?): Features {
        val valid = bytes != null && bytes.size > 15 && (bytes[0].toInt() and 0xFF) == 0xAA
        val flags = if (valid) (bytes?.get(15)?.toInt() ?: 0) and 0xFF else 0
        return Features(
            valid = valid,
            sdk = flags and 0x10 != 0,
            deviceInfo = flags and 0x20 != 0,
            medicine = flags and 0x08 != 0,
            drinkWater = flags and 0x04 != 0,
        )
    }

    /** 校验挑战应答：应答第 11 字节应为 sum(b xor 0xFF) */
    fun challengeCode(challenge: ByteArray): Int =
        challenge.sumOf { (it.toInt() and 0xFF) xor 0xFF } and 0xFF

    data class DeviceInfo(val firmware: String, val project: String)

    /** F3 应答：vLen + 固件版本 + nLen + 工程名 */
    fun parseDeviceInfo(payload: ByteArray): DeviceInfo? {
        if (payload.size < 2) return null
        val vLen = payload[0].toInt() and 0xFF
        if (1 + vLen >= payload.size) return null
        val firmware = String(payload, 1, vLen, Charsets.UTF_8)
        val nLen = payload[1 + vLen].toInt() and 0xFF
        val projectStart = 2 + vLen
        val project = if (projectStart + nLen <= payload.size) String(payload, projectStart, nLen, Charsets.UTF_8) else ""
        return DeviceInfo(firmware, project)
    }

    /** 09/09 应答：连续的 [id][data...]；按已知 id 长度切分，未知 id 停止 */
    fun parseSettings(payload: ByteArray): Map<Int, ByteArray> {
        val lengths = mapOf(0x01 to 4, 0x02 to 4, 0x03 to 8, 0x05 to 5, 0x06 to 4, 0x07 to 5, 0x08 to 8, 0x09 to 5, 0x0A to 7)
        val out = linkedMapOf<Int, ByteArray>()
        var i = 0
        while (i < payload.size) {
            val id = payload[i].toInt() and 0xFF
            val len = lengths[id] ?: break
            if (i + 1 + len > payload.size) break
            out[id] = payload.copyOfRange(i + 1, i + 1 + len)
            i += 1 + len
        }
        return out
    }

    data class Health(
        val steps: Int? = null,
        val distanceM: Int? = null,
        val kcal: Int? = null,
        val heartRate: Int? = null,
        val bpSys: Int? = null,
        val bpDia: Int? = null,
        val sleepLightMin: Int? = null,
        val sleepDeepMin: Int? = null,
        val sleepAwakeMin: Int? = null,
        val spo2: Int? = null,
        val hourlySteps: Int? = null,
    )

    /** 健康帧（group 0x05）各子命令的字段布局 */
    fun decodeHealth(sub: Int, p: ByteArray, base: Health = Health()): Health = when (sub) {
        0x0A -> {
            // 当天整包：每轮同步都会请求，但头部日期字段长度因固件而异，
            // 按候选偏移逐个尝试，命中当天总计或逐时汇总即采用。
            // 之前没有这个分支，整包回包被直接丢弃，表现为步数只在首次连接时更新。
            val day = todayAt(p, little = true) ?: todayAt(p, little = false) ?: return base
            base.copy(
                steps = base.steps ?: day.steps,
                distanceM = base.distanceM ?: day.distance,
                kcal = base.kcal ?: day.kcal,
                hourlySteps = base.hourlySteps ?: day.hourly,
            )
        }
        0x0C -> {
            // 当天总计：步/距/卡各 4 字节。个别固件会带 2 字节日期前缀，也有按大端写的，
            // 候选偏移与两种字节序都试，取落在合理区间内的那个，避免读出天文数字
            val t = totalsFrom(p) ?: return base
            base.copy(steps = t[0], distanceM = t[1], kcal = t[2])
        }
        0x02 -> {
            // 24 组 × 8 字节：步数 u16、距离 u16（米）、卡路里 u32
            val le = hourlyAt(p, little = true)
            val be = hourlyAt(p, little = false)
            val h = when {
                le != null -> le
                be != null -> be
                else -> return base
            }
            // 逐时汇总只在缺当天总计（0x0C）时兜底，避免把更粗的统计覆盖到权威值上
            base.copy(
                steps = base.steps ?: h.steps,
                distanceM = base.distanceM ?: h.distance,
                kcal = base.kcal ?: h.kcal,
                hourlySteps = h.steps,
            )
        }
        0x04 -> {
            val count = p.getOrNull(3)?.toInt() ?: 0
            val last = (0 until count).mapNotNull { i -> p.getOrNull(4 + i * 8 + 7)?.toInt()?.and(0xFF) }.lastOrNull()
            base.copy(heartRate = last ?: base.heartRate)
        }
        0x05 -> {
            val count = p.getOrNull(3)?.toInt() ?: 0
            val lastIdx = (0 until count).lastOrNull { i -> 4 + i * 8 + 7 < p.size }
            if (lastIdx == null) base else base.copy(
                bpSys = p[4 + lastIdx * 8 + 6].toInt() and 0xFF,
                bpDia = p[4 + lastIdx * 8 + 7].toInt() and 0xFF,
            )
        }
        0x09 -> {
            val s = sleepFrom(p) ?: return base
            base.copy(
                sleepLightMin = s[0],
                sleepDeepMin = s[1],
                sleepAwakeMin = if (s[2] >= 0) s[2] else base.sleepAwakeMin,
            )
        }
        // 0x1C 是血氧测量指令号，个别固件也用同一个子命令回传结果
        0x0E, 0x1C -> {
            val v = spo2From(p) ?: return base
            base.copy(spo2 = v)
        }
        else -> base
    }

    /**
     * 0x09 睡眠：固件的日期前缀长度与字段宽度不一致，按候选组合逐个尝试。
     * 只接受「浅睡 + 深睡」落在 10~1440 分钟、且加上清醒后仍不超过一天的结果，
     * 避免错位读出的几十万分钟或无意义的 1 分钟。
     */
    private fun sleepFrom(p: ByteArray): IntArray? {
        val readers: List<(Int) -> Int?> = listOf(
            { at -> u16(p, at, little = true) },
            { at -> u16(p, at, little = false) },
            { at -> u8(p, at) },
        )
        for (read in readers) {
            for (l in SLEEP_LAYOUTS) {
                val light = read(l[0])
                val deep = read(l[1])
                if (light == null || deep == null) continue
                val awake = if (l[2] >= 0) read(l[2]) else null
                if (l[2] >= 0 && awake == null) continue
                if (sleepPlausible(light, deep, awake)) return intArrayOf(light, deep, awake ?: -1)
            }
        }
        return null
    }

    private fun sleepPlausible(light: Int, deep: Int, awake: Int?): Boolean {
        if (light > 1440 || deep > 1440) return false
        // 0xFF 是「无数据」填充：单字节读法下会变成 255，必须按哨兵值排除
        if (light == 0xFF && deep == 0xFF) return false
        if (awake == 0xFF) return false
        val total = light + deep
        if (total !in 10..1440) return false
        if (awake != null && (awake > 1440 || total + awake > 1440)) return false
        return true
    }

    /**
     * 0x0E / 0x1C 血氧：与 0x04 心率同构的「条数 + 8 字节记录」优先，
     * 再退到单值布局。只接受 50~100 的生理区间，0/255 这类无数据填充直接丢弃。
     */
    private fun spo2From(p: ByteArray): Int? {
        val count = p.getOrNull(3)?.toInt()?.and(0xFF) ?: 0
        if (count in 1..12) {
            val last = (0 until count).lastOrNull { i -> 4 + i * 8 + 7 < p.size }
            if (last != null) {
                val v = p[4 + last * 8 + 7].toInt() and 0xFF
                if (v in 50..100) return v
            }
        }
        for (at in intArrayOf(p.size - 1, 11, 3, 4, 5)) {
            val v = u8(p, at) ?: continue
            if (v in 50..100) return v
        }
        return null
    }

    /**
     * 0x0C 三项总计（步/距/卡）：候选偏移 0/2/4/6 × 两种字节序 × u32/u16 两种宽度。
     * 先按自洽性挑（距离、卡路里与步数的比例落在合理区间），
     * 都不自洽时再退一步只做上限过滤，避免个别固件的距离单位不同导致整包被丢弃。
     */
    private fun totalsFrom(p: ByteArray): IntArray? {
        val candidates = ArrayList<IntArray>()
        for (width in intArrayOf(4, 2)) {
            for (off in intArrayOf(0, 2, 4, 6)) {
                for (little in booleanArrayOf(true, false)) {
                    val t = totalsAt(p, little, off, width) ?: continue
                    if (plausible(t[0], t[1], t[2])) return t
                    candidates += t
                }
            }
        }
        return candidates.firstOrNull { it[0] > 0 && !isSentinel(it) }
    }

    /**
     * 读取一组 (步/距/卡)，只做基本上限过滤，不判断比例。
     * 错位读出的组合也常常落在上限内，因此调用方还要再用 [plausible] 校验。
     */
    private fun totalsAt(p: ByteArray, little: Boolean, off: Int, width: Int): IntArray? {
        if (p.size < off + width * 3) return null
        val steps = if (width == 4) u32(p, off, little) else u16(p, off, little)
        val distance = if (width == 4) u32(p, off + width, little) else u16(p, off + width, little)
        val kcal = if (width == 4) u32(p, off + width * 2, little) else u16(p, off + width * 2, little)
        if (steps == null || distance == null || kcal == null) return null
        if (steps > 240_000 || distance > 192_000 || kcal > 4_464_000) return null
        return intArrayOf(steps, distance, kcal)
    }

    /** 全 0xFFFF/0xFFFFFFFF 是「无数据」填充，不能当成真实步数 */
    private fun isSentinel(t: IntArray): Boolean = t.any {
        it == 0xFFFF || it == 0xFFFF_FFFF.toInt()
    }

    /**
     * 步/距/卡是否互相自洽。
     * 只靠上限过滤太松：错位读出的 1234/5678 也落在范围内，会变成一个永远不动的假步数。
     * 真实数据里 距离≈0.15~2 米/步、卡路里≈0.005~0.25 千卡/步，用它排除错位组合。
     * 只报步数（距离与卡路里为 0）的固件同样视为有效。
     */
    private fun plausible(steps: Int, distance: Int, kcal: Int): Boolean {
        if (steps <= 0) return false
        if (distance > 0) {
            val perStep = distance.toDouble() / steps
            if (perStep < 0.15 || perStep > 2.0) return false
        }
        if (kcal > 0) {
            val perStep = kcal.toDouble() / steps
            if (perStep < 0.005 || perStep > 0.25) return false
        }
        return true
    }

    private class Hourly(val steps: Int, val distance: Int, val kcal: Int)

    private class Day(val steps: Int, val distance: Int, val kcal: Int, val hourly: Int?)

    /**
     * 0x0A（当天整包）解析：整包可能是「日期前缀 + 逐时桶」，
     * 也可能是「日期前缀 + 步/距/卡总计」。先按逐时整包识别（长度能被 8 整除且桶数够多），
     * 再退回总计三元组，按候选偏移逐个尝试，命中即采用；都不成立返回 null。
     */
    private fun todayAt(p: ByteArray, little: Boolean): Day? {
        // 桶数太少（<6）时长度整除关系同样成立，会把总计段误读成小时桶，宁可走总计分支
        for (off in intArrayOf(2, 0, 4, 6)) {
            if (p.size <= off || (p.size - off) % 8 != 0) continue
            val buckets = (p.size - off) / 8
            if (buckets < 6 || buckets > 24) continue
            hourlyAt(p, little)?.let { return Day(it.steps, it.distance, it.kcal, it.steps) }
        }
        val t = totalsFrom(p) ?: return null
        return Day(t[0], t[1], t[2], null)
    }

    /**
     * 0x02 的每小时 8 字节桶（步 u16、距 u16 米、卡 u32）。
     * 文档给的是小端；0xFFFF 表示该小时无数据，单小时 >20000 步视为错位脏值整桶丢弃。
     * 部分固件只回「到当前小时为止」的部分桶，因此按剩余长度算桶数，不强制 24 个。
     * 日期前缀 2 字节个别固件不带，按长度能否被 8 整除自动识别偏移。
     */
    private fun hourlyAt(p: ByteArray, little: Boolean): Hourly? {
        val offset = when {
            p.size >= 2 + 8 && (p.size - 2) % 8 == 0 -> 2
            p.size >= 8 && p.size % 8 == 0 -> 0
            else -> return null
        }
        val buckets = minOf(24, (p.size - offset) / 8)
        if (buckets <= 0) return null
        var steps = 0
        var distance = 0
        var kcal = 0
        var bad = 0
        var valid = 0
        for (h in 0 until buckets) {
            val at = offset + h * 8
            val st = u16(p, at, little) ?: return null
            if (st == 0xFFFF) continue
            if (st > 20_000) {
                bad++
                continue
            }
            valid++
            steps += st
            distance += u16(p, at + 2, little) ?: return null
            kcal += u32(p, at + 4, little) ?: return null
        }
        // 一个有效桶都没有：整包无数据（全 0xFFFF 或全脏值），交给上一级保留原值
        if (valid == 0) return null
        // 有脏桶且一个有效桶都没有：多半是字节序猜反，交给另一种字节序重试
        if (bad > 0 && steps == 0 && distance == 0 && kcal == 0) return null
        if (steps > 100_000 || distance > 200_000 || kcal > 4_464_000) return null
        return Hourly(steps, distance, kcal)
    }

    private fun u16(b: ByteArray, at: Int): Int? {
        if (b.size < at + 2) return null
        return ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)
    }

    private fun u8(b: ByteArray, at: Int): Int? {
        if (at < 0 || b.size <= at) return null
        return b[at].toInt() and 0xFF
    }

    private fun u16(b: ByteArray, at: Int, little: Boolean): Int? {
        if (b.size < at + 2) return null
        return if (little) {
            (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)
        } else {
            ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)
        }
    }

    private fun u32(b: ByteArray, at: Int, little: Boolean): Int? {
        if (b.size < at + 4) return null
        val b0 = b[at].toInt() and 0xFF
        val b1 = b[at + 1].toInt() and 0xFF
        val b2 = b[at + 2].toInt() and 0xFF
        val b3 = b[at + 3].toInt() and 0xFF
        val v = if (little) {
            b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
        } else {
            (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
        }
        return if (v < 0) null else v
    }
}
