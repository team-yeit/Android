package team.yeet.yeetapplication.ocr

import android.graphics.Bitmap
import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import java.io.ByteArrayOutputStream
import java.io.IOException

class OcrApiService {
    
    private val client = OkHttpClient.Builder()
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        })
        .build()
    
    private val gson = Gson()
    private val baseUrl = "https://yeit.ijw.app"
    
    companion object {
        private const val TAG = "OcrApiService"
    }
    
    // ===== 데이터 클래스 정의 =====
    
    data class OcrResponse(
        @SerializedName("success") val success: Boolean,
        @SerializedName("text_list") val textList: List<TextResult>,
        @SerializedName("total_count") val totalCount: Int,
        @SerializedName("message") val message: String? = null
    )
    
    data class TextResult(
        @SerializedName("text") val text: String,
        @SerializedName("x") val x: Int,
        @SerializedName("y") val y: Int
    )
    
    data class TextExtractRequest(
        @SerializedName("text") val text: String
    )
    
    data class TextExtractResponse(
        @SerializedName("result") val result: String
    )
    
    data class HealthResponse(
        @SerializedName("status") val status: String,
        @SerializedName("ocr") val ocr: Boolean
    )
    
    // ===== OCR API 메서드들 =====
    
    /**
     * 이미지에서 텍스트를 추출합니다
     * @param bitmap 분석할 이미지
     * @param filterType 필터링 타입 (null: 전체, "store": 가게이름, "food": 음식이름)
     * @return OCR 결과
     */
    suspend fun extractTextFromImage(
        bitmap: Bitmap,
        filterType: String? = null
    ): Result<OcrResponse> = withContext(Dispatchers.IO) {
        try {
            // 비트맵을 JPEG 바이트 배열로 변환
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)
            val imageBytes = stream.toByteArray()
            
            // Multipart 요청 생성
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "image",
                    "image.jpg",
                    imageBytes.toRequestBody("image/jpeg".toMediaTypeOrNull())
                )
                .build()
            
            // URL 구성
            val urlBuilder = "$baseUrl/image/extract".toHttpUrlOrNull()?.newBuilder()
            filterType?.let {
                urlBuilder?.addQueryParameter("type", it)
            }
            
            val request = Request.Builder()
                .url(urlBuilder?.build()!!)
                .post(requestBody)
                .build()
            
            Log.d(TAG, "Sending OCR request to: ${request.url}")
            
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()
            
            Log.d(TAG, "OCR response code: ${response.code}")
            Log.d(TAG, "OCR response body: $responseBody")
            
            if (response.isSuccessful && responseBody != null) {
                val ocrResponse = gson.fromJson(responseBody, OcrResponse::class.java)
                Result.success(ocrResponse)
            } else {
                Result.failure(IOException("OCR request failed: ${response.code} - $responseBody"))
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "OCR request error", e)
            Result.failure(e)
        }
    }
    
    /**
     * 더듬거리는 텍스트에서 원하는 정보를 추출합니다
     * @param text 더듬거리는 텍스트
     * @param extractType 추출 타입 ("store": 가게이름, "number": 숫자, "food": 음식이름)
     * @return 추출된 결과
     */
    suspend fun extractFromText(
        text: String,
        extractType: String
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val requestBody = gson.toJson(TextExtractRequest(text))
                .toRequestBody("application/json".toMediaTypeOrNull())
            
            val url = "$baseUrl/text/extract".toHttpUrlOrNull()?.newBuilder()
                ?.addQueryParameter("type", extractType)
                ?.build()
            
            val request = Request.Builder()
                .url(url!!)
                .post(requestBody)
                .build()
            
            Log.d(TAG, "Sending text extract request: $text -> $extractType")
            
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()
            
            Log.d(TAG, "Text extract response: ${response.code} - $responseBody")
            
            if (response.isSuccessful && responseBody != null) {
                val extractResponse = gson.fromJson(responseBody, TextExtractResponse::class.java)
                Result.success(extractResponse.result)
            } else {
                Result.failure(IOException("Text extract failed: ${response.code} - $responseBody"))
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Text extract error", e)
            Result.failure(e)
        }
    }
    
    /**
     * 서비스 상태를 확인합니다
     */
    suspend fun checkHealth(): Result<HealthResponse> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$baseUrl/health")
                .get()
                .build()
            
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()
            
            if (response.isSuccessful && responseBody != null) {
                val healthResponse = gson.fromJson(responseBody, HealthResponse::class.java)
                Result.success(healthResponse)
            } else {
                Result.failure(IOException("Health check failed: ${response.code}"))
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Health check error", e)
            Result.failure(e)
        }
    }
    
    // ===== 편의 메서드들 =====
    
    /**
     * 가게 이름 추출 (이미지)
     */
    suspend fun extractStoreFromImage(bitmap: Bitmap): Result<List<String>> {
        return extractTextFromImage(bitmap, "store").map { response ->
            if (response.success) {
                response.textList.map { it.text }
            } else {
                emptyList()
            }
        }
    }
    
    /**
     * 음식 이름 추출 (이미지)
     */
    suspend fun extractFoodFromImage(bitmap: Bitmap): Result<List<String>> {
        return extractTextFromImage(bitmap, "food").map { response ->
            if (response.success) {
                response.textList.map { it.text }
            } else {
                emptyList()
            }
        }
    }
    
    /**
     * 가게 이름 정제 (음성 인식 텍스트)
     */
    suspend fun refineStoreName(speechText: String): Result<String> {
        return extractFromText(speechText, "store")
    }
    
    /**
     * 음식 이름 정제 (음성 인식 텍스트)
     */
    suspend fun refineFoodName(speechText: String): Result<String> {
        return extractFromText(speechText, "food")
    }
    
    /**
     * 숫자 추출 (음성 인식 텍스트)
     */
    suspend fun extractNumber(speechText: String): Result<String> {
        return extractFromText(speechText, "number")
    }
}