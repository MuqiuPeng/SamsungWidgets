package com.guans.widgets.widgets.exchangerate

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.guans.widgets.MainActivity
import com.guans.widgets.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ExchangeRateWidget : GlanceAppWidget() {
    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        updateAppWidgetState(context, id) { RateUpdater.seedFromCache(context, it) }
        provideContent {
            GlanceTheme {
                Content(currentState())
            }
        }
    }
}

class ExchangeRateWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ExchangeRateWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        RateScheduler.schedule(context)
        RateScheduler.refreshNow(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        RateScheduler.cancel(context)
    }
}

class RefreshAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        RateUpdater.refresh(context)
    }
}

@Composable
private fun Content(state: Preferences) {
    val rows = decodeRates(state[RateKeys.RATES])
    val error = state[RateKeys.ERROR]
    val updatedAt = state[RateKeys.UPDATED_AT]
    val time = updatedAt?.let { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it)) }
    val status = when {
        state[RateKeys.LOADING] == true -> "更新中…"
        error != null -> if (time != null) "离线 · $time" else "获取失败"
        time != null -> "${state[RateKeys.SOURCE].orEmpty()} · $time"
        else -> ""
    }

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.widgetBackground)
            .cornerRadius(24.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "汇率",
                style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, color = GlanceTheme.colors.onSurface),
                modifier = GlanceModifier.defaultWeight().clickable(actionStartActivity<MainActivity>()),
            )
            Text(
                text = status,
                style = TextStyle(
                    fontSize = 11.sp,
                    color = if (error != null) GlanceTheme.colors.error else GlanceTheme.colors.onSurfaceVariant,
                ),
            )
            Spacer(GlanceModifier.width(8.dp))
            Image(
                provider = ImageProvider(R.drawable.ic_refresh),
                contentDescription = "刷新",
                colorFilter = ColorFilter.tint(GlanceTheme.colors.primary),
                modifier = GlanceModifier.size(22.dp).clickable(actionRunCallback<RefreshAction>()),
            )
        }
        Spacer(GlanceModifier.height(6.dp))

        if (rows.isEmpty()) {
            Text(
                text = if (error != null) "获取失败，点右上角重试" else "加载中…",
                style = TextStyle(fontSize = 13.sp, color = GlanceTheme.colors.onSurfaceVariant),
            )
        } else {
            LazyColumn(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {
                items(rows) { (pair, rate) ->
                    Row(
                        modifier = GlanceModifier.fillMaxWidth().padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = pair,
                            style = TextStyle(fontSize = 14.sp, color = GlanceTheme.colors.onSurfaceVariant),
                            modifier = GlanceModifier.defaultWeight(),
                        )
                        Text(
                            text = rate?.let(::formatRate) ?: "—",
                            style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, color = GlanceTheme.colors.onSurface),
                        )
                    }
                }
            }
        }
    }
}

private fun decodeRates(encoded: String?): List<Pair<String, Double?>> =
    encoded.orEmpty().lines().filter { it.isNotBlank() }.map { line ->
        val (pair, value) = line.split('=', limit = 2)
        pair to value.toDoubleOrNull()
    }

/** 大数少保留小数，小数（如 JPY/CNY）多保留几位。 */
private fun formatRate(rate: Double): String {
    val pattern = when {
        rate >= 100 -> "%.2f"
        rate >= 1 -> "%.4f"
        else -> "%.6f"
    }
    return String.format(Locale.US, pattern, rate)
}
