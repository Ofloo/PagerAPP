package io.ofloo.pagerapp.diag

import android.content.Context
import android.os.Build
import io.ofloo.pagerapp.BuildConfig
import io.ofloo.pagerapp.data.Store
import io.ofloo.pagerapp.net.PagerClient
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persists the most recent uncaught exception to a file so a crash that kills the app during
 * startup can still be read on the next launch, and — in debug builds only — forwards it to a
 * diagnostic PagerMCP mailbox so it can be retrieved remotely without adb.
 */
object CrashLog {

    private const val FILE_NAME = "last_crash.txt"

    /** PagerAPP crash collector mailbox: reports are POSTed here as PagerMCP pages (debug only). */
    private const val DIAG_MAILBOX = "2f858a5f-ccfb-415e-ba5b-4478a17d7d93"

    /** Remote crash reporting is a development aid; release builds never send anything. */
    val remoteEnabled: Boolean = BuildConfig.DEBUG

    fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    fun write(context: Context, throwable: Throwable, thread: Thread) {
        runCatching { file(context).writeText(build(context, throwable, thread)) }
    }

    fun read(context: Context): String? = file(context).takeIf { it.exists() }?.readText()

    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }

    private fun build(context: Context, throwable: Throwable, thread: Thread): String {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        return buildString {
            appendLine("time: $stamp")
            appendLine("app: 0.1.0 (versionCode 1)")
            appendLine("android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
            appendLine("thread: ${thread.name}")
            appendLine("--- stack trace ---")
            append(sw.toString())
        }
    }

    /** Sends the stored crash to [DIAG_MAILBOX]. Debug builds only. Off the main thread. */
    fun sendRemote(context: Context): Boolean {
        if (!remoteEnabled) return false
        val report = read(context) ?: return false
        val store = Store(context)
        return runCatching {
            PagerClient.notify(
                serverUrl = store.serverUrl,
                bearer = DIAG_MAILBOX,
                payload = mapOf(
                    "PROJECT" to "PagerAPP",
                    "JOB_ID" to "crash-${System.currentTimeMillis()}",
                    "status" to 2,
                    "message" to "PagerAPP crash report",
                    "device" to "${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE}",
                    "logs" to report.take(8000),
                ),
            )
            true
        }.getOrDefault(false)
    }
}
