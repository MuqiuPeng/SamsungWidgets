package com.guans.widgets

import android.app.Application
import com.guans.widgets.data.ErrorLog

/** 应用进程入口：最早装上崩溃记录，小工具和后台任务的崩溃也能记下来。 */
class ToolboxApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ErrorLog.installCrashHandler(this)
    }
}
