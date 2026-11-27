# 牛马费倒计时 · 配置与构建说明

自用 Android 倒计时 + 桌面小组件。四个倒计时（发薪 / 下班 / 距离周五 / 距离下一个法定节假日）+ 随时间递增的「牛马费」+ 猫猫坐电脑旁。

**业务口径以项目根目录之外的 `牛马费倒计时-项目档案.md` 为准（11 条已锁定）。**

---

## ✅ 本机交付状态

**已在本机编译通过**：`gradlew.bat assembleDebug` → `BUILD SUCCESSFUL`（33 tasks），产物 `牛马费倒计时-debug.apk`（v2 签名，apksigner 验证通过）位于工作区根目录，可直接安装。**当前版本 v1.3**（华为 5 秒限流加固 + 四向可调 + **尺寸自适应，任意大小不留白**）。本机的华为秒级验收需按《v1.2 华为 5 秒问题排查》在手机上确认。

本机实测编译环境：

| 项 | 值 |
|---|---|
| Android Studio | 2026.2.2.9，`D:\软件\android-studio` |
| Studio 自带 JBR | **JDK 25 — 与本项目不兼容**，见下方说明 |
| 实际编译 JDK | Temurin **17.0.21**（便携版，`.toolchain/jdk-17.0.21+9`） |
| Android SDK | `C:\Users\Administrator\AppData\Local\Android\Sdk` |
| 必需 SDK 组件 | `platforms;android-34`、`build-tools;34.0.0`（AGP 另自动补装 33.0.1） |

⚠ **JDK 版本坑**：工程锁定 Gradle 8.2 / AGP 8.1.4 / Kotlin 1.9.20，只支持 JDK 17~20；Studio 2026.2.2 自带 JBR 25，直接点 Run 会报 `Unsupported class file major version` / `Could not determine java version`。命令行需 `set JAVA_HOME=<JDK17>`，Studio 里需 `Settings → Build Tools → Gradle → Gradle JDK` 选 17。

需要重新构建时的两条路径：

**A. Android Studio（推荐）**
1. 安装 Android Studio；确认已装 `SDK Platform 34` / `Build Tools 34.0.0`，**Gradle JDK 选 17（不要用 Studio 自带 JBR 25）**
2. `File → Open` 选中本目录 `niumafei-countdown`。工程已内置完整 Gradle Wrapper（`gradlew` / `gradlew.bat` / `gradle-wrapper.jar`），**无需 IDE 补齐**
3. `Build → Build Bundle(s) / APK(s) → Build APK(s)`，或直接点绿三角 Run 到真机/模拟器
4. 产物：`app/build/outputs/apk/debug/app-debug.apk`

命令行等价（Windows PowerShell，JDK 17 + 国内镜像加速）：

```powershell
$env:JAVA_HOME = '<JDK 17 目录>'
$env:ANDROID_HOME = 'C:\Users\Administrator\AppData\Local\Android\Sdk'
.\gradlew.bat -I tools\gradle-mirrors.init.gradle assembleDebug
```

`-I` 指向的镜像脚本只改变本机仓库地址，不改工程版本口径；GitHub Actions 云编译不使用它。

**B. GitHub Actions 云编译（不必装开发环境）**
1. 把**本目录作为仓库根目录**推到 GitHub（即仓库根下直接是 `settings.gradle.kts`、`app/`、`.github/`）
2. 已内置 `.github/workflows/build-apk.yml`，push 或手动 `workflow_dispatch` 即触发
3. 在 Actions 运行页下载 artifact `niumafei-debug-apk`，里面就是 `app-debug.apk`

首次构建需联网拉取 `AGP 8.1.4` / `Kotlin 1.9.20` / `Gradle 8.2`（本机实测约 5 分钟）。工程 `dependencies` 为空（纯 Android Framework + Kotlin 标准库），不依赖任何第三方库，构建失败面最小。

---

## 本机调试（Windows）

### 0. 装环境（Android Studio + SDK + adb，并额外准备 JDK 17）

```bat
winget install -e --id Google.AndroidStudio
```

（若包 ID 不通，先 `winget search AndroidStudio` 查本机实际 ID。）首次启动后在 `SDK Manager` 勾选：`SDK Platforms → Android 14 (API 34)`、`SDK Tools → Android SDK Build-Tools 34 / Android SDK Platform-Tools / Android Emulator`。**注意：Studio 2026.2.x 自带 JBR 25，与 Gradle 8.2 不兼容，需另装 JDK 17 并在 `Settings → Build Tools → Gradle → Gradle JDK` 指定。**

