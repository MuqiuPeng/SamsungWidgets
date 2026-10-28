package com.guans.widgets

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.os.Bundle
import android.util.TypedValue
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.guans.widgets.data.Settings
import com.guans.widgets.widgets.exchangerate.ExchangeRateWidgetReceiver
import com.guans.widgets.widgets.exchangerate.RateScheduler

/** 设置页：编辑汇率小工具显示的货币对，以及一键添加小工具到桌面。 */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = dp(20)

        val pairsInput = EditText(this).apply {
            setText(Settings.pairs(this@MainActivity).joinToString("\n"))
            minLines = 4
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(TextView(context).apply {
                text = "实时汇率小工具"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            })
            addView(TextView(context).apply {
                text = "每行一个货币对，如 USD/CNY、EUR/CNY、JPY/CNY（ISO 4217 代码）。"
                setPadding(0, dp(12), 0, dp(4))
            })
            addView(pairsInput)
            addView(Button(context).apply {
                text = "保存并刷新"
                setOnClickListener {
                    val pairs = Settings.parsePairs(pairsInput.text.toString())
                    if (pairs.isEmpty()) {
                        Toast.makeText(context, "没有识别到有效的货币对", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    Settings.setPairs(context, pairs)
                    pairsInput.setText(pairs.joinToString("\n"))
                    RateScheduler.refreshNow(context)
                    Toast.makeText(context, "已保存，小工具正在刷新", Toast.LENGTH_SHORT).show()
                }
            })
            addView(Button(context).apply {
                text = "添加到桌面"
                setOnClickListener { requestPin() }
            })
            addView(TextView(context).apply {
                text = "也可以长按桌面空白处 → 小组件 → 桌面小工具 → 实时汇率。\n" +
                    "小工具每 15 分钟自动更新一次，点右上角刷新图标可立即更新。"
                setPadding(0, dp(16), 0, 0)
            })
        }
        setContentView(root)
    }

    private fun requestPin() {
        val manager = getSystemService(AppWidgetManager::class.java)
        if (manager?.isRequestPinAppWidgetSupported == true) {
            manager.requestPinAppWidget(ComponentName(this, ExchangeRateWidgetReceiver::class.java), null, null)
        } else {
            Toast.makeText(this, "当前桌面不支持一键添加，请手动添加", Toast.LENGTH_LONG).show()
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
