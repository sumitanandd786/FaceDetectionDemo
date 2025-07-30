package com.example.fingerprint

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.AttributeSet
import android.util.Size
import android.view.Surface
import android.view.TextureView
import androidx.camera.core.AspectRatio
import androidx.core.graphics.drawable.toBitmap
import kotlin.math.max
import kotlin.math.min

/**
 * A drop-in TextureView that streams camera preview via Camera2, draws an oval overlay indicating
 * where the user should place their finger, and exposes callbacks for:
 *  – Finger presence within overlay (basic segmentation stub provided – replace with proper ML)
 *  – Autofocus trigger as soon as finger enters overlay (milliseconds)
 *  – Blur detection via Laplacian variance
 *  – Liveness check (very naive motion-based stub – replace with PPG / advanced ML as needed)
 *  – Segmentation mask bitmap callback (for downstream processing)
 *
 * This code intentionally keeps most heavy work off the UI thread. ML parts are left as
 * TODOs so that you can plug-in TensorFlow Lite / MediaPipe-based solutions without changing the
 * camera plumbing.
 */
class FingerDetectionView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : TextureView(context, attrs, defStyleAttr), TextureView.SurfaceTextureListener {

    // region Public API -------------------------------------------------------------------------

    interface Listener {
        /** Called once the camera has opened and preview started. */
        fun onCameraReady() {}

        /** Finger detected inside the oval overlay. */
        fun onFingerDetected() {}

        /** Called when finger is lost / moved away. */
        fun onFingerLost() {}

        /** Receives blur status per frame. */
        fun onBlurState(isBlur: Boolean) {}

        /** Very naive liveness – true if passing. */
        fun onLiveness(isLive: Boolean) {}

        /** Segmentation mask rendered as alpha-only bitmap the same size as preview. */
        fun onSegmentationMask(mask: Bitmap) {}
    }

    var listener: Listener? = null

    /** Controls the oval overlay size relative to view; 0.0 – 1.0 (default 0.6). */
    var overlayScale = 0.6f
        set(value) {
            field = value.coerceIn(0f, 1f)
            postInvalidate()
        }

    /** Laplacian variance threshold below which frame is considered blurred. */
    var blurThreshold = 100f

    // endregion ----------------------------------------------------------------------------------

    // region Camera2 plumbing --------------------------------------------------------------------

    private val cameraManager by lazy { context.getSystemService(Context.CAMERA_SERVICE) as CameraManager }
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewRequestBuilder: CaptureRequest.Builder? = null
    private var previewSize: Size = Size(1280, 720)

    private var bgThread: HandlerThread? = null
    private var bgHandler: Handler? = null

    private lateinit var imageReader: ImageReader

    private val availableCameras: List<String> by lazy {
        cameraManager.cameraIdList.filter { id ->
            val characteristics = cameraManager.getCameraCharacteristics(id)
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
            facing == CameraCharacteristics.LENS_FACING_BACK || facing == CameraCharacteristics.LENS_FACING_EXTERNAL
        }
    }

    private val cameraId: String
        get() = availableCameras.firstOrNull()
            ?: throw IllegalStateException("No back/external camera available")

    // endregion ----------------------------------------------------------------------------------

    // region TextureView lifecycle ---------------------------------------------------------------

    init {
        surfaceTextureListener = this
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        startBackgroundThread()
        openCamera(width, height)
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        closeCamera()
        stopBackgroundThread()
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}

    // endregion ----------------------------------------------------------------------------------

    // region Camera open/start -------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun openCamera(width: Int, height: Int) {
        // Choose a preview size as close as possible to the TextureView size with matching aspect.
        previewSize = chooseOptimalSize(width, height)

