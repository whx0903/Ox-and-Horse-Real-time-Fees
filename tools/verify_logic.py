#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
编译前口径自检（python3，仅标准库）

与 Kotlin 的 HolidayCalendar / Calculator / WidgetRefresher 逻辑一一对应，
用于在没有 Android SDK 的环境下验证：假日数据、账平、四分支（秒级递增）、秒级闹钟链状态机、
发薪日调整，以及全部 XML / JSON 资源的可解析性。

运行：python3 tools/verify_logic.py
退出码：0 = 全部通过；1 = 有失败项
"""

import calendar
import glob
import json
import os
import xml.etree.ElementTree as ET
from datetime import date, datetime, timedelta
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CAL_PATH = ROOT / "app" / "src" / "main" / "assets" / "holidays.json"
CAL = json.loads(CAL_PATH.read_text(encoding="utf-8"))

DAYS_OFF = {date.fromisoformat(x) for x in CAL["daysOff"]}
MAKEUP = {date.fromisoformat(x) for x in CAL["makeupWorkdays"]}
FESTIVALS = [
    (f["name"], date.fromisoformat(f["start"]), date.fromisoformat(f["end"]))
    for f in CAL["festivals"]
]
YEARS = set(CAL["years"])

PASS = 0
FAIL = 0


def check(name, cond, detail=""):
    global PASS, FAIL
    if cond:
        PASS += 1
        print("[PASS] " + name + ("  " + detail if detail else ""))
    else:
        FAIL += 1
        print("[FAIL] " + name + ("  " + detail if detail else ""))


# ---------- Kotlin 逻辑的 python 复刻 ----------

def is_workday(d: date) -> bool:
    if d in MAKEUP:
        return True
    if d in DAYS_OFF:
        return False
    return d.weekday() < 5


def month_days(y, m):
    return [date(y, m, i) for i in range(1, calendar.monthrange(y, m)[1] + 1)]


def workdays_in_month(y, m):
    return sum(1 for d in month_days(y, m) if is_workday(d))


def workday_index(d):
    return sum(1 for x in month_days(d.year, d.month) if x <= d and is_workday(x))


def cap_cents(S, d):
    """cap(k) = floor(k*S/n) - floor((k-1)*S/n)"""
    n = workdays_in_month(d.year, d.month)
    if n <= 0:
        return 0
    k = workday_index(d)
    if k <= 0:
        return 0
    return (k * S) // n - ((k - 1) * S) // n


def daily_cents(S, d):
    n = workdays_in_month(d.year, d.month)
    return 0 if n <= 0 else S // n


def money(c):
    sign = "-" if c < 0 else ""
    v = abs(c)
    return f"{sign}¥{v // 100}.{v % 100:02d}"


def fee(S, start, end, now):
    """秒级：start/end 参数仍是 HH:mm 的分钟数，内部换算成秒，与 Kotlin fee() 一致"""
    d = now.date()
    if not is_workday(d):
        return 0, 0, "REST"
    cap = cap_cents(S, d)
    now_sec = now.hour * 3600 + now.minute * 60 + now.second
    start_sec, end_sec = start * 60, end * 60
    if now_sec < start_sec:
        return 0, 0, "BEFORE"
    if now_sec >= end_sec:
        return cap, 1000, "AFTER"
    elapsed = now_sec - start_sec
    span = end_sec - start_sec
    permille = (elapsed * 1000) // span
    return cap * elapsed // span, permille, "WORKING"


def next_trigger(now, start, end):
    d = now.date()
    sec = now.hour * 3600 + now.minute * 60 + now.second
    start_sec, end_sec = start * 60, end * 60
    wd = is_workday(d)
    midnight = lambda x: datetime.combine(x, datetime.min.time())
    if wd and start_sec <= sec < end_sec:
        return now.replace(microsecond=0) + timedelta(seconds=1), "下一整秒"
    if wd and sec < start_sec:
        return midnight(d) + timedelta(minutes=start), "今天上班启链"
    return midnight(d + timedelta(days=1)), "次日00:00"


def trigger_kind(now, start, end):
    """v1.2：SECOND=工作秒链(用 setExact)；BOUNDARY=上班/0点边界(用 allowWhileIdle)"""
    sec = now.hour * 3600 + now.minute * 60 + now.second
    wd = is_workday(now.date())
    return "SECOND" if wd and start * 60 <= sec < end * 60 else "BOUNDARY"


def pay_date_for(y, m, payday, rule):
    last = calendar.monthrange(y, m)[1]
    d = date(y, m, min(payday, last))
    if rule == "NONE":
        return d
    guard = 0
    while not is_workday(d) and guard < 20:
        d += timedelta(days=-1 if rule == "BEFORE" else 1)
        guard += 1
    return d



def run_selftest():
    print("=" * 68)
    print("牛马费倒计时 · 编译前口径自检")
    print("=" * 68)

    # ---------- 1. 假日数据完整性 ----------
    print("\n[1] 假日日历数据")
    check("7 个法定放假档期", len(FESTIVALS) == 7, f"实际 {len(FESTIVALS)}")
    covered = set()
    for _, s, e in FESTIVALS:
        cur = s
        while cur <= e:
            covered.add(cur)
            cur += timedelta(days=1)
    check("每个档期的每一天都在 daysOff", covered <= DAYS_OFF,
          f"档期 {len(covered)} 天 / daysOff {len(DAYS_OFF)} 天")
    check("5 个调休补班日且均落在周末", len(MAKEUP) == 5 and all(d.weekday() >= 5 for d in MAKEUP),
          ", ".join(str(d) for d in sorted(MAKEUP)))
    check("补班日与放假日无重叠", MAKEUP.isdisjoint(DAYS_OFF))
    check("2027 年在日历覆盖范围内", 2027 in YEARS)
    check("数据来源标注为国务院办公厅通知", "国务院办公厅" in CAL.get("source", ""))

    # ---------- 2. 每月工作日数 ----------
    print("\n[2] 2027 年各月工作日数（周一到周五 − 放假日 + 补班日）")
    counts = []
    for m in range(1, 13):
        n = workdays_in_month(2027, m)
        counts.append(n)
        print(f"    {m:2d} 月: {n:2d} 天", end="\n" if m % 4 == 0 else "")
    print()
    check("各月工作日数在 15–23 天合理区间", all(15 <= n <= 23 for n in counts),
          f"min={min(counts)} max={max(counts)}")
    check("春节月(2月)工作日数明显少于 3 月", counts[1] < counts[2],
          f"2月={counts[1]} 3月={counts[2]}")

    # ---------- 3. 账平：Σ 当日封顶 = 月工资 ----------
    print("\n[3] 账平验证（4 档工资 × 12 个月）")
    for S in (950000, 1000000, 1234567, 9999999):
        ok = True
        for m in range(1, 13):
            total = sum(cap_cents(S, d) for d in month_days(2027, m) if is_workday(d))
            if total != S:
                ok = False
                break
        check(f"Σcap = {money(S)} 全年 12 个月", ok, "12/12 通过" if ok else "存在断账月份")
    # 每日差 <= 1 分
    S = 1234567
    maxdiff = 0
    for m in range(1, 13):
        caps = [cap_cents(S, d) for d in month_days(2027, m) if is_workday(d)]
        if caps:
            maxdiff = max(maxdiff, max(caps) - min(caps))
    check("每日封顶差 ≤ 1 分（无末日突增）", maxdiff <= 1, f"最大差 {maxdiff} 分")

    # ---------- 4. 牛马费四分支 ----------
    print("\n[4] 牛马费四分支与放假日/补班日")
    S = 950000          # ¥9500.00
    START, END = 9 * 60, 18 * 60   # 09:00 – 18:00
    mon = date(2027, 1, 4)
    check("2027-01-04 是工作日", is_workday(mon))
    check("08:59 上班前 = ¥0.00 / BEFORE", fee(S, START, END, datetime(2027, 1, 4, 8, 59)) == (0, 0, "BEFORE"))
    noon = fee(S, START, END, datetime(2027, 1, 4, 12, 0))
    expected_noon = cap_cents(S, mon) // 3
    check("12:00 处于 WORKING 且约为封顶 1/3", noon[2] == "WORKING" and abs(noon[0] - expected_noon) <= 1,
          f"{money(noon[0])} vs 1/3 封顶 {money(expected_noon)}")
    check("秒级递增：12:00:30 金额 > 12:00:00",
          fee(S, START, END, datetime(2027, 1, 4, 12, 0, 30))[0] > noon[0],
          f"{money(fee(S, START, END, datetime(2027, 1, 4, 12, 0, 30))[0])} > {money(noon[0])}")
    check("18:00 整分封顶 / AFTER", fee(S, START, END, datetime(2027, 1, 4, 18, 0))
          == (cap_cents(S, mon), 1000, "AFTER"))
    check("次日 00:00 归零 / BEFORE", fee(S, START, END, datetime(2027, 1, 5, 0, 0))[0] == 0)
    check("元旦假期 2027-01-02(周六) 不涨钱", fee(S, START, END, datetime(2027, 1, 2, 12, 0))[2] == "REST")
    check("春节放假的周一 2027-02-08 不涨钱", fee(S, START, END, datetime(2027, 2, 8, 12, 0))[2] == "REST")
    check("补班周日 2027-01-31 照常涨钱", is_workday(date(2027, 1, 31))
          and fee(S, START, END, datetime(2027, 1, 31, 12, 0))[2] == "WORKING")
    check("放假日不计入当月分母", not is_workday(date(2027, 2, 8)))
    n_jan = workdays_in_month(2027, 1)
    print(f"    参考：2027-01 工作日 {n_jan} 天，¥9500 的日薪 = {money(daily_cents(S, mon))}")

    # ---------- 5. 单点闹钟链状态机 ----------
    print("\n[5] 单点闹钟链状态机（含研发师指出的边界）")
    check("工作中 → 下一整秒", next_trigger(datetime(2027, 1, 4, 10, 30, 25), START, END)[1] == "下一整秒")
    check("10:30:25 的下一闹钟 = 10:30:26",
          next_trigger(datetime(2027, 1, 4, 10, 30, 25), START, END)[0] == datetime(2027, 1, 4, 10, 30, 26))
    check("纳秒对齐：10:30:00.5 → 10:30:01（不产生连击）",
          next_trigger(datetime(2027, 1, 4, 10, 30, 0, 500000), START, END)[0] == datetime(2027, 1, 4, 10, 30, 1))
    check("工作日 00:00 → 今天 09:00 启链",
          next_trigger(datetime(2027, 1, 4, 0, 0), START, END)[0] == datetime(2027, 1, 4, 9, 0))
    check("下班整分 18:00 → 次日 00:00",
          next_trigger(datetime(2027, 1, 4, 18, 0), START, END)[0] == datetime(2027, 1, 5, 0, 0))
    check("周六 00:00 → 次日 00:00（不误排 startMin）",
          next_trigger(datetime(2027, 1, 2, 0, 0), START, END)[0] == datetime(2027, 1, 3, 0, 0))
    check("周六白天 → 次日 00:00（零唤醒）",
          next_trigger(datetime(2027, 1, 2, 14, 0), START, END)[0] == datetime(2027, 1, 3, 0, 0))
    check("法定放假日(周一) → 次日 00:00",
          next_trigger(datetime(2027, 2, 8, 14, 0), START, END)[0] == datetime(2027, 2, 9, 0, 0))
    check("补班周日 00:00 → 当天 09:00 启链",
          next_trigger(datetime(2027, 1, 31, 0, 0), START, END)[0] == datetime(2027, 1, 31, 9, 0))
    check("工作中 → 触发类型 SECOND（走 setExact，不走 allowWhileIdle）",
          trigger_kind(datetime(2027, 1, 4, 10, 30, 25), START, END) == "SECOND")
    check("下班后 / 上班前 / 休息日 → 触发类型 BOUNDARY",
          all(trigger_kind(t, START, END) == "BOUNDARY" for t in (
              datetime(2027, 1, 4, 18, 0), datetime(2027, 1, 4, 8, 0),
              datetime(2027, 1, 2, 14, 0), datetime(2027, 2, 8, 12, 0))))

    # ---------- 6. 发薪日调整 ----------
    print("\n[6] 发薪日遇假三档调整")
    check("2027-05-15 是周六(非工作日)", not is_workday(date(2027, 5, 15)))
    check("提前 → 2027-05-14", pay_date_for(2027, 5, 15, "BEFORE") == date(2027, 5, 14))
    check("延后 → 2027-05-17", pay_date_for(2027, 5, 15, "AFTER") == date(2027, 5, 17))
    check("不调整 → 2027-05-15", pay_date_for(2027, 5, 15, "NONE") == date(2027, 5, 15))
    check("2 月填 31 号自动取月末 02-28", pay_date_for(2027, 2, 31, "NONE") == date(2027, 2, 28))

    # ---------- 7. 资源静态校验 ----------
    print("\n[7] 资源文件静态校验")
    xml_files = sorted(glob.glob(str(ROOT / "app" / "src" / "main" / "**" / "*.xml"), recursive=True))
    bad = []
    for f in xml_files:
        try:
            ET.parse(f)
        except Exception as e:
            bad.append((os.path.basename(f), str(e)))
    check(f"{len(xml_files)} 个 XML 均可解析", not bad, "; ".join(f"{a}:{b}" for a, b in bad))
    check("holidays.json 可解析", isinstance(CAL, dict))
    kt_files = sorted(glob.glob(str(ROOT / "app" / "src" / "main" / "java" / "**" / "*.kt"), recursive=True))
    check(f"{len(kt_files)} 个 Kotlin 源文件齐全", len(kt_files) == 7,
          ", ".join(os.path.basename(f) for f in kt_files))
    check("3 帧猫猫矢量图齐全", all((ROOT / "app" / "src" / "main" / "res" / "drawable" / f"cat_{n}.xml").exists()
                                for n in ("idle", "working", "done")))

    # ---------- 8. v1.2 秒级加固 + 四向缩放静态校验 ----------
    print("\n[8] v1.2 秒级加固与四向缩放")
    main = ROOT / "app" / "src" / "main"
    A = "{http://schemas.android.com/apk/res/android}"

    def text(p):
        return (main / p).read_text(encoding="utf-8")

    refresher = text("java/com/example/niumafei/WidgetRefresher.kt")
    check("工作秒链使用 setExact（不再用 setExactAndAllowWhileIdle 跑秒链）",
          'am.setExact(AlarmManager.RTC_WAKEUP, millis, pi)' in refresher)
    check("边界闹钟仍保留 Doze 豁免 setExactAndAllowWhileIdle",
          'am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)' in refresher)
    check("闹钟广播加 FLAG_RECEIVER_FOREGROUND", "FLAG_RECEIVER_FOREGROUND" in refresher)
    check("精度诊断 delta 已输出", "delta=" in refresher and "lastDeltaMs" in refresher)
    check("重绘按 widgetId 逐个取版式", "WidgetLayout.pick" in refresher and "for (id in ids)" in refresher)

    manifest = text("AndroidManifest.xml")
    check("前台服务权限与声明齐全",
          'android.permission.FOREGROUND_SERVICE"' in manifest and ".TickService" in manifest)
    check("解锁 / 精确闹钟权限变更可自愈", "USER_PRESENT" in manifest
          and "SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED" in manifest)

    service = text("java/com/example/niumafei/TickService.kt")
    check("前台服务每 1000ms 直推重绘", "TICK_MS = 1_000L" in service and "WidgetRefresher.redraw" in service)
    check("开关关闭时停止服务", "Prefs.keepAlive" in service and "stopSelf()" in service)

    info = ET.parse(main / "res" / "xml" / "niumafei_widget_info.xml").getroot()
    attrs = {k.split("}")[-1]: v for k, v in info.attrib.items()}
    check("resizeMode 允许上下左右（horizontal|vertical）",
          attrs.get("resizeMode") == "horizontal|vertical", attrs.get("resizeMode", ""))
    check("默认高度降到 110dp（4×2，卡片不再自带空白）", attrs.get("minHeight") == "110dp")
    check("targetCellHeight 同步降为 2", attrs.get("targetCellHeight") == "2")
    check("最小可缩放高度放到 70dp（约 1 格，可继续缩小）", attrs.get("minResizeHeight") == "70dp")
    check("最小可缩放宽度放到 110dp（约 2 格）", attrs.get("minResizeWidth") == "110dp")

    def ids(path):
        root = ET.parse(main / path).getroot()
        return {e.attrib.get(A + "id", "").split("/")[-1]
                for e in root.iter() if A + "id" in e.attrib}

    full, compact = ids("res/layout/widget_niumafei.xml"), ids("res/layout/widget_niumafei_compact.xml")
    needed = {"widget_root", "iv_cat", "tv_title", "tv_amount", "tv_sub", "pb_progress",
              "tv_pay_label", "tv_pay_value", "tv_off_label", "tv_off_value",
              "tv_fri_label", "tv_fri_value", "tv_holiday_label",
              "tv_holiday_value", "tv_updated"}
    check("紧凑版与完整版 view id 完全一致", full == compact and needed <= full,
          f"缺失 {sorted(needed - full)}")
    check("尺寸变更回调已接入重绘", "onAppWidgetOptionsChanged" in text(
        "java/com/example/niumafei/NiuMaFeiWidget.kt"))

    # ---------- 9. v1.3 尺寸自适应 ----------
    print("\n[9] v1.3 尺寸自适应（消除留白 / 兼容任意尺寸）")

    def root_children(path):
        root = ET.parse(main / path).getroot()
        return root, list(root)

    for path, name in (("res/layout/widget_niumafei.xml", "标准版"),
                       ("res/layout/widget_niumafei_compact.xml", "紧凑版")):
        root, children = root_children(path)
        check(f"{name}根布局铺满整个卡片", root.attrib.get(A + "layout_height") == "match_parent")
        rows = [c for c in children if not c.tag.endswith("ProgressBar")]
        bad_rows = [c for c in rows
                    if c.attrib.get(A + "layout_height") != "0dp" or A + "layout_weight" not in c.attrib]
        check(f"{name}所有内容行都是 0dp + weight（不会在底部留白）",
              len(rows) >= 4 and not bad_rows, f"{len(bad_rows)} 行仍是固定高度")
        check(f"{name}内容行垂直居中分配多余高度",
              all(c.attrib.get(A + "gravity") == "center_vertical"
                  for c in rows if c.tag.endswith("LinearLayout")))

    widget = text("java/com/example/niumafei/NiuMaFeiWidget.kt")
    check("尺寸优先取 OPTION_APPWIDGET_SIZES 精确值",
          "OPTION_APPWIDGET_SIZES" in widget and "getParcelableArrayList<SizeF>" in widget)
    check("MIN/MAX 尺寸兜底", "OPTION_APPWIDGET_MIN_HEIGHT" in widget
          and "OPTION_APPWIDGET_MAX_HEIGHT" in widget)
    check("运行时按尺寸缩放字号", "setTextViewTextSize" in widget and "TEXT_SIZES" in widget)
    check("运行时缩放内边距与猫图", "setViewPadding" in widget and "setViewLayoutWidth" in widget)
    check("API 31+ 才调用 setViewLayoutWidth（兼容 minSdk 26）",
          "if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)" in widget)
    check("极小卡片进迷你档隐藏标签", "LABEL_IDS" in widget and "View.GONE" in widget)
    check("四种版式常量齐全", all(f'MODE_{m} = "{m.lower()}"' in widget
                            for m in ("AUTO", "COMPACT", "STANDARD", "LARGE")))

    prefs = text("java/com/example/niumafei/Prefs.kt")
    check("版式偏好持久化", "layoutMode" in prefs and "setLayoutMode" in prefs and "K_LAYOUT_MODE" in prefs)
    activity = text("res/layout/activity_main.xml")
    main_act = text("java/com/example/niumafei/MainActivity.kt")
    check("设置页提供四档手动版式", "rg_layout" in activity and all(
        f"rb_layout_{m}" in activity for m in ("auto", "compact", "standard", "large")))
    check("手动切换立即强制重绘", "notifySettingsChanged" in main_act and "rgLayout.check" in main_act)
    check("版式切换绕过 350ms 去重", "force = action == ACTION_SETTINGS_CHANGED" in refresher
          and "redraw(context, force)" in refresher)
    check("设置页展示实测桌面尺寸与版式", "lastLayoutInfo" in refresher and "lastLayoutInfo" in main_act)

    # 与 WidgetLayout.auto() 完全同构的 Python 镜像，验证档位边界
    def layout_spec(w, h):
        scale = max(0.68, min(1.30, min(h / 150.0, w / 250.0)))
        tiny = h < 95 or w < 150
        compact = h < 130 or w < 200
        return {
            "compact": compact or tiny,
            "tiny": tiny,
            "scale": min(scale, 0.72) if tiny else scale,
            "show_footer": not compact and not tiny,
            "label": ("自动·迷你" if tiny else "自动·紧凑" if compact
                      else "自动·舒展" if scale > 1.1 else "自动·标准"),
        }

    cases = [
        (250, 180, "自动·标准", False, True),
        (250, 110, "自动·紧凑", False, False),
        (250, 80, "自动·迷你", True, False),
        (320, 260, "自动·舒展", False, True),
        (110, 200, "自动·迷你", True, False),
    ]
    for w, h, label, tiny, footer in cases:
        s = layout_spec(w, h)
        check(f"{w}×{h}dp → {label}（tiny={tiny} footer={footer}）",
              s["label"] == label and s["tiny"] == tiny and s["show_footer"] == footer,
              str(s))
    check("任意尺寸下字号缩放系数都落在 0.68~1.30",
          all(0.68 <= layout_spec(w, h)["scale"] <= 1.30
              for w in (110, 180, 250, 320, 480) for h in (70, 95, 130, 180, 260, 400)))

    print("\n" + "=" * 68)
    print(f"结果：PASS {PASS} / FAIL {FAIL}")
    print("=" * 68)
    return 1 if FAIL else 0


if __name__ == "__main__":
    raise SystemExit(run_selftest())
