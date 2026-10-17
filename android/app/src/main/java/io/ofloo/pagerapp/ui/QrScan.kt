package io.ofloo.pagerapp.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

/**
 * A QR scanner launcher that first ensures the CAMERA permission.
 *
 * zxing's CaptureActivity would prompt on its own, but doing it here lets us give a clear
 * message when permission is denied instead of the scan silently doing nothing.
 *
 * Returns a `launch()` function; [onResult] receives the scanned text, [onDenied] is called when
 * the user refuses the camera permission.
 */
@Composable
fun rememberQrScanner(
    prompt: String,
    onResult: (String) -> Unit,
    onDenied: () -> Unit = {},
): () -> Unit {
    val context = LocalContext.current
    val resultCb = rememberUpdatedState(onResult)
    val deniedCb = rememberUpdatedState(onDenied)

    fun options() = ScanOptions()
        .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
        .setPrompt(prompt)
        .setBeepEnabled(false)

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.trim()?.takeIf { it.isNotBlank() }?.let { resultCb.value(it) }
    }

    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) scanLauncher.launch(options()) else deniedCb.value()
        }

    return {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) scanLauncher.launch(options())
        else permissionLauncher.launch(Manifest.permission.CAMERA)
    }
}
