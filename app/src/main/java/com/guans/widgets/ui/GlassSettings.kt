package com.guans.widgets.ui

import android.Manifest
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.guans.widgets.R
import com.guans.widgets.data.GlassBackground
import com.guans.widgets.widgets.common.redrawAllWidgets
import com.guans.widgets.widgets.exchangerate.ExchangeRateWidgetReceiver
import com.guans.widgets.widgets.routine.RoutineWidgetReceiver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** 桌面上的一个小工具实例。 */
private data class PlacedWidget(val appWidgetId: Int, val label: String)

/** 磨砂玻璃需要的两件事：读壁纸的权限，以及每个小工具在屏幕上的位置。 */
@Composable
fun GlassSettings() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var access by remember { mutableStateOf(GlassBackground.hasAccess(context)) }
    var editing by remember { mutableStateOf<PlacedWidget?>(null) }
    var version by remember { mutableIntStateOf(0) }

    fun recheck() {
        val now = GlassBackground.hasAccess(context)
        if (now != access) {
            access = now
            scope.launch { redrawAllWidgets(context) }
        }
        version++
    }

    // 从系统设置授权回来后刷新
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) recheck() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val requestPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { recheck() }

    if (!access) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.glass_need_access), style = MaterialTheme.typography.bodyMedium)
                FilledTonalButton(onClick = {
                    if (GlassBackground.needsSettingsPage) {
                        context.startActivity(GlassBackground.settingsIntent(context))
                    } else {
                        requestPermission.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                    }
                }) { Text(stringResource(R.string.glass_grant)) }
            }
        }
        return
    }

    val placed = remember(version) { placedWidgets(context) }
    Card(Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.glass_position_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
        )
        if (placed.isEmpty()) {
            Text(
                stringResource(R.string.glass_no_widgets),
                modifier = Modifier.padding(16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        placed.forEachIndexed { index, widget ->
            if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(widget.label)
                    Text(
                        stringResource(
                            if (GlassBackground.position(context, widget.appWidgetId) == null) R.string.glass_not_aligned
                            else R.string.glass_aligned,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { editing = widget }) { Text(stringResource(R.string.glass_align)) }
            }
        }
    }

    editing?.let { widget ->
        PositionEditor(
            widget = widget,
            onDismiss = { editing = null },
            onSave = { position ->
                GlassBackground.setPosition(context, widget.appWidgetId, position)
                editing = null
                version++
                scope.launch { redrawAllWidgets(context) }
            },
        )
    }
}

private fun placedWidgets(context: android.content.Context): List<PlacedWidget> {
    val manager = AppWidgetManager.getInstance(context)
    fun of(receiver: Class<*>, name: String): List<PlacedWidget> {
        val ids = manager.getAppWidgetIds(ComponentName(context, receiver))
        return ids.mapIndexed { i, id -> PlacedWidget(id, if (ids.size > 1) "$name ${i + 1}" else name) }
    }
    return of(ExchangeRateWidgetReceiver::class.java, context.getString(R.string.widget_exchange_rate_name)) +
        of(RoutineWidgetReceiver::class.java, context.getString(R.string.widget_routine_name))
}

/** 在壁纸缩略图上拖动方框，对齐到小工具在桌面上的位置。 */
@Composable
private fun PositionEditor(widget: PlacedWidget, onDismiss: () -> Unit, onSave: (GlassBackground.Position) -> Unit) {
    val context = LocalContext.current
    val (screenW, screenH) = remember { GlassBackground.screenSizePx(context) }
    val (widgetW, widgetH) = remember { GlassBackground.widgetSizePx(context, widget.appWidgetId) }
    val start = remember { GlassBackground.position(context, widget.appWidgetId) ?: GlassBackground.Position(0.5f, 0.5f) }
    var centerX by remember { mutableFloatStateOf(start.centerX) }
    var centerY by remember { mutableFloatStateOf(start.centerY) }
    var wallpaper by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(Unit) {
        wallpaper = withContext(Dispatchers.IO) { GlassBackground.screenWallpaper(context, 1f / 3) }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(widget.label, style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.glass_drag_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                )
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    BoxWithConstraints(
                        Modifier
                            .fillMaxHeight()
                            .aspectRatio(screenW.toFloat() / screenH, matchHeightConstraintsFirst = true)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        val density = LocalDensity.current
                        val boxW = constraints.maxWidth.toFloat()
                        val boxH = constraints.maxHeight.toFloat()
                        val rectW = boxW * widgetW / screenW
                        val rectH = boxH * widgetH / screenH
                        wallpaper?.let {
                            Image(
                                bitmap = it.asImageBitmap(),
                                contentDescription = null,
                                contentScale = ContentScale.FillBounds,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        Box(
                            Modifier
                                .offset { IntOffset((centerX * boxW - rectW / 2).roundToInt(), (centerY * boxH - rectH / 2).roundToInt()) }
                                .size(with(density) { rectW.toDp() }, with(density) { rectH.toDp() })
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color.White.copy(alpha = 0.35f))
                                .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp))
                                .pointerInput(boxW, boxH) {
                                    detectDragGestures { change, drag ->
                                        change.consume()
                                        val halfW = rectW / 2 / boxW
                                        val halfH = rectH / 2 / boxH
                                        centerX = (centerX + drag.x / boxW).coerceIn(halfW, 1 - halfW)
                                        centerY = (centerY + drag.y / boxH).coerceIn(halfH, 1 - halfH)
                                    }
                                },
                        )
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                    Spacer(Modifier.size(8.dp))
                    FilledTonalButton(onClick = { onSave(GlassBackground.Position(centerX, centerY)) }) {
                        Text(stringResource(R.string.routine_confirm))
                    }
                }
            }
        }
    }
}
