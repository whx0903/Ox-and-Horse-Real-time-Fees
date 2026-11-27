package com.example.niumafei

import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import kotlin.math.abs

enum class CalcState { BEFORE, WORKING, AFTER, REST }
enum class CatFrame { IDLE, WORKING, DONE }

data class PayInfo(val date: LocalDate, val isToday: Boolean)

data class ViewState(
    val amountText: String,
    val dailyText: String,
    val capText: String,
    val progressPermille: Int,
    val frame: CatFrame,
    val subText: String,
    val payText: String,
    val offText: String,
    val friLabel: String,
    val friText: String,
    val holidayLabel: String,
    val holidayText: String,
    val updatedText: String,
    val workdays: Int,
    val calendarMissing: Boolean,
    val previewText: String
)

/** 纯 Kotlin 计算核心：无 Android 依赖，便于单独核对口径 */
object Calculator {
    const val PROGRESS_MAX = 1000

    fun money(cents: Long): String {
        val v = abs(cents)
        val sign = if (cents < 0) "-" else ""
        return "%s¥%,d.%02d".format(sign, v / 100, v % 100)
    }

    /**
     * 倒计时显示。默认秒级（HH:MM:SS / N天 HH:MM:SS）；withSeconds=false 保留分钟版，
     * 供 30 分钟兜底或降级场景使用。now 先截断到整秒，避免纳秒让秒位看起来慢一拍。
     */
    fun duration(target: LocalDateTime, now: LocalDateTime, withSeconds: Boolean = true): String {
        val secs = Duration.between(now.withNano(0), target).seconds
        if (secs <= 0) return "已到点"
        val days = secs / 86400
        val h = (secs % 86400) / 3600
        val m = (secs % 3600) / 60
        val s = secs % 60
        return when {
            !withSeconds && days > 0 -> "${days}天%02d:%02d".format(h, m)
            !withSeconds -> "%02d:%02d".format(h, m)
            days > 0 -> "${days}天%02d:%02d:%02d".format(h, m, s)
            else -> "%02d:%02d:%02d".format(h, m, s)
        }
    }

    fun workdaysInMonth(d: LocalDate, cal: HolidayCalendar): Int =
        cal.workdaysInMonth(d.year, d.monthValue)

    /** d 是当月第几个工作日（1..n）；非工作日返回 0 */
    fun workdayIndexInMonth(d: LocalDate, cal: HolidayCalendar): Int {
        var cur = d.withDayOfMonth(1)
        var k = 0
        while (!cur.isAfter(d)) {
            if (cal.isWorkday(cur)) k++
            cur = cur.plusDays(1)
        }
        return k
    }

    /** 日薪基准（分）：月工资 ÷ 当月工作日数，用于展示「平均每日到手」 */
    fun dailyCents(s: Settings, cal: HolidayCalendar, d: LocalDate): Long {
        val n = workdaysInMonth(d, cal)
        return if (n <= 0) 0L else s.salaryCents / n
    }

    /**
     * 当日封顶值：按当月工作日序号分摊
     *   cap(k) = ⌊k·S/n⌋ − ⌊(k−1)·S/n⌋
     * 每日差 ≤ 1 分、无末日突增，且 Σcap = S 严格账平（累计牛马费 = 月工资）。
     * 前提：当月内月工资 / 作息 / 假日表未变更；月中改设置会断账，自用场景可接受。
     */
    fun capCents(s: Settings, cal: HolidayCalendar, d: LocalDate): Long {
        val n = workdaysInMonth(d, cal)
        if (n <= 0) return 0L
        val k = workdayIndexInMonth(d, cal)
        if (k <= 0) return 0L
        val total = s.salaryCents
        return (k.toLong() * total) / n - ((k - 1L) * total) / n
    }

    /** 牛马费四分支：非工作日 / 上班前 / 工作中 / 下班后封顶 */
    fun fee(now: LocalDateTime, s: Settings, cal: HolidayCalendar): Triple<Long, Int, CalcState> {
        if (!s.isConfigured) return Triple(0L, 0, CalcState.REST)
        val d = now.toLocalDate()
        if (!cal.isWorkday(d)) return Triple(0L, 0, CalcState.REST)

        val cap = capCents(s, cal, d)
        // 秒级：全程用「当日已过秒数」，金额按精确整数时间比递增，每秒约跳 1~2 分
        val nowSec = (now.hour * 3600 + now.minute * 60 + now.second).toLong()
        val startSec = s.startMin.toLong() * 60
        val endSec = s.endMin.toLong() * 60
        if (nowSec < startSec) return Triple(0L, 0, CalcState.BEFORE)
        if (nowSec >= endSec) return Triple(cap, PROGRESS_MAX, CalcState.AFTER)

        val span = (endSec - startSec).coerceAtLeast(1)
        val elapsed = nowSec - startSec
        val permille = (elapsed * PROGRESS_MAX / span).toInt().coerceIn(0, PROGRESS_MAX)
        // 金额用精确整数时间比计算，不经过千分比量化，避免二次截断误差
        // （例：09:00→18:00 的 12:00:00 应精确等于当日封顶的 1/3；千分比只用于进度条）
        return Triple(cap * elapsed / span, permille, CalcState.WORKING)
    }

    /** 指定月份的实际发薪日：无 31 号取月末；撞假按提前 / 延后 / 不调整处理 */
    fun payDateFor(year: Int, month: Int, s: Settings, cal: HolidayCalendar): LocalDate {
        val ym = YearMonth.of(year, month)
        var d = ym.atDay(minOf(s.payday, ym.lengthOfMonth()))
        if (s.adjust == Adjust.NONE) return d
        var guard = 0
        while (!cal.isWorkday(d) && guard < 20) {
            d = if (s.adjust == Adjust.EARLY) d.minusDays(1) else d.plusDays(1)
            guard++
        }
        return d
    }

