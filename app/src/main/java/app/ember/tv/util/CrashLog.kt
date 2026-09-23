package app.ember.tv.util

import android.content.Context
import android.os.Build
import app.ember.tv.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records the last uncaught exception so it can be shown on the next launch.
 *
 * Sideloaded builds have no Play Console crash reporting, and asking someone
 * to run `adb logcat` on a TV box is a non-starter — so the app keeps the
 * stack trace itself and offers it for copying.
 */
object CrashLog {

    private const val FILE_NAME = "last_crash.txt"

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { file(appContext).writeText(format(thread, error)) }
            // Hand off to the system handler so the process still dies normally.
            previous?.uncaughtException(thread, error)
        }
    }

    fun read(context: Context): String? =
        file(context).takeIf { it.exists() }?.readText()?.takeIf { it.isNotBlank() }

    fun clear(context: Context) {
        file(context).delete()
    }

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    private fun format(thread: Thread, error: Throwable): String {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        return buildString {
            appendLine("Ember ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Time: $time   Thread: ${thread.name}")
            appendLine()
            // The top of the trace is what matters; very deep ones just add noise.
            append(trace.lines().take(80).joinToString("\n"))
        }
    }
}
