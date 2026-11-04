# Toolbox — home-screen widgets for Samsung One UI

> 中文说明见 [README.zh-CN.md](README.zh-CN.md)

A collection of Android home-screen widgets built with Kotlin and Jetpack
Glance, made for the One UI (Samsung) launcher: live exchange rates with
day-over-day movement, a timer counting how long since you last did something,
and a GitHub widget showing a repository's latest release, last Actions run,
most recent push and open issue count.

One app, one drawer, a settings page per widget. The drawer has two sections:
**Widgets** (settings for each one) and **Settings** (language, updates, about).
On launch it checks GitHub Releases for a newer version and can download and
install it in-app.

## Install

There is no Play Store listing, so you sideload the APK:

1. On your phone, open [Releases](https://github.com/MuqiuPeng/SamsungWidgets/releases)
   and download the latest `Toolbox-vX.Y.Z.apk`.
2. Android will ask you to allow installing from your browser or file manager
   the first time — allow it, then open the APK.
3. Long-press the home screen → **Widgets** → **Toolbox**, and pick one.

Installing over an older version keeps your settings and placed widgets. From
v0.3.0 on, the app updates itself from GitHub Releases.

Requires Android 8.0 or later (`minSdk 26`). Built and tested against One UI;
it works on other
launchers that support Glance widgets, but the glass background is tuned for
One UI.

## The widgets

### Live exchange rates

Several currency pairs with their movement against the previous day — red for up
and green for down in Chinese, green for up and red for down in English, each
matching local convention. Auto-updates on the interval you set (15 / 30 / 60
minutes, or manual only); the icon in the top-right refreshes immediately;
tapping the title opens settings.

The settings page is pick-only, no typing: choose a pair from the currency list
in two steps, drag the six-dot handle to reorder, and use the row menu to invert
or delete a pair.

### Routine timer

Named things you do irregularly, showing how long it has been since you last did
each. Tap a row when you have done it and the timer restarts; you have five
minutes to undo from the top-right. No schedules and no reminders — it only
shows elapsed time. The row menu backfills or corrects the last time with date
and time pickers, renames, undoes and deletes.

It also carries **quota countdowns** for Claude's and Codex's five-hour and
weekly limits: how long until the next reset, with the row shading towards red as
the current window is used up. Sign in to claude.ai or ChatGPT on the settings
page and it reads the real reset times (via a non-public endpoint, refreshed
about every 15 minutes; the session cookie is encrypted with the Android
Keystore). Without a sign-in, or if the read fails, it falls back to timing the
five-hour window itself — tap to start, and it stops at the end. Everything else
in this widget works offline and recomputes every 15 minutes.

### GitHub repository

Link an account (GitHub App device authorisation, read-only, token encrypted
on-device, several accounts supported) and pick a repository — private ones
included. The widget shows its latest release, the result of the last Actions run
(✓ passed / ✕ failed / ● running), the most recent push time and the number of
open issues. Tapping the repository opens it in the browser. System notifications
fire when Actions fail or recover and when a release is published, each toggled
separately. Requests carry an ETag, so an unchanged repository costs nothing
against the rate limit.

## Settings

- **Widget background**: frosted glass or solid colour, shared by every widget.
  Frosted glass reads your wallpaper, blurs it and crops the piece sitting behind
  the widget, which needs wallpaper read permission. Tapping an empty part of a
  widget (the title, say) records its position and real size automatically; you
  can also align it by hand on the settings page.
- **Language**: follow the system, 简体中文 or English. On Android 13+ you can
  also set it from the system's per-app language settings.
- **Check for updates**, **error log** (crashes and background failures are kept
  on-device and can be shared in one tap; the log carries no tokens), **about**.

## Build

Needs JDK 17+ and the Android SDK (compileSdk 35; the SDK path goes in
`local.properties`).

```bash
./gradlew assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/`.

Each widget lives in `widgets/<name>/`, registers a receiver in
`AndroidManifest.xml`, and gets an entry in `WIDGETS` in `ui/ToolboxApp.kt` —
that entry is what puts its settings page in the drawer. Strings are in
`res/values` (English) and `res/values-zh` (Chinese). See
[README.zh-CN.md](README.zh-CN.md) for the full source layout.

## Releasing

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Commit.
3. Run `scripts/release.sh "release notes"` — builds a signed APK, pushes, and
   creates a release tagged `v<versionName>`.

The release keystore is not in the repository; `keystore.properties` points at
it. **Back it up** — lose it and new APKs can no longer install over old ones.

## Free exchange-rate APIs, tested 2026-10-29

Worth writing down, because the answer surprised me: there is essentially no
free, registration-free API that gives genuinely real-time FX quotes.

| API | Key needed | Updates | Result | Notes |
| --- | --- | --- | --- | --- |
| Coinbase `api.coinbase.com/v2/exchange-rates?currency=USD` | no | intraday | ✅ works | Closest to real-time among key-free options. Not a dedicated FX API — mid prices, no SLA. **Used here by default** |
| fawazahmed0/exchange-api (jsDelivr / `currency-api.pages.dev`) | no | daily | ✅ works | No quota, CDN-served, many currencies including crypto, historical by date. Fallback 1 |
| ExchangeRate-API open endpoint `open.er-api.com/v6/latest/USD` | no | daily | ✅ works | Requires attribution; rate-limited. Fallback 2 |
| HexaRate `hexarate.paikama.co` | no | daily | ✅ works | One pair per request |
| Frankfurter (`api.frankfurter.dev` / `.app`) | no | each business day (ECB) | ❌ 520/522 at the time | Usually stable, ECB data, but only ~30 currencies |
| exchangerate.host | **yes** (now part of apilayer) | — | ❌ 401 | Used to be free without a key |
| Open Exchange Rates / ExchangeRate-API standard / currencyapi | yes (free tier) | hourly or daily | not tested | ~1000–1500 calls a month on the free tier |

Also: Android caps widget background refresh at 15 minutes, which is why the
widgets are "every 15 minutes, plus tap to refresh now" rather than continuous.

## License

MIT — see [LICENSE](LICENSE).
