package com.guans.widgets

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.glance.appwidget.updateAll
import androidx.lifecycle.lifecycleScope
import com.guans.widgets.ui.AppTheme
import com.guans.widgets.ui.AppWithUpdates
import com.guans.widgets.ui.Destination
import com.guans.widgets.ui.DestinationRequest
import com.guans.widgets.widgets.exchangerate.ExchangeRateWidget
import com.guans.widgets.widgets.exchangerate.ExchangeRateWidgetReceiver
import com.guans.widgets.widgets.github.GitHubUpdater
import com.guans.widgets.widgets.github.GitHubWidgetReceiver
import com.guans.widgets.widgets.routine.RoutineTicker
import com.guans.widgets.widgets.routine.RoutineWidgetReceiver
import kotlinx.coroutines.launch

/** 应用入口（左侧抽屉：小工具设置 / 设置）。用 AppCompatActivity 以支持应用内切换语言（Android 12 及以下）。 */
class MainActivity : AppCompatActivity() {

    private var request by mutableStateOf<DestinationRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handle(intent)
        setContent {
            AppTheme {
                AppWithUpdates(request = request, onAddToHome = ::requestPin)
            }
        }
        // 语言切换后界面会重建，顺便让桌面上的小工具用新语言重绘
        lifecycleScope.launch {
            ExchangeRateWidget().updateAll(applicationContext)
            RoutineTicker.pushAll(applicationContext)
            GitHubUpdater.push(applicationContext)
        }
        // 老版本升级上来时补上定时重绘
        if (RoutineTicker.hasWidgets(this)) RoutineTicker.schedule(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /** 从小工具点进来时，直接打开对应的设置页。 */
    private fun handle(intent: Intent?) {
        val name = intent?.getStringExtra(DESTINATION.name) ?: return
        val destination = Destination.entries.firstOrNull { it.name == name } ?: return
        request = DestinationRequest(destination)
    }

    private fun requestPin(destination: Destination) {
        val receiver = when (destination) {
            Destination.Routines -> RoutineWidgetReceiver::class.java
            Destination.GitHub -> GitHubWidgetReceiver::class.java
            else -> ExchangeRateWidgetReceiver::class.java
        }
        val manager = getSystemService(AppWidgetManager::class.java)
        if (manager?.isRequestPinAppWidgetSupported == true) {
            manager.requestPinAppWidget(ComponentName(this, receiver), null, null)
        } else {
            Toast.makeText(this, R.string.add_to_home_unsupported, Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        /** 小工具打开应用时带的参数：要显示的页面（Destination 的名字）。 */
        val DESTINATION = androidx.glance.action.ActionParameters.Key<String>("destination")
        val ROUTINES = Destination.Routines.name
    }
}
