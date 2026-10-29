# Toolbox — 三星桌面小工具

Android 主屏小组件集合（Kotlin + Jetpack Glance），在 One UI（三星）桌面上使用。
一个 App 可以提供多个小工具：每个小工具放在 `widgets/<名字>/` 下，在 `AndroidManifest.xml` 里注册一个 receiver，
并在 `ui/ToolboxApp.kt` 的 `WIDGETS` 里加一项，左侧抽屉就会出现它的设置页。

App 左侧抽屉分两部分：**小工具**（各小工具的设置）和**设置**（语言、更新、关于）。
每次打开会检查 GitHub Releases 是否有新版本，有则提示，可在 App 内下载并调起安装。

## 小工具

| 小工具 | 位置 | 说明 |
| --- | --- | --- |
| 实时汇率 | `app/src/main/java/com/guans/widgets/widgets/exchangerate/` | 显示多个货币对的汇率及较前一日涨跌（中文红涨绿跌，英文绿涨红跌）；按设置的间隔自动更新（15/30/60 分钟或仅手动），点右上角图标立即刷新；点标题打开设置页 |
| 习惯计时 | `app/src/main/java/com/guans/widgets/widgets/routine/` | 洗澡、护肤等事项距上次做过去了多久；做完点一下该事项即重新计时，5 分钟内可在右上角撤销；不设周期、不提醒，只显示过了多久；另有“循环倒计时”（Claude 5 小时额度、每周额度），显示距下次重置还剩多久，条目底色随本轮进度逐渐变红，点一下即从现在开始新一轮；不联网，每 15 分钟重算一次 |

汇率设置页（`ui/ExchangeRateScreen.kt`）全部通过选择完成：从货币列表两步选出货币对，拖动行首六点手柄排序，菜单里反转、删除；选择刷新间隔。
设置页（`ui/AppSettingsScreen.kt`）：小工具背景（磨砂玻璃 / 实色，所有小工具共用。磨砂玻璃读取壁纸做高斯模糊，按用户拖动对齐的位置裁出小工具背后的一块，需要读壁纸权限；见 `data/GlassBackground.kt`、`ui/GlassSettings.kt`）、语言（跟随系统 / 简体中文 / English，Android 13+ 也可在系统“应用语言”里设置）、检查更新、关于。
习惯计时设置页（`ui/RoutineScreen.kt`）：从预设事项（个人护理、家务、健康、生活、循环倒计时五类）中选择添加，菜单里补记或修正上次时间（日期、时刻选择器）、撤销、删除；拖动行首六点手柄排序。
文案在 `res/values`（英文）和 `res/values-zh`（中文）。

## 结构

```
app/src/main/java/com/guans/widgets/
├── MainActivity.kt                 应用入口（一键添加到桌面）
├── ui/                             界面（Compose + Material 3，跟随壁纸取色）：抽屉、各设置页、更新提示
├── update/UpdateChecker.kt         查询 GitHub 最新 Release、下载并安装 APK
├── data/
│   ├── Currencies.kt               可选货币、本地化名称
│   ├── Settings.kt                 货币对与刷新间隔
│   ├── Routines.kt                 习惯计时：预设事项、每项上次完成的时间
│   └── RateRepository.kt           汇率数据源（多源回退，统一换算成 USD 基准表）
├── widgets/exchangerate/
│   ├── ExchangeRateWidget.kt       Glance 界面、receiver、刷新按钮
│   └── RateUpdater.kt              拉取并推送到所有实例、WorkManager 定时任务
└── widgets/routine/
    ├── RoutineWidget.kt            Glance 界面、点击记录与撤销
    └── RoutineTicker.kt            定时重绘“多久之前”
```

## 免费汇率 API 调研（2026-10-29 实测）

| API | 需要 key | 更新频率 | 实测 | 备注 |
| --- | --- | --- | --- | --- |
| Coinbase `api.coinbase.com/v2/exchange-rates?currency=USD` | 否 | 盘中更新 | ✅ 可用 | 免费里最接近“实时”；并非专门的外汇 API，取中间价，无 SLA。**本项目首选** |
| fawazahmed0/exchange-api（jsDelivr / `currency-api.pages.dev`） | 否 | 每日 | ✅ 可用 | 无限额，走 CDN，币种多（含加密货币），支持按日期查历史。回退 1 |
| ExchangeRate-API 开放端点 `open.er-api.com/v6/latest/USD` | 否 | 每日 | ✅ 可用 | 要求注明来源；有速率限制。回退 2 |
| HexaRate `hexarate.paikama.co` | 否 | 每日 | ✅ 可用 | 一次只返回一个货币对 |
| Frankfurter（`api.frankfurter.dev` / `.app`） | 否 | 每个工作日（欧洲央行） | ❌ 当时 520/522 | 平时很稳定，数据来自 ECB，但只有约 30 种货币 |
| exchangerate.host | **是**（已并入 apilayer） | — | ❌ 401 | 以前免费无 key，现在需要注册 |
| Open Exchange Rates / ExchangeRate-API 标准版 / currencyapi 等 | 是（免费档） | 每小时或每日 | 未测 | 免费档每月约 1000–1500 次，注册即可 |

结论：完全免费、免注册、并且真正逐笔“实时”的外汇行情 API 基本不存在；Coinbase 是免 key 里更新最勤的，
其余免费源多为每日一更。需要更高频率时，可以注册带免费额度的服务（如 Open Exchange Rates 每小时）。

另外，Android 对小组件的后台刷新有限制：系统定时最短 15 分钟，所以小工具采用“15 分钟自动 + 点击立即刷新”。

## 构建

需要 JDK 17+ 和 Android SDK（compileSdk 35，本机装在 `%LOCALAPPDATA%\Android\Sdk`，路径写在 `local.properties`）。

```bash
./gradlew assembleDebug
```

APK 位于 `app/build/outputs/apk/debug/`，装到三星手机后长按桌面 → 小组件 → 桌面小工具 → 实时汇率。

## 发布与更新

手机上到本仓库的 Releases 页面下载最新的 `Toolbox-vX.Y.Z.apk`（v0.3.0 起 App 内可直接更新） 安装即可，会覆盖旧版本，设置和小工具都保留。

发新版本：

1. 在 `app/build.gradle.kts` 里把 `versionCode` 加 1，并修改 `versionName`
2. 提交改动
3. 运行 `scripts/release.sh "更新说明"`：构建签名 APK、推送代码，并以 `v<versionName>` 为 tag 创建 Release

发布签名用的 keystore 不在仓库里（`keystore.properties` 指向它）。**keystore 丢失后，新 APK 就无法覆盖安装旧版本**，务必备份。
