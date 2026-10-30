package com.guans.widgets.widgets.routine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
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
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.guans.widgets.MainActivity
import com.guans.widgets.R
import com.guans.widgets.ui.Destination
import com.guans.widgets.widgets.common.WidgetContainer
import com.guans.widgets.data.RoutinePreset
import com.guans.widgets.data.Routines
import com.guans.widgets.data.formatStatus
import com.guans.widgets.data.LOOP_TINT_RED
import com.guans.widgets.data.loopTintAlpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.glance.color.ColorProvider

class RoutineWidget : GlanceAppWidget() {
    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            GlanceTheme {
                Content(currentState())
            }
        }
    }
}

class RoutineWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = RoutineWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        RoutineTicker.schedule(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        RoutineTicker.cancel(context)
    }
}

/** 点事项：记为刚刚完成。 */
class MarkDoneAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val preset = parameters[RoutineKeys.ID]?.let(RoutinePreset::of) ?: return
        RoutineTicker.tap(context, preset)
        RoutineTicker.pushAll(context)
    }
}

/** 撤销小工具上最近一次点击（误点时用）。 */
class UndoAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        Routines.pendingUndo(context)?.let { Routines.undo(context, it) }
        RoutineTicker.pushAll(context)
    }
}

/** 用量进度条：Glance 没有局部填充的背景，只能画成图片。 */
private fun usageBar(context: Context, widthDp: Float, usage: Float): Bitmap {
    val density = context.resources.displayMetrics.density
    val width = (widthDp * density).toInt().coerceAtLeast(1)
    val height = (USAGE_BAR_HEIGHT_DP * density).toInt()
    val radius = USAGE_BAR_RADIUS_DP * density
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.clipPath(Path().apply { addRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), radius, radius, Path.Direction.CW) })
    canvas.drawColor(Color(LOOP_TINT_RED).copy(alpha = loopTintAlpha(0f)).toArgb())
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color(LOOP_TINT_RED).copy(alpha = loopTintAlpha(usage)).toArgb() }
    canvas.drawRect(0f, 0f, width * usage, height.toFloat(), fill)
    return bitmap
}

private const val USAGE_BAR_HEIGHT_DP = 28
private const val USAGE_BAR_RADIUS_DP = 10f

/** 小工具左右各 16dp 内边距。 */
private const val WIDGET_PADDING_DP = 32f

@Composable
private fun Content(state: Preferences) {
    val context = LocalContext.current
    // 状态里的 tick 每次推送都会变，用它触发重绘；“现在”取真实时间
    state[RoutineKeys.TICK]
    val now = System.currentTimeMillis()
    val routines = Routines.all(context)
    val rowWidth = LocalSize.current.width.value - WIDGET_PADDING_DP
    val undo = Routines.pendingUndo(context, now)
    val openApp = actionStartActivity<MainActivity>(actionParametersOf(MainActivity.DESTINATION to MainActivity.ROUTINES))

    WidgetContainer(Destination.Routines) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = context.getString(R.string.routine_widget_title),
                style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, color = GlanceTheme.colors.onSurface),
                maxLines = 1,
                modifier = GlanceModifier.defaultWeight(),
            )
            if (undo != null) {
                Text(
                    text = context.getString(R.string.routine_undo_short),
                    style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, color = GlanceTheme.colors.primary),
                    maxLines = 1,
                    modifier = GlanceModifier.padding(start = 8.dp).clickable(actionRunCallback<UndoAction>()),
                )
            }
        }
        Spacer(GlanceModifier.height(6.dp))

        if (routines.isEmpty()) {
            Text(
                text = context.getString(R.string.routine_widget_empty),
                style = TextStyle(fontSize = 13.sp, color = GlanceTheme.colors.onSurfaceVariant),
                modifier = GlanceModifier.clickable(openApp),
            )
        } else {
            LazyColumn(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {
                items(routines, itemId = { it.preset.ordinal.toLong() }) { routine ->
                    val elapsed = routine.elapsed(now)
                    val tint = routine.loopProgress(now)?.let { Color(LOOP_TINT_RED).copy(alpha = loopTintAlpha(it)) }
                    // 倒计时条目底色随本轮进度逐渐变红；其他条目留同样的内边距，文字对齐
                    val usage = routine.usage(now)
                    val rowBackground = if (usage != null) {
                        GlanceModifier.background(ImageProvider(usageBar(context, rowWidth, usage)), ContentScale.FillBounds)
                    } else if (tint != null) {
                        GlanceModifier.background(ColorProvider(day = tint, night = tint)).cornerRadius(10.dp)
                    } else {
                        GlanceModifier
                    }
                    Column(
                        modifier = GlanceModifier.fillMaxWidth().padding(vertical = 2.dp)
                            .then(rowBackground)
                            .padding(horizontal = 6.dp, vertical = 3.dp)
                            .clickable(
                            actionRunCallback<MarkDoneAction>(actionParametersOf(RoutineKeys.ID to routine.preset.name)),
                        ),
                    ) {
                        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = context.getString(routine.preset.nameRes),
                                style = TextStyle(fontSize = 14.sp, color = GlanceTheme.colors.onSurface),
                                maxLines = 1,
                                modifier = GlanceModifier.defaultWeight(),
                            )
                            Text(
                                text = formatStatus(context, routine, now),
                                style = TextStyle(
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = if (elapsed == null) GlanceTheme.colors.onSurfaceVariant
                                    else GlanceTheme.colors.onSurface,
                                ),
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}
