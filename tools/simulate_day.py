#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
时间旅行模拟调试（python3，仅标准库，不需要 Android 设备 / SDK）

复用 verify_logic.py 里与 Kotlin 一一对应的计算逻辑，
把任意日期、任意时刻的「牛马费 / 状态 / 猫猫帧 / 下一次闹钟」直接算出来，
用于本机无设备时核对金额曲线与单点闹钟链。

用法：
  python3 tools/simulate_day.py
  python3 tools/simulate_day.py --date 2027-01-04 --salary 9500 --start 09:00 --end 18:00
  python3 tools/simulate_day.py --at "2027-01-04 12:00"
  python3 tools/simulate_day.py --date 2027-02-08 --step 120      # 春节放假日
  python3 tools/simulate_day.py --date 2027-01-31                  # 周日补班
  python3 tools/simulate_day.py --at "2027-01-04 12:00:30"            # 秒级递增核对
  python3 tools/simulate_day.py --date 2027-01-04 --step 1 | head -80   # 每分钟采样曲线

注意：本工具只模拟「牛马费 + 刷新链」，四个倒计时的文案请在 App / 小组件上核对。
"""

import argparse
from datetime import date, datetime

import verify_logic as V

WEEK = ["周一", "周二", "周三", "周四", "周五", "周六", "周日"]
STATE_CN = {
    "BEFORE": "上班前",
    "WORKING": "搬砖中",
    "AFTER": "已收工封顶",
    "REST": "休息日·不涨钱",
}
FRAME_CN = {"IDLE": "摸鱼猫", "WORKING": "搬砖猫", "DONE": "收工猫"}


def parse_hhmm(s):
    h, m = s.strip().split(":")
    return int(h) * 60 + int(m)


def hhmm(minute):
    return f"{minute // 60:02d}:{minute % 60:02d}"


def frame_of(state, permille):
    if state in ("AFTER", "REST"):
        return "DONE"
    if state == "BEFORE" or permille < 1000 // 3:
        return "IDLE"
    return "WORKING"


def row(S, start, end, when):
    cents, permille, state = V.fee(S, start, end, when)
    nxt, why = V.next_trigger(when, start, end)
    return (when.strftime("%H:%M:%S"), V.money(cents), STATE_CN[state],
            f"{permille / 10:.0f}%", FRAME_CN[frame_of(state, permille)],
            nxt.strftime("%m-%d %H:%M:%S"), why)


def print_table(rows):
    widths = [max(len(str(r[i])) for r in rows) for i in range(len(rows[0]))]
    widths = [max(w, len(h)) for w, h in zip(widths, ("时刻", "牛马费", "状态", "进度", "猫猫", "下次重绘", "排程原因"))]
    header = ("时刻", "牛马费", "状态", "进度", "猫猫", "下次重绘", "排程原因")
    print("  " + "  ".join(str(h).ljust(w) for h, w in zip(header, widths)))
    print("  " + "  ".join("-" * w for w in widths))
    for r in rows:
        print("  " + "  ".join(str(c).ljust(w) for c, w in zip(r, widths)))


def main(argv=None):
    ap = argparse.ArgumentParser(description="牛马费倒计时 · 本机时间旅行模拟调试")
    ap.add_argument("--date", default=date.today().isoformat(), help="模拟日期 YYYY-MM-DD，默认今天")
    ap.add_argument("--at", default=None, help="只算一个时刻，如 '12:00' 或 '2027-01-04 12:00'")
    ap.add_argument("--salary", type=float, default=9500.0, help="月工资（元），默认 9500")
    ap.add_argument("--start", default="09:00", help="上班 HH:mm，默认 09:00")
    ap.add_argument("--end", default="18:00", help="下班 HH:mm，默认 18:00")
    ap.add_argument("--step", type=int, default=60, help="时间曲线采样间隔（分钟），默认 60")
    ap.add_argument("--payday", type=int, default=15, help="发薪日几号，默认 15")
    ap.add_argument("--rule", choices=["EARLY", "AFTER", "NONE"], default="EARLY",
                    help="遇假策略：提前 EARLY / 延后 AFTER / 不调整 NONE")
    a = ap.parse_args(argv)

    S = round(a.salary * 100)
    start, end = parse_hhmm(a.start), parse_hhmm(a.end)
    if end <= start:
        ap.error("--end 必须晚于 --start")

    d = date.fromisoformat(a.date)
    n = V.workdays_in_month(d.year, d.month)
    if n <= 0:
        print("当月工作日数为 0，请检查假日表 holidays.json（与 App 内提示一致）")
        return 0

    wd = V.is_workday(d)
    k = V.workday_index(d)
    cap = V.cap_cents(S, d)
    total = sum(V.cap_cents(S, x) for x in V.month_days(d.year, d.month) if V.is_workday(x))

    print("=" * 78)
    print(f"模拟 {d} {WEEK[d.weekday()]} · {'工作日' if wd else '非工作日（休息日）'}"
          + (f" · 当月第 {k}/{n} 个工作日" if wd else ""))
    if d.year not in V.YEARS:
        print(f"⚠ 假日日历未覆盖 {d.year} 年，已按 App 的降级规则只认周一到周五")
    daily = V.money(V.daily_cents(S, d))
    if wd:
        print(f"月工资 {V.money(S)} ÷ {n} 个工作日 = 日均 {daily}"
              f"；今日封顶 {V.money(cap)}（按工作日序分摊，账平）")
    else:
        print(f"月工资 {V.money(S)} ÷ {n} 个工作日 = 日均 {daily}"
              f"；今日休息日，全天 ¥0.00 不涨钱")
    print(f"作息 {hhmm(start)}–{hhmm(end)}  发薪日 {a.payday} 号 / 遇假 {a.rule}")
    pay = V.pay_date_for(d.year, d.month, a.payday, a.rule)
    print(f"本月实际发薪日 {pay} {WEEK[pay.weekday()]}")
    print("=" * 78)

    rows = []
    if a.at:
        if " " in a.at.strip():
            ds, ts = a.at.strip().split()
            when = datetime.fromisoformat(f"{ds}T{ts}")
        else:
            when = datetime.fromisoformat(f"{d.isoformat()}T{a.at.strip()}")
        rows.append(row(S, start, end, when))
        print(f"\n单时刻：{when}")
    else:
        points = sorted({0, max(0, start - 1), start, start + (end - start) // 3,
                         start + (end - start) // 2, end - 1, end, 23 * 60 + 59}
                        | set(range(0, 24 * 60, max(1, a.step))))
        for m in points:
            if 0 <= m < 24 * 60:
                rows.append(row(S, start, end, datetime(d.year, d.month, d.day, m // 60, m % 60)))
        print(f"\n全天时间曲线（采样 {a.step} 分钟 + 关键边界点）：")
    print_table(rows)

    print(f"\n账平自检：本月所有工作日封顶合计 {V.money(total)} "
          f"{'✓ 等于月工资' if total == S else '✗ 与月工资不符，请检查假日表'}")
    print("提示：这不等于 App 运行效果。装上 APK 后请用 adb logcat -s NiuMaFei:V 核对真实刷新。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
