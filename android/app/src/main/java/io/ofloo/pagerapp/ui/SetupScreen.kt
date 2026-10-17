package io.ofloo.pagerapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ofloo.pagerapp.data.Store
import io.ofloo.pagerapp.net.PagerClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * First-run setup: generate a mailbox, or adopt one by scanning its QR code / pasting the UUID.
 * Also reachable later (showGenerate = false) to re-adopt or restore a backup.
 */
@Composable
fun SetupScreen(
    store: Store,
    onReady: (String) -> Unit,
    showGenerate: Boolean = true,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var manual by remember { mutableStateOf(store.uuid.orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val scan = rememberQrScanner(
        prompt = "Scan the PagerAPP mailbox QR code",
        onResult = { scanned ->
            val uuid = scanned.substringAfterLast('/').trim()
            store.uuid = uuid
            onReady(uuid)
        },
        onDenied = {
            error = "Camera permission is needed to scan. You can also paste the UUID below."
        },
    )

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("PagerAPP", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "This device's mailbox UUID is created once and then never changes. " +
                "Back it up — scripts send pages to it.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))

        if (showGenerate) {
            Button(
                enabled = !busy,
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            val uuid = withContext(Dispatchers.IO) {
                                PagerClient.createMailbox(store.serverUrl)
                            }
                            store.uuid = uuid
                            onReady(uuid)
                        } catch (e: Exception) {
                            error = "Could not create mailbox: ${e.message}"
                        } finally {
                            busy = false
                        }
                    }
                },
            ) { Text(if (busy) "Creating…" else "Create a new mailbox") }
            Spacer(Modifier.height(12.dp))
        }

        Text("or", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))

        OutlinedButton(onClick = { scan() }) { Text("Scan QR code") }

        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = manual,
            onValueChange = { manual = it },
            label = { Text("or paste UUID") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Button(
            enabled = manual.isNotBlank(),
            onClick = {
                val uuid = manual.trim()
                store.uuid = uuid
                onReady(uuid)
            },
        ) { Text("Use this UUID") }

        // Show the current UUID so the user can back it up (QR / copy).
        val current = store.uuid
        if (current != null) {
            Spacer(Modifier.height(24.dp))
            Text("Your mailbox UUID", fontWeight = FontWeight.Bold)
            Text(current, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            Text(
                "(back it up outside the app; the app never uploads it)",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            )
        }

        error?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }
    }
}
