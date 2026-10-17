package io.ofloo.pagerapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ofloo.pagerapp.AppLinks
import io.ofloo.pagerapp.BuildConfig
import io.ofloo.pagerapp.data.Store
import io.ofloo.pagerapp.net.PagerClient
import io.ofloo.pagerapp.net.ServerInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * About: the app's own build, the server it talks to (version, build number and hostname from
 * `GET /version`) and the links that matter. The build number is what identifies the exact server
 * image, which is why it is shown next to the version.
 */
@Composable
fun AboutDialog(store: Store, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current

    var info by remember { mutableStateOf<ServerInfo?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }

    suspend fun load() {
        loading = true
        failure = null
        val result = withContext(Dispatchers.IO) {
            runCatching { PagerClient.version(store.serverUrl) }
        }
        info = result.getOrNull()
        failure = result.exceptionOrNull()?.message
        loading = false
    }

    LaunchedEffect(Unit) { load() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("About PagerAPP") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                RowInfo("app", "${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})")
                RowInfo("package", BuildConfig.APPLICATION_ID)
                Spacer(Modifier.height(8.dp))
                RowInfo("server", store.serverUrl)
                RowInfo(
                    "version",
                    when {
                        loading -> "checking…"
                        info != null -> "${info!!.version} (build ${info!!.build})"
                        else -> failure ?: "unreachable"
                    },
                )
                info?.hostname?.let { RowInfo("host", it) }
                Spacer(Modifier.height(8.dp))
                RowInfo("mailbox", store.uuid ?: "(none yet)")
                RowInfo(
                    "polling",
                    if (store.reliableDelivery) "${store.pollSeconds}s (foreground)"
                    else "background (15 min minimum)",
                )
                RowInfo("theme", store.themeMode.name.lowercase())
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { scope.launch { load() } }) { Text("Check again") }
                    TextButton(onClick = { uriHandler.openUri(AppLinks.PRIVACY_POLICY) }) {
                        Text("Privacy")
                    }
                    TextButton(onClick = { uriHandler.openUri("${store.serverUrl}/rfc") }) {
                        Text("Protocol")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun RowInfo(key: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            key,
            modifier = Modifier.width(84.dp),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
    }
}
