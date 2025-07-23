package team.yeet.yeetapplication.screenshot

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import kotlin.coroutines.resume

class ScreenshotManager(private val context: Context) {
    
    private var mediaProjection: MediaProjection? = null
    private var mediaProjectionManager: MediaProjectionManager? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    
    companion object {
        private const val TAG = "ScreenshotManager"
        const val SCREENSHOT_REQUEST_CODE = 1001
    }
    
    init {
        mediaProjectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }
    
    /**
     * 스크린샷 권한 요청 Intent 생성
     */
    fun createScreenCaptureIntent(): Intent {
        return mediaProjectionManager?.createScreenCaptureIntent() 
            ?: throw IllegalStateException("MediaProjectionManager is null")
    }
    
    /**
     * MediaProjection 초기화
     */
    fun initializeMediaProjection(resultCode: Int, data: Intent): Boolean {
        return try {
            mediaProjection = mediaProjectionManager?.getMediaProjection(resultCode, data)
            mediaProjection != null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize MediaProjection", e)
            false
        }
    }
    
    /**
     * 화면 스크린샷 촬영
     */
    suspend fun takeScreenshot(): Result<Bitmap> = withContext(Dispatchers.IO) {
        try {
            val projection = mediaProjection 
                ?: return@withContext Result.failure(IllegalStateException("MediaProjection not initialized"))
            
            val displayMetrics = context.resources.displayMetrics
            val width = displayMetrics.widthPixels
            val height = displayMetrics.heightPixels
            val density = displayMetrics.densityDpi
            
            Log.d(TAG, "Screenshot dimensions: ${width}x${height}, density: $density")
            
            // ImageReader 설정
            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 1)
            
            // VirtualDisplay 생성
            virtualDisplay = projection.createVirtualDisplay(
                "ScreenCapture",
                width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null, null
            )
            
            // 스크린샷 촬영 대기
            val bitmap = suspendCancellableCoroutine<Bitmap> { continuation ->
                val handler = Handler(Looper.getMainLooper())
                
                imageReader?.setOnImageAvailableListener({ reader ->
                    try {
                        val image = reader?.acquireLatestImage()
                        if (image != null) {
                            val bitmap = imageToBitmap(image, width, height)
                            image.close()
                            
                            if (!continuation.isCompleted) {
                                continuation.resume(bitmap)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing screenshot", e)
                        if (!continuation.isCompleted) {
                            continuation.resume(createErrorBitmap(width, height))
                        }
                    }
                }, handler)
                
                // 타임아웃 설정 (5초)
                handler.postDelayed({
                    if (!continuation.isCompleted) {
                        Log.w(TAG, "Screenshot timeout")
                        continuation.resume(createErrorBitmap(width, height))
                    }
                }, 5000)
                
                // 취소 시 리소스 정리
                continuation.invokeOnCancellation {
                    cleanupVirtualDisplay()
                }
            }
            
            // 가상 디스플레이 정리
            cleanupVirtualDisplay()
            
            Log.d(TAG, "Screenshot captured successfully: ${bitmap.width}x${bitmap.height}")
            Result.success(bitmap)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to take screenshot", e)
            cleanupVirtualDisplay()
            Result.failure(e)
        }
    }
    
    /**
     * Image를 Bitmap으로 변환
     */
    private fun imageToBitmap(image: Image, width: Int, height: Int): Bitmap {
        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * width
        
        val bitmap = Bitmap.createBitmap(
            width + rowPadding / pixelStride, 
            height, 
            Bitmap.Config.ARGB_8888
        )
        
        bitmap.copyPixelsFromBuffer(buffer)
        
        // 패딩이 있는 경우 크롭
        return if (rowPadding == 0) {
            bitmap
        } else {
            Bitmap.createBitmap(bitmap, 0, 0, width, height)
        }
    }
    
    /**
     * 오류 시 표시할 더미 비트맵 생성
     */
    private fun createErrorBitmap(width: Int, height: Int): Bitmap {
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.RED)
        }
    }
    
    /**
     * 가상 디스플레이 정리
     */
    private fun cleanupVirtualDisplay() {
        virtualDisplay?.release()
        virtualDisplay = null
        
        imageReader?.close()
        imageReader = null
    }
    
    /**
     * 스크린샷을 파일로 저장
     */
    suspend fun saveScreenshotToFile(bitmap: Bitmap, filename: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val file = java.io.File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES), "$filename.png")
            val outputStream = FileOutputStream(file)
            
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
            outputStream.flush()
            outputStream.close()
            
            Log.d(TAG, "Screenshot saved to: ${file.absolutePath}")
            Result.success(file.absolutePath)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save screenshot", e)
            Result.failure(e)
        }
    }
    
    /**
     * 스크린샷을 Base64로 인코딩
     */
    fun bitmapToBase64(bitmap: Bitmap): String {
        val byteArrayOutputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, byteArrayOutputStream)
        val byteArray = byteArrayOutputStream.toByteArray()
        return android.util.Base64.encodeToString(byteArray, android.util.Base64.DEFAULT)
    }
    
    /**
     * 배달앱 화면 분석을 위한 스크린샷 최적화
     */
    fun optimizeForDeliveryApp(bitmap: Bitmap): Bitmap {
        // 화면 크기 조정 (OCR 성능 최적화)
        val maxSize = 1080
        val width = bitmap.width
        val height = bitmap.height
        
        return if (width > maxSize || height > maxSize) {
            val ratio = minOf(maxSize.toFloat() / width, maxSize.toFloat() / height)
            val newWidth = (width * ratio).toInt()
            val newHeight = (height * ratio).toInt()
            
            Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
        } else {
            bitmap
        }
    }
    
    /**
     * 스크린샷 권한 확인
     */
    fun hasScreenshotPermission(): Boolean {
        return mediaProjection != null
    }
    
    /**
     * 리소스 해제
     */
    fun release() {
        cleanupVirtualDisplay()
        mediaProjection?.stop()
        mediaProjection = null
    }
    
    /**
     * 빠른 스크린샷 + OCR 분석 (편의 메서드)
     */
    suspend fun captureAndAnalyze(ocrService: team.yeet.yeetapplication.ocr.OcrApiService, filterType: String? = null): Result<team.yeet.yeetapplication.ocr.OcrApiService.OcrResponse> {
        return try {
            // 스크린샷 촬영
            val screenshotResult = takeScreenshot()
            if (screenshotResult.isFailure) {
                return Result.failure(screenshotResult.exceptionOrNull() ?: Exception("Screenshot failed"))
            }
            
            val bitmap = screenshotResult.getOrThrow()
            val optimizedBitmap = optimizeForDeliveryApp(bitmap)
            
            // OCR 분석
            ocrService.extractTextFromImage(optimizedBitmap, filterType)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to capture and analyze", e)
            Result.failure(e)
        }
    }
}