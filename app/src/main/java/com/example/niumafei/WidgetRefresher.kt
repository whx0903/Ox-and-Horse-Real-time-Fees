package com.example.niumafei

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * 刷新 + 重排程的唯一入口（v1.2）。
 *
 * 单点闹钟链：每次重绘后只排「下一次必须重绘」的那一个时刻。
 *  工作中         -> 下一整秒（秒链只在工作区间自续；用户已拍板「桌面全部真秒级」）
 *  工作日、上班前 -> 今天上班时刻（一次性启链）
 *  下班后/休息日  -> 次日 00:00（归零边界），到点重绘后自动接上面两个分支
 *
 * v1.2 华为 5 秒问题的三处加固：
 *  1) 工作秒链改用 [AlarmManager.setExact]：setExactAndAllowWhileIdle 允许系统重排/限流
 *     （AOSP 明示非 Doze 下也可能约 1 分钟才放行一次，EMUI 实测约 5 秒合并一次），
 *     而桌面可见时屏幕必然亮着、本就不在 Doze，用普通精确闹钟更合适；
 *     只有「0 点 / 上班」这类一天 1~2 次的边界闹钟才需要 Doze 豁免。
 *  2) 闹钟广播加 Intent.FLAG_RECEIVER_FOREGROUND：官方明确指出后台优先级广播会被延迟。
 *  3) 可选前台服务 [TickService] 用进程内 Handler 每秒直推，与闹钟链并行互为备份，
 *     彻底绕开 ROM 的后台唤醒合并窗口。redraw() 内 350ms 去重，两驱动不会画两次。
 *
 * 诊断：每次重绘打印 delta=本次距上次重绘的毫秒数（真秒级应为 ~1000），
 *      adb logcat -s NiuMaFei:V；设置页也直接显示这个数值，不用连电脑。
 */
