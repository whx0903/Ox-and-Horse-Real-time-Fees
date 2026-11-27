package com.example.niumafei

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import kotlin.math.roundToInt

/** 桌面小组件：只负责接系统广播，重绘与排程统一交给 WidgetRefresher */
class NiuMaFeiWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        // 系统 30 分钟兜底：非工作区间只重绘、不动排程；工作区间才重建秒链
        WidgetRefresher.refreshFromSystemFallback(context)
    }

    /** v1.2：拖动改变尺寸后立即重绘；v1.3：强制绕过 350ms 去重，避免快速拖动被吞掉 */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        WidgetRefresher.refreshAll(context, force = true)
    }

    override fun onEnabled(context: Context) {
        TickService.start(context)
        WidgetRefresher.refreshAll(context)
    }

    override fun onDisabled(context: Context) {
        // 最后一个小组件被移除：取消闹钟、停掉前台服务，不再唤醒
        WidgetRefresher.cancelAlarm(context)
        TickService.stop(context)
    }

    companion object {
        fun ids(context: Context): IntArray =
            AppWidgetManager.getInstance(context)
                .getAppWidgetIds(ComponentName(context, NiuMaFeiWidget::class.java))
    }
}

/** 一个 widgetId 最终采用的版式参数 */
data class LayoutSpec(
    val compact: Boolean,
    /** 极小卡片：隐藏标签行，把标签并进数值，保证 1~2 格高度也不裁字 */
    val tiny: Boolean,
    /** 字号 / 内边距 / 猫图的整体缩放系数 */
    val scale: Float,
    val showFooter: Boolean,
    val wDp: Int,
    val hDp: Int,
    val label: String
)

/**
 * v1.3 尺寸自适应：把「桌面到底给了多大」换算成版式参数。
 *
 * EMUI / 部分国产桌面不回报 OPTION_APPWIDGET_SIZES，或只给 MIN 值，
 * 单靠 getAppWidgetOptions 判断会选错版式（v1.2 就是这样把大卡片渲染成
 * 顶部堆叠的紧凑版、留下半截空白）。因此：
 *  1) 布局本身一律 weight 铺满，尺寸判断错也不会留白；
 *  2) 尺寸优先取 SIZES（API 31+ 精确值），否则用 MIN/MAX 兜底；
 *  3) 设置页提供手动版式，桌面完全不报尺寸时用户也能一步选到合适的字号。
 */
object WidgetLayout {
    const val MODE_AUTO = "auto"
    const val MODE_COMPACT = "compact"
    const val MODE_STANDARD = "standard"
    const val MODE_LARGE = "large"

    fun pick(context: Context, opts: Bundle): LayoutSpec {
        val minW = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250)
        val minH = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 150)
        var w = minW
        var h = minH

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            @Suppress("DEPRECATION")
            val sizes: ArrayList<SizeF>? =
                opts.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)
            if (!sizes.isNullOrEmpty()) {
                w = sizes[0].width.roundToInt()
                h = sizes[0].height.roundToInt()
            }
        }

        val maxW = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, w)
        val maxH = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, h)
        if (w <= 0) w = maxW.coerceAtLeast(110)
        if (h <= 0) h = maxH.coerceAtLeast(70)

        return when (Prefs.layoutMode(context)) {
            MODE_COMPACT -> LayoutSpec(true, false, 0.78f, false, w, h, "手动·紧凑")
            MODE_STANDARD -> LayoutSpec(false, false, 1.0f, true, w, h, "手动·标准")
            MODE_LARGE -> LayoutSpec(false, false, 1.28f, true, w, h, "手动·舒展")
            else -> auto(w, h)
        }
    }

    /** 纯函数，便于自检脚本按同样公式镜像验证档位边界 */
    private fun auto(w: Int, h: Int): LayoutSpec {
        // 取宽高中「更紧」的那个做上限，避免窄而高时字号撑破列宽
        val scale = (h / 150f).coerceAtMost(w / 250f).coerceIn(0.68f, 1.30f)
        val tiny = h < 95 || w < 150
        val compact = h < 130 || w < 200
        return LayoutSpec(
            compact = compact || tiny,
            tiny = tiny,
            scale = if (tiny) scale.coerceAtMost(0.72f) else scale,
            showFooter = !compact && !tiny,
            wDp = w,
            hDp = h,
            label = when {
                tiny -> "自动·迷你"
                compact -> "自动·紧凑"
                scale > 1.1f -> "自动·舒展"
                else -> "自动·标准"
            }
        )
    }
}