不装 IDE 的轻量路线：装 `EclipseAdoptium.Temurin.17.JDK` + Android `commandlinetools`，再执行 `sdkmanager "platforms;android-34" "build-tools;34.0.0" "platform-tools"` 并 `sdkmanager --licenses` 全部接受。

**只想装机调试、不想装几个 G 的 IDE**：走上面「B. GitHub Actions 云编译」拿 APK，本机只装 `platform-tools`（含 adb）即可直接跳到第 2 步，第 1 步的编译可完全跳过。

### 1. 编译 + 安装

```bat
set JAVA_HOME=<JDK 17 目录>
set ANDROID_HOME=C:\Users\Administrator\AppData\Local\Android\Sdk
cd niumafei-countdown
gradlew.bat -I tools\gradle-mirrors.init.gradle assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

`adb` 在 `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe`，Studio 的 Terminal 已自带。若报 `SDK location not found`，在工程根建 `local.properties` 写 `sdk.dir=C\:\\Users\\Administrator\\AppData\\Local\\Android\\Sdk`（不要提交 git）。

### 1.5 零设备时间旅行（不需要手机、不需要模拟器）

```bat
cd niumafei-countdown
python3 tools\simulate_day.py --date 2027-01-04 --salary 9500 --start 09:00 --end 18:00
python3 tools\simulate_day.py --at "2027-01-04 12:00"
python3 tools\simulate_day.py --at "2027-01-04 12:00:30"
python3 tools\simulate_day.py --date 2027-02-08 --step 360
python3 tools\simulate_day.py --date 2027-01-31
python3 tools\simulate_day.py --date 2027-01-04 --step 1
python3 tools\verify_logic.py
```

`simulate_day.py` 复用 `verify_logic.py` 里与 Kotlin 一一对应的计算逻辑，打印任意时刻的牛马费、状态、进度、猫猫帧、下一次闹钟时刻与排程原因，并断言「本月工作日封顶合计 = 月工资」。上面 6 条依次看：全天曲线 / 12:00 单时刻 / 12:00:30 秒级递增 / 春节放假日全天 ¥0.00 / 周日补班照常涨 / 每分钟采样曲线；最后一条是 83 项口径自检（含 v1.3 尺寸档位镜像测试）。

⚠ 它验证的是**算法**，不能验证系统闹钟是否真的唤醒 —— 后者必须装上 APK 看 `logcat`。

### 2. 设备（本机实测：AVD 已有，但缺硬件加速）

本机已有现成 AVD `Medium_Phone_API_37.0`（API 37 / x86_64 / Google Play 镜像，2.6 GB 系统镜像已下载），`emulator.exe -list-avds` 可见。但实测启动报：

> `ERROR | x86_64 emulation currently requires hardware acceleration!`
> `CPU acceleration status: Unable to open AEHD device: ERROR_ACCESS_DENIED`

原因：`HypervisorPlatform / VirtualMachinePlatform / Hyper-V` 三项全部 Disabled，且未装 AEHD 驱动；CPU 本身支持 VT-x（`VirtualizationFirmwareEnabled=True`）。三条路径按代价排序：

| 路径 | 做法 | 代价 |
|---|---|---|
| **A 真机（最快）** | 手机开「开发者选项 → USB 调试」，USB 连上，`adb devices` 出 `device` | 不用重启、不改系统 |
| **B 开 WHPX（推荐）** | 管理员 PowerShell 执行 `Enable-WindowsOptionalFeature -Online -FeatureName HypervisorPlatform -All`，**重启电脑**；之后 Studio `Device Manager` 直接点 ▶ 启动该 AVD | 重启一次 |
| **C 装 AEHD** | Studio → `Settings → Languages & Frameworks → Android SDK → SDK Tools` 勾 `Android Emulator Hypervisor Driver (AEHD)` → Apply → 以管理员运行安装目录下的 `silent_install.bat` → 重启 | 管理员 + 通常也要重启 |

命令行启动等价：`emulator.exe -avd Medium_Phone_API_37.0 -gpu swiftshader`（加 `-no-window` 可做无界面自检）。启动成功判定：`adb devices` 出现 `emulator-5554 device`。

⚠ Play Store 镜像是 user build，**不能 `adb root`**（`adb shell date -s` 不可用），但仍可在系统「设置 → 日期和时间」关自动后手动改日期，用来跑第 6 节的放假日 / 补班日验证。

⚠ **必须先把小组件加到桌面**：没加时 `redraw()` 查不到 id 会主动取消闹钟，刷新链根本不会启动。

### 3. 看日志（最关键）

```bat
adb logcat -c
adb logcat -s NiuMaFei:V
```

每次重绘打印 `draw now=... amount=... sub=... widgets=N`，排程打印 `alarm next=... exact=true/false`，系统兜底打印 `fallback onUpdate working=...`。崩溃看 `adb logcat -s AndroidRuntime:E`。

### 4. 手动触发一次秒级重绘

```bat
adb shell am broadcast -n com.example.niumafei/.WidgetRefresher -a com.example.niumafei.REFRESH
adb shell am broadcast -n com.example.niumafei/.WidgetRefresher -a com.example.niumafei.SETTINGS_CHANGED
```

`.NiuMaFeiWidget` 仍是 `exported=false`，且系统 `APPWIDGET_UPDATE` 需携带 `appWidgetIds`，不要手工给它发广播；用上面两条即可。

Android 8+ 隐式广播受限，**必须带 `-n` 显式组件**。debug 构建已在 `app/src/debug/AndroidManifest.xml` 把 `WidgetRefresher` 覆盖为 `exported=true`（release 构建仍为 `false`），所以上面命令可直接用，**不需要临时改 Manifest 再改回**。

### 5. 查排程

```bat
adb shell dumpsys alarm | findstr /i niumafei
adb shell dumpsys appwidget | findstr /i niumafei
```

看下一个闹钟时刻是否符合《刷新机制》表；日志里 `exact=false` 说明精确闹钟权限没开，到设置页点按钮开启。

### 6. 时间旅行（不用改系统时间，几分钟跑完四分支）

在设置页改上下班时间并保存，每次保存都会触发 `SETTINGS_CHANGED` 重绘 + 重排：

| 想验证 | 把作息改成（假设现在 09:00） | 预期 |
|---|---|---|
| 工作中递增 | `08:00` / `17:00` | 金额每秒涨、猫猫搬砖，`alarm next` 为下一整秒 |
| 上班前 | `09:05` / `17:00` | `¥0.00` + 摸鱼猫，`alarm next=今天 09:05` |
| 下班封顶 | `08:00` / `09:02` | 2 分钟后封顶 + 收工猫，之后只排次日 00:00 |
| 下班后零唤醒 | `08:00` / `08:30` | 金额保持封顶不变，无秒链 |

休息日 / 补班日：在模拟器「设置 → 日期和时间」关自动后手改日期到 `2027-02-08`（春节周一）看「¥0.00 / 休息日 · 不涨钱」，改到 `2027-01-31`（周日补班）看照常涨钱。真机改日期一般需 root，用真实周末验证即可。

### 7. 常见卡顿

| 现象 | 处理 |
|---|---|
| 小组件只显示上半截 / 周五、节假日、更新于被切掉 | v1.3 两种版式都按高度等分铺满；若仍是升级前的旧实例，**删掉桌面组件重新添加**即可 |
| **调整大小后下半截一大片空白 / 拖到某处就拖不动了** | 见《尺寸自适应（v1.3）》：旧紧凑版是全 `wrap_content` 顶部堆叠 + `minResizeHeight=110dp` 卡住下限；新版已铺满、下限放到 70dp，并可在设置页手动选版式 |
| `x86_64 emulation currently requires hardware acceleration` / `Unable to open AEHD device` | 本机 WHPX/AEHD 未开，见第 2 节；临时可先用真机或 `simulate_day.py` |
| `JAVA_HOME is not set` / JDK 版本不对 | `set JAVA_HOME=<JDK 17 目录>`；Studio 里设 `Gradle JDK = 17`，**不要用自带 JBR 25** |
| `Unsupported class file major version 69` / `Could not determine java version '25'` | 用了 JDK 25，换回 JDK 17 |
| Gradle 发行版下载 `Read timed out` | wrapper 超时已放宽到 120s；国内可先 curl 下载发行版注入 `~/.gradle/wrapper/dists`，或用 `tools/gradle-mirrors.init.gradle` 走镜像 |
| `License not accepted` | `sdkmanager --licenses` 一路 `y` |
| 装不上 / 签名冲突 | `adb uninstall com.example.niumafei` 后再 `adb install -r` |
| 桌面数字/秒位不动 | 先看设置页「刷新诊断」的实际间隔（见《v1.2 华为 5 秒问题排查》）；没有 `draw` → 精确闹钟权限 + 电池白名单 + 华为应用启动管理四项；有 `draw` 但金额不变 → 核对是否落在非工作区间 |
| **华为变成约 5 秒一跳** | 确认「真秒级保活」开关已开，并按《权限与保活》补齐华为四项；诊断显示 ≈1000ms 但桌面仍 5 秒则是桌面自身限流 |
| 组件显示旧值 | 看右下角「更新于 HH:mm:ss」是否推进；手动发一次 `REFRESH` 广播 |
| 通知栏常驻一条「秒级刷新中」 | 这是前台服务；可在系统通知设置里静默/关闭该类别，或在 App 设置页关掉「真秒级保活」（关闭后回到纯闹钟链） |

---

## 首次使用

1. 安装 APK，打开「牛马费」
2. 填 4 项：月工资（**实发到手**）、上班时间、下班时间（`HH:mm`，支持 `18:30`）、每月发薪日（1–31）
3. 选发薪日撞周末 / 放假日时的策略：**提前 / 延后 / 不调整**
4. 点「保存并刷新桌面」。非法输入会就地红字提示，**已填内容不会被清空**
5. 若出现精确闹钟权限提示，点按钮开启（Android 12/13+ 需要）
6. 长按桌面空白处 → 小组件 → 「牛马费倒计时」，默认占 **4×2 格**（`minHeight=110dp`）。**升级后请把旧小组件删掉重新添加** —— 已添加的实例不会自动套用新布局与新的 resizeMode
7. **v1.3：长按组件进入编辑态后，上下左右四个把手都能拖动**（`resizeMode=horizontal|vertical`），最小可缩到约 **2×1 格**（`minResizeWidth=110dp` / `minResizeHeight=70dp`）；任何尺寸下内容都按高度等分铺满，不会出现下半截空白
8. 若桌面缩放后字太大/太小，或桌面压根不回报尺寸：设置页「小组件版式」直接选 **自动 / 紧凑 / 标准 / 舒展**，切换后立即重绘、无需删组件；「刷新诊断」里同时显示实测桌面尺寸
9. 点小组件任意位置可回到设置页；设置页「刷新与保活」区可开关真秒级前台服务，并直接查看实际重绘间隔

---

## 核心口径速查

| 项 | 规则 |
|---|---|
| 工作日 | 当月周一到周五 − 法定放假日 + 调休补班日 |
| 日薪 | 实发月工资 ÷ 当月工作日数（**不使用固定 21 / 22 天**） |
| 牛马费 | 上班时刻从 ¥0 **按秒**线性涨 → 下班时刻涨满当日封顶 → 保持不变 → 次日 00:00 归零；非工作日 ¥0.00 /「休息日 · 不涨钱」 |
| 倒计时与刷新 | 4 项倒计时均显示到秒（`HH:MM:SS` / `N天 HH:MM:SS`）；桌面工作时每秒重绘，下班后与休息日零唤醒 |
| 秒级保活（v1.2） | `setExact` 秒链 + 可选前台服务双驱动，350ms 去重；设置页一键开关，默认开启（常驻静默通知） |
| 小组件尺寸（v1.3） | 默认 4×2、最小约 2×1，四向可调；所有版式按 weight 铺满任意高度，字号/内边距/猫图按实测尺寸运行时缩放；设置页可手动指定版式兜底 |
| 账平 | 当日封顶按工作日序号分摊 `cap(k)=⌊kS/n⌋−⌊(k−1)S/n⌋`，每日差 ≤1 分，累计严格等于月工资 |
| 发薪日 | 手填每月几号、按月循环；遇假可提前 / 延后 / 不调整 |
| 距离周五 | 算到本周五下班时刻；周五下班后及周末显示「周末中」 |
| 距离节假日 | 只认法定放假档期（不含普通周末），显示节日名 + 天数 + 距放假首日 00:00 |
| 猫猫 | 3 帧静态切换，随牛马费进度：前 1/3 摸鱼 / 中后段搬砖 / 封顶与休息日收工 |

**账平前提**：当月内月工资 / 作息 / 假日表未变更。月中改设置会断账（自用场景可接受），改完重新按月看即可。

---

## 假日日历

数据源：**国务院办公厅关于 2027 年部分节假日安排的通知**（国办发明电〔2026〕10号，2026-11-05 发布），已核对中国政府网原文。

文件：`app/src/main/assets/holidays.json`

```json
{
  "years": [2027],
  "festivals": [{ "name": "春节", "start": "2027-02-05", "end": "2027-02-13" }],
  "daysOff": ["2027-02-05", "..."],
  "makeupWorkdays": ["2027-01-31", "2027-02-14", "2027-05-08", "2027-09-26", "2027-10-09"]
}
```

- `daysOff`：所有不上班的日期（含落在周末的），用于扣分母 + 跳过涨钱
- `makeupWorkdays`：调休补班的周末，**优先级高于放假日与周末**，这几天照常涨钱并计入分母
- `festivals`：只用于节假日倒计时

**每年 11 月**国务院办公厅发布次年安排后，替换这个文件即可（把 `years`、`festivals`、`daysOff`、`makeupWorkdays` 四块一起换）。未覆盖的年份会自动降级为「仅周一到周五」，并在设置页与主界面预览里提示。全程离线，不申请联网权限。

---

## 刷新机制（v1.2 双驱动）

采用**单点闹钟链 + 可选前台秒针**双驱动，每次重绘后只排「下一次必须重绘」的那一个时刻：

| 当前状态 | 下一次触发 |
|---|---|
| 工作日、上班→下班之间 | **下一整秒**（秒链只在此自续） |
| 工作日、还没上班 | 今天上班时刻（一次性启链） |
| 下班后 / 周末 / 放假日 | 次日 00:00（归零边界） |

> **v1.1 口径变更（用户 2026-11-10 拍板）**：由「工作时分钟级」升级为「**桌面全部真秒级，含牛马费金额**」。
> **v1.2 口径变更（同日华为真机反馈）**：「不用前台服务」放宽为「**可选前台服务，默认开启、设置页可一键关闭**」。用户已关电池优化但华为仍约 5 秒一跳，纯闹钟链在 EMUI 上不可靠。

### v1.2 针对华为 5 秒问题改了什么

1. **工作秒链改用 `setExact(RTC_WAKEUP, …)`**：`setExactAndAllowWhileIdle` 允许系统重排/限流（AOSP 明示非 Doze 也可能约 1 分钟才放行一次，EMUI 实测约 5 秒合并一次）。桌面可见时屏幕必然亮着、本就不在 Doze，普通精确闹钟更合适。**只有 0 点 / 上班这类一天 1~2 次的边界闹钟**才继续用 `setExactAndAllowWhileIdle` 穿透 Doze。
2. **闹钟广播加 `Intent.FLAG_RECEIVER_FOREGROUND`**：官方明确指出后台优先级广播会被延迟，这是最便宜的一处提速。
3. **新增 `TickService` 前台服务**：进程内 `Handler` 每 1000ms 直接 `updateAppWidget`，完全绕开 AlarmManager / 后台广播调度。与闹钟链**并行互为备份**，`redraw()` 内 350ms 去重，正常每秒仍只画一次；任一路被杀，另一路继续。开机 / 解锁 / 亮屏 / 精确闹钟权限变更 / 打开设置页都会尝试拉起。
4. **解锁自动重排**：新增 `USER_PRESENT` / `SCREEN_ON` 广播，息屏期间被限流后一亮屏立刻接上。
5. **精确闹钟权限未授予时**先试 `setAlarmClock()`（系统不调整其投递时间），被拒才降级非精确闹钟。

**代价**：前台服务会有一条 `IMPORTANCE_MIN`、无声音无震动的常驻通知（可在系统通知设置里设为静默或直接关掉该通知类别，服务仍运行）。设置页关掉「真秒级保活」开关即回到纯闹钟链、**零通知**。

### 权限与保活（华为必做，只关电池优化不够）

- Android 12（API 31）+ 需要「精确闹钟（闹钟和提醒）」权限；设置页会自动检测并给出去开启的按钮
- 电池优化白名单：`系统设置 → 应用 → 牛马费 → 电池` 关闭电池优化 / 允许后台运行
- **华为/荣耀额外四项（5 秒问题的常见根因）**：
  1. `设置 → 应用和服务 → 应用启动管理 → 牛马费` → **关闭「自动管理」**，在手动弹窗里勾选 **允许自启动 / 允许关联启动 / 允许后台活动**
  2. 多任务界面把「牛马费」卡片**下拉锁定**
  3. `设置 → 电池` 关掉省电模式 / 超级省电
  4. `设置 → 应用 → 华为桌面 → 电池` 也确认没被限制（Launcher 被回收会断掉 AppWidgetHost 监听链）
- 安装后**先打开一次 App 并保存设置**，让前台服务从前台合规启动；之后开机自启会接管

开机、时间/时区变更、日期变更、应用更新、保存设置、解锁，都会触发全量重绘 + 重排。小组件右下角显示「更新于 HH:mm:ss」，可据此一眼判断刷新是否还活着。

### v1.2 华为 5 秒问题排查（不用连电脑也能判）

设置页「刷新与保活」区的**刷新诊断**会显示「最近实际重绘间隔」：

| 诊断值 | 含义 | 处理 |
|---|---|---|
| ≈1000 ms，桌面也每秒跳 | 已真秒级 | 完成 |
| ≈5000 ms | 后台唤醒被合并 | 确认「真秒级保活」开关已开 + 上面华为四项全勾；看精确闹钟权限 |
| ≈1000 ms，但桌面仍 5 秒一跳 | **华为桌面自身在限流 `updateAppWidget`** | 前台服务也无解；下一步只能把 4 个倒计时改成 `Chronometer` 由桌面进程自绘（会改变 `N天 HH:MM:SS` 的显示格式），需你确认后再做 |

连电脑可看更细的日志：

```bat
adb logcat -c
adb logcat -s NiuMaFei:V
```

每次重绘打印 `draw now=... delta=NNNms amount=...`，`delta` 就是实际间隔；排程打印 `alarm next=... kind=SECOND/BOUNDARY exact=true/false`。`kind=SECOND` 应走 `setExact`。

---

## 尺寸自适应（v1.3：兼容所有显示大小）

**问题**：华为桌面把卡片给得很高，但 v1.2 的紧凑版布局所有行都是 `wrap_content`，内容堆在上半截；同时 `minResizeHeight=110dp` 把缩小下限卡住，于是「一大片空白 + 没法继续调整」。

**改法（四层，逐层兜底）**：

| 层 | 做法 | 解决什么 |
|---|---|---|
| 1. 布局铺满 | 标准版与紧凑版的所有内容行一律 `0dp + layout_weight` + `gravity=center_vertical`，根布局 `match_parent` | 桌面给多高就铺满多高，**尺寸判断错了也不会留白** |
| 2. 动态缩放 | 按 widgetId 实测尺寸在运行时 `setTextViewTextSize` / `setViewPadding` / `setViewLayoutWidth(Height)`（API 31+）缩放 12 个文本、内边距、猫图 | 拉大字变大、拉小字自动缩小，不裁字 |
| 3. 尺寸档位 | 自动档：`h<95dp` 或 `w<150dp` → 迷你（隐藏标签、标签并入数值）；`h<130dp` 或 `w<200dp` → 紧凑（隐藏「更新于」）；其余标准/舒展 | 1~2 格的小卡片也能完整显示 4 个倒计时 |
| 4. 手动兜底 | 设置页「小组件版式」= 自动 / 紧凑 / 标准 / 舒展，存 SharedPreferences | EMUI 不回报或乱报尺寸时，用户一步选到合适字号 |

尺寸来源优先 `OPTION_APPWIDGET_SIZES`（API 31+ 精确值），否则用 `MIN/MAX` 兜底；实测尺寸与当前版式会显示在设置页「刷新诊断」，也可在 `adb logcat -s NiuMaFei:V` 看 `size id=... 250x110dp mode=... scale=...`。

> 升级后**务必删掉旧小组件重新添加**：桌面已存在的实例不会自动套用新的 `minHeight` / `resizeMode`。

---

## 装机自检清单（Phase 2 质量门禁压缩版）

| # | 检查项 | 通过标准 |
|---|---|---|
| 1 | 首次启动与保存 | 无崩溃；4 项设置可保存；杀进程重开配置仍在 |
| 2 | 非法输入 | 空 / `25:00` / `32` 号就地红字，且已填内容保留 |
| 3 | 小组件 | 添加成功；金额 + 4 个倒计时 + 猫猫齐全，无重叠截断；四向拉到最大 / 最小（约 2×1 格）都**铺满无空白、不裁字**；设置页切四档版式立即生效 |
| 4 | 四分支 | 上班前 ¥0.00/摸鱼猫；工作中按秒递增；下班整秒封顶/收工猫；次日 00:00 归零 |
| 5 | 休息日 | 周末、法定放假日（如 2027-02-08 周一）¥0.00 且显示「休息日 · 不涨钱」 |
| 6 | 补班日 | 2027-01-31 / 02-14 / 05-08 / 09-26 / 10-09 照常涨钱且计入分母 |
| 7 | 账平 | `python3 tools/verify_logic.py` 全部 PASS（Σ 当日封顶 = 月工资） |
| 8 | 周五 | 到本周五下班时刻；过后切「周末中」 |
| 9 | **静置 2 小时** | 工作时段每 10 分钟看一次桌面，金额与秒位持续跳变；设置页「刷新诊断」间隔 ≈1000 ms（**必须先加电池白名单 + 华为应用启动管理四项**；R1 国产 ROM 保活验收） |
| 10 | 零唤醒 | 下班后 / 夜间金额保持封顶不变、不再排秒链；前台服务的静默通知可在系统里关掉或关闭保活开关 |
| 11 | 重启与时间变更 | 重启手机后小组件自动恢复；改系统时间 / 时区后数值正确重算 |
| 12 | 一致性 | 同一时刻 App 主界面与小组件 6 项数值差异为 0（共用同一计算引擎） |

**质量门禁**：P0 bug = 0；核心链路（涨钱 / 封顶 / 归零 / 休息日 / 补班日）覆盖 100%；静置 2 小时无中断。

---

## 目录结构

```
niumafei-countdown/
├── app/src/main/
│   ├── java/com/example/niumafei/
│   │   ├── Prefs.kt               设置存取（SharedPreferences）
│   │   ├── HolidayCalendar.kt    假日日历解析与工作日判定
│   │   ├── Calculator.kt         纯计算核心：日薪/牛马费/4 个倒计时/文案
│   │   ├── NiuMaFeiWidget.kt     AppWidgetProvider + WidgetLayout 尺寸档位 + RemoteViews 渲染
│   │   ├── WidgetRefresher.kt    单点闹钟链 + 刷新调度 + 350ms 去重与间隔/尺寸诊断
│   │   ├── TickService.kt        v1.2 可选前台秒针（设置页开关）
│   │   └── MainActivity.kt       设置页 + 实时预览 + 保活开关 + 版式选择 + 刷新诊断
│   ├── assets/holidays.json      2027 官方放假调休数据
│   └── res/                     两种 weight 铺满布局、3 帧猫猫、通知小图标等
├── gradlew / gradlew.bat             官方 Gradle 8.2 Wrapper 启动脚本
├── gradle/wrapper/gradle-wrapper.jar
├── app/src/debug/AndroidManifest.xml 仅 debug 放开刷新接收器，供 adb 手动触发
├── .github/workflows/build-apk.yml   云编译出 APK
├── tools/verify_logic.py            编译前口径自检（83 项，含 v1.3 尺寸档位镜像测试，python3 仅标准库）
├── tools/simulate_day.py            零设备时间旅行模拟调试（复用上面的计算逻辑）
├── tools/gradle-mirrors.init.gradle  本机首次构建可选的国内 Maven 镜像加速
└── build.gradle.kts / settings.gradle.kts
```

---

## 已知限制 / 技术债务

- 已本机编译通过（BUILD SUCCESSFUL / v2 签名验证通过），但**未做真机装机验收**；连上设备后按《装机自检清单》逐项跑
- 月中改工资 / 作息 / 假日表会断账平
- 假日表每年 11 月需手动替换
- 秒级刷新约 32,400 次/天；息屏 Doze / 国产 ROM 会限流，**电池优化白名单 + 华为应用启动管理四项为必需项**
- v1.2 前台服务会带来一条常驻静默通知（可关）；若华为桌面自身限流 `updateAppWidget`，前台服务也做不到金额每秒，需改用 `Chronometer` 自绘并保持格式变更的确认
- 桌面小组件只能做静态图切换，猫猫无法做 Lottie / 属性动画
- 未覆盖年份降级为周一到周五
- 自用不上架：无隐私政策、无商店素材、无锁屏 / 悬浮窗 / 金额隐藏
