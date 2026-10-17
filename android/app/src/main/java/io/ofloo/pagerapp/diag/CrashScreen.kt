package io.ofloo.pagerapp.diag

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import io.ofloo.pagerapp.data.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shown on launch when the previous run ended in an uncaught exception. Displays the captured
 * stack so the user can copy it, and offers to forward it to the diagnostic mailbox.
 */
@Composable
fun CrashScreen(report: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
    ) {
        Text("PagerAPP stopped unexpectedly", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "The previous run crashed. The report is below and is also being sent for diagnosis.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            report,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
        )
        Spacer(Modifier.height(12.dp))
        Text(status ?: "sending…", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        Spacer(Modifier.height(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { copy(context, report); status = "copied to clipboard" }) {
                Text("Copy report")
            }
            TextButton(onClick = { CrashLog.clear(context); onDismiss() }) {
                Text("Dismiss and continue")
            }
        }
    }

    // Auto-send once per view; harmless if it fails (offline).
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (status == null) {
            val ok = withContext(Dispatchers.IO) { CrashLog.sendRemote(context) }
            status = if (ok) "report sent" else "could not send (offline?) — use Copy report"
        }
    }
}

private fun copy(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("PagerAPP crash", text))
}
