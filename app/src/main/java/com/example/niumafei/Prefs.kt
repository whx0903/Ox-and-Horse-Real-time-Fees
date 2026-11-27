package com.example.niumafei

import android.content.Context

/** 发薪日撞上周末 / 放假日时的处理策略 */
enum class Adjust { EARLY, AFTER, NONE }

data class Settings(
    /** 月工资（实发到手），单位：分 */
    val salaryCents: Long,
    /** 上班时刻，从 00:00 起算的分钟数，支持 18:30 这类半小时 */
    val startMin: Int,
    val endMin: Int,
    /** 每月发薪日 1..31 */
    val payday: Int,
    val adjust: Adjust
) {
    val isConfigured: Boolean
        get() = salaryCents > 0 && endMin > startMin && payday in 1..31
}

object Prefs {
    private const val FILE = "niumafei_settings"
    private const val K_SALARY = "salary_cents"
    private const val K_START = "start_min"
    private const val K_END = "end_min"
    private const val K_PAYDAY = "payday"
    private const val K_ADJUST = "adjust"
    private const val K_KEEP_ALIVE = "keep_alive"
    private const val K_LAYOUT_MODE = "layout_mode"

    /**
     * v1.3 小组件版式：auto / compact / standard / large。
     * 国产桌面可能不回报真实尺寸，自动档判断失准时可在设置页手动指定。
     */
    fun layoutMode(context: Context): String =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(K_LAYOUT_MODE, WidgetLayout.MODE_AUTO) ?: WidgetLayout.MODE_AUTO

    fun setLayoutMode(context: Context, mode: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(K_LAYOUT_MODE, mode).apply()
    }

    /**
     * 「真秒级保活」开关：默认开启。
     * 华为 / EMUI 会把后台闹钟与后台广播合并（实测约 5 秒一跳），只关电池优化不够。
     * 开启后用前台服务的进程内 Handler 每秒直推小组件，绕过 AlarmManager 调度。
     * 代价是通知栏一条可静音的常驻通知；关掉即回到纯闹钟链（零通知）。
     */
    fun keepAlive(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(K_KEEP_ALIVE, true)

    fun setKeepAlive(context: Context, on: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putBoolean(K_KEEP_ALIVE, on).apply()
    }

    fun load(context: Context): Settings {
        val sp = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val adjustName = sp.getString(K_ADJUST, Adjust.EARLY.name) ?: Adjust.EARLY.name
        val adjust = try {
            Adjust.valueOf(adjustName)
        } catch (e: IllegalArgumentException) {
            Adjust.EARLY
        }
        return Settings(
            salaryCents = sp.getLong(K_SALARY, 0L),
            startMin = sp.getInt(K_START, 9 * 60),
            endMin = sp.getInt(K_END, 18 * 60),
            payday = sp.getInt(K_PAYDAY, 15).coerceIn(1, 31),
            adjust = adjust
        )
    }

    fun save(context: Context, s: Settings) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putLong(K_SALARY, s.salaryCents)
            .putInt(K_START, s.startMin)
            .putInt(K_END, s.endMin)
            .putInt(K_PAYDAY, s.payday)
            .putString(K_ADJUST, s.adjust.name)
            .apply()
    }

    /** "18:30" -> 1110；格式非法返回 null */
    fun parseTime(text: String): Int? {
        val parts = text.trim().split(":")
        if (parts.size != 2) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return h * 60 + m
    }

    fun formatTime(min: Int): String = "%02d:%02d".format(min / 60, min % 60)
}