/** RemoteViews 渲染：只用系统支持的控件与 setter，不依赖任何第三方库 */
object WidgetRenderer {
    /** 12 个可缩放文本：id 与标准字号（sp）一一对应 */
    private val TEXT_IDS = intArrayOf(
        R.id.tv_title, R.id.tv_amount, R.id.tv_sub,
        R.id.tv_pay_label, R.id.tv_pay_value,
        R.id.tv_off_label, R.id.tv_off_value,
        R.id.tv_fri_label, R.id.tv_fri_value,
        R.id.tv_holiday_label, R.id.tv_holiday_value,
        R.id.tv_updated
    )
    private val TEXT_SIZES = floatArrayOf(
        10f, 24f, 9f,
        10f, 12f,
        10f, 12f,
        10f, 12f,
        10f, 12f,
        9f
    )

    /** 迷你档隐藏这些标签行，腾出两行高度 */
    private val LABEL_IDS = intArrayOf(
        R.id.tv_title, R.id.tv_sub,
        R.id.tv_pay_label, R.id.tv_off_label,
        R.id.tv_fri_label, R.id.tv_holiday_label
    )

    fun render(context: Context, state: ViewState, spec: LayoutSpec): RemoteViews {
        val layout = if (spec.compact) R.layout.widget_niumafei_compact else R.layout.widget_niumafei
        val v = RemoteViews(context.packageName, layout)

        val tiny = spec.tiny
        val p = context.getString(R.string.lbl_pay) + " "
        val o = context.getString(R.string.lbl_off) + " "
        val f = context.getString(R.string.lbl_fri) + " "
        val d = context.getString(R.string.lbl_holiday) + " "

        v.setTextViewText(R.id.tv_amount, state.amountText)
        v.setTextViewText(R.id.tv_sub, state.subText)
        v.setTextViewText(
            R.id.tv_pay_value, if (tiny) p + state.payText else state.payText
        )
        v.setTextViewText(
            R.id.tv_off_value, if (tiny) o + state.offText else state.offText
        )
        // 秒级后目标时刻改由标签承载，数值只放纯倒计时，避免单行截断秒位
        v.setTextViewText(R.id.tv_fri_label, state.friLabel)
        v.setTextViewText(
            R.id.tv_fri_value, if (tiny) f + state.friText else state.friText
        )
        v.setTextViewText(R.id.tv_holiday_label, state.holidayLabel)
        v.setTextViewText(
            R.id.tv_holiday_value, if (tiny) d + state.holidayText else state.holidayText
        )
        v.setTextViewText(R.id.tv_updated, state.updatedText)
        v.setViewVisibility(R.id.tv_updated, if (spec.showFooter) View.VISIBLE else View.GONE)
        for (id in LABEL_IDS) {
            v.setViewVisibility(id, if (tiny) View.GONE else View.VISIBLE)
        }

        // 运行时按桌面实际尺寸缩放：高度越大字越大，高度不足字自动缩小，不会裁字也不留白
        for (i in TEXT_IDS.indices) {
            v.setTextViewTextSize(
                TEXT_IDS[i], TypedValue.COMPLEX_UNIT_SP, TEXT_SIZES[i] * spec.scale
            )
        }
        val density = context.resources.displayMetrics.density
        val pad = ((if (spec.compact) 5f else 8f) * spec.scale * density).roundToInt()
        v.setViewPadding(R.id.widget_root, pad, pad, pad, pad)

        v.setImageViewResource(
            R.id.iv_cat, when (state.frame) {
                CatFrame.IDLE -> R.drawable.cat_idle
                CatFrame.WORKING -> R.drawable.cat_working
                CatFrame.DONE -> R.drawable.cat_done
            }
        )
        // setViewLayoutWidth/Height 为 API 31+；旧平台保留 XML 内的固定尺寸
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val baseW = if (spec.compact) 34f else 52f
            val baseH = if (spec.compact) 30f else 42f
            v.setViewLayoutWidth(R.id.iv_cat, baseW * spec.scale, TypedValue.COMPLEX_UNIT_DIP)
            v.setViewLayoutHeight(R.id.iv_cat, baseH * spec.scale, TypedValue.COMPLEX_UNIT_DIP)
        }
        v.setProgressBar(R.id.pb_progress, Calculator.PROGRESS_MAX, state.progressPermille, false)

        // 点小组件任意位置回到设置页
        val pi = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        v.setOnClickPendingIntent(R.id.widget_root, pi)
        return v
    }
}
