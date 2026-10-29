package com.guans.widgets.data

import android.Manifest
import android.app.WallpaperManager
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings as SystemSettings
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 磨砂玻璃背景：小工具拿不到身后的画面，所以自己读壁纸、整体高斯模糊，
 * 再按用户对齐的位置裁出小工具背后那一块，叠一层半透明底色。
 *
 * - 读壁纸需要权限：Android 13 起是“所有文件访问”，之前是读取存储。
 * - 桌面不会告诉小工具它在屏幕上的位置，位置由用户在设置页拖动对齐（屏幕比例坐标，存的是中心点）。
 */
object GlassBackground {
    private const val PREFS = "glass"

    /** 模糊在缩小后的壁纸上做：省内存，模糊后放大也看不出差别。 */
    private const val SCALE = 1f / 6
    private const val BLUR_RADIUS = 14
    private const val CORNER_DP = 24f

    // ---------- 权限

    fun hasAccess(context: Context): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
    }

    /** Android 13+ 要跳到系统设置里打开“所有文件访问”；更早的版本走运行时权限。 */
    val needsSettingsPage: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    fun settingsIntent(context: Context): Intent =
        Intent(SystemSettings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))

    // ---------- 位置

    data class Position(val centerX: Float, val centerY: Float)

    fun position(context: Context, appWidgetId: Int): Position? {
        val prefs = prefs(context)
        if (!prefs.contains("x_$appWidgetId")) return null
        return Position(prefs.getFloat("x_$appWidgetId", 0.5f), prefs.getFloat("y_$appWidgetId", 0.5f))
    }

    fun setPosition(context: Context, appWidgetId: Int, position: Position) {
        prefs(context).edit()
            .putFloat("x_$appWidgetId", position.centerX)
            .putFloat("y_$appWidgetId", position.centerY)
            .apply()
    }

    /** 小工具在竖屏下的大小（像素）。 */
    fun widgetSizePx(context: Context, appWidgetId: Int): Pair<Int, Int> {
        val options = AppWidgetManager.getInstance(context).getAppWidgetOptions(appWidgetId)
        val density = context.resources.displayMetrics.density
        val w = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 180)
        val h = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 110)
        return (w * density).roundToInt() to (h * density).roundToInt()
    }

    /**
     * 桌面在格子里给小工具留的边距（dp，每边）。系统报给小工具的大小是整块格子区域，
     * 实际画出来的小工具比它小一圈，裁剪时要扣掉这一圈。各家桌面不一样，由用户在对齐页选。
     */
    val INSET_OPTIONS = listOf(0, 4, 8, 12, 16)

    fun insetDp(context: Context): Int = prefs(context).getInt("inset_dp", 8)

    fun setInsetDp(context: Context, dp: Int) {
        prefs(context).edit().putInt("inset_dp", dp).apply()
    }

    /**
     * 点一下小工具时自动定位：bounds 是小工具可见区域在屏幕上的位置（像素）。
     * 同时记下实际大小，和当时系统报的大小一起存；小工具被拉伸后系统报的大小变了，就不再用旧的实测值。
     * 返回位置是否有明显变化。
     */
    fun locate(context: Context, appWidgetId: Int, bounds: android.graphics.Rect): Boolean {
        val (screenW, screenH) = screenSizePx(context)
        val position = Position(bounds.exactCenterX() / screenW, bounds.exactCenterY() / screenH)
        val old = position(context, appWidgetId)
        val (reportedW, reportedH) = widgetSizePx(context, appWidgetId)
        setPosition(context, appWidgetId, position)
        prefs(context).edit()
            .putInt("w_$appWidgetId", bounds.width())
            .putInt("h_$appWidgetId", bounds.height())
            .putString("reported_$appWidgetId", "${reportedW}x$reportedH")
            .putBoolean("located_$appWidgetId", true)
            .apply()
        return old == null ||
            kotlin.math.abs(old.centerX - position.centerX) * screenW > 4 ||
            kotlin.math.abs(old.centerY - position.centerY) * screenH > 4
    }

    /** 是否由点击自动定位过（设置页显示用）。 */
    fun located(context: Context, appWidgetId: Int): Boolean = prefs(context).getBoolean("located_$appWidgetId", false)

    /** 实测的可见大小；没测过或小工具大小已经变了时为 null。 */
    private fun measuredSizePx(context: Context, appWidgetId: Int): Pair<Int, Int>? {
        val prefs = prefs(context)
        val w = prefs.getInt("w_$appWidgetId", 0)
        val h = prefs.getInt("h_$appWidgetId", 0)
        if (w <= 0 || h <= 0) return null
        val (reportedW, reportedH) = widgetSizePx(context, appWidgetId)
        if (prefs.getString("reported_$appWidgetId", null) != "${reportedW}x$reportedH") return null
        return w to h
    }

    /** 小工具实际可见的大小（像素）：有实测值就用实测值，否则用格子区域扣掉每边的边距。 */
    fun visibleSizePx(context: Context, appWidgetId: Int, insetDp: Int = insetDp(context)): Pair<Int, Int> {
        measuredSizePx(context, appWidgetId)?.let { return it }
        val (w, h) = widgetSizePx(context, appWidgetId)
        val inset = (2 * insetDp * context.resources.displayMetrics.density).roundToInt()
        return max(1, w - inset) to max(1, h - inset)
    }

    /** 整块屏幕（含状态栏、导航栏，壁纸铺满的范围），按竖屏算。 */
    fun screenSizePx(context: Context): Pair<Int, Int> {
        val windows = context.getSystemService(WindowManager::class.java)
        val (w, h) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windows.maximumWindowMetrics.bounds.let { it.width() to it.height() }
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windows.defaultDisplay.getRealMetrics(metrics)
            metrics.widthPixels to metrics.heightPixels
        }
        return minOf(w, h) to maxOf(w, h)
    }

    // ---------- 壁纸

    /** 按屏幕比例裁好、模糊过的整张壁纸（缩小尺寸），壁纸没变就用缓存。 */
    fun blurredWallpaper(context: Context): Bitmap? {
        if (!hasAccess(context)) return null
        val key = wallpaperKey(context) ?: return null
        val file = File(context.filesDir, "glass/wallpaper.png")
        val prefs = prefs(context)
        if (file.exists() && prefs.getString("wallpaper_key", null) == key) {
            BitmapFactory.decodeFile(file.path)?.let { return it }
        }
        val screen = screenWallpaper(context, SCALE) ?: return null
        val blurred = gaussianBlur(screen, BLUR_RADIUS)
        file.parentFile?.mkdirs()
        file.outputStream().use { blurred.compress(Bitmap.CompressFormat.PNG, 100, it) }
        prefs.edit().putString("wallpaper_key", key).apply()
        return blurred
    }

    /** 壁纸按屏幕比例居中裁剪铺满（和桌面显示方式一致），缩放到屏幕的 scale 倍。 */
    fun screenWallpaper(context: Context, scale: Float): Bitmap? {
        if (!hasAccess(context)) return null
        val (screenW, screenH) = screenSizePx(context)
        val width = max(1, (screenW * scale).roundToInt())
        val height = max(1, (screenH * scale).roundToInt())
        val wallpaper = runCatching { WallpaperManager.getInstance(context).drawable }.getOrNull() ?: return null
        val source = wallpaper.toBitmap()
        val fit = max(width.toFloat() / source.width, height.toFloat() / source.height)
        val screen = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(screen).apply {
            translate((width - source.width * fit) / 2f, (height - source.height * fit) / 2f)
            scale(fit, fit)
            drawBitmap(source, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        }
        return screen
    }

    /** 某个小工具的背景：裁出背后那一块、叠底色、切圆角。没权限时返回 null。 */
    fun backgroundFor(context: Context, appWidgetId: Int): Bitmap? {
        val (widgetW, widgetH) = visibleSizePx(context, appWidgetId)
        val (screenW, screenH) = screenSizePx(context)
        val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        val position = position(context, appWidgetId) ?: Position(0.5f, 0.5f)
        val wallpaperKey = wallpaperKey(context) ?: return null
        val key = "$wallpaperKey|$widgetW|$widgetH|${position.centerX}|${position.centerY}|$night"

        val file = File(context.filesDir, "glass/widget_$appWidgetId.png")
        val prefs = prefs(context)
        if (file.exists() && prefs.getString("key_$appWidgetId", null) == key) {
            BitmapFactory.decodeFile(file.path)?.let { return it }
        }
        val wall = blurredWallpaper(context) ?: return null

        val s = wall.width.toFloat() / screenW
        val w = max(1, (widgetW * s).roundToInt()).coerceAtMost(wall.width)
        val h = max(1, (widgetH * s).roundToInt()).coerceAtMost(wall.height)
        val left = (position.centerX * wall.width - w / 2f).roundToInt().coerceIn(0, wall.width - w)
        val top = (position.centerY * wall.height - h / 2f).roundToInt().coerceIn(0, wall.height - h)

        // 输出比裁剪区大一些，让圆角边缘平滑
        val upscale = 3
        val out = Bitmap.createBitmap(w * upscale, h * upscale, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val rect = RectF(0f, 0f, out.width.toFloat(), out.height.toFloat())
        val radius = CORNER_DP * context.resources.displayMetrics.density * s * upscale
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawRoundRect(rect, radius, radius, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(Bitmap.createBitmap(wall, left, top, w, h), null, rect, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)
        paint.color = if (night) 0x8C1C1D22.toInt() else 0x80F7F8FA.toInt()
        canvas.drawRect(rect, paint)

        file.parentFile?.mkdirs()
        file.outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
        prefs.edit().putString("key_$appWidgetId", key).apply()
        return out
    }

    /** 壁纸换了 key 就变；没权限时返回 null。拿不到壁纸 id 时退回按小时更新。 */
    private fun wallpaperKey(context: Context): String? {
        if (!hasAccess(context)) return null
        val id = runCatching { WallpaperManager.getInstance(context).getWallpaperId(WallpaperManager.FLAG_SYSTEM) }
            .getOrNull() ?: "h${System.currentTimeMillis() / 3_600_000}"
        val (w, h) = screenSizePx(context)
        return "$id|$w|$h"
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 三次盒式模糊，效果接近高斯模糊。 */
    fun gaussianBlur(source: Bitmap, radius: Int): Bitmap {
        val w = source.width
        val h = source.height
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)
        val buffer = IntArray(w * h)
        val box = max(1, radius / 2)
        repeat(3) {
            boxBlur(pixels, buffer, w, h, box, horizontal = true)
            boxBlur(buffer, pixels, w, h, box, horizontal = false)
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun boxBlur(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val lines = if (horizontal) h else w
        val length = if (horizontal) w else h
        val span = 2 * r + 1
        for (line in 0 until lines) {
            fun index(i: Int): Int {
                val c = i.coerceIn(0, length - 1)
                return if (horizontal) line * w + c else c * w + line
            }
            var sr = 0; var sg = 0; var sb = 0
            for (i in -r..r) {
                val p = src[index(i)]
                sr += p shr 16 and 0xFF; sg += p shr 8 and 0xFF; sb += p and 0xFF
            }
            for (i in 0 until length) {
                dst[index(i)] = (0xFF shl 24) or ((sr / span) shl 16) or ((sg / span) shl 8) or (sb / span)
                val add = src[index(i + r + 1)]
                val remove = src[index(i - r)]
                sr += (add shr 16 and 0xFF) - (remove shr 16 and 0xFF)
                sg += (add shr 8 and 0xFF) - (remove shr 8 and 0xFF)
                sb += (add and 0xFF) - (remove and 0xFF)
            }
        }
    }
}

/**
 * 桌面网格，用于对齐时自动吸附。One UI 的网格可在桌面设置里选（4×5、4×6、5×5、5×6），
 * 这里按“状态栏下方是图标区、最下面一行是 Dock”估算每个格子的位置。
 */
data class HomeGrid(val columns: Int, val rows: Int) {
    /** 屏幕上的格子区域（像素）：左、上、格宽、格高。 */
    data class Cells(val left: Float, val top: Float, val cellW: Float, val cellH: Float)

    fun cells(context: Context, screenW: Int, screenH: Int): Cells {
        val res = context.resources
        fun dimen(name: String): Float {
            val id = res.getIdentifier(name, "dimen", "android")
            return if (id != 0) res.getDimension(id) else 0f
        }
        val statusBar = dimen("status_bar_height")
        val navBar = dimen("navigation_bar_height")
        // 图标区下面还有页码指示和 Dock，约占 1.3 行
        val cellH = (screenH - statusBar - navBar) / (rows + 1.3f)
        return Cells(left = 0f, top = statusBar, cellW = screenW.toFloat() / columns, cellH = cellH)
    }

    /**
     * 把小工具中心吸附到最近的格子位置：按小工具大小算出占几格，候选位置是占满整格时的中心。
     * 离最近的候选位置不到半格才吸附，否则保持原样。
     */
    fun snap(context: Context, center: GlassBackground.Position, widgetW: Int, widgetH: Int, screenW: Int, screenH: Int): GlassBackground.Position {
        val c = cells(context, screenW, screenH)
        val spanX = (widgetW / c.cellW).roundToInt().coerceIn(1, columns)
        val spanY = (widgetH / c.cellH).roundToInt().coerceIn(1, rows)
        val x = center.centerX * screenW
        val y = center.centerY * screenH
        var best: Pair<Float, Float>? = null
        var bestDistance = Float.MAX_VALUE
        for (col in 0..columns - spanX) {
            for (row in 0..rows - spanY) {
                val cx = c.left + (col + spanX / 2f) * c.cellW
                val cy = c.top + (row + spanY / 2f) * c.cellH
                val d = kotlin.math.hypot(cx - x, cy - y)
                if (d < bestDistance) {
                    bestDistance = d
                    best = cx to cy
                }
            }
        }
        val target = best ?: return center
        if (bestDistance > minOf(c.cellW, c.cellH) / 2) return center
        return GlassBackground.Position(target.first / screenW, target.second / screenH)
    }

    companion object {
        val OPTIONS = listOf(HomeGrid(4, 5), HomeGrid(4, 6), HomeGrid(5, 5), HomeGrid(5, 6))
        private const val PREFS = "glass"

        fun current(context: Context): HomeGrid {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return HomeGrid(prefs.getInt("grid_columns", 4), prefs.getInt("grid_rows", 5))
        }

        fun set(context: Context, grid: HomeGrid) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt("grid_columns", grid.columns)
                .putInt("grid_rows", grid.rows)
                .apply()
        }
    }
}
