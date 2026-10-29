package com.guans.widgets.widgets.common

import android.content.Context
import androidx.compose.runtime.Composable
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
        runCatching { GlassBackground.backgroundFor(context, appWidgetId) }.getOrNull()
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
