package team.yeet.yeetapplication.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class CameraManager(private val context: Context) {
    
    private var imageCapture: ImageCapture? = null
    private var cameraProvider: ProcessCameraProvider? = null
    
    companion object {
        private const val TAG = "CameraManager"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
    }
    
    /**
     * 카메라 권한이 있는지 확인
     */
    fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    /**
     * 카메라 초기화
     */
    suspend fun initializeCamera(
        lifecycleOwner: LifecycleOwner,
        preview: Preview? = null
    ): Result<Unit> = withContext(Dispatchers.Main) {
        try {
            if (!hasCameraPermission()) {
                return@withContext Result.failure(SecurityException("Camera permission not granted"))
            }
            
            // ProcessCameraProvider 가져오기
            cameraProvider = suspendCoroutine { continuation ->
                val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
                cameraProviderFuture.addListener({
                    continuation.resume(cameraProviderFuture.get())
                }, ContextCompat.getMainExecutor(context))
            }
            
            // ImageCapture 설정
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            
            // 카메라 셀렉터 (후면 카메라 기본)
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            
            try {
                // 기존 바인딩 해제
                cameraProvider?.unbindAll()
                
                // 카메라를 라이프사이클에 바인드
                val useCases = mutableListOf<UseCase>().apply {
                    imageCapture?.let { add(it) }
                    preview?.let { add(it) }
                }
                
                cameraProvider?.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    *useCases.toTypedArray()
                )
                
                Log.d(TAG, "Camera initialized successfully")
                Result.success(Unit)
                
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
                Result.failure(exc)
            }
            
        } catch (exc: Exception) {
            Log.e(TAG, "Camera initialization failed", exc)
            Result.failure(exc)
        }
    }
    
    /**
     * 사진 촬영 (파일로 저장)
     */
    suspend fun takePhotoToFile(): Result<File> = withContext(Dispatchers.IO) {
        try {
            val imageCapture = this@CameraManager.imageCapture
                ?: return@withContext Result.failure(IllegalStateException("Camera not initialized"))
            
            // 파일 생성
            val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US)
                .format(System.currentTimeMillis())
            val photoFile = File(
                context.getExternalFilesDir(Environment.DIRECTORY_PICTURES),
                "$name.jpg"
            )
            
            val outputFileOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()
            
            // 촬영 실행
            suspendCoroutine<ImageCapture.OutputFileResults> { continuation ->
                imageCapture.takePicture(
                    outputFileOptions,
                    ContextCompat.getMainExecutor(context),
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onError(exception: ImageCaptureException) {
                            Log.e(TAG, "Photo capture failed: ${exception.message}", exception)
                            continuation.resume(
                                ImageCapture.OutputFileResults(
                                    Uri.fromFile(photoFile)
                                )
                            )
                        }
                        
                        override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                            Log.d(TAG, "Photo capture succeeded: ${output.savedUri}")
                            continuation.resume(output)
                        }
                    }
                )
            }
            
            if (photoFile.exists()) {
                Log.d(TAG, "Photo saved to: ${photoFile.absolutePath}")
                Result.success(photoFile)
            } else {
                Result.failure(Exception("Photo file was not created"))
            }
            
        } catch (exc: Exception) {
            Log.e(TAG, "Photo capture failed", exc)
            Result.failure(exc)
        }
    }
    
    /**
     * 사진 촬영 (Bitmap으로 반환)
     */
    suspend fun takePhotoToBitmap(): Result<Bitmap> = withContext(Dispatchers.IO) {
        try {
            val photoFile = takePhotoToFile().getOrThrow()
            val bitmap = loadBitmapFromFile(photoFile)
            
            // 임시 파일 삭제
            photoFile.delete()
            
            bitmap?.let { 
                Result.success(it) 
            } ?: Result.failure(Exception("Failed to load bitmap from photo"))
            
        } catch (exc: Exception) {
            Log.e(TAG, "Photo to bitmap failed", exc)
            Result.failure(exc)
        }
    }
    
    /**
     * 파일에서 Bitmap 로드 (회전 보정 포함)
     */
    private fun loadBitmapFromFile(file: File): Bitmap? {
        return try {
            // 원본 비트맵 로드
            var bitmap = BitmapFactory.decodeFile(file.absolutePath)
            
            // EXIF 정보에서 회전 각도 확인
            val exif = ExifInterface(file.absolutePath)
            val orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_UNDEFINED
            )
            
            // 회전 각도에 따라 비트맵 회전
            bitmap = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> rotateBitmap(bitmap, 90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> rotateBitmap(bitmap, 180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> rotateBitmap(bitmap, 270f)
                else -> bitmap
            }
            
            Log.d(TAG, "Bitmap loaded: ${bitmap.width}x${bitmap.height}")
            bitmap
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load bitmap from file", e)
            null
        }
    }
    
    /**
     * 비트맵 회전
     */
    private fun rotateBitmap(bitmap: Bitmap, angle: Float): Bitmap {
        val matrix = Matrix()
        matrix.postRotate(angle)
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
    
    /**
     * 비트맵을 파일로 저장
     */
    suspend fun saveBitmapToFile(bitmap: Bitmap, filename: String? = null): Result<File> = withContext(Dispatchers.IO) {
        try {
            val name = filename ?: SimpleDateFormat(FILENAME_FORMAT, Locale.US)
                .format(System.currentTimeMillis())
            val file = File(
                context.getExternalFilesDir(Environment.DIRECTORY_PICTURES),
                "$name.jpg"
            )
            
            val outputStream = FileOutputStream(file)
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
            outputStream.flush()
            outputStream.close()
            
            Log.d(TAG, "Bitmap saved to: ${file.absolutePath}")
            Result.success(file)
            
        } catch (exc: Exception) {
            Log.e(TAG, "Failed to save bitmap", exc)
            Result.failure(exc)
        }
    }
    
    /**
     * 카메라 리소스 해제
     */
    fun release() {
        try {
            cameraProvider?.unbindAll()
            cameraProvider = null
            imageCapture = null
            Log.d(TAG, "Camera resources released")
        } catch (exc: Exception) {
            Log.e(TAG, "Failed to release camera resources", exc)
        }
    }
    
    /**
     * 비트맵 크기 조정 (OCR 성능 최적화용)
     */
    fun resizeBitmapForOcr(bitmap: Bitmap, maxSize: Int = 1024): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        
        if (width <= maxSize && height <= maxSize) {
            return bitmap
        }
        
        val ratio = if (width > height) {
            maxSize.toFloat() / width
        } else {
            maxSize.toFloat() / height
        }
        
        val newWidth = (width * ratio).toInt()
        val newHeight = (height * ratio).toInt()
        
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }
}