package com.facedetectiondemo.camera2

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.camera2.*
import android.hardware.camera2.params.MeteringRectangle
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.AttributeSet
import android.util.Log
import android.util.Size
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.core.app.ActivityCompat
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.*
import kotlin.collections.ArrayList
import android.widget.Toast

class Camera2SurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : SurfaceView(context, attrs, defStyleAttr), SurfaceHolder.Callback {

    companion object {
        private const val TAG = "Camera2SurfaceView"
        private const val MAX_PREVIEW_WIDTH = 1920
        private const val MAX_PREVIEW_HEIGHT = 1080
    }

    private var cameraManager: CameraManager? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewRequestBuilder: CaptureRequest.Builder? = null
    private var previewRequest: CaptureRequest? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var imageReader: ImageReader? = null
    private var cameraId: String = "0"
    private var previewSize: Size? = null
    private var characteristics: CameraCharacteristics? = null
    
    // Overlay properties
    private val overlayPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }
    
    private val backgroundPaint = Paint().apply {
        color = Color.BLACK
        alpha = 128
    }
    
    // Autofocus properties
    private var isAutoFocusSupported = false
    private var focusAreaSize = 200
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var showFocusIndicator = false
    private var isFocused = false
    
    // Capture callback
    var onImageCaptured: ((File) -> Unit)? = null
    var onFocusStateChanged: ((Boolean) -> Unit)? = null

    init {
        holder.addCallback(this)
        setWillNotDraw(false) // Enable onDraw calls for overlay
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        Log.d(TAG, "Surface created")
        initializeCamera()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        Log.d(TAG, "Surface changed: ${width}x${height}")
        if (cameraDevice != null) {
            createCameraPreviewSession()
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        Log.d(TAG, "Surface destroyed")
        closeCamera()
    }

    @SuppressLint("MissingPermission")
    private fun initializeCamera() {
        cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        
        try {
            // Get the best camera (usually back camera)
            cameraId = getBestCameraId()
            characteristics = cameraManager?.getCameraCharacteristics(cameraId)
            
            // Check autofocus support
            val afModes = characteristics?.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
            isAutoFocusSupported = afModes?.contains(CameraCharacteristics.CONTROL_AF_MODE_AUTO) == true ||
                    afModes?.contains(CameraCharacteristics.CONTROL_AF_MODE_CONTINUOUS_PICTURE) == true
            
                         // Set up preview size
             val map = characteristics?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
             previewSize = CameraUtils.chooseOptimalSize(
                 map?.getOutputSizes(SurfaceHolder::class.java) ?: arrayOf(),
                 width, height, MAX_PREVIEW_WIDTH, MAX_PREVIEW_HEIGHT
             )
            
            // Set up image reader for capture
            setupImageReader()
            
            // Start background thread
            startBackgroundThread()
            
            // Open camera
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.CAMERA) 
                == PackageManager.PERMISSION_GRANTED) {
                cameraManager?.openCamera(cameraId, stateCallback, backgroundHandler)
            }
            
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Camera access exception", e)
        }
    }
    
    private fun getBestCameraId(): String {
        return try {
            val cameraIds = cameraManager?.cameraIdList ?: arrayOf()
            // Prefer back camera
            for (id in cameraIds) {
                val characteristics = cameraManager?.getCameraCharacteristics(id)
                val facing = characteristics?.get(CameraCharacteristics.LENS_FACING)
                if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                    return id
                }
            }
            // Fallback to first available camera
            cameraIds.firstOrNull() ?: "0"
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Error getting camera ID", e)
            "0"
        }
    }
    
    private fun setupImageReader() {
        previewSize?.let { size ->
            imageReader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 1)
            imageReader?.setOnImageAvailableListener(imageAvailableListener, backgroundHandler)
        }
    }
    
    private val imageAvailableListener = ImageReader.OnImageAvailableListener { reader ->
        val image = reader.acquireLatestImage()
        backgroundHandler?.post {
            saveImage(image)
        }
    }
    
    private fun saveImage(image: Image) {
        try {
            val buffer: ByteBuffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            
            val file = File(context.getExternalFilesDir(null), "captured_${System.currentTimeMillis()}.jpg")
            val output = FileOutputStream(file)
            output.write(bytes)
            output.close()
            
            post {
                onImageCaptured?.invoke(file)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error saving image", e)
        } finally {
            image.close()
        }
    }

    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            Log.d(TAG, "Camera opened")
            cameraDevice = camera
            createCameraPreviewSession()
        }

        override fun onDisconnected(camera: CameraDevice) {
            Log.d(TAG, "Camera disconnected")
            camera.close()
            cameraDevice = null
        }

        override fun onError(camera: CameraDevice, error: Int) {
            Log.e(TAG, "Camera error: $error")
            camera.close()
            cameraDevice = null
        }
    }

    private fun createCameraPreviewSession() {
        try {
            val surface = holder.surface
            previewRequestBuilder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            previewRequestBuilder?.addTarget(surface)
            
            // Enable continuous autofocus
            if (isAutoFocusSupported) {
                previewRequestBuilder?.set(
                    CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                )
            }
            
            // Auto exposure and white balance
            previewRequestBuilder?.set(
                CaptureRequest.CONTROL_AE_MODE,
                CaptureRequest.CONTROL_AE_MODE_ON
            )
            previewRequestBuilder?.set(
                CaptureRequest.CONTROL_AWB_MODE,
                CaptureRequest.CONTROL_AWB_MODE_AUTO
            )

            val surfaces = ArrayList<Surface>()
            surfaces.add(surface)
            imageReader?.surface?.let { surfaces.add(it) }

            cameraDevice?.createCaptureSession(
                surfaces,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (cameraDevice == null) return
                        
                        captureSession = session
                        try {
                            previewRequest = previewRequestBuilder?.build()
                            previewRequest?.let {
                                session.setRepeatingRequest(it, captureCallback, backgroundHandler)
                            }
                        } catch (e: CameraAccessException) {
                            Log.e(TAG, "Error starting preview", e)
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Camera configuration failed")
                    }
                },
                backgroundHandler
            )
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Error creating camera preview session", e)
        }
    }
    
    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            // Handle autofocus state
            val afState = result.get(CaptureResult.CONTROL_AF_STATE)
            val focused = afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                    afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED
            
            if (isFocused != focused) {
                isFocused = focused
                post {
                    onFocusStateChanged?.invoke(focused)
                    invalidate() // Redraw to update focus indicator
                }
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN && isAutoFocusSupported) {
            lastTouchX = event.x
            lastTouchY = event.y
            showFocusIndicator = true
            
            // Only allow focus within the oval area
            if (CameraUtils.isPointInOval(event.x, event.y, width, height)) {
                triggerFocusArea(event.x.toInt(), event.y.toInt())
                invalidate() // Redraw to show focus indicator
                return true
            } else {
                Toast.makeText(context, "Focus within the oval area", Toast.LENGTH_SHORT).show()
            }
        }
        return super.onTouchEvent(event)
    }
    
    private fun triggerFocusArea(x: Int, y: Int) {
        try {
            if (cameraDevice == null || captureSession == null) return
            
            // Convert touch coordinates to sensor coordinates
            val sensorArraySize = characteristics?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            if (sensorArraySize == null) return
            
            val focusX = (x.toFloat() / width * sensorArraySize.width()).toInt()
            val focusY = (y.toFloat() / height * sensorArraySize.height()).toInt()
            
            val halfTouchWidth = focusAreaSize / 2
            val halfTouchHeight = focusAreaSize / 2
            
            val focusRect = Rect(
                Math.max(focusX - halfTouchWidth, 0),
                Math.max(focusY - halfTouchHeight, 0),
                Math.min(focusX + halfTouchWidth, sensorArraySize.width()),
                Math.min(focusY + halfTouchHeight, sensorArraySize.height())
            )
            
            val meteringRectangle = MeteringRectangle(focusRect, MeteringRectangle.METERING_WEIGHT_MAX - 1)
            val meteringRectangles = arrayOf(meteringRectangle)
            
            // Create focus request
            val focusBuilder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            focusBuilder?.addTarget(holder.surface)
            focusBuilder?.set(CaptureRequest.CONTROL_AF_REGIONS, meteringRectangles)
            focusBuilder?.set(CaptureRequest.CONTROL_AE_REGIONS, meteringRectangles)
            focusBuilder?.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            focusBuilder?.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
            
            focusBuilder?.build()?.let { focusRequest ->
                captureSession?.capture(focusRequest, null, backgroundHandler)
            }
            
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Error triggering focus", e)
        }
    }

    fun captureImage() {
        try {
            if (cameraDevice == null || imageReader == null) return
            
            val captureBuilder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            captureBuilder?.addTarget(imageReader?.surface!!)
            
            // Use same settings as preview
            captureBuilder?.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            captureBuilder?.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            
            captureBuilder?.build()?.let { captureRequest ->
                captureSession?.capture(captureRequest, null, backgroundHandler)
            }
            
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Error capturing image", e)
        }
    }

    override fun onDraw(canvas: Canvas?) {
        super.onDraw(canvas)
        canvas?.let { 
            drawOvalOverlay(it)
            if (showFocusIndicator) {
                CameraUtils.drawFocusIndicator(it, lastTouchX, lastTouchY, isFocused)
            }
        }
    }
    
    private fun drawOvalOverlay(canvas: Canvas) {
        val centerX = width / 2f
        val centerY = height / 2f
        val ovalWidth = width * 0.7f
        val ovalHeight = height * 0.5f
        
        // Draw semi-transparent background
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)
        
        // Clear the oval area
        val ovalRect = RectF(
            centerX - ovalWidth / 2,
            centerY - ovalHeight / 2,
            centerX + ovalWidth / 2,
            centerY + ovalHeight / 2
        )
        
        canvas.drawOval(ovalRect, Paint().apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        })
        
        // Draw oval border
        canvas.drawOval(ovalRect, overlayPaint)
        
        // Draw instruction text
        val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = 48f
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        
        canvas.drawText(
            "Position object within oval",
            centerX,
            centerY + ovalHeight / 2 + 80f,
            textPaint
        )
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread?.looper!!)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            Log.e(TAG, "Error stopping background thread", e)
        }
    }

    private fun closeCamera() {
        captureSession?.close()
        captureSession = null
        
        cameraDevice?.close()
        cameraDevice = null
        
        imageReader?.close()
        imageReader = null
        
        stopBackgroundThread()
    }


}