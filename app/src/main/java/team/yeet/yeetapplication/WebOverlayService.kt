package team.yeet.yeetapplication

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Path
import android.media.MediaMetadataRetriever
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Base64
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import team.yeet.yeetapplication.ocr.OcrApiService
import team.yeet.yeetapplication.camera.CameraManager
import team.yeet.yeetapplication.screenshot.ScreenshotManager
import java.io.ByteArrayOutputStream
import java.util.*

class WebOverlayService : LifecycleService() {
    
    private lateinit var windowManager: WindowManager
    private var webView: WebView? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private var mediaProjection: MediaProjection? = null
    private var isTouchable = false // 터치 모드 상태
    
    // OCR, 카메라 및 스크린샷 관련
    private lateinit var ocrApiService: OcrApiService
    private lateinit var cameraManager: CameraManager
    private lateinit var screenshotManager: ScreenshotManager
    
    private val hardcodedStores = mapOf(
        "맥도날드" to listOf("빅맥", "치킨버거", "감자튀김", "콜라", "맥너겟"),
        "버거킹" to listOf("와퍼", "치킨킹", "어니언링", "사이다", "프라이"),
        "피자헛" to listOf("슈퍼슈프림", "치즈피자", "페퍼로니", "콜라", "갈릭브레드"),
        "치킨매니아" to listOf("후라이드치킨", "양념치킨", "간장치킨", "맥주", "무"),
        "떡볶이왕" to listOf("떡볶이", "순대", "튀김", "김밥", "어묵"),
        "짜장면집" to listOf("짜장면", "짬뽕", "탕수육", "군만두", "볶음밥"),
        "김밥천국" to listOf("참치김밥", "불고기김밥", "계란김밥", "라면", "우동")
    )
    
    private var selectedStore = ""
    
    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        
        // OCR, 카메라 및 스크린샷 서비스 초기화
        ocrApiService = OcrApiService()
        cameraManager = CameraManager(this)
        screenshotManager = ScreenshotManager(this)
        
        initializeSpeechRecognizer()
        
        // 기존 앱들 모두 백그라운드로 보내기 (홈으로 이동)
        goToHomeScreen()
        
