package com.facedetectiondemo.camera2

import android.graphics.*
import android.util.Size
import kotlin.math.max
import kotlin.math.min

object CameraUtils {
    
    /**
     * Checks if a point is within the oval overlay area
     */
    fun isPointInOval(x: Float, y: Float, viewWidth: Int, viewHeight: Int): Boolean {
        val centerX = viewWidth / 2f
        val centerY = viewHeight / 2f
        val ovalWidth = viewWidth * 0.7f
        val ovalHeight = viewHeight * 0.5f
        
        val a = ovalWidth / 2f
        val b = ovalHeight / 2f
        
        val dx = x - centerX
        val dy = y - centerY
        
        return (dx * dx) / (a * a) + (dy * dy) / (b * b) <= 1
    }
    
    /**
     * Gets the oval bounds for the overlay
     */
    fun getOvalBounds(viewWidth: Int, viewHeight: Int): RectF {
        val centerX = viewWidth / 2f
        val centerY = viewHeight / 2f
        val ovalWidth = viewWidth * 0.7f
        val ovalHeight = viewHeight * 0.5f
        
        return RectF(
            centerX - ovalWidth / 2,
            centerY - ovalHeight / 2,
            centerX + ovalWidth / 2,
            centerY + ovalHeight / 2
        )
    }
    
    /**
     * Creates a focus area rectangle around a point
     */
    fun createFocusArea(x: Float, y: Float, viewWidth: Int, viewHeight: Int, 
                       sensorWidth: Int, sensorHeight: Int, focusSize: Int = 200): Rect {
        
        // Convert view coordinates to sensor coordinates
        val sensorX = (x / viewWidth * sensorWidth).toInt()
        val sensorY = (y / viewHeight * sensorHeight).toInt()
        
        val halfSize = focusSize / 2
        
        return Rect(
            max(sensorX - halfSize, 0),
            max(sensorY - halfSize, 0),
            min(sensorX + halfSize, sensorWidth),
            min(sensorY + halfSize, sensorHeight)
        )
    }
    
    /**
     * Analyzes if there's sufficient contrast in the focus area for good focus
     */
    fun analyzeFocusQuality(bitmap: Bitmap?, focusRect: Rect): Float {
        if (bitmap == null) return 0f
        
        try {
            // Create a smaller bitmap from the focus area
            val focusBitmap = Bitmap.createBitmap(
                bitmap,
                max(0, focusRect.left),
                max(0, focusRect.top),
                min(focusRect.width(), bitmap.width - focusRect.left),
                min(focusRect.height(), bitmap.height - focusRect.top)
            )
            
            // Calculate variance (contrast measure)
            val pixels = IntArray(focusBitmap.width * focusBitmap.height)
            focusBitmap.getPixels(pixels, 0, focusBitmap.width, 0, 0, 
                                focusBitmap.width, focusBitmap.height)
            
            var sum = 0.0
            var sumSquares = 0.0
            
            for (pixel in pixels) {
                val gray = (Color.red(pixel) + Color.green(pixel) + Color.blue(pixel)) / 3
                sum += gray
                sumSquares += gray * gray
            }
            
            val mean = sum / pixels.size
            val variance = (sumSquares / pixels.size) - (mean * mean)
            
            focusBitmap.recycle()
            
            // Normalize variance to 0-1 range (higher = better focus)
            return (variance / 10000f).coerceIn(0f, 1f)
            
        } catch (e: Exception) {
            return 0f
        }
    }
    
    /**
     * Creates a paint for drawing focus indicators
     */
    fun createFocusPaint(focused: Boolean): Paint {
        return Paint().apply {
            color = if (focused) Color.GREEN else Color.YELLOW
            style = Paint.Style.STROKE
            strokeWidth = 3f
            isAntiAlias = true
        }
    }
    
    /**
     * Draws a focus indicator at the specified location
     */
    fun drawFocusIndicator(canvas: Canvas, x: Float, y: Float, focused: Boolean, size: Float = 100f) {
        val paint = createFocusPaint(focused)
        
        // Draw focus square
        canvas.drawRect(
            x - size / 2,
            y - size / 2,
            x + size / 2,
            y + size / 2,
            paint
        )
        
        // Draw corner indicators
        val cornerSize = size / 6
        canvas.drawLine(x - size / 2, y - size / 2, x - size / 2 + cornerSize, y - size / 2, paint)
        canvas.drawLine(x - size / 2, y - size / 2, x - size / 2, y - size / 2 + cornerSize, paint)
        
        canvas.drawLine(x + size / 2, y - size / 2, x + size / 2 - cornerSize, y - size / 2, paint)
        canvas.drawLine(x + size / 2, y - size / 2, x + size / 2, y - size / 2 + cornerSize, paint)
        
        canvas.drawLine(x - size / 2, y + size / 2, x - size / 2 + cornerSize, y + size / 2, paint)
        canvas.drawLine(x - size / 2, y + size / 2, x - size / 2, y + size / 2 - cornerSize, paint)
        
        canvas.drawLine(x + size / 2, y + size / 2, x + size / 2 - cornerSize, y + size / 2, paint)
        canvas.drawLine(x + size / 2, y + size / 2, x + size / 2, y + size / 2 - cornerSize, paint)
    }
    
    /**
     * Calculates optimal preview size
     */
    fun chooseOptimalSize(
        choices: Array<Size>,
        textureViewWidth: Int,
        textureViewHeight: Int,
        maxWidth: Int,
        maxHeight: Int
    ): Size {
        val bigEnough = mutableListOf<Size>()
        val notBigEnough = mutableListOf<Size>()
        val w = textureViewWidth
        val h = textureViewHeight
        
        for (option in choices) {
            if (option.width <= maxWidth && option.height <= maxHeight) {
                if (option.width >= textureViewWidth && option.height >= textureViewHeight) {
                    bigEnough.add(option)
                } else {
                    notBigEnough.add(option)
                }
            }
        }

        return when {
            bigEnough.isNotEmpty() -> bigEnough.minByOrNull { it.width * it.height } ?: choices[0]
            notBigEnough.isNotEmpty() -> notBigEnough.maxByOrNull { it.width * it.height } ?: choices[0]
            else -> choices[0]
        }
    }
}