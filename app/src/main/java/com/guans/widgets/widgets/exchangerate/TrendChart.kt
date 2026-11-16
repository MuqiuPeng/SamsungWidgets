package com.guans.widgets.widgets.exchangerate

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader

/**
 * 小工具背景里的走势图（Glance 不能直接画路径，只能放位图）。
 * 画成白色蒙版、只靠透明度分层，由 ColorFilter.tint 按日夜上色；
 * 横向从左到右渐显，左边留给汇率文字。
 */
object TrendChart {
    /** 位图边长上限，避免超出 RemoteViews 的位图内存限制。 */
    private const val MAX_SIDE = 900

    /** 横向渐显：这个位置之前完全透明，到 [SOLID_AT] 完全不透明。 */
    private const val FADE_FROM = 0.40f
    private const val SOLID_AT = 0.85f

    fun render(values: List<Double>, widthPx: Int, heightPx: Int, density: Float): Bitmap {
        val scale = minOf(1f, MAX_SIDE.toFloat() / maxOf(widthPx, heightPx, 1))
        val w = (widthPx * scale).toInt().coerceAtLeast(1)
        val h = (heightPx * scale).toInt().coerceAtLeast(1)
        val d = density * scale
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val min = values.min()
        val max = values.max()
        val span = (max - min).takeIf { it > 0 } ?: 1.0
        val pad = 4 * d
        val plotH = h - pad * 2
        val right = w - pad
        fun x(i: Int) = right * i / (values.size - 1).coerceAtLeast(1)
        fun y(v: Double) = (pad + plotH * (1 - (v - min) / span)).toFloat()

        val line = Path()
        values.forEachIndexed { i, v -> if (i == 0) line.moveTo(x(i), y(v)) else line.lineTo(x(i), y(v)) }

        // 折线下方的纵向渐变填充
        val area = Path(line).apply {
            lineTo(x(values.lastIndex), h.toFloat())
            lineTo(x(0), h.toFloat())
            close()
        }
        canvas.drawPath(area, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, 0f, h.toFloat(), 0x55FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        })
        canvas.drawPath(line, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 2 * d
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        })
        canvas.drawCircle(x(values.lastIndex), y(values.last()), 3 * d, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
        })

        // 横向蒙版：左侧透明 → 右侧不透明
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            shader = LinearGradient(
                w * FADE_FROM, 0f, w * SOLID_AT, 0f,
                0x00FFFFFF, 0xFFFFFFFF.toInt(),
                Shader.TileMode.CLAMP,
            )
        })
        return bitmap
    }
}