        createWebViewOverlay()
    }
    
    // onBind is already implemented by LifecycleService
    
    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebViewOverlay() {
        try {
            val displayMetrics = resources.displayMetrics
            val screenWidth = displayMetrics.widthPixels
            val screenHeight = displayMetrics.heightPixels
            
            // WebView 생성 및 설정
            webView = WebView(applicationContext).apply {
                // WebView 기본 설정
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = true
                settings.allowContentAccess = true
                settings.mediaPlaybackRequiresUserGesture = false
                settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
                
                // 투명 배경 설정
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                
                // 디버깅 활성화 (개발 시에만)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT) {
                    android.webkit.WebView.setWebContentsDebuggingEnabled(true)
                }
                
                // JavaScript 인터페이스 추가
                addJavascriptInterface(AndroidBridge(), "Android")
                
                // WebViewClient 설정
                webViewClient = object : android.webkit.WebViewClient() {
                    override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        android.util.Log.d("WebOverlayService", "Page loaded: $url")
                        
                        // 페이지 로딩 완료 후 오버레이 초기화
                        view?.postDelayed({
                            view.evaluateJavascript("""
                                console.log('=== OVERLAY SERVICE: Initializing overlay ===');
                                if (typeof window.initializeOverlay === 'function') {
                                    window.initializeOverlay();
                                    console.log('=== OVERLAY SERVICE: Overlay initialized ===');
                                } else {
                                    console.error('=== OVERLAY SERVICE: initializeOverlay not found ===');
                                    console.log('Available functions:', Object.keys(window));
                                }
                            """.trimIndent()) { result ->
                                android.util.Log.d("WebOverlayService", "Overlay init result: $result")
                            }
                        }, 1500) // 1.5초 지연
                    }
                }
                
                webChromeClient = object : android.webkit.WebChromeClient() {
                    override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
                        android.util.Log.d("WebOverlayService", "Console: ${consoleMessage?.message()}")
                        return true
                    }
                }
                
                // HTML 파일 로드
                loadUrl("file:///android_asset/index.html")
            }
            
            // 작은 정사각형 오버레이 설정
            val overlaySize = (200 * displayMetrics.density).toInt() // 200dp를 픽셀로 변환
            
            val params = WindowManager.LayoutParams(
                overlaySize, // 정사각형 폭
                overlaySize, // 정사각형 높이
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or // 포커스 받지 않음
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or // 터치 모달 아님
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or // 터치 이벤트를 받지 않음 (아래 앱이 터치 받음)
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED, // 하드웨어 가속
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL // 상단 중앙
                x = 0
                y = (50 * displayMetrics.density).toInt() // 상단에서 50dp 아래
            }
            
            // 윈도우 매니저에 WebView 추가
            windowManager.addView(webView, params)
            
            android.util.Log.d("WebOverlayService", "Overlay WebView created successfully")
            
        } catch (e: Exception) {
            android.util.Log.e("WebOverlayService", "Failed to create overlay WebView", e)
            runOnUIThread {
                Toast.makeText(this@WebOverlayService, "오버레이 생성 실패: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
    
    private fun initializeSpeechRecognizer() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                isListening = true
                runOnUIThread { 
                    webView?.evaluateJavascript("onSpeechStart();", null)
                }
            }
            
            override fun onBeginningOfSpeech() {
                runOnUIThread { 
                    webView?.evaluateJavascript("onSpeechBegin();", null)
                }
            }
            
            override fun onRmsChanged(rmsdB: Float) {
                runOnUIThread { 
                    webView?.evaluateJavascript("onVolumeChange($rmsdB);", null)
                }
            }
            
            override fun onBufferReceived(buffer: ByteArray?) {}
            
            override fun onEndOfSpeech() {
                isListening = false
                runOnUIThread { 
                    webView?.evaluateJavascript("onSpeechEnd();", null)
                }
            }
            
            override fun onError(error: Int) {
                isListening = false
                val errorMessage = when (error) {
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "네트워크 연결을 확인해주세요"
                    SpeechRecognizer.ERROR_NO_MATCH -> "음성을 인식하지 못했어요"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "음성 입력 시간이 초과되었어요"
                    else -> "음성 인식에 문제가 있어요"
                }
                runOnUIThread { 
                    webView?.evaluateJavascript("onSpeechError('$errorMessage');", null)
                }
            }
            
            override fun onResults(results: Bundle?) {
                isListening = false
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (matches != null && matches.isNotEmpty()) {
                    val recognizedText = matches[0]
                    runOnUIThread { 
                        webView?.evaluateJavascript("onSpeechResult('$recognizedText');", null)
                    }
                }
            }
            
            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (matches != null && matches.isNotEmpty()) {
                    val partialText = matches[0]
                    runOnUIThread { 
                        webView?.evaluateJavascript("onPartialResult('$partialText');", null)
                    }
                }
            }
            
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }
    
    private fun runOnUIThread(action: () -> Unit) {
        Handler(Looper.getMainLooper()).post(action)
    }
    
    private fun updateTouchMode() {
        runOnUIThread {
            val params = webView?.layoutParams as? WindowManager.LayoutParams
            params?.let {
                if (isTouchable) {
                    // 터치 가능 모드: FLAG_NOT_TOUCHABLE 제거
                    it.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                } else {
                    // 터치 불가능 모드: FLAG_NOT_TOUCHABLE 추가
                    it.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                }
                windowManager.updateViewLayout(webView, it)
                android.util.Log.d("WebOverlay", "Touch mode updated: touchable=$isTouchable")
            }
        }
    }
    
    private fun goToHomeScreen() {
        try {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
        } catch (e: Exception) {
            android.util.Log.e("WebOverlay", "Failed to go home: ${e.message}")
        }
    }
    
    inner class AndroidBridge {
        
        // ========== 음성 인식 API ==========
        @JavascriptInterface
        fun startVoiceRecognition() {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.KOREAN)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            }
            speechRecognizer?.startListening(intent)
        }
        
        @JavascriptInterface
        fun stopVoiceRecognition() {
            speechRecognizer?.cancel()
            isListening = false
        }
        
        @JavascriptInterface
        fun isVoiceRecognitionAvailable(): Boolean {
            return SpeechRecognizer.isRecognitionAvailable(this@WebOverlayService)
        }
        
        // ========== 데이터 검색 API ==========
        @JavascriptInterface
        fun searchStore(storeName: String): String {
            val foundStore = hardcodedStores.keys.find { 
                it.contains(storeName, ignoreCase = true) || storeName.contains(it, ignoreCase = true)
            }
            
            if (foundStore != null) {
                selectedStore = foundStore
                return foundStore
            }
            return ""
        }
        
        @JavascriptInterface
        fun searchMenu(menuName: String): String {
            val storeMenus = hardcodedStores[selectedStore] ?: emptyList()
            val foundMenus = storeMenus.filter { 
                it.contains(menuName, ignoreCase = true) || menuName.contains(it, ignoreCase = true)
            }
            return foundMenus.joinToString(",")
        }
        
        @JavascriptInterface
        fun getAllStores(): String {
            return hardcodedStores.keys.joinToString(",")
        }
        
        @JavascriptInterface
        fun getStoreMenus(storeName: String): String {
            return hardcodedStores[storeName]?.joinToString(",") ?: ""
        }
        
        @JavascriptInterface
        fun getSelectedStore(): String {
            return selectedStore
        }
        
        // ========== 화면 및 터치 API ==========
        @JavascriptInterface
        fun getScreenSize(): String {
            val displayMetrics = resources.displayMetrics
            return "${displayMetrics.widthPixels},${displayMetrics.heightPixels}"
        }
        
        @JavascriptInterface
        fun getScreenDensity(): Float {
            return resources.displayMetrics.density
        }
        
        @JavascriptInterface
        fun simulateTouch(x: Int, y: Int): Boolean {
            try {
                val downEvent = MotionEvent.obtain(
                    System.currentTimeMillis(),
                    System.currentTimeMillis(),
                    MotionEvent.ACTION_DOWN,
                    x.toFloat(),
                    y.toFloat(),
                    0
                )
                
                val upEvent = MotionEvent.obtain(
                    System.currentTimeMillis(),
                    System.currentTimeMillis() + 100,
                    MotionEvent.ACTION_UP,
                    x.toFloat(),
                    y.toFloat(),
                    0
                )
                
                // 실제 터치 시뮬레이션을 위해서는 Accessibility Service나 Root 권한이 필요
                // 현재는 로그만 출력
                log("Touch simulated at ($x, $y)")
                
                downEvent.recycle()
                upEvent.recycle()
                
                return true
            } catch (e: Exception) {
                log("Touch simulation failed: ${e.message}")
                return false
            }
        }
        
        @JavascriptInterface
        fun simulateLongPress(x: Int, y: Int, duration: Long): Boolean {
            try {
                log("Long press simulated at ($x, $y) for ${duration}ms")
                return true
            } catch (e: Exception) {
                log("Long press simulation failed: ${e.message}")
                return false
            }
        }
        
        @JavascriptInterface
        fun simulateSwipe(startX: Int, startY: Int, endX: Int, endY: Int, duration: Long): Boolean {
            try {
                log("Swipe simulated from ($startX, $startY) to ($endX, $endY)")
                return true
            } catch (e: Exception) {
                log("Swipe simulation failed: ${e.message}")
                return false
            }
        }
        
        // ========== 스크린샷 API ==========
        @JavascriptInterface
        fun takeScreenshot(): String {
            try {
                // 스크린샷을 위해서는 MediaProjection이 필요
                // 현재는 더미 데이터 반환
                log("Screenshot requested")
                
                // 실제 구현에서는 MediaProjection을 사용하여 스크린샷 촬영
                // val bitmap = captureScreen()
                // return bitmapToBase64(bitmap)
                
                return "screenshot_placeholder_base64"
            } catch (e: Exception) {
                log("Screenshot failed: ${e.message}")
                return ""
            }
        }
        
        @JavascriptInterface
        fun takeScreenshotRegion(x: Int, y: Int, width: Int, height: Int): String {
            try {
                log("Region screenshot requested: ($x, $y, $width, $height)")
                return "region_screenshot_placeholder_base64"
            } catch (e: Exception) {
                log("Region screenshot failed: ${e.message}")
                return ""
            }
        }
        
        // ========== 터치 모드 제어 API ==========
        @JavascriptInterface
        fun enableTouchMode() {
            isTouchable = true
            updateTouchMode()
        }
        
        @JavascriptInterface
        fun disableTouchMode() {
            isTouchable = false
            updateTouchMode()
        }
        
        @JavascriptInterface
        fun toggleTouchMode() {
            isTouchable = !isTouchable
            updateTouchMode()
        }
        
        @JavascriptInterface
        fun isTouchModeEnabled(): Boolean {
            return isTouchable
        }
        
        // ========== 오버레이 제어 API ==========
        @JavascriptInterface
        fun updatePosition(x: Int, y: Int) {
            runOnUIThread {
                val params = webView?.layoutParams as? WindowManager.LayoutParams
                params?.let {
                    it.x = x
                    it.y = y
                    windowManager.updateViewLayout(webView, it)
                }
            }
        }
        
        @JavascriptInterface
        fun updateSize(width: Int, height: Int) {
            runOnUIThread {
                val params = webView?.layoutParams as? WindowManager.LayoutParams
                params?.let {
                    val density = resources.displayMetrics.density
                    it.width = (width * density).toInt()
                    it.height = (height * density).toInt()
                    windowManager.updateViewLayout(webView, it)
                }
            }
        }
        
        @JavascriptInterface
        fun setOverlayOpacity(opacity: Float) {
            runOnUIThread {
                webView?.alpha = opacity.coerceIn(0.0f, 1.0f)
            }
        }
        
        @JavascriptInterface
        fun hideOverlay() {
            runOnUIThread {
                webView?.visibility = View.GONE
            }
        }
        
        @JavascriptInterface
        fun showOverlay() {
            runOnUIThread {
                webView?.visibility = View.VISIBLE
            }
        }
        
        // ========== 시스템 정보 API ==========
        @JavascriptInterface
        fun getDeviceInfo(): String {
            return "${android.os.Build.MANUFACTURER},${android.os.Build.MODEL},${android.os.Build.VERSION.RELEASE}"
        }
        
        @JavascriptInterface
        fun getBatteryLevel(): Int {
            return 100 // 실제 구현에서는 BatteryManager 사용
        }
        
        @JavascriptInterface
        fun isWifiConnected(): Boolean {
            return true // 실제 구현에서는 ConnectivityManager 사용
        }
        
        @JavascriptInterface
        fun getCurrentTime(): Long {
            return System.currentTimeMillis()
        }
        
        // ========== 권한 API ==========
        @JavascriptInterface
        fun hasPermission(permission: String): Boolean {
            return ContextCompat.checkSelfPermission(this@WebOverlayService, permission) == 
                   PackageManager.PERMISSION_GRANTED
        }
        
        @JavascriptInterface
        fun hasOverlayPermission(): Boolean {
            return android.provider.Settings.canDrawOverlays(this@WebOverlayService)
        }
        
        @JavascriptInterface
        fun hasAccessibilityPermission(): Boolean {
            // AccessibilityService 활성화 여부 확인
            return false // 실제 구현 필요
        }
        
        // ========== 알림 API ==========
        @JavascriptInterface
        fun showToast(message: String) {
            showToast(message, 2000)
        }
        
        @JavascriptInterface
        fun showToast(message: String, duration: Int) {
            runOnUIThread {
                val toastDuration = if (duration > 3000) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
                Toast.makeText(this@WebOverlayService, message, toastDuration).show()
            }
        }
        
        @JavascriptInterface
        fun vibrate(duration: Long) {
            try {
                val vibrator = getSystemService(VIBRATOR_SERVICE) as android.os.Vibrator
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    vibrator.vibrate(android.os.VibrationEffect.createOneShot(duration, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(duration)
                }
            } catch (e: Exception) {
                log("Vibration failed: ${e.message}")
            }
        }
        
        // ========== 파일 시스템 API ==========
        @JavascriptInterface
        fun saveFile(filename: String, content: String): Boolean {
            try {
                val file = java.io.File(filesDir, filename)
                file.writeText(content)
                return true
            } catch (e: Exception) {
                log("Save file failed: ${e.message}")
                return false
            }
        }
        
        @JavascriptInterface
        fun loadFile(filename: String): String {
            try {
                val file = java.io.File(filesDir, filename)
                return if (file.exists()) file.readText() else ""
            } catch (e: Exception) {
                log("Load file failed: ${e.message}")
                return ""
            }
        }
        
        @JavascriptInterface
        fun deleteFile(filename: String): Boolean {
            try {
                val file = java.io.File(filesDir, filename)
                return file.delete()
            } catch (e: Exception) {
                log("Delete file failed: ${e.message}")
                return false
            }
        }
        
        // ========== 앱 제어 API ==========
        @JavascriptInterface
        fun openApp(packageName: String): Boolean {
            try {
                val intent = packageManager.getLaunchIntentForPackage(packageName)
                intent?.let {
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(it)
                    return true
                }
                return false
            } catch (e: Exception) {
                log("Open app failed: ${e.message}")
                return false
            }
        }
        
        @JavascriptInterface
        fun closeCurrentApp(): Boolean {
            try {
                goToHomeScreen()
                return true
            } catch (e: Exception) {
                log("Close current app failed: ${e.message}")
                return false
            }
        }
        
        @JavascriptInterface
        fun killApp(packageName: String): Boolean {
            try {
                val activityManager = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
                activityManager.killBackgroundProcesses(packageName)
                return true
            } catch (e: Exception) {
                log("Kill app failed: ${e.message}")
                return false
            }
        }
        
        @JavascriptInterface
        fun isAppInstalled(packageName: String): Boolean {
            return try {
                packageManager.getPackageInfo(packageName, 0)
                true
            } catch (e: Exception) {
                false
            }
        }
        
        @JavascriptInterface
        fun getInstalledApps(): String {
            return try {
                val apps = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
                apps.map { it.packageName }.joinToString(",")
            } catch (e: Exception) {
                ""
            }
        }
        
        // ========== 기본 API ==========
        @JavascriptInterface
        fun closeOverlay() {
            stopSelf()
        }
        
        @JavascriptInterface
        fun log(message: String) {
            android.util.Log.d("WebOverlay", message)
        }
        
        // ========== OCR 및 카메라 API ==========
        @JavascriptInterface
        fun hasCameraPermission(): Boolean {
            return cameraManager.hasCameraPermission()
        }
        
        @JavascriptInterface
        fun takePhotoAndAnalyze(filterType: String) {
            lifecycleScope.launch {
                try {
                    if (!cameraManager.hasCameraPermission()) {
                        runOnUIThread { 
                            webView?.evaluateJavascript("onCameraError('카메라 권한이 필요합니다');", null)
                        }
                        return@launch
                    }
                    
                    // 카메라 초기화
                    cameraManager.initializeCamera(this@WebOverlayService).fold(
                        onSuccess = {
                            // 사진 촬영
                            cameraManager.takePhotoToBitmap().fold(
                                onSuccess = { bitmap ->
                                    // OCR 분석
                                    val resizedBitmap = cameraManager.resizeBitmapForOcr(bitmap)
                                    analyzeImageWithOcr(resizedBitmap, filterType)
                                },
                                onFailure = { error ->
                                    runOnUIThread { 
                                        webView?.evaluateJavascript("onCameraError('사진 촬영 실패: ${error.message}');", null)
                                    }
                                }
                            )
                        },
                        onFailure = { error ->
                            runOnUIThread { 
                                webView?.evaluateJavascript("onCameraError('카메라 초기화 실패: ${error.message}');", null)
                            }
                        }
                    )
                } catch (e: Exception) {
                    runOnUIThread { 
                        webView?.evaluateJavascript("onCameraError('카메라 오류: ${e.message}');", null)
                    }
                }
            }
        }
        
        private suspend fun analyzeImageWithOcr(bitmap: Bitmap, filterType: String) {
            try {
                val filter = when (filterType.lowercase()) {
                    "store" -> "store"
                    "food" -> "food"
                    else -> null
                }
                
                ocrApiService.extractTextFromImage(bitmap, filter).fold(
                    onSuccess = { response ->
                        if (response.success) {
                            val results = response.textList.map { 
                                mapOf(
                                    "text" to it.text,
                                    "x" to it.x,
                                    "y" to it.y
                                )
                            }
                            val jsonResults = com.google.gson.Gson().toJson(results)
                            
                            runOnUIThread { 
                                webView?.evaluateJavascript("onOcrResults('$filterType', $jsonResults);", null)
                            }
                        } else {
                            runOnUIThread { 
                                webView?.evaluateJavascript("onOcrError('OCR 분석 실패: ${response.message ?: "알 수 없는 오류"}');", null)
                            }
                        }
                    },
                    onFailure = { error ->
                        runOnUIThread { 
                            webView?.evaluateJavascript("onOcrError('OCR API 오류: ${error.message}');", null)
                        }
                    }
                )
            } catch (e: Exception) {
                runOnUIThread { 
                    webView?.evaluateJavascript("onOcrError('OCR 분석 중 오류: ${e.message}');", null)
                }
            }
        }
        
        @JavascriptInterface
        fun refineTextWithOcr(text: String, extractType: String) {
            lifecycleScope.launch {
                try {
                    ocrApiService.extractFromText(text, extractType).fold(
                        onSuccess = { result ->
                            runOnUIThread { 
                                webView?.evaluateJavascript("onTextRefined('$extractType', '$result');", null)
                            }
                        },
                        onFailure = { error ->
                            runOnUIThread { 
                                webView?.evaluateJavascript("onTextRefineError('텍스트 정제 실패: ${error.message}');", null)
                            }
                        }
                    )
                } catch (e: Exception) {
                    runOnUIThread { 
                        webView?.evaluateJavascript("onTextRefineError('텍스트 정제 중 오류: ${e.message}');", null)
                    }
                }
            }
        }
        
        @JavascriptInterface
        fun checkOcrServiceHealth() {
            lifecycleScope.launch {
                try {
                    ocrApiService.checkHealth().fold(
                        onSuccess = { health ->
                            runOnUIThread { 
                                webView?.evaluateJavascript("onOcrHealthCheck(${health.ocr}, '${health.status}');", null)
                            }
                        },
                        onFailure = { error ->
                            runOnUIThread { 
                                webView?.evaluateJavascript("onOcrHealthCheck(false, 'OCR 서비스 연결 실패: ${error.message}');", null)
                            }
                        }
                    )
                } catch (e: Exception) {
                    runOnUIThread { 
                        webView?.evaluateJavascript("onOcrHealthCheck(false, 'OCR 상태 확인 중 오류: ${e.message}');", null)
                    }
                }
            }
        }
        
        // 편의 메서드들
        @JavascriptInterface
        fun scanStoreSign() {
            takePhotoAndAnalyze("store")
        }
        
        @JavascriptInterface
        fun scanMenu() {
            takePhotoAndAnalyze("food")
        }
        
        @JavascriptInterface
        fun scanAll() {
            takePhotoAndAnalyze("all")
        }
        
        // ========== 배달앱 화면 스크린샷 분석 API ==========
        @JavascriptInterface
        fun hasScreenshotPermission(): Boolean {
            return screenshotManager.hasScreenshotPermission()
        }
        
        @JavascriptInterface
        fun requestScreenshotPermission() {
            try {
                val intent = screenshotManager.createScreenCaptureIntent()
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                showToast("화면 캡처 권한을 허용해주세요")
            } catch (e: Exception) {
                runOnUIThread { 
                    webView?.evaluateJavascript("onScreenshotError('권한 요청 실패: ${e.message}');", null)
                }
            }
        }
        
        @JavascriptInterface
        fun initializeScreenCapture(resultCode: Int, data: String) {
            try {
                // Intent 데이터 복원 로직 필요
                // 실제 구현에서는 Activity Result API 사용
                showToast("화면 캡처가 활성화되었습니다")
                runOnUIThread { 
                    webView?.evaluateJavascript("onScreenCaptureReady();", null)
                }
            } catch (e: Exception) {
                runOnUIThread { 
                    webView?.evaluateJavascript("onScreenshotError('초기화 실패: ${e.message}');", null)
                }
            }
        }
        
        @JavascriptInterface
        fun captureDeliveryAppScreen(filterType: String) {
            if (!screenshotManager.hasScreenshotPermission()) {
                runOnUIThread { 
                    webView?.evaluateJavascript("onScreenshotError('화면 캡처 권한이 필요합니다');", null)
                }
                return
            }
            
            lifecycleScope.launch {
                try {
                    runOnUIThread { 
                        webView?.evaluateJavascript("onScreenshotStarted();", null)
                    }
                    
                    // 스크린샷 촬영 및 OCR 분석
                    screenshotManager.captureAndAnalyze(ocrApiService, filterType).fold(
                        onSuccess = { response ->
                            if (response.success) {
                                val results = response.textList.map { 
                                    mapOf(
                                        "text" to it.text,
                                        "x" to it.x,
                                        "y" to it.y
                                    )
                                }
                                val jsonResults = com.google.gson.Gson().toJson(results)
                                
                                runOnUIThread { 
                                    webView?.evaluateJavascript("onDeliveryAppAnalyzed('$filterType', $jsonResults);", null)
                                }
                            } else {
                                runOnUIThread { 
                                    webView?.evaluateJavascript("onScreenshotError('화면 분석 실패: ${response.message ?: "알 수 없는 오류"}');", null)
                                }
                            }
                        },
                        onFailure = { error ->
                            runOnUIThread { 
                                webView?.evaluateJavascript("onScreenshotError('스크린샷 실패: ${error.message}');", null)
                            }
                        }
                    )
                } catch (e: Exception) {
                    runOnUIThread { 
                        webView?.evaluateJavascript("onScreenshotError('캡처 중 오류: ${e.message}');", null)
                    }
                }
            }
        }
        
        // 편의 메서드들 - 배달앱 화면 분석
        @JavascriptInterface
        fun findStoresOnScreen() {
            captureDeliveryAppScreen("store")
        }
        
        @JavascriptInterface
        fun findMenusOnScreen() {
            captureDeliveryAppScreen("food")
        }
        
        @JavascriptInterface
        fun analyzeEntireScreen() {
            captureDeliveryAppScreen("all")
        }
        
        @JavascriptInterface
        fun captureAndSave() {
            if (!screenshotManager.hasScreenshotPermission()) {
                runOnUIThread { 
                    webView?.evaluateJavascript("onScreenshotError('화면 캡처 권한이 필요합니다');", null)
                }
                return
            }
            
            lifecycleScope.launch {
                try {
                    screenshotManager.takeScreenshot().fold(
                        onSuccess = { bitmap ->
                            val filename = "delivery_app_${System.currentTimeMillis()}"
                            screenshotManager.saveScreenshotToFile(bitmap, filename).fold(
                                onSuccess = { filepath ->
                                    runOnUIThread { 
                                        webView?.evaluateJavascript("onScreenshotSaved('$filepath');", null)
                                    }
                                },
                                onFailure = { error ->
                                    runOnUIThread { 
                                        webView?.evaluateJavascript("onScreenshotError('저장 실패: ${error.message}');", null)
                                    }
                                }
                            )
                        },
                        onFailure = { error ->
                            runOnUIThread { 
                                webView?.evaluateJavascript("onScreenshotError('캡처 실패: ${error.message}');", null)
                            }
                        }
                    )
                } catch (e: Exception) {
                    runOnUIThread { 
                        webView?.evaluateJavascript("onScreenshotError('저장 중 오류: ${e.message}');", null)
                    }
                }
            }
        }

        @JavascriptInterface
        fun getApiList(): String {
            return """
                [음성인식] startVoiceRecognition, stopVoiceRecognition, isVoiceRecognitionAvailable
                [데이터검색] searchStore, searchMenu, getAllStores, getStoreMenus, getSelectedStore  
                [화면터치] getScreenSize, getScreenDensity, simulateTouch, simulateLongPress, simulateSwipe
                [스크린샷] takeScreenshot, takeScreenshotRegion
                [터치모드] enableTouchMode, disableTouchMode, toggleTouchMode, isTouchModeEnabled
                [오버레이] updatePosition, updateSize, setOverlayOpacity, hideOverlay, showOverlay
                [시스템정보] getDeviceInfo, getBatteryLevel, isWifiConnected, getCurrentTime
                [권한] hasPermission, hasOverlayPermission, hasAccessibilityPermission
                [알림] showToast, vibrate
                [파일시스템] saveFile, loadFile, deleteFile
                [앱제어] openApp, isAppInstalled, getInstalledApps
                [OCR카메라] hasCameraPermission, takePhotoAndAnalyze, scanStoreSign, scanMenu, scanAll
                [OCR텍스트] refineTextWithOcr, checkOcrServiceHealth
                [화면캡처] hasScreenshotPermission, requestScreenshotPermission, captureDeliveryAppScreen
                [배달앱분석] findStoresOnScreen, findMenusOnScreen, analyzeEntireScreen, captureAndSave
                [기본] closeOverlay, log, getApiList
            """.trimIndent()
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        speechRecognizer?.destroy()
        cameraManager.release()
        screenshotManager.release()
        webView?.let {
            windowManager.removeView(it)
            it.destroy()
        }
    }
}