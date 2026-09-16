package com.sahayat.app.ui.screens.pairing

import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.mlkit.vision.MlKitAnalyzer
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode

/**
 * Live camera preview that decodes QR codes on-device with ML Kit (via CameraX's MlKitAnalyzer).
 * [onResult] is called once per distinct QR value; [onError] if the camera can't start.
 */
@Composable
fun QrScanner(
    modifier: Modifier = Modifier,
    paused: Boolean,
    onResult: (String) -> Unit,
    onError: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnResult = rememberUpdatedState(onResult)
    val currentOnError = rememberUpdatedState(onError)
    val currentPaused = rememberUpdatedState(paused)

    val scanner: BarcodeScanner = remember {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build(),
        )
    }
    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }
    val controller = remember { LifecycleCameraController(context) }

    DisposableEffect(lifecycleOwner) {
        var lastValue: String? = null
        val executor = ContextCompat.getMainExecutor(context)
        try {
            controller.cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            controller.setImageAnalysisBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            controller.setImageAnalysisAnalyzer(
                executor,
                MlKitAnalyzer(
                    listOf(scanner),
                    ImageAnalysis.COORDINATE_SYSTEM_ORIGINAL,
                    executor,
                ) { result ->
                    val value = if (currentPaused.value) null
                    else result.getValue(scanner)?.firstOrNull()?.rawValue
                    if (value != null && value != lastValue) {
                        lastValue = value
                        currentOnResult.value(value)
                    }
                },
            )
            controller.bindToLifecycle(lifecycleOwner)
            previewView.controller = controller
        } catch (e: Exception) {
            Log.w("SahayatQR", "Camera unavailable: ${e::class.java.simpleName}")
            currentOnError.value()
        }
        onDispose {
            controller.clearImageAnalysisAnalyzer()
            controller.unbind()
        }
    }

    DisposableEffect(Unit) {
        onDispose { scanner.close() }
    }

    AndroidView(factory = { previewView }, modifier = modifier)
}
