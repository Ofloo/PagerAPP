package io.ofloo.pagerapp.ui

import android.Manifest
import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.ofloo.pagerapp.BuildConfig
import io.ofloo.pagerapp.ThemeMode
import io.ofloo.pagerapp.data.Store
import io.ofloo.pagerapp.diag.CrashLog
import io.ofloo.pagerapp.diag.CrashScreen
import io.ofloo.pagerapp.notify.Notifications
import io.ofloo.pagerapp.poll.PollScheduler

class MainActivity : ComponentActivity() {

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private lateinit var store: Store

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Notifications.ensureChannels(this)
        requestNotificationPermission()

        store = Store(this)
        consumeNfcIntent(intent)
        // The crash report screen is a development aid; store builds behave normally.
        val crash = if (BuildConfig.DEBUG) CrashLog.read(this) else null

        setContent {
            var themeMode by remember { mutableStateOf(store.themeMode) }
            PagerTheme(mode = themeMode) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    if (BuildConfig.DEBUG && crash != null) {
                        CrashScreen(report = crash, onDismiss = { recreate() })
                    } else {
                        PagerAppRoot(
                            onServiceChanged = { active ->
                                if (active) PollScheduler.apply(this, store)
                                else PollScheduler.stop(this)
                            },
                            onThemeChanged = { themeMode = it },
                        )
                    }
                }
            }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeNfcIntent(intent)
    }

    /** A contact tag tapped while the app was closed arrives as an NDEF intent. */
    @Suppress("DEPRECATION")
    private fun consumeNfcIntent(intent: Intent?) {
        if (intent?.action != NfcAdapter.ACTION_NDEF_DISCOVERED) return
        val messages = intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES) ?: return
        val message = messages.firstOrNull() as? NdefMessage ?: return
        NfcShare.textFrom(message)?.let { store.pendingContact = it }
    }
}
