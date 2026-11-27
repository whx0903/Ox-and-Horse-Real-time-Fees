package com.example.niumafei

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDateTime

class MainActivity : Activity() {

    private lateinit var etSalary: EditText
    private lateinit var etStart: EditText
    private lateinit var etEnd: EditText
    private lateinit var etPayday: EditText
    private lateinit var rgAdjust: RadioGroup
    private lateinit var tvPreview: TextView
    private lateinit var tvAlarmHint: TextView
    private lateinit var btnAlarm: Button
    private lateinit var tvHolidayInfo: TextView
    private lateinit var ivCatPreview: ImageView
    private lateinit var tvAmountPreview: TextView
    private lateinit var tvSubPreview: TextView
    private lateinit var swKeepAlive: Switch
    private lateinit var tvDiag: TextView
    private lateinit var rgLayout: RadioGroup

    private val handler = Handler(Looper.getMainLooper())
    /** 前台时每秒刷新预览（仅 Activity 可见期间，后台零唤醒） */
    private val secondTick = object : Runnable {
        override fun run() {
            updatePreview()
            handler.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etSalary = findViewById(R.id.et_salary)
        etStart = findViewById(R.id.et_start)
        etEnd = findViewById(R.id.et_end)
        etPayday = findViewById(R.id.et_payday)
        rgAdjust = findViewById(R.id.rg_adjust)
        tvPreview = findViewById(R.id.tv_preview)
        tvAlarmHint = findViewById(R.id.tv_alarm_hint)
        btnAlarm = findViewById(R.id.btn_alarm)
        tvHolidayInfo = findViewById(R.id.tv_holiday_info)
        ivCatPreview = findViewById(R.id.iv_cat_preview)
        tvAmountPreview = findViewById(R.id.tv_amount_preview)
        tvSubPreview = findViewById(R.id.tv_sub_preview)
        swKeepAlive = findViewById(R.id.sw_keep_alive)
        tvDiag = findViewById(R.id.tv_diag)
        rgLayout = findViewById(R.id.rg_layout)

        // v1.3：桌面不回报尺寸时手动指定版式；先 check 再挂监听，避免初始化时误触发重绘
        rgLayout.check(
            when (Prefs.layoutMode(this)) {
                WidgetLayout.MODE_COMPACT -> R.id.rb_layout_compact
                WidgetLayout.MODE_STANDARD -> R.id.rb_layout_standard
                WidgetLayout.MODE_LARGE -> R.id.rb_layout_large
                else -> R.id.rb_layout_auto
            }
        )
        rgLayout.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.rb_layout_compact -> WidgetLayout.MODE_COMPACT
                R.id.rb_layout_standard -> WidgetLayout.MODE_STANDARD
                R.id.rb_layout_large -> WidgetLayout.MODE_LARGE
                else -> WidgetLayout.MODE_AUTO
            }
            Prefs.setLayoutMode(this, mode)
            // 显式 SETTINGS_CHANGED 广播：绕过 350ms 去重，立即按新版式重绘
            WidgetRefresher.notifySettingsChanged(this)
            Toast.makeText(this, "已切换小组件版式，桌面立即重绘", Toast.LENGTH_SHORT).show()
        }

        swKeepAlive.isChecked = Prefs.keepAlive(this)
        swKeepAlive.setOnCheckedChangeListener { _, on ->
            Prefs.setKeepAlive(this, on)
            if (on) TickService.start(this) else TickService.stop(this)
            WidgetRefresher.refreshAll(this)
            Toast.makeText(
                this,
                if (on) "已开启真秒级保活（通知栏可设为静默）" else "已关闭保活，回到纯闹钟链",
                Toast.LENGTH_SHORT
            ).show()
        }

        val s = Prefs.load(this)
        if (s.salaryCents > 0) {
            etSalary.setText("%.2f".format(s.salaryCents / 100.0))
        }
        etStart.setText(Prefs.formatTime(s.startMin))
        etEnd.setText(Prefs.formatTime(s.endMin))
        etPayday.setText(s.payday.toString())
        when (s.adjust) {
            Adjust.EARLY -> rgAdjust.check(R.id.rb_before)
            Adjust.AFTER -> rgAdjust.check(R.id.rb_after)
            Adjust.NONE -> rgAdjust.check(R.id.rb_none)
        }

