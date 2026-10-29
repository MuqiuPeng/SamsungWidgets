package com.guans.widgets.widgets.common

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.layout.ContentScale
import com.guans.widgets.R
import com.guans.widgets.data.Settings
import com.guans.widgets.widgets.exchangerate.ExchangeRateWidget
import com.guans.widgets.widgets.routine.RoutineWidget

/** 所有小工具共用的背景：磨砂玻璃或实色，由设置页的“小工具背景”决定。 */
@Composable
fun GlanceModifier.widgetSurface(): GlanceModifier {
    val glass = Settings.glassBackground(LocalContext.current)
    val base = if (glass) {
        background(ImageProvider(R.drawable.widget_glass), ContentScale.FillBounds)
    } else {
        background(GlanceTheme.colors.widgetBackground)
    }
    return base.cornerRadius(24.dp)
}

private val STYLE_CHANGED = longPreferencesKey("style_changed")

/** 背景样式变化后重绘桌面上的所有小工具。 */
suspend fun redrawAllWidgets(context: Context) {
    val now = System.currentTimeMillis()
    listOf<GlanceAppWidget>(ExchangeRateWidget(), RoutineWidget()).forEach { widget ->
        GlanceAppWidgetManager(context).getGlanceIds(widget.javaClass).forEach { id ->
            // 改动实例状态，确保已在运行的小工具也会重新组合
            updateAppWidgetState(context, id) { it[STYLE_CHANGED] = now }
            widget.update(context, id)
        }
    }
}