        imageReader = ImageReader.newInstance(
            previewSize.width,
            previewSize.height,
            ImageFormat.YUV_420_888,
            2
        )
        imageReader.setOnImageAvailableListener(::onImageAvailable, bgHandler)
        cameraManager.openCamera(cameraId, stateCallback, bgHandler)
    }

    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(device: CameraDevice) {
            cameraDevice = device
            createCameraPreviewSession()
            listener?.onCameraReady()
        }

        override fun onDisconnected(device: CameraDevice) {
            device.close()
            cameraDevice = null
        }

        override fun onError(device: CameraDevice, error: Int) {
            device.close()
            cameraDevice = null
        }
    }

    private fun createCameraPreviewSession() {
        val texture = surfaceTexture ?: return
        texture.setDefaultBufferSize(previewSize.width, previewSize.height)
        val surface = Surface(texture)

        cameraDevice?.let { camera ->
            previewRequestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                addTarget(imageReader.surface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            }

            camera.createCaptureSession(listOf(surface, imageReader.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        try {
                            session.setRepeatingRequest(previewRequestBuilder!!.build(), null, bgHandler)
                        } catch (e: CameraAccessException) {
                            e.printStackTrace()
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {}
                }, bgHandler)
        }
    }

    private fun triggerAutofocus() {
        try {
            previewRequestBuilder?.set(
                CaptureRequest.CONTROL_AF_TRIGGER,
                CameraMetadata.CONTROL_AF_TRIGGER_START
            )
            captureSession?.capture(previewRequestBuilder!!.build(), null, bgHandler)
            previewRequestBuilder?.set(
                CaptureRequest.CONTROL_AF_TRIGGER,
                CaptureRequest.CONTROL_AF_TRIGGER_IDLE
            )
            captureSession?.setRepeatingRequest(previewRequestBuilder!!.build(), null, bgHandler)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun closeCamera() {
        captureSession?.close(); captureSession = null
        cameraDevice?.close(); cameraDevice = null
        imageReader.close()
    }

    private fun startBackgroundThread() {
        bgThread = HandlerThread("FingerBG").also { it.start() }
        bgHandler = Handler(bgThread!!.looper)
    }

    private fun stopBackgroundThread() {
        bgThread?.quitSafely()
        try { bgThread?.join() } catch (ignored: InterruptedException) {}
        bgThread = null
        bgHandler = null
    }

    // endregion ----------------------------------------------------------------------------------

    // region Image analysis ----------------------------------------------------------------------

    private var fingerPresent = false
    private var lastMaskBitmap: Bitmap? = null

    private fun onImageAvailable(reader: ImageReader) {
        val image = reader.acquireLatestImage() ?: return
        val bitmap = image.toBitmapAndClose()

        val mask = segmentFinger(bitmap) // alpha-only mask or null
        if (mask != null) {
            // Finger detected inside overlay?
            val inOverlay = isFingerInsideOverlay(mask)
            if (inOverlay && !fingerPresent) {
                fingerPresent = true
                triggerAutofocus()
                listener?.onFingerDetected()
            } else if (!inOverlay && fingerPresent) {
                fingerPresent = false
                listener?.onFingerLost()
            }

            // Push segmentation result
            listener?.onSegmentationMask(mask)
            lastMaskBitmap = mask
        } else if (fingerPresent) {
            fingerPresent = false
            listener?.onFingerLost()
        }

        // Blur measurement
        val isBlur = isFrameBlurred(bitmap)
        listener?.onBlurState(isBlur)

        // Naive liveness – detect motion between consecutive masks
        val live = isLive(mask)
        listener?.onLiveness(live)
    }

    // Converts YUV image to ARGB Bitmap efficiently
    private fun Image.toBitmapAndClose(): Bitmap {
        val yBuffer = planes[0].buffer
        val vuBuffer = planes[2].buffer
        val ySize = yBuffer.remaining()
        val vuSize = vuBuffer.remaining()
        val nv21 = ByteArray(ySize + vuSize)
        yBuffer.get(nv21, 0, ySize)
        vuBuffer.get(nv21, ySize, vuSize)
        // Simple thumb rule – decode via RenderScript / YuvToRgbConverter if available.
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, width, height), 75, out)
        close()
        val bytes = out.toByteArray()
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    // TODO Replace stub with TFLite/MediaPipe finger segmentation.
    private fun segmentFinger(frame: Bitmap): Bitmap? {
        // Very naive skin-color thresholding as placeholder.
        val mask = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ALPHA_8)
        val canvas = Canvas(mask)
        val paint = Paint().apply { color = Color.WHITE }

        val pixels = IntArray(frame.width)
        var nonZero = 0
        for (y in 0 until frame.height step 4) { // sample rows
            frame.getPixels(pixels, 0, frame.width, 0, y, frame.width, 1)
            for (x in pixels.indices step 4) { // sample cols
                val color = pixels[x]
                val r = Color.red(color)
                val g = Color.green(color)
                val b = Color.blue(color)
                val hsv = FloatArray(3)
                Color.RGBToHSV(r, g, b, hsv)
                val skin = hsv[0] < 50 && hsv[0] > 0 && hsv[1] > 0.23 && hsv[2] > 0.35
                if (skin) {
                    canvas.drawPoint(x.toFloat(), y.toFloat(), paint)
                    nonZero++
                }
            }
        }
        return if (nonZero > frame.width * frame.height * 0.01) mask else null
    }

    private fun isFingerInsideOverlay(mask: Bitmap): Boolean {
        val overlayRect = overlayRect()
        val sampled = IntArray(1000)
        var count = 0
        val rnd = java.util.Random()
        for (i in sampled.indices) {
            val x = overlayRect.left + rnd.nextInt(max(1, overlayRect.width()))
            val y = overlayRect.top + rnd.nextInt(max(1, overlayRect.height()))
            sampled[i] = mask.getPixel(x, y)
            if (Color.alpha(sampled[i]) > 0) count++
        }
        return count > sampled.size * 0.2 // 20% of points covered
    }

    private fun isFrameBlurred(frame: Bitmap): Boolean {
        // Laplacian variance – quick blur metric.
        val mat = org.opencv.android.Utils.bitmapToMat(frame)
        val gray = org.opencv.core.Mat()
        org.opencv.imgproc.Imgproc.cvtColor(mat, gray, org.opencv.imgproc.Imgproc.COLOR_BGR2GRAY)
        val laplacian = org.opencv.core.Mat()
        org.opencv.imgproc.Imgproc.Laplacian(gray, laplacian, org.opencv.core.CvType.CV_64F)
        val mean = org.opencv.core.Core.meanStdDev(laplacian)
        val variance = mean.`val`[1] * mean.`val`[1]
        mat.release(); gray.release(); laplacian.release()
        return variance < blurThreshold
    }

    private var lastLiveMask: Bitmap? = null
    private fun isLive(currentMask: Bitmap?): Boolean {
        val last = lastLiveMask ?: return false.also { lastLiveMask = currentMask }
        if (currentMask == null) return false
        val diff = bitmapDiff(last, currentMask)
        lastLiveMask = currentMask
        return diff > 0.05 // >5% pixel difference implies movement
    }

    private fun bitmapDiff(a: Bitmap, b: Bitmap): Float {
        if (a.width != b.width || a.height != b.height) return 1f
        var diff = 0
        val bufferA = IntArray(a.width * a.height)
        val bufferB = IntArray(b.width * b.height)
        a.getPixels(bufferA, 0, a.width, 0, 0, a.width, a.height)
        b.getPixels(bufferB, 0, b.width, 0, 0, b.width, b.height)
        for (i in bufferA.indices) if (bufferA[i] != bufferB[i]) diff++
        return diff.toFloat() / bufferA.size
    }

    // endregion ----------------------------------------------------------------------------------

    // region Overlay drawing ---------------------------------------------------------------------

    private val overlayPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f * resources.displayMetrics.density
        isAntiAlias = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val rect = overlayRect()
        canvas.drawOval(RectF(rect), overlayPaint)
    }

    private fun overlayRect(): Rect {
        val w = width * overlayScale
        val h = height * overlayScale
        val left = (width - w) / 2f
        val top = (height - h) / 2f
        return Rect(left.toInt(), top.toInt(), (left + w).toInt(), (top + h).toInt())
    }

    // endregion ----------------------------------------------------------------------------------

    // region Utility -----------------------------------------------------------------------------

    private fun chooseOptimalSize(texWidth: Int, texHeight: Int): Size {
        val map = cameraManager.getCameraCharacteristics(cameraId)
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return Size(texWidth, texHeight)
        val choices = map.getOutputSizes(SurfaceTexture::class.java)
        val aspectRatio = texWidth.toFloat() / texHeight
        var best: Size = choices[0]
        for (size in choices) {
            if (size.width <= 1920 && size.height <= 1080) {
                val diff = kotlin.math.abs(size.width.toFloat() / size.height - aspectRatio)
                val bestDiff = kotlin.math.abs(best.width.toFloat() / best.height - aspectRatio)
                if (diff < bestDiff) best = size
            }
        }
        return best
    }

    // endregion ----------------------------------------------------------------------------------
}