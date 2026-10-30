package com.guans.widgets.data

import android.content.Context
import android.os.Build
import com.guans.widgets.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 本地错误日志：崩溃和后台出错（刷新失败、读壁纸失败等）都记在这里，
 * 在“设置 → 错误日志”里可以分享出去，方便排查真机上的问题。
 * 只记录异常信息，不含令牌、密码等内容。只保留最近一部分，不会无限增长。
 */
object ErrorLog {
    private const val FILE = "error_log.txt"
    private const val MAX_BYTES = 64 * 1024

    private fun file(context: Context) = File(context.filesDir, FILE)

    /** 断网时的正常失败，下次刷新会自己恢复，不记录。 */
    private fun isOffline(error: Throwable): Boolean =
        generateSequence(error) { it.cause }.any {
            it is UnknownHostException || it is ConnectException || it is NoRouteToHostException || it is SocketTimeoutException
        }

    fun record(context: Context, where: String, error: Throwable) {
        if (isOffline(error)) return
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        append(context, "[$where] ${error.javaClass.simpleName}: ${error.message}\n$trace")
    }

    fun note(context: Context, where: String, message: String) {
        append(context, "[$where] $message\n")
    }

    @Synchronized
    private fun append(context: Context, entry: String) {
        runCatching {
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val f = file(context)
            f.appendText("=== $time v${BuildConfig.VERSION_NAME}\n$entry\n")
            // 超出上限时只保留后半部分
            if (f.length() > MAX_BYTES) {
                val text = f.readText()
                f.writeText(text.takeLast(MAX_BYTES / 2))
            }
        }
    }

    fun read(context: Context): String = runCatching { file(context).readText() }.getOrDefault("")

    fun count(context: Context): Int = read(context).split("\n=== ").count { it.isNotBlank() }

    fun clear(context: Context) {
        file(context).delete()
    }

    /** 分享用的完整文本：设备信息 + 日志。 */
    fun report(context: Context): String = buildString {
        appendLine("Toolbox ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine()
        append(read(context).ifEmpty { "(empty)" })
    }

    /** 未捕获的异常（崩溃）先写日志，再交给系统原来的处理。 */
    fun installCrashHandler(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            record(app, "crash @${thread.name}", error)
            previous?.uncaughtException(thread, error)
        }
    }
}
