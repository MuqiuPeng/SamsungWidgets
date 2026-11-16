package com.guans.widgets.widgets.exchangerate

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.color.ColorProvider
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.guans.widgets.R
import com.guans.widgets.data.RateHistory
import com.guans.widgets.data.Settings
import com.guans.widgets.data.changeDirection
import com.guans.widgets.data.formatChange
import com.guans.widgets.data.formatRate
import com.guans.widgets.data.upIsRed
import com.guans.widgets.ui.Destination
import com.guans.widgets.widgets.common.WidgetContainer
import java.text.DateFormat
import java.util.Date
import androidx.glance.unit.ColorProvider as GlanceColorProvider

/** 每个实例只显示一个货币对：汇率、当日涨跌和 30 天走势。 */
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

    companion object {
        /** 这个实例显示的货币对；没设过或已从设置里删掉时用第一个。 */
        val PAIR = stringPreferencesKey("pair")

        fun pairFor(state: Preferences, pairs: List<String>): String? =
            state[PAIR]?.takeIf { it in pairs } ?: pairs.firstOrNull()
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

/** 切到设置里的下一个货币对，只影响被点的这个实例。 */
class NextPairAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val pairs = Settings.pairs(context)
        if (pairs.size < 2) return
        updateAppWidgetState(context, glanceId) { prefs ->
            val current = ExchangeRateWidget.pairFor(prefs, pairs)
            prefs[ExchangeRateWidget.PAIR] = pairs[(pairs.indexOf(current) + 1) % pairs.size]
        }
        ExchangeRateWidget().update(context, glanceId)
    }
}

@Composable
private fun Content(state: Preferences) {
    val context = LocalContext.current
    val pairs = Settings.pairs(context)
    val pair = ExchangeRateWidget.pairFor(state, pairs)
    val row = decodeRates(state[RateKeys.RATES]).firstOrNull { it.pair == pair }
    val error = state[RateKeys.ERROR]
    val loading = state[RateKeys.LOADING] == true
    val time = state[RateKeys.UPDATED_AT]?.let { DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it)) }
    val status = when {
        loading -> context.getString(R.string.status_updating)
        error != null -> if (time != null) context.getString(R.string.status_offline, time)
        else context.getString(R.string.status_failed)
        else -> time.orEmpty()
    }
    val size = LocalSize.current
    val upIsRed = upIsRed(context.resources.configuration.locales[0])
    val points = pair?.let { RateHistory.series(context, it) }.orEmpty()
    val trend = if (points.size >= 2) (points.last().rate / points.first().rate - 1) * 100 else null
    // 内容区 = 小工具减去外框留白（左右 16、上下 12）和标题行
    val bodyWidth = size.width - 32.dp
    val bodyHeight = size.height - 24.dp - 24.dp
    val tall = bodyHeight >= 56.dp

    WidgetContainer(Destination.ExchangeRate) {
        // 标题行：货币对（点了换下一个）+ 状态 + 刷新
        Row(modifier = GlanceModifier.fillMaxWidth().height(24.dp), verticalAlignment = Alignment.CenterVertically) {
            val (from, to) = (pair ?: "—/—").split('/')
            Text(
                text = if (pairs.size > 1) "$from → $to  ⇅" else "$from → $to",
                style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold, color = GlanceTheme.colors.onSurface),
                maxLines = 1,
                modifier = GlanceModifier.defaultWeight().let {
                    if (pairs.size > 1) it.clickable(actionRunCallback<NextPairAction>()) else it
                },
            )
            Text(
                text = status,
                style = TextStyle(
                    fontSize = 11.sp,
                    color = if (error != null) GlanceTheme.colors.error else GlanceTheme.colors.onSurfaceVariant,
                ),
                maxLines = 1,
            )
            Spacer(GlanceModifier.width(6.dp))
            Image(
                provider = ImageProvider(R.drawable.ic_refresh),
                contentDescription = context.getString(R.string.action_refresh),
                colorFilter = ColorFilter.tint(GlanceTheme.colors.primary),
                modifier = GlanceModifier.size(20.dp).clickable(actionRunCallback<RefreshAction>()),
            )
        }

        // 主体：走势图铺在背后，左侧透明、往右渐显；汇率压在左边
        Box(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {
            if (points.size >= 2 && bodyHeight >= 24.dp) {
                val density = context.resources.displayMetrics.density
                Image(
                    provider = ImageProvider(
                        TrendChart.render(
                            values = points.map { it.rate },
                            widthPx = (bodyWidth.value * density).toInt(),
                            heightPx = (bodyHeight.value * density).toInt(),
                            density = density,
                        ),
                    ),
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    // 位图只有透明度，颜色交给系统按日夜着色，切换深色模式不用重画
                    colorFilter = ColorFilter.tint(trendColor(trend, upIsRed)),
                    modifier = GlanceModifier.fillMaxSize(),
                )
            }
            Column(modifier = GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = row?.rate?.let(::formatRate)
                        ?: context.getString(if (error != null) R.string.widget_failed_retry else R.string.widget_loading),
                    style = TextStyle(
                        fontSize = if (row?.rate == null) 13.sp else if (tall) 28.sp else 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (row?.rate != null) GlanceTheme.colors.onSurface else GlanceTheme.colors.onSurfaceVariant,
                    ),
                    maxLines = 1,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    row?.changePercent?.let { change ->
                        Text(
                            text = formatChange(change),
                            style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, color = changeColor(change, upIsRed)),
                            maxLines = 1,
                        )
                    }
                    if (trend != null && tall) {
                        Text(
                            text = "  " + context.getString(R.string.trend_widget_change, points.size, formatChange(trend)),
                            style = TextStyle(fontSize = 11.sp, color = GlanceTheme.colors.onSurfaceVariant),
                            maxLines = 1,
                        )
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

private val TREND_FLAT = ColorProvider(day = Color(0xFF5B6B80), night = Color(0xFFB0C4DE))

@Composable
private fun trendColor(change: Double?, upIsRed: Boolean): GlanceColorProvider {
    val direction = change?.let(::changeDirection) ?: 0
    return when {
        direction == 0 -> TREND_FLAT
        (direction > 0) == upIsRed -> RISE_RED
        else -> FALL_GREEN
    }
}
