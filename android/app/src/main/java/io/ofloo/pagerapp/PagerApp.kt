package io.ofloo.pagerapp

import android.app.Application
import io.ofloo.pagerapp.data.Store
import io.ofloo.pagerapp.diag.CrashLog
import io.ofloo.pagerapp.ui.Translations

/**
 * Installs a catch-all handler so an uncaught exception (including one during startup, before the
 * UI exists) is written to disk and can be shown/forwarded on the next launch.
 */
class PagerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Translations.setLanguage(Store(this).appLanguage)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            CrashLog.write(this, throwable, thread)
            previous?.uncaughtException(thread, throwable)
        }
    }
}
