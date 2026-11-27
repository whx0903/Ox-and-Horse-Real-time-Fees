package com.example.niumafei

import android.content.Context
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate

data class Festival(val name: String, val start: LocalDate, val end: LocalDate)

/**
 * 假日日历：一份本地 JSON 三处共用
 *  1) 扣/加当月工作日分母
 *  2) 决定当天牛马费涨不涨
 *  3) 驱动「距离下一个法定节假日」倒计时
 *
 * 工作日判定优先级：补班日 > 放假日 > 周一到周五
 * 未覆盖年份自动降级为「仅周一到周五」（covers() 返回 false，主界面会红字提示）。
 */
class HolidayCalendar private constructor(
    private val years: Set<Int>,
    private val daysOff: Set<LocalDate>,
    private val makeupWorkdays: Set<LocalDate>,
    private val festivals: List<Festival>,
    val source: String
) {
    fun covers(year: Int): Boolean = year in years

    fun isWorkday(d: LocalDate): Boolean {
        if (d in makeupWorkdays) return true
        if (d in daysOff) return false
        val w = d.dayOfWeek
        return w != DayOfWeek.SATURDAY && w != DayOfWeek.SUNDAY
    }

    fun isDayOff(d: LocalDate): Boolean = d in daysOff

    fun workdaysInMonth(year: Int, month: Int): Int {
        val first = LocalDate.of(year, month, 1)
        var n = 0
        for (i in 0 until first.lengthOfMonth()) {
            if (isWorkday(first.plusDays(i.toLong()))) n++
        }
        return n
    }

    /** 下一个放假档期（只认首日严格晚于今天，进入假期后自动跳下一档） */
    fun nextFestival(from: LocalDate): Festival? =
        festivals.firstOrNull { it.start.isAfter(from) }

    val festivalCount: Int get() = festivals.size

    companion object {
        fun load(context: Context): HolidayCalendar {
            return try {
                val text = context.assets.open("holidays.json").bufferedReader().use { it.readText() }
                val json = JSONObject(text)

                val years = mutableSetOf<Int>()
                val yArr = json.optJSONArray("years")
                if (yArr != null) for (i in 0 until yArr.length()) years.add(yArr.getInt(i))

                fun dates(key: String): Set<LocalDate> {
                    val arr = json.optJSONArray(key) ?: return emptySet()
                    val out = mutableSetOf<LocalDate>()
                    for (i in 0 until arr.length()) {
                        try { out.add(LocalDate.parse(arr.getString(i))) } catch (e: Exception) { /* 跳过脏数据 */ }
                    }
                    return out
                }

                val list = mutableListOf<Festival>()
                val fArr = json.optJSONArray("festivals")
                if (fArr != null) for (i in 0 until fArr.length()) {
                    val o = fArr.getJSONObject(i)
                    try {
                        list.add(
                            Festival(
                                o.getString("name"),
                                LocalDate.parse(o.getString("start")),
                                LocalDate.parse(o.getString("end"))
                            )
                        )
                    } catch (e: Exception) { /* 跳过脏数据 */ }
                }
                list.sortBy { it.start }

                HolidayCalendar(
                    years,
                    dates("daysOff"),
                    dates("makeupWorkdays"),
                    list,
                    json.optString("source", "")
                )
            } catch (e: Exception) {
                // 文件缺失或解析失败：降级为纯周一到周五，且 covers() 恒 false 以便提示
                HolidayCalendar(emptySet(), emptySet(), emptySet(), emptyList(), "")
            }
        }
    }
}
