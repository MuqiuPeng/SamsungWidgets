package com.guans.widgets.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.guans.widgets.R
import com.guans.widgets.data.ErrorLog
import com.guans.widgets.data.RateHistory
import com.guans.widgets.data.changeDirection
import com.guans.widgets.data.formatChange
import com.guans.widgets.data.formatRate
import java.time.format.DateTimeFormatter

/** 一个货币对最近 30 天的走势：先显示本地已有的点，缺的日期在后台回补。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RateTrendSheet(pair: String, upIsRed: Boolean, rise: Color, fall: Color, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var points by remember { mutableStateOf(RateHistory.series(context, pair)) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }

    LaunchedEffect(pair) {
        val codes = pair.split('/').toSet() + "USD"
        runCatching { RateHistory.backfill(context, codes) }
            .onFailure { ErrorLog.record(context, "rate history", it); failed = true }
        points = RateHistory.series(context, pair)
        loading = false
    }

    val (from, to) = pair.split('/')
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 32.dp)) {
            Text("$from → $to", style = MaterialTheme.typography.titleLarge)
            Text(
                stringResource(R.string.trend_title, RateHistory.DAYS),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            val change = if (points.size >= 2) (points.last().rate / points.first().rate - 1) * 100 else null
            val direction = change?.let(::changeDirection) ?: 0
            val color = when {
                direction == 0 -> MaterialTheme.colorScheme.primary
                (direction > 0) == upIsRed -> rise
                else -> fall
            }

            Box(Modifier.fillMaxWidth().height(200.dp).padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                when {
                    points.size >= 2 -> TrendLine(points.map { it.rate }, color, MaterialTheme.colorScheme.outlineVariant)
                    loading -> CircularProgressIndicator()
                    else -> Text(
                        stringResource(if (failed) R.string.trend_failed else R.string.trend_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (points.size >= 2) {
                val fmt = DateTimeFormatter.ofPattern("M/d")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(points.first().date.format(fmt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(points.last().date.format(fmt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Stat(stringResource(R.string.trend_high), formatRate(points.maxOf { it.rate }))
                    Stat(stringResource(R.string.trend_low), formatRate(points.minOf { it.rate }))
                    Stat(stringResource(R.string.trend_change), change?.let(::formatChange) ?: "—", color)
                }
                if (loading) {
                    Text(
                        stringResource(R.string.trend_loading),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, color: Color = Color.Unspecified) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium, color = color)
    }
}

/** 折线：上下留白，最低/最高处画一条虚线参考。 */
@Composable
private fun TrendLine(values: List<Double>, color: Color, guide: Color) {
    Canvas(Modifier.fillMaxWidth().height(168.dp)) {
        val min = values.min()
        val max = values.max()
        val span = (max - min).takeIf { it > 0 } ?: 1.0
        val pad = 8.dp.toPx()
        val h = size.height - pad * 2
        fun x(i: Int) = if (values.size == 1) size.width / 2 else size.width * i / (values.size - 1)
        fun y(v: Double) = (pad + h * (1 - (v - min) / span)).toFloat()

        val dash = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 6.dp.toPx()))
        for (v in listOf(min, max)) {
            drawLine(guide, Offset(0f, y(v)), Offset(size.width, y(v)), strokeWidth = 1.dp.toPx(), pathEffect = dash)
        }
        val path = Path().apply {
            values.forEachIndexed { i, v -> if (i == 0) moveTo(x(i), y(v)) else lineTo(x(i), y(v)) }
        }
        drawPath(path, color, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawCircle(color, radius = 4.dp.toPx(), center = Offset(x(values.lastIndex), y(values.last())))
    }
}
