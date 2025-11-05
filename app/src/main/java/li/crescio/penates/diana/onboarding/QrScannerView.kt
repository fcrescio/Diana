package li.crescio.penates.diana.onboarding

import android.content.Context
import android.util.AttributeSet
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.common.util.concurrent.ListenableFuture
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode.FORMAT_QR_CODE
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

class QrScannerView @JvmOverloads constructor(
    context: Context,
    private val lifecycleOwner: LifecycleOwner,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    private val previewView = PreviewView(context).apply {
        layoutParams = LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        scaleType = PreviewView.ScaleType.FILL_CENTER
    }

    private val cameraProviderFuture: ListenableFuture<ProcessCameraProvider> =
        ProcessCameraProvider.getInstance(context)

    private val barcodeScanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(FORMAT_QR_CODE)
            .build(),
    )

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var executor: Executor = ContextCompat.getMainExecutor(context)

    private var onResult: ((String) -> Unit)? = null
    private var onError: ((Throwable) -> Unit)? = null

    private val analyzer = object : ImageAnalysis.Analyzer {
        private val inFlight = AtomicBoolean(false)
        private val hasResult = AtomicBoolean(false)

        override fun analyze(imageProxy: ImageProxy) {
            if (hasResult.get()) {
                imageProxy.close()
                return
            }
            val mediaImage = imageProxy.image ?: run {
                imageProxy.close()
                return
            }
            if (!inFlight.compareAndSet(false, true)) {
                imageProxy.close()
                return
            }
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            barcodeScanner.process(image)
                .addOnSuccessListener { barcodes ->
                    val rawValue = barcodes
                        .firstOrNull { barcode ->
                            barcode.format == FORMAT_QR_CODE && !barcode.rawValue.isNullOrEmpty()
                        }
                        ?.rawValue
                    if (rawValue != null && hasResult.compareAndSet(false, true)) {
                        onResult?.invoke(rawValue)
                    }
                }
                .addOnFailureListener { throwable ->
                    if (!hasResult.get()) {
                        onError?.invoke(throwable)
                    }
                }
                .addOnCompleteListener {
                    inFlight.set(false)
                    imageProxy.close()
                }
        }
    }

    init {
        addView(previewView)
        bindCamera()
    }

    fun setCallbacks(onCodeScanned: (String) -> Unit, onScannerError: (Throwable) -> Unit) {
        onResult = onCodeScanned
        onError = onScannerError
    }

    private fun bindCamera() {
        cameraProviderFuture.addListener(
            {
                try {
                    val provider = cameraProviderFuture.get()
                    cameraProvider = provider
                    val preview = Preview.Builder().build().apply {
                        setSurfaceProvider(previewView.surfaceProvider)
                    }
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    analysis.setAnalyzer(executor, analyzer)
                    imageAnalysis = analysis
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis,
                    )
                } catch (error: Throwable) {
                    onError?.invoke(error)
                }
            },
            executor,
        )
    }

    fun release() {
        try {
            imageAnalysis?.clearAnalyzer()
        } catch (_: Throwable) {
            // Ignore cleanup errors
        }
        try {
            cameraProvider?.unbindAll()
        } catch (_: Throwable) {
            // Ignore cleanup errors
        }
        try {
            barcodeScanner.close()
        } catch (_: Throwable) {
            // Ignore cleanup errors
        }
    }

    override fun onDetachedFromWindow() {
        release()
        super.onDetachedFromWindow()
    }
}
