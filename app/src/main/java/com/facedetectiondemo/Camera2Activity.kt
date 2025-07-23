package com.facedetectiondemo

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.facedetectiondemo.camera2.Camera2SurfaceView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import android.widget.TextView
import java.io.File

class Camera2Activity : AppCompatActivity() {

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
    }

    private lateinit var cameraView: Camera2SurfaceView
    private lateinit var captureButton: FloatingActionButton
    private lateinit var focusIndicator: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera2)

        initViews()
        setupCameraCallbacks()
        checkPermissions()
    }

    private fun initViews() {
        cameraView = findViewById(R.id.camera_surface_view)
        captureButton = findViewById(R.id.fab_capture)
        focusIndicator = findViewById(R.id.tv_focus_indicator)
        
        captureButton.setOnClickListener {
            cameraView.captureImage()
        }
    }

    private fun setupCameraCallbacks() {
        cameraView.onImageCaptured = { file ->
            runOnUiThread {
                Toast.makeText(this, "Image saved: ${file.absolutePath}", Toast.LENGTH_LONG).show()
            }
        }
        
        cameraView.onFocusStateChanged = { focused ->
            runOnUiThread {
                // Update UI based on focus state
                captureButton.alpha = if (focused) 1.0f else 0.7f
                focusIndicator.visibility = if (focused) 
                    android.view.View.VISIBLE else android.view.View.GONE
                
                // Enable/disable capture button based on focus
                captureButton.isEnabled = focused
            }
        }
    }

    private fun checkPermissions() {
        if (allPermissionsGranted()) {
            // Permissions already granted, camera will start automatically
        } else {
            ActivityCompat.requestPermissions(
                this,
                REQUIRED_PERMISSIONS,
                REQUEST_CODE_PERMISSIONS
            )
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                // Permissions granted, camera will restart automatically
                recreate()
            } else {
                Toast.makeText(
                    this,
                    "Permissions not granted by the user.",
                    Toast.LENGTH_SHORT
                ).show()
                finish()
            }
        }
    }
}