        findViewById<Button>(R.id.btn_save).setOnClickListener { onSave() }
        btnAlarm.setOnClickListener { openExactAlarmSettings() }
        updatePreview()
    }

    override fun onResume() {
        super.onResume()
        // 前台启动前台服务一定被允许；从 Activity 拉起可覆盖「后台启动受限」的 ROM 策略
        TickService.start(this)
        updatePreview()
        handler.removeCallbacks(secondTick)
        handler.post(secondTick)
    }

    override fun onPause() {
        handler.removeCallbacks(secondTick)
        super.onPause()
    }

    private fun onSave() {
        val salary = etSalary.text.toString().trim().toBigDecimalOrNull()
        if (salary == null || salary <= BigDecimal.ZERO) {
            etSalary.error = getString(R.string.err_salary)
            return
        }
        val cents = salary.movePointRight(2).setScale(0, RoundingMode.HALF_UP).toLong()

        val start = Prefs.parseTime(etStart.text.toString())
        val end = Prefs.parseTime(etEnd.text.toString())
        if (start == null) {
            etStart.error = getString(R.string.err_time)
            return
        }
        if (end == null) {
            etEnd.error = getString(R.string.err_time)
            return
        }
        if (end <= start) {
            etEnd.error = "下班时间必须晚于上班时间"
            return
        }
        val payday = etPayday.text.toString().trim().toIntOrNull()
        if (payday == null || payday !in 1..31) {
            etPayday.error = getString(R.string.err_payday)
            return
        }
        val adjust = when (rgAdjust.checkedRadioButtonId) {
            R.id.rb_after -> Adjust.AFTER
            R.id.rb_none -> Adjust.NONE
            else -> Adjust.EARLY
        }

        // F-05：校验失败不清空已填内容；校验通过才清掉红字
        etSalary.error = null
        etStart.error = null
        etEnd.error = null
        etPayday.error = null

        Prefs.save(this, Settings(cents, start, end, payday, adjust))
        WidgetRefresher.notifySettingsChanged(this)
        Toast.makeText(this, getString(R.string.saved), Toast.LENGTH_SHORT).show()
        updatePreview()
    }

    private fun updatePreview() {
        val s = Prefs.load(this)
        val cal = HolidayCalendar.load(this)
        // F-06：与小组件共用同一个 Calculator 引擎，同一时刻 6 项数值差异为 0
        val state = Calculator.build(LocalDateTime.now(), s, cal)

        ivCatPreview.setImageResource(
            when (state.frame) {
                CatFrame.IDLE -> R.drawable.cat_idle
                CatFrame.WORKING -> R.drawable.cat_working
                CatFrame.DONE -> R.drawable.cat_done
            }
        )
        tvAmountPreview.text = state.amountText
        tvSubPreview.text = state.subText

        tvPreview.text = state.previewText
        tvHolidayInfo.text = if (cal.source.isBlank()) {
            getString(R.string.calendar_missing)
        } else {
            "假日日历：${cal.source}\n共 ${cal.festivalCount} 个法定档期，含 5 个调休补班日；每年 11 月替换 holidays.json 即可"
        }

        val ok = WidgetRefresher.canScheduleExact(this)
        tvAlarmHint.visibility = if (ok) View.GONE else View.VISIBLE
        btnAlarm.visibility = if (ok) View.GONE else View.VISIBLE
        if (!ok) {
            tvAlarmHint.text = "${getString(R.string.alarm_hint)}；若金额仍不动，请把本应用加入电池优化白名单"
        }

        // v1.2 现场诊断：不连电脑也能分辨「闹钟被限流」还是「桌面被限流」
        val delta = WidgetRefresher.lastDeltaMs
        val deltaText = if (delta > 0) "${delta} ms" else "等待首次重绘"
        val cadence = when {
            delta == 0L -> "尚未采集"
            delta < 1_600L -> "真秒级 ✓"
            delta < 3_500L -> "约 2~3 秒（仍被合并）"
            else -> "明显被限流"
        }
        tvDiag.text = buildString {
            append("刷新诊断（设置页停留几秒后自动更新）\n")
            append("精确闹钟：").append(if (ok) "已允许" else "未允许 ⚠").append('\n')
            append("保活模式：").append(if (Prefs.keepAlive(this@MainActivity)) "前台服务" else "纯闹钟链")
            append('\n')
            append("最近实际重绘间隔：").append(deltaText).append(" · ").append(cadence)
            append('\n')
            append("桌面尺寸/版式：").append(WidgetRefresher.lastLayoutInfo)
            append("\n判定：间隔 ≈1000 ms 但桌面仍 5 秒一跳 → 桌面自身在限流；间隔 ≈5000 ms → 后台唤醒被合并")
        }
    }

    private fun openExactAlarmSettings() {
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                    .apply { data = Uri.parse("package:$packageName") }
            )
        } catch (e: Exception) {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .apply { data = Uri.parse("package:$packageName") }
            )
        }
    }
}
