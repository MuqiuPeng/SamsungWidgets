# SamsungWidgets — 三星桌面小工具

Android 主屏小组件集合（Kotlin + Jetpack Glance），在 One UI（三星）桌面上使用。
一个 App 可以提供多个小工具：每个小工具放在 `widgets/<名字>/` 下，并在 `AndroidManifest.xml` 里注册一个 receiver。

## 小工具

| 小工具 | 位置 | 说明 |
| --- | --- | --- |
| 实时汇率 | `app/src/main/java/com/guans/widgets/widgets/exchangerate/` | 显示多个货币对的汇率；按设置的间隔自动更新（15/30/60 分钟或仅手动），点右上角图标立即刷新；点标题打开设置页 |

设置页（`ui/SettingsScreen.kt`）全部通过选择完成：从货币列表两步选出货币对，菜单里反转、排序、删除；选择刷新间隔；
切换语言（跟随系统 / 简体中文 / English，Android 13+ 也可在系统“应用语言”里设置）。文案在 `res/values`（英文）和 `res/values-zh`（中文）。

## 结构

```
app/src/main/java/com/guans/widgets/
├── MainActivity.kt                 设置页入口（一键添加到桌面）
├── ui/                             设置页界面（Compose + Material 3，跟随壁纸取色）
├── data/
│   ├── Currencies.kt               可选货币、旗帜、本地化名称
│   ├── Settings.kt                 货币对与刷新间隔
│   └── RateRepository.kt           汇率数据源（多源回退，统一换算成 USD 基准表）
└── widgets/exchangerate/
    ├── ExchangeRateWidget.kt       Glance 界面、receiver、刷新按钮
    └── RateUpdater.kt              拉取并推送到所有实例、WorkManager 定时任务
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

手机上到本仓库的 Releases 页面下载最新的 `SamsungWidgets-vX.Y.Z.apk` 安装即可，会覆盖旧版本，设置和小工具都保留。

发新版本：

1. 在 `app/build.gradle.kts` 里把 `versionCode` 加 1，并修改 `versionName`
2. 提交改动
3. 运行 `scripts/release.sh "更新说明"`：构建签名 APK、推送代码，并以 `v<versionName>` 为 tag 创建 Release

发布签名用的 keystore 不在仓库里（`keystore.properties` 指向它）。**keystore 丢失后，新 APK 就无法覆盖安装旧版本**，务必备份。
