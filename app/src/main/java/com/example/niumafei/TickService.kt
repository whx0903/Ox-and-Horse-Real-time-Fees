package com.example.niumafei

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log

/**
 * 「真秒级保活」前台服务（v1.2，可由设置页一键关闭）。
 *
 * 为什么需要它：华为 / EMUI 会把应用后台的精确闹钟与后台广播做合并唤醒，
 * 实测 1 秒的闹钟链退化成约 5 秒一跳；即便已关闭电池优化也仍然存在。
 * 本服务在**自己的进程内**用 Handler 每 1000ms 直接重绘小组件，
 * 完全绕开 AlarmManager / 后台广播调度，因此不受 ROM 合并窗口影响。
 *
 * 与闹钟链的关系：两者并行、互为备份，WidgetRefresher.redraw() 内做 350ms 去重，
 * 所以正常工作时每秒仍然只画一次；前台服务被杀时闹钟链继续兜底，反之亦然。
 *
 * 代价：通知栏有一条 IMPORTANCE_MIN、无声音无震动的常驻通知（可在系统通知设置里
 * 设为静默或直接关闭该通知类别，服务仍会运行）。关闭开关即回到纯闹钟方案、零通知。
 */
class TickService : Service() {

    private val handler = Handler(Looper.getMainLooper())

    private val tick = object : Runnable {
        override fun run() {
            if (!Prefs.keepAlive(this@TickService)) {
                stopSelf()
                return
            }
            WidgetRefresher.redraw(this@TickService)
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        running = true
        try {
            startAsForeground()
        } catch (e: Exception) {
            // 个别 ROM 对前台服务类型有特殊校验，失败时仍保留进程内秒针
            Log.e(WidgetRefresher.TAG, "startForeground failed, tick continues", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        handler.removeCallbacks(tick)
        handler.post(tick)
        // 启动时全量重绘并重建闹钟链（闹钟链仍是备用驱动）
        WidgetRefresher.refreshAll(this)
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacks(tick)
        // 服务退出后，闹钟链继续接管（此处重排一次，避免链断在半路）
        WidgetRefresher.refreshAll(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    private fun startAsForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "秒级刷新", NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "保持桌面小组件每秒刷新，可在系统通知设置中设为静默"
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }

        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val notification = builder
            .setSmallIcon(R.drawable.ic_stat_tick)
            .setContentTitle("牛马费 · 秒级刷新中")
            .setContentText("每秒更新桌面倒计时与牛马费")
            .setContentIntent(pi)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setPriority(Notification.PRIORITY_MIN)
            .build()

        // Android 14（API 34）起 targetSdk 34 需要在调用里显式带前台服务类型；
        // 个别 ROM 对 specialUse 校验较严，失败就退回两参数版本，保证秒针不被中断。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                return
            } catch (e: Exception) {
                Log.w(WidgetRefresher.TAG, "typed startForeground rejected, fallback", e)
            }
        }
        startForeground(NOTIF_ID, notification)
    }

    companion object {
        const val CHANNEL_ID = "niumafei_tick"
        const val NOTIF_ID = 20261110
        const val TICK_MS = 1_000L

        @Volatile
        var running = false

        /** 从前台（Activity）或开机 / 解锁等广播里尝试拉起；被 ROM 拒绝时静默降级到闹钟链 */
        fun start(context: Context) {
            if (!Prefs.keepAlive(context)) return
            try {
                val i = Intent(context, TickService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(i)
                } else {
                    context.startService(i)
                }
            } catch (e: Exception) {
                Log.w(WidgetRefresher.TAG, "cannot start tick service, alarm chain remains", e)
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, TickService::class.java))
            } catch (e: Exception) {
                Log.w(WidgetRefresher.TAG, "stop tick service failed", e)
            }
        }
    }
}