class WidgetRefresher : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.d(TAG, "onReceive action=$action")
        // 开机 / 解锁 / 亮屏 / 应用更新 / 权限变更后，前台服务若被杀则拉起
        if (action in REVIVE_ACTIONS) TickService.start(context)
        // 版式/设置变更必须立即重绘，不能被 350ms 去重吞掉
        refreshAll(context, force = action == ACTION_SETTINGS_CHANGED)
    }

    companion object {
        /** 调试日志 TAG：adb logcat -s NiuMaFei:V */
        const val TAG = "NiuMaFei"
        const val ACTION_REFRESH = "com.example.niumafei.REFRESH"
        const val ACTION_SETTINGS_CHANGED = "com.example.niumafei.SETTINGS_CHANGED"
        private const val REQ_ALARM = 1001
        private const val DEDUPE_MS = 350L

        private val REVIVE_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_USER_PRESENT,
            Intent.ACTION_SCREEN_ON,
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"
        )

        @Volatile
        private var lastDrawAt = 0L

        /** 最近一次实际重绘间隔（毫秒），设置页直接展示，用来区分「闹钟被限流」还是「桌面被限流」 */
        @Volatile
        var lastDeltaMs = 0L
            private set

        /** v1.3：最近一次检测到的桌面尺寸与版式，设置页展示，用来排查「尺寸报不准」 */
        @Volatile
        var lastLayoutInfo = "尚未采集"
            private set

        /** 设置页保存后调用，显式 component 广播，避免 Android 8+ 隐式广播限制 */
        fun notifySettingsChanged(context: Context) {
            context.sendBroadcast(
                Intent(context, WidgetRefresher::class.java).setAction(ACTION_SETTINGS_CHANGED)
            )
        }

        /** 闹钟 / 开机 / 时间变更 / 设置变更：重绘 + 重排（全量）
         *  @param force 版式切换、尺寸拖动等用户操作，跳过 350ms 去重立即重绘 */
        fun refreshAll(context: Context, force: Boolean = false) {
            Log.d(TAG, "refreshAll force=$force")
            if (!redraw(context, force)) return
            val now = LocalDateTime.now()
            val s = Prefs.load(context)
            val cal = HolidayCalendar.load(context)
            scheduleNext(context, now, s, cal)
        }

        /**
         * 系统 30 分钟兜底 onUpdate 专用：
         * 非工作区间只重绘、绝不碰排程（闹钟仍正确指向次日 00:00）；
         * 工作区间才重建秒链 —— 精确闹钟被 ROM 冻结后可自愈恢复。
         */
        fun refreshFromSystemFallback(context: Context) {
            if (!redraw(context)) return
            val now = LocalDateTime.now()
            val s = Prefs.load(context)
            val cal = HolidayCalendar.load(context)
            val working = isWorking(now, s, cal)
            Log.d(TAG, "fallback onUpdate working=$working")
            if (working) {
                TickService.start(context)
                scheduleNext(context, now, s, cal)
            }
        }

        fun isWorking(now: LocalDateTime, s: Settings, cal: HolidayCalendar): Boolean {
            val sec = now.hour * 3600 + now.minute * 60 + now.second
            return cal.isWorkday(now.toLocalDate()) &&
                    sec >= s.startMin * 60 && sec < s.endMin * 60
        }

        /**
         * 重绘全部小组件。v1.3：按每个 widgetId 的实测尺寸换算版式与字号，
         * 布局本身 weight 铺满，桌面给多高都不会留白；
         * 两个驱动源（闹钟 / 前台服务）在此处按 350ms 去重。
         * @return 是否仍有小组件；无小组件时顺手取消闹钟
         */
        fun redraw(context: Context, force: Boolean = false): Boolean {
            // 先查小组件是否存在：组件被删光时必须取消闹钟，不能被去重叠加掉
            val ids = NiuMaFeiWidget.ids(context)
            if (ids.isEmpty()) {
                Log.d(TAG, "no widget on home screen -> cancel alarm")
                lastDrawAt = 0L
                lastLayoutInfo = "无小组件"
                cancelAlarm(context)
                return false
            }

            val uptime = SystemClock.elapsedRealtime()
            val delta = uptime - lastDrawAt
            if (!force && lastDrawAt != 0L && delta in 0 until DEDUPE_MS) {
                return true // 同一秒内已被另一个驱动画过，跳过
            }
            lastDrawAt = uptime
            if (delta in 1..60_000L) lastDeltaMs = delta

            val now = LocalDateTime.now()
            val s = Prefs.load(context)
            val cal = HolidayCalendar.load(context)
            val state = Calculator.build(now, s, cal)
            Log.d(TAG, "draw now=$now delta=${delta}ms amount=${state.amountText} widgets=${ids.size}")

            val mgr = AppWidgetManager.getInstance(context)
            var info = "尚未采集"
            for (id in ids) {
                val opts = mgr.getAppWidgetOptions(id)
                val spec = WidgetLayout.pick(context, opts)
                info = "${spec.wDp}×${spec.hDp} dp · ${spec.label} · 字号 ×%.2f".format(spec.scale)
                Log.d(TAG, "size id=$id ${spec.wDp}x${spec.hDp}dp mode=${spec.label} scale=${spec.scale} tiny=${spec.tiny}")
                val views = WidgetRenderer.render(context, state, spec)
                try {
                    mgr.updateAppWidget(id, views)
                } catch (e: Exception) {
                    Log.e(TAG, "updateAppWidget id=$id failed", e)
                }
            }
            lastLayoutInfo = info
            return true
        }

        /** 可单独核对的下一次触发时刻 */
        fun nextTrigger(now: LocalDateTime, s: Settings, cal: HolidayCalendar): LocalDateTime {
            val today = now.toLocalDate()
            val sec = now.hour * 3600 + now.minute * 60 + now.second
            val startSec = s.startMin * 60
            val endSec = s.endMin * 60
            val workday = cal.isWorkday(today)
            val start = LocalTime.of(s.startMin / 60, s.startMin % 60)

            if (workday && sec >= startSec && sec < endSec) {
                // 下一整秒：对齐秒边界，Receiver 自续秒链
                return now.withNano(0).plusSeconds(1)
            }
            if (workday && sec < startSec) {
                return LocalDateTime.of(today, start)
            }
            // 下班后 / 非工作日：只留 0 点归零这一个边界（整秒封顶在 endSec 那次重绘已完成）
            return today.plusDays(1).atStartOfDay()
        }

        /** 下一次触发的类型：SECOND = 工作秒链；BOUNDARY = 上班 / 0 点边界 */
        fun triggerKind(now: LocalDateTime, s: Settings, cal: HolidayCalendar): String {
            val sec = now.hour * 3600 + now.minute * 60 + now.second
            val workday = cal.isWorkday(now.toLocalDate())
            return if (workday && sec >= s.startMin * 60 && sec < s.endMin * 60) {
                "SECOND"
            } else {
                "BOUNDARY"
            }
        }

        fun scheduleNext(context: Context, now: LocalDateTime, s: Settings, cal: HolidayCalendar) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = alarmIntent(context)
            am.cancel(pi) // 单点：先清掉旧闹钟，绝不累积

            val next = nextTrigger(now, s, cal)
            val kind = triggerKind(now, s, cal)
            val millis = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
            Log.d(TAG, "alarm next=$next kind=$kind exact=$exact")

            if (!exact) {
                // 精确闹钟权限未授予：先试闹钟级 setAlarmClock（系统不调整其投递时间），
                // 仍被拒才降级为非精确闹钟，并在设置页提示去开权限。
                val armed = try {
                    am.setAlarmClock(AlarmManager.AlarmClockInfo(millis, null), pi)
                    true
                } catch (e: Exception) {
                    Log.w(TAG, "setAlarmClock rejected, fallback to inexact", e)
                    false
                }
                if (!armed) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
                return
            }

            if (kind == "SECOND") {
                // 关键修复：不用 allowWhileIdle，避免 5 秒 / 1 分钟的合并窗口。
                // 息屏 Doze 时会被延后，但那时桌面不可见；解锁/亮屏广播会立刻重排。
                am.setExact(AlarmManager.RTC_WAKEUP, millis, pi)
            } else {
                // 一天 1~2 次的边界，必须能穿透 Doze，限流窗口对它无意义
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
            }
        }

        fun cancelAlarm(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(alarmIntent(context))
        }

        fun canScheduleExact(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            return am.canScheduleExactAlarms()
        }

        private fun alarmIntent(context: Context): PendingIntent {
            val i = Intent(context, WidgetRefresher::class.java)
                .setAction(ACTION_REFRESH)
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND) // 官方：后台优先级广播会被延迟
            return PendingIntent.getBroadcast(
                context, REQ_ALARM, i,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }
}
