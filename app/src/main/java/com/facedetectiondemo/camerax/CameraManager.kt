package com.facedetectiondemo.camerax

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContentProviderCompat.requireContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.facedetectiondemo.face_detection.FaceContourDetectionProcessor
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraManager(
    private val context: Context,
    private val finderView: PreviewView,
    private val lifecycleOwner: LifecycleOwner,
    private val graphicOverlay: GraphicOverlay
) {

    private var preview: Preview? = null

    private var camera: Camera? = null
    private lateinit var cameraExecutor: ExecutorService
    private var cameraSelectorOption = CameraSelector.LENS_FACING_FRONT
    private var cameraProvider: ProcessCameraProvider? = null

    private var imageAnalyzer: ImageAnalysis? = null
    private var imageCapture: ImageCapture? = null

    init {
        createNewExecutor()
    }

    private fun createNewExecutor() {
        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener(
            Runnable {
                cameraProvider = cameraProviderFuture.get()
                preview = Preview.Builder()
                    .build()

                imageAnalyzer = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also {
                        it.setAnalyzer(cameraExecutor, selectAnalyzer())
                    }

                val cameraSelector = CameraSelector.Builder()
                    .requireLensFacing(cameraSelectorOption)
                    .build()

                setCameraConfig(cameraProvider, cameraSelector)

            }, ContextCompat.getMainExecutor(context)
        )
    }

    private fun selectAnalyzer(): ImageAnalysis.Analyzer {
        return FaceContourDetectionProcessor(graphicOverlay)
    }

    private fun setCameraConfig(
        cameraProvider: ProcessCameraProvider?,
        cameraSelector: CameraSelector
    ) {
        try {
            cameraProvider?.unbindAll()

            //takePhoto()
            /*imageCapture = camera?.let {
                ImageCapture.Builder()
                    .setFlashMode(ImageCapture.FLASH_MODE_ON)
                    .setTargetAspectRatio(AspectRatio.RATIO_16_9) // width:height
                    .build()
            }*/
            camera = cameraProvider?.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,//imageCapture
                imageAnalyzer
            )
            preview?.setSurfaceProvider(
                finderView.surfaceProvider
            )

        } catch (e: Exception) {
            Log.e(TAG, "Use case binding failed", e)
        }
    }

    fun changeCameraSelector() {
        cameraProvider?.unbindAll()
        cameraSelectorOption =
            if (cameraSelectorOption == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT
            else CameraSelector.LENS_FACING_BACK
        graphicOverlay.toggleSelector()
        startCamera()
    }

    private fun takePhoto() {
        var photoFile =
            File(context.externalMediaDirs.firstOrNull(),"CapturePro-${System.currentTimeMillis()}.jpg")
        var imageOptions: ImageCapture.OutputFileOptions = ImageCapture.OutputFileOptions.Builder(
            photoFile
        ).build()
        imageCapture?.takePicture(
            imageOptions,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    Toast.makeText(
                        context,
                        "Image Saved ${outputFileResults.savedUri} at ${context.externalCacheDir!!.absolutePath}",
                        Toast.LENGTH_LONG
                    ).show()
                    Log.d("TAG", "onImageSaved:  ${context.externalCacheDir!!.absolutePath}")
                }

                override fun onError(exception: ImageCaptureException) {
                    Toast.makeText(
                        context,
                        "Failed: ${exception.message} and ${exception.localizedMessage}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            })
    }

    companion object {
        private const val TAG = "CameraXBasic"
    }

}