# Camera2 SurfaceView with Autofocus and Oval Overlay

This implementation provides a custom SurfaceView using Camera2 API with the following features:

## Features

### 1. Custom SurfaceView Implementation
- Uses Camera2 API for better camera control
- Handles camera lifecycle automatically
- Supports background threading for camera operations

### 2. Autofocus Functionality
- **Continuous Autofocus**: Automatically focuses on objects in the scene
- **Touch to Focus**: Tap anywhere within the oval area to focus on that point
- **Focus Indicators**: Visual feedback showing focus state and location
- **Focus Quality Analysis**: Analyzes contrast to determine focus quality

### 3. Oval Overlay
- **Semi-transparent background** with clear oval area for object positioning
- **Customizable size**: 70% width × 50% height of the screen
- **Visual guidance**: Instructions text for user guidance
- **Restricted focus area**: Only allows focusing within the oval region

### 4. Image Capture
- **High-quality capture**: Uses optimal resolution for image capture
- **Auto-save**: Automatically saves captured images to external storage
- **Callback support**: Notifies when image is captured and saved

## Components

### Camera2SurfaceView
The main custom view that handles all camera operations:

```kotlin
class Camera2SurfaceView : SurfaceView, SurfaceHolder.Callback {
    // Callbacks
    var onImageCaptured: ((File) -> Unit)? = null
    var onFocusStateChanged: ((Boolean) -> Unit)? = null
    
    // Methods
    fun captureImage()
}
```

### Camera2Activity
Activity that demonstrates the usage:

```kotlin
class Camera2Activity : AppCompatActivity() {
    private lateinit var cameraView: Camera2SurfaceView
    private lateinit var captureButton: FloatingActionButton
}
```

### CameraUtils
Utility class with helper functions:

```kotlin
object CameraUtils {
    fun isPointInOval(x: Float, y: Float, viewWidth: Int, viewHeight: Int): Boolean
    fun getOvalBounds(viewWidth: Int, viewHeight: Int): RectF
    fun createFocusArea(...): Rect
    fun analyzeFocusQuality(bitmap: Bitmap?, focusRect: Rect): Float
    fun drawFocusIndicator(canvas: Canvas, x: Float, y: Float, focused: Boolean)
}
```

## Usage

### 1. Add to Layout
```xml
<com.facedetectiondemo.camera2.Camera2SurfaceView
    android:id="@+id/camera_surface_view"
    android:layout_width="match_parent"
    android:layout_height="match_parent" />
```

### 2. Setup in Activity
```kotlin
private fun setupCameraCallbacks() {
    cameraView.onImageCaptured = { file ->
        // Handle captured image
        Toast.makeText(this, "Image saved: ${file.absolutePath}", Toast.LENGTH_LONG).show()
    }
    
    cameraView.onFocusStateChanged = { focused ->
        // Update UI based on focus state
        captureButton.alpha = if (focused) 1.0f else 0.7f
    }
}
```

### 3. Capture Image
```kotlin
captureButton.setOnClickListener {
    cameraView.captureImage()
}
```

## Permissions Required

Add these permissions to your AndroidManifest.xml:

```xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" />
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" />

<uses-feature android:name="android.hardware.camera.any" />
```

## Key Features Explained

### Autofocus Behavior
1. **Continuous Focus**: Camera continuously focuses on objects in the scene
2. **Touch Focus**: Tap within the oval to focus on specific points
3. **Focus Restriction**: Only allows focusing within the oval overlay area
4. **Visual Feedback**: Shows focus indicator with color coding:
   - **Yellow**: Focusing in progress
   - **Green**: Successfully focused

### Oval Overlay Design
- **Background Dimming**: Semi-transparent black overlay (50% opacity)
- **Clear Oval Area**: Transparent oval for object positioning
- **White Border**: Clear oval boundary with 4px stroke
- **Instruction Text**: User guidance below the oval

### Image Capture Process
1. **Focus Check**: Ensures proper focus before capture
2. **High Resolution**: Uses optimal resolution for image quality
3. **Background Processing**: Saves images on background thread
4. **File Naming**: Uses timestamp for unique filenames
5. **Callback Notification**: Notifies when save is complete

## Customization Options

### Oval Size and Position
Modify in `CameraUtils.getOvalBounds()`:
```kotlin
val ovalWidth = viewWidth * 0.7f  // Change width percentage
val ovalHeight = viewHeight * 0.5f // Change height percentage
```

### Focus Area Size
Modify in `Camera2SurfaceView`:
```kotlin
private var focusAreaSize = 200 // Change focus area size in pixels
```

### Overlay Colors
Modify paint objects in `Camera2SurfaceView`:
```kotlin
private val overlayPaint = Paint().apply {
    color = Color.WHITE // Change oval border color
    strokeWidth = 4f    // Change border thickness
}

private val backgroundPaint = Paint().apply {
    color = Color.BLACK // Change background color
    alpha = 128         // Change transparency (0-255)
}
```

## Error Handling

The implementation includes comprehensive error handling for:
- Camera access permissions
- Camera device errors
- Focus failures
- Image capture errors
- Background thread management

## Performance Considerations

- **Background Threading**: All camera operations run on background threads
- **Memory Management**: Proper cleanup of camera resources
- **Image Processing**: Efficient bitmap handling for focus analysis
- **UI Updates**: Main thread updates for smooth user experience

## Testing

To test the implementation:
1. Run the Camera2Activity
2. Grant camera permissions when prompted
3. Point camera at objects within the oval area
4. Tap to focus on specific areas
5. Press capture button to take photos
6. Check saved images in the app's external files directory

## Troubleshooting

### Common Issues:
1. **Black Screen**: Check camera permissions
2. **No Focus**: Ensure sufficient lighting and contrast
3. **Capture Fails**: Verify storage permissions
4. **App Crashes**: Check for proper camera resource cleanup

### Debug Tips:
- Enable debug logging by setting log level to DEBUG
- Check Logcat for camera-related error messages
- Verify all permissions are granted in device settings