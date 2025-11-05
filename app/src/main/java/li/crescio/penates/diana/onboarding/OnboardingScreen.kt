package li.crescio.penates.diana.onboarding

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.launch
import li.crescio.penates.diana.R

@Composable
fun OnboardingScreen(
    modifier: Modifier = Modifier,
    validateAndSignIn: suspend (String) -> Unit,
    onAuthenticated: () -> Unit,
) {
    var isScanning by remember { mutableStateOf(false) }
    var isProcessing by remember { mutableStateOf(false) }
    var permissionDenied by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            permissionDenied = false
            errorMessage = null
            isScanning = true
        } else {
            permissionDenied = true
            isScanning = false
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp, vertical = 32.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.onboarding_title),
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = stringResource(R.string.onboarding_body),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
                if (errorMessage != null) {
                    Text(
                        text = errorMessage!!,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                }
                if (permissionDenied) {
                    Text(
                        text = stringResource(R.string.onboarding_permission_denied),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                }
                Button(
                    onClick = {
                        if (!isProcessing) {
                            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                    },
                    enabled = !isProcessing,
                ) {
                    Text(text = stringResource(R.string.onboarding_scan_button))
                }
            }
        }

        if (isScanning) {
            QrScanner(
                modifier = Modifier.fillMaxSize(),
                onCodeScanned = { raw ->
                    if (!isProcessing) {
                        isProcessing = true
                        isScanning = false
                        coroutineScope.launch {
                            try {
                                validateAndSignIn(raw)
                                onAuthenticated()
                            } catch (e: InviteValidationException) {
                                errorMessage = e.message ?: context.getString(R.string.onboarding_scanner_error)
                                isProcessing = false
                            } catch (e: Exception) {
                                errorMessage = e.message ?: context.getString(R.string.onboarding_scanner_error)
                                isProcessing = false
                            }
                        }
                    }
                },
                onError = { throwable ->
                    if (!isProcessing) {
                        errorMessage = throwable.message ?: context.getString(R.string.onboarding_scanner_error)
                        isScanning = false
                    }
                }
            )
            ScannerOverlay(
                onCancel = {
                    isScanning = false
                }
            )
        }

        if (isProcessing) {
            ProcessingOverlay()
        }
    }
}

@Composable
private fun ScannerOverlay(
    onCancel: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.4f)),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            IconButton(onClick = onCancel) {
                Icon(
                    imageVector = Icons.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.onboarding_cancel_scan),
                    tint = Color.White,
                )
            }
        }
        Surface(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(32.dp),
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 4.dp,
            color = Color.Black.copy(alpha = 0.45f),
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 32.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.onboarding_scanner_instruction),
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                )
                TextButton(onClick = onCancel) {
                    Text(
                        text = stringResource(R.string.onboarding_cancel_scan),
                        color = Color.White,
                    )
                }
            }
        }
    }
}

@Composable
private fun ProcessingOverlay() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f)),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            tonalElevation = 6.dp,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth(0.65f),
        ) {
            Row(
                modifier = Modifier
                    .padding(horizontal = 24.dp, vertical = 32.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(48.dp),
                )
                Text(
                    text = stringResource(R.string.onboarding_validation_in_progress),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}

@Composable
private fun QrScanner(
    modifier: Modifier = Modifier,
    onCodeScanned: (String) -> Unit,
    onError: (Throwable) -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var scannerView by remember { mutableStateOf<QrScannerView?>(null) }

    AndroidViewContainer(
        modifier = modifier,
        lifecycleOwner = lifecycleOwner,
        onViewReady = { view ->
            view.setCallbacks(onCodeScanned, onError)
            scannerView = view
        },
    )

    DisposableScannerHandle(scannerView)
}

@Composable
private fun AndroidViewContainer(
    modifier: Modifier,
    lifecycleOwner: LifecycleOwner,
    onViewReady: (QrScannerView) -> Unit,
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            QrScannerView(context, lifecycleOwner).also(onViewReady)
        }
    )
}

@Composable
private fun DisposableScannerHandle(scannerView: QrScannerView?) {
    DisposableEffect(scannerView) {
        onDispose {
            scannerView?.release()
        }
    }
}