    fun nextPayday(now: LocalDateTime, s: Settings, cal: HolidayCalendar): PayInfo {
        val today = now.toLocalDate()
        var ym = YearMonth.from(today)
        var d = payDateFor(ym.year, ym.monthValue, s, cal)
        if (d.isEqual(today)) return PayInfo(d, true)
        var guard = 0
        while (!d.isAfter(today) && guard < 4) {
            ym = ym.plusMonths(1)
            d = payDateFor(ym.year, ym.monthValue, s, cal)
            guard++
        }
        return PayInfo(d, false)
    }

    /** 到本周五下班时刻；周五已收工或周末则顺延到下周五 */
    fun fridayTarget(now: LocalDateTime, s: Settings): LocalDateTime {
        val d = now.toLocalDate()
        val delta = (DayOfWeek.FRIDAY.value - d.dayOfWeek.value + 7) % 7
        var fri = d.plusDays(delta.toLong())
        val time = LocalTime.of(s.endMin / 60, s.endMin % 60)
        var t = LocalDateTime.of(fri, time)
        if (!t.isAfter(now)) {
            fri = fri.plusDays(7)
            t = LocalDateTime.of(fri, time)
        }
        return t
    }

    fun build(now: LocalDateTime, s: Settings, cal: HolidayCalendar): ViewState {
        val d = now.toLocalDate()
        val configured = s.isConfigured
        val missing = !cal.covers(d.year)
        val workdays = workdaysInMonth(d, cal)
        val (amount, permille, state) = fee(now, s, cal)
        val daily = dailyCents(s, cal, d)
        val cap = capCents(s, cal, d)

        // 猫猫三帧随牛马费进度切换：上班前 + 前 1/3 摸鱼 / 中后段搬砖 / 封顶与休息日收工
        val frame = when {
            state == CalcState.AFTER || state == CalcState.REST -> CatFrame.DONE
            state == CalcState.BEFORE || permille < PROGRESS_MAX / 3 -> CatFrame.IDLE
            else -> CatFrame.WORKING
        }

        val sub = when {
            !configured -> "请先填写月工资与上下班时间"
            state == CalcState.REST -> "休息日 · 不涨钱"
            state == CalcState.BEFORE -> "还没上班 · 待命中"
            state == CalcState.AFTER -> "今日已挣 · 已收工（封顶）"
            else -> "今日已挣 · 每秒都在涨"
        }

        val payInfo = if (configured) nextPayday(now, s, cal) else null
        val payText = when {
            payInfo == null -> "未配置"
            payInfo.isToday -> "今天发薪"
            else -> duration(payInfo.date.atStartOfDay(), now)
        }

        val offText = when {
            !configured -> "未配置"
            !cal.isWorkday(d) -> "休息日"
            else -> {
                val end = LocalDateTime.of(d, LocalTime.of(s.endMin / 60, s.endMin % 60))
                if (end.isAfter(now)) duration(end, now) else "已收工"
            }
        }

        // 周五下班后 / 周六 / 周日切「周末中」，与 Q6「到周五下班时刻」口径一致
        val w = d.dayOfWeek
        val weekendMood = w == DayOfWeek.SATURDAY || w == DayOfWeek.SUNDAY ||
                (w == DayOfWeek.FRIDAY && now.hour * 60 + now.minute >= s.endMin)
        // 秒级后数值变长，目标时刻挪到标签承载，数值只留纯倒计时
        val hhmm = "%02d:%02d".format(s.endMin / 60, s.endMin % 60)
        val friLabel = if (weekendMood) "周五" else "周五 · $hhmm 前"
        val friText = when {
            !configured -> "未配置"
            weekendMood -> "周末中"
            else -> duration(fridayTarget(now, s), now)
        }

        val fest = cal.nextFestival(d)
        val holidayLabel = if (fest != null) {
            val days = fest.end.toEpochDay() - fest.start.toEpochDay() + 1
            "节假日 · ${fest.name}（${days} 天）"
        } else "节假日"
        val holidayText = if (fest != null) duration(fest.start.atStartOfDay(), now) else "未配置"

        val updated = "更新于 %02d:%02d:%02d".format(now.hour, now.minute, now.second)

        val preview = buildString {
            append("当月工作日：${workdays} 天")
            append("\n日薪（月工资 ÷ ${workdays}）：${money(daily)}")
            append("\n今日封顶：${money(cap)}（分位按工作日序分摊，累计严格=月工资）")
            append("\n此刻牛马费：${money(amount)}")
            append("\n发薪：${payText}    下班：${offText}")
            append("\n${friLabel}：${friText}    ${holidayLabel}：${holidayText}")
            if (workdays <= 0) append("\n⚠ 当月工作日数为 0，请检查 holidays.json 的 daysOff / makeupWorkdays")
            if (missing) append("\n⚠ 假日日历未覆盖 ${d.year} 年，已降级为仅周一到周五")
        }

        return ViewState(
            amountText = money(amount),
            dailyText = money(daily),
            capText = money(cap),
            progressPermille = permille,
            frame = frame,
            subText = sub,
            payText = payText,
            offText = offText,
            friLabel = friLabel,
            friText = friText,
            holidayLabel = holidayLabel,
            holidayText = holidayText,
            updatedText = updated,
            workdays = workdays,
            calendarMissing = missing,
            previewText = preview
        )
    }
}
