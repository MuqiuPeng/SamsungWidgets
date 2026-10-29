package com.guans.widgets.widgets.common

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.compose.runtime.Composable
import androidx.glance.appwidget.AndroidRemoteViews
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ColumnScope
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import com.guans.widgets.MainActivity
import com.guans.widgets.R
import com.guans.widgets.data.ErrorLog
import com.guans.widgets.ui.Destination
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalGlanceId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.layout.ContentScale
import com.guans.widgets.data.GlassBackground
import com.guans.widgets.data.Settings
import com.guans.widgets.widgets.exchangerate.ExchangeRateWidget
import com.guans.widgets.widgets.github.GitHubWidget
import com.guans.widgets.widgets.routine.RoutineWidget

/**
 * 所有小工具共用的背景，由设置页的“小工具背景”决定：
 * 磨砂玻璃 = 壁纸模糊后裁出小工具背后那一块；还没授权读壁纸时退回实色。
 */
@Composable
fun GlanceModifier.widgetSurface(): GlanceModifier {
    val context = LocalContext.current
    val glass = if (Settings.glassBackground(context)) {
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(LocalGlanceId.current)
        runCatching { GlassBackground.backgroundFor(context, appWidgetId) }
            .onFailure { ErrorLog.record(context, "glass background", it) }
            .getOrNull()
    } else {
        null
    }
    val base = if (glass != null) {
        background(ImageProvider(glass), ContentScale.FillBounds)
    } else {
        background(GlanceTheme.colors.widgetBackground)
    }
    return base.cornerRadius(24.dp)
}

/**
 * 小工具的外框：背景 + 一层铺满的透明点击层 + 内容。
 * 点到内容里没有点击动作的地方（标题、空白、边距）时由点击层接住：打开对应设置页，
 * 并且系统会附上被点区域在屏幕上的位置，用来自动定位玻璃背景（Glance 自己的点击拿不到这个位置）。
 */
@Composable
fun WidgetContainer(destination: Destination, content: @Composable ColumnScope.() -> Unit) {
    val context = LocalContext.current
    val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(LocalGlanceId.current)
    Box(GlanceModifier.fillMaxSize().widgetSurface()) {
        AndroidRemoteViews(tapProbe(context, appWidgetId, destination), GlanceModifier.fillMaxSize())
        Column(
            modifier = GlanceModifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
            content = content,
        )
    }
}

private fun tapProbe(context: Context, appWidgetId: Int, destination: Destination): RemoteViews {
    val intent = Intent(context, MainActivity::class.java)
        .putExtra(MainActivity.DESTINATION.name, destination.name)
        .putExtra(MainActivity.EXTRA_LOCATE, appWidgetId)
    // 必须是可变的 PendingIntent，桌面才能把点击位置（sourceBounds）填进去
    val pending = PendingIntent.getActivity(
        context,
        appWidgetId,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )
    return RemoteViews(context.packageName, R.layout.widget_tap_probe).apply {
        setOnClickPendingIntent(R.id.tap_probe, pending)
    }
}

private val STYLE_CHANGED = longPreferencesKey("style_changed")

/** 背景样式、位置或权限变化后重绘桌面上的所有小工具。 */
suspend fun redrawAllWidgets(context: Context) {
    val now = System.currentTimeMillis()
    listOf<GlanceAppWidget>(ExchangeRateWidget(), RoutineWidget(), GitHubWidget()).forEach { widget ->
        GlanceAppWidgetManager(context).getGlanceIds(widget.javaClass).forEach { id ->
            // 改动实例状态，确保已在运行的小工具也会重新组合
            updateAppWidgetState(context, id) { it[STYLE_CHANGED] = now }
            widget.update(context, id)
        }
    }
}
