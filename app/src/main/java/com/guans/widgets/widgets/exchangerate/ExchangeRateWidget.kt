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
import androidx.glance.LocalContext
import androidx.glance.LocalSize
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
import com.guans.widgets.ui.Destination
import com.guans.widgets.widgets.common.WidgetContainer
import com.guans.widgets.data.changeDirection
import com.guans.widgets.data.formatChange
import com.guans.widgets.data.formatRate
import com.guans.widgets.data.upIsRed
import androidx.compose.ui.graphics.Color
import androidx.glance.color.ColorProvider
import androidx.glance.text.TextAlign
import androidx.glance.unit.ColorProvider as GlanceColorProvider
import java.text.DateFormat
import java.util.Date

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
    val context = LocalContext.current
    val rows = decodeRates(state[RateKeys.RATES])
    val error = state[RateKeys.ERROR]
    val time = state[RateKeys.UPDATED_AT]?.let {
        DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it))
    }
    val status = when {
        state[RateKeys.LOADING] == true -> context.getString(R.string.status_updating)
        error != null -> if (time != null) context.getString(R.string.status_offline, time)
        else context.getString(R.string.status_failed)
        time != null -> "${state[RateKeys.SOURCE].orEmpty()} · $time"
        else -> ""
    }
    // 窄小工具上省掉数据源和涨跌、货币对写法更紧凑，保证汇率完整显示
    val width = LocalSize.current.width
    val roomy = width >= 220.dp
    val showChange = width >= 180.dp
    val upIsRed = upIsRed(context.resources.configuration.locales[0])

    WidgetContainer(Destination.ExchangeRate) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = context.getString(R.string.widget_title),
                style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, color = GlanceTheme.colors.onSurface),
                modifier = GlanceModifier.defaultWeight(),
            )
            Text(
                text = if (roomy || error != null || state[RateKeys.LOADING] == true) status else time.orEmpty(),
                style = TextStyle(
                    fontSize = 11.sp,
                    color = if (error != null) GlanceTheme.colors.error else GlanceTheme.colors.onSurfaceVariant,
                ),
                maxLines = 1,
            )
            Spacer(GlanceModifier.width(8.dp))
            Image(
                provider = ImageProvider(R.drawable.ic_refresh),
                contentDescription = context.getString(R.string.action_refresh),
                colorFilter = ColorFilter.tint(GlanceTheme.colors.primary),
                modifier = GlanceModifier.size(22.dp).clickable(actionRunCallback<RefreshAction>()),
            )
        }
        Spacer(GlanceModifier.height(6.dp))

        if (rows.isEmpty()) {
            Text(
                text = context.getString(if (error != null) R.string.widget_failed_retry else R.string.widget_loading),
                style = TextStyle(fontSize = 13.sp, color = GlanceTheme.colors.onSurfaceVariant),
            )
        } else {
            LazyColumn(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {
                items(rows) { (pair, rate, change) ->
                    val (from, to) = pair.split('/')
                    Row(
                        modifier = GlanceModifier.fillMaxWidth().padding(vertical = 3.dp)
                            .clickable(actionStartActivity<MainActivity>()),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (roomy) "$from → $to" else "$from/$to",
                            style = TextStyle(fontSize = 13.sp, color = GlanceTheme.colors.onSurfaceVariant),
                            maxLines = 1,
                            modifier = GlanceModifier.defaultWeight(),
                        )
                        Text(
                            text = rate?.let(::formatRate) ?: "—",
                            style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, color = GlanceTheme.colors.onSurface),
                        )
                        if (showChange) {
                            Text(
                                text = change?.let(::formatChange) ?: "",
                                style = TextStyle(
                                    fontSize = 12.sp,
                                    textAlign = TextAlign.End,
                                    color = changeColor(change, upIsRed),
                                ),
                                maxLines = 1,
                                modifier = GlanceModifier.width(64.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

private val RISE_RED = ColorProvider(day = Color(0xFFD32F2F), night = Color(0xFFFF6B6B))
private val FALL_GREEN = ColorProvider(day = Color(0xFF2E7D32), night = Color(0xFF5BD27A))

@Composable
private fun changeColor(change: Double?, upIsRed: Boolean): GlanceColorProvider {
    val direction = change?.let(::changeDirection) ?: 0
    return when {
        direction == 0 -> GlanceTheme.colors.onSurfaceVariant
        (direction > 0) == upIsRed -> RISE_RED
        else -> FALL_GREEN
    }
}
