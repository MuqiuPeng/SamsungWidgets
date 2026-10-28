package com.guans.widgets

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.glance.appwidget.updateAll
import androidx.lifecycle.lifecycleScope
import com.guans.widgets.ui.AppTheme
import com.guans.widgets.ui.SettingsScreen
import com.guans.widgets.widgets.exchangerate.ExchangeRateWidget
import com.guans.widgets.widgets.exchangerate.ExchangeRateWidgetReceiver
import kotlinx.coroutines.launch

/** 设置页。用 AppCompatActivity 以支持应用内切换语言（Android 12 及以下）。 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                SettingsScreen(onAddToHome = ::requestPin)
            }
        }
        // 语言切换后界面会重建，顺便让桌面上的小工具用新语言重绘
        lifecycleScope.launch { ExchangeRateWidget().updateAll(applicationContext) }
    }

    private fun requestPin() {
        val manager = getSystemService(AppWidgetManager::class.java)
        if (manager?.isRequestPinAppWidgetSupported == true) {
            manager.requestPinAppWidget(ComponentName(this, ExchangeRateWidgetReceiver::class.java), null, null)
        } else {
            Toast.makeText(this, R.string.add_to_home_unsupported, Toast.LENGTH_LONG).show()
        }
    }
}
