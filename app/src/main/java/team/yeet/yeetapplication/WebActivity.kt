package team.yeet.yeetapplication

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import android.content.ComponentName
import android.content.Context
import android.provider.Settings.Secure
import android.text.TextUtils
import android.view.accessibility.AccessibilityManager
import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo
import android.view.KeyEvent
import android.content.ClipData
import android.content.ClipboardManager
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import team.yeet.yeetapplication.ocr.OcrApiService
import team.yeet.yeetapplication.camera.CameraManager

class WebActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private var currentPage = "main"
    private var pendingPermissionCallback: String? = null
    
    // OCR 및 카메라 관련
    private lateinit var ocrApiService: OcrApiService
    private lateinit var cameraManager: CameraManager

    // 단일 권한 요청
    private val singlePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        val permission = pendingPermissionCallback ?: "unknown"
        if (isGranted) {
            webView.evaluateJavascript("onPermissionGranted('$permission');", null)
        } else {
            webView.evaluateJavascript("onPermissionDenied('$permission');", null)
        }
        pendingPermissionCallback = null
    }

    // 다중 권한 요청
    private val multiplePermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = mutableListOf<String>()
        val denied = mutableListOf<String>()

        permissions.entries.forEach { entry ->
            if (entry.value) {
                granted.add(entry.key)
            } else {
                denied.add(entry.key)
            }
        }

        webView.evaluateJavascript(
            "onMultiplePermissionsResult(${granted.joinToString(",") { "'$it'" }}, ${denied.joinToString(",") { "'$it'" }});",
            null
        )
    }

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(this)) {
            webView.evaluateJavascript("onPermissionGranted('overlay');", null)
        } else {
            webView.evaluateJavascript("onPermissionDenied('overlay');", null)
        }
    }

    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            webView.evaluateJavascript("onPermissionGranted('audio');", null)
        } else {
            webView.evaluateJavascript("onPermissionDenied('audio');", null)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // OCR 및 카메라 서비스 초기화
        ocrApiService = OcrApiService()
        cameraManager = CameraManager(this)

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.allowContentAccess = true
            addJavascriptInterface(WebAppBridge(), "Android")

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    // 페이지가 로드된 후 현재 페이지로 네비게이션
                    evaluateJavascript("initializePage('$currentPage');", null)
                }
            }
        }

        setContentView(webView)

        // 시작 페이지 결정
        currentPage = intent.getStringExtra("page") ?: "main"
        loadPage()
    }

    private fun loadPage() {
        // 모든 페이지를 index.html로 통일
        webView.loadUrl("file:///android_asset/index.html")
    }

    inner class WebAppBridge {

        @JavascriptInterface
        fun log(message: String) {
            android.util.Log.d("WebApp", message)
        }

        @JavascriptInterface
        fun showToast(message: String) {
            runOnUiThread {
                Toast.makeText(this@WebActivity, message, Toast.LENGTH_SHORT).show()
            }
        }

        @JavascriptInterface
        fun navigateTo(page: String) {
            runOnUiThread {
                currentPage = page
                // HTML 내에서 페이지 전환 처리
                webView.evaluateJavascript("navigateToPage('$page');", null)
            }
        }

        @JavascriptInterface
        fun getCurrentPage(): String {
            return currentPage
        }

        // ========== 권한 관리 API ==========
        @JavascriptInterface
        fun checkPermissions(): String {
            val hasAudio = ContextCompat.checkSelfPermission(
                this@WebActivity, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED

            val hasOverlay = Settings.canDrawOverlays(this@WebActivity)

            return "${hasAudio},${hasOverlay}"
        }

        @JavascriptInterface
        fun checkPermission(permission: String): Boolean {
            return when (permission) {
                "overlay" -> Settings.canDrawOverlays(this@WebActivity)
                else -> ContextCompat.checkSelfPermission(
                    this@WebActivity, permission
                ) == PackageManager.PERMISSION_GRANTED
            }
        }

        @JavascriptInterface
        fun checkMultiplePermissions(permissions: String): String {
            val permissionList = permissions.split(",")
            val results = mutableListOf<String>()

            permissionList.forEach { permission ->
                val hasPermission = checkPermission(permission.trim())
                results.add("${permission.trim()}:${hasPermission}")
            }

            return results.joinToString(",")
        }

        @JavascriptInterface
        fun requestPermission(permission: String) {
            when (permission) {
                "overlay" -> {
                    requestOverlayPermission()
                }
                "audio" -> {
                    pendingPermissionCallback = "audio"
                    singlePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
                "camera" -> {
                    pendingPermissionCallback = "camera"
                    singlePermissionLauncher.launch(Manifest.permission.CAMERA)
                }
                "location" -> {
                    pendingPermissionCallback = "location"
                    singlePermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                }
                "storage" -> {
                    pendingPermissionCallback = "storage"
                    singlePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
                "phone" -> {
                    pendingPermissionCallback = "phone"
                    singlePermissionLauncher.launch(Manifest.permission.CALL_PHONE)
                }
                "sms" -> {
                    pendingPermissionCallback = "sms"
                    singlePermissionLauncher.launch(Manifest.permission.SEND_SMS)
                }
                "contacts" -> {
                    pendingPermissionCallback = "contacts"
                    singlePermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
                }
                else -> {
                    // 사용자 지정 권한
                    pendingPermissionCallback = permission
                    singlePermissionLauncher.launch(permission)
                }
            }
        }

        @JavascriptInterface
        fun requestMultiplePermissions(permissions: String) {
            val permissionList = permissions.split(",").map { it.trim() }
            val androidPermissions = mutableListOf<String>()

            permissionList.forEach { permission ->
                when (permission) {
                    "audio" -> androidPermissions.add(Manifest.permission.RECORD_AUDIO)
                    "camera" -> androidPermissions.add(Manifest.permission.CAMERA)
                    "location" -> {
                        androidPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
                        androidPermissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
                    }
                    "storage" -> {
                        androidPermissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
                        androidPermissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    }
                    "phone" -> androidPermissions.add(Manifest.permission.CALL_PHONE)
                    "sms" -> {
                        androidPermissions.add(Manifest.permission.SEND_SMS)
                        androidPermissions.add(Manifest.permission.RECEIVE_SMS)
                    }
                    "contacts" -> {
                        androidPermissions.add(Manifest.permission.READ_CONTACTS)
                        androidPermissions.add(Manifest.permission.WRITE_CONTACTS)
                    }
                    else -> androidPermissions.add(permission)
                }
            }

            multiplePermissionsLauncher.launch(androidPermissions.toTypedArray())
        }

        @JavascriptInterface
        fun getAllPermissionStatus(): String {
            val permissions = listOf(
                "audio" to Manifest.permission.RECORD_AUDIO,
                "camera" to Manifest.permission.CAMERA,
                "location" to Manifest.permission.ACCESS_FINE_LOCATION,
                "storage" to Manifest.permission.WRITE_EXTERNAL_STORAGE,
                "phone" to Manifest.permission.CALL_PHONE,
                "sms" to Manifest.permission.SEND_SMS,
                "contacts" to Manifest.permission.READ_CONTACTS
            )

            val results = mutableListOf<String>()

            permissions.forEach { (name, permission) ->
                val hasPermission = ContextCompat.checkSelfPermission(
                    this@WebActivity, permission
                ) == PackageManager.PERMISSION_GRANTED
                results.add("${name}:${hasPermission}")
            }

            // 오버레이 권한 추가
            val hasOverlay = Settings.canDrawOverlays(this@WebActivity)
            results.add("overlay:${hasOverlay}")

            return results.joinToString(",")
        }

        @JavascriptInterface
        fun requestAudioPermission() {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }

        @JavascriptInterface
        fun requestOverlayPermission() {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
        }

        // ========== 키보드 입력 API ==========
        @JavascriptInterface
        fun isAccessibilityServiceEnabled(): Boolean {
            val accessibilityManager = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            val enabledServices = Secure.getString(
                contentResolver,
                Secure.ENABLED_ACCESSIBILITY_SERVICES
            )

            val colonSplitter = TextUtils.SimpleStringSplitter(':')
            colonSplitter.setString(enabledServices)

            val componentName = ComponentName(
                this@WebActivity,
                "team.yeet.yeetapplication.KeyboardInputService"
            )

            while (colonSplitter.hasNext()) {
                val componentNameString = colonSplitter.next()
                val enabledService = ComponentName.unflattenFromString(componentNameString)
                if (enabledService != null && enabledService.equals(componentName)) {
                    return true
                }
            }
            return false
        }

        @JavascriptInterface
        fun requestAccessibilityPermission() {
            try {
                val intent = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                startActivity(intent)
                showToast("접근성 서비스에서 '키보드 입력 도우미'를 활성화해주세요")
            } catch (e: Exception) {
                android.util.Log.e("WebApp", "Failed to open accessibility settings", e)
                showToast("접근성 설정을 열 수 없습니다")
            }
        }

        @JavascriptInterface
        fun sendTextInput(text: String): Boolean {
            return if (isAccessibilityServiceEnabled()) {
                try {
                    // 접근성 서비스를 통한 텍스트 입력
                    val intent = Intent("team.yeet.yeetapplication.SEND_TEXT")
                    intent.putExtra("text", text)
                    sendBroadcast(intent)
                    showToast("텍스트를 입력했습니다: $text")
                    true
                } catch (e: Exception) {
                    android.util.Log.e("WebApp", "Failed to send text input", e)
                    showToast("텍스트 입력에 실패했습니다")
                    false
                }
            } else {
                // 클립보드를 이용한 대체 방법
                copyToClipboard(text)
                showToast("텍스트를 클립보드에 복사했습니다. 붙여넣기 해주세요")
                false
            }
        }

        @JavascriptInterface
        fun sendKeyEvent(keyCode: Int): Boolean {
            return if (isAccessibilityServiceEnabled()) {
                try {
                    val intent = Intent("team.yeet.yeetapplication.SEND_KEY")
                    intent.putExtra("keyCode", keyCode)
                    sendBroadcast(intent)
                    true
                } catch (e: Exception) {
                    android.util.Log.e("WebApp", "Failed to send key event", e)
                    false
                }
            } else {
                showToast("접근성 서비스가 비활성화되어 있습니다")
                false
            }
        }

        @JavascriptInterface
        fun sendBackKey(): Boolean {
            return sendKeyEvent(KeyEvent.KEYCODE_BACK)
        }

        @JavascriptInterface
        fun sendEnterKey(): Boolean {
            return sendKeyEvent(KeyEvent.KEYCODE_ENTER)
        }

        @JavascriptInterface
        fun sendDeleteKey(): Boolean {
            return sendKeyEvent(KeyEvent.KEYCODE_DEL)
        }

        @JavascriptInterface
        fun clearTextInput(): Boolean {
            return if (isAccessibilityServiceEnabled()) {
                try {
                    val intent = Intent("team.yeet.yeetapplication.CLEAR_TEXT")
                    sendBroadcast(intent)
                    showToast("텍스트를 지웠습니다")
                    true
                } catch (e: Exception) {
                    android.util.Log.e("WebApp", "Failed to clear text", e)
                    false
                }
            } else {
                showToast("접근성 서비스가 비활성화되어 있습니다")
                false
            }
        }

        @JavascriptInterface
        fun copyToClipboard(text: String) {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("YeetApp", text)
            clipboard.setPrimaryClip(clip)
        }

        @JavascriptInterface
        fun getClipboardText(): String {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = clipboard.primaryClip
            return if (clip != null && clip.itemCount > 0) {
                clip.getItemAt(0).text?.toString() ?: ""
            } else {
                ""
            }
        }

        @JavascriptInterface
        fun clickElement(): Boolean {
            return if (isAccessibilityServiceEnabled()) {
                try {
                    val intent = Intent("team.yeet.yeetapplication.CLICK_ELEMENT")
                    sendBroadcast(intent)
                    true
                } catch (e: Exception) {
                    android.util.Log.e("WebApp", "Failed to click element", e)
                    false
                }
            } else {
                showToast("접근성 서비스가 비활성화되어 있습니다")
                false
            }
        }

        @JavascriptInterface
        fun findAndClickByText(text: String): Boolean {
            return if (isAccessibilityServiceEnabled()) {
                try {
                    val intent = Intent("team.yeet.yeetapplication.FIND_AND_CLICK")
                    intent.putExtra("text", text)
                    sendBroadcast(intent)
                    showToast("'$text' 요소를 찾아서 클릭합니다")
                    true
                } catch (e: Exception) {
                    android.util.Log.e("WebApp", "Failed to find and click element", e)
                    false
                }
            } else {
                showToast("접근성 서비스가 비활성화되어 있습니다")
                false
            }
        }

        @JavascriptInterface
        fun typeInSearchBox(searchText: String, boxHint: String = ""): Boolean {
            return if (isAccessibilityServiceEnabled()) {
                try {
                    val intent = Intent("team.yeet.yeetapplication.TYPE_IN_SEARCH")
                    intent.putExtra("searchText", searchText)
                    intent.putExtra("boxHint", boxHint)
                    sendBroadcast(intent)
                    showToast("검색어를 입력합니다: $searchText")
                    true
                } catch (e: Exception) {
                    android.util.Log.e("WebApp", "Failed to type in search box", e)
                    false
                }
            } else {
                showToast("접근성 서비스가 비활성화되어 있습니다")
                false
            }
        }

        // ========== 배달 앱 API ==========
        @JavascriptInterface
        fun openBaemin(): Boolean {
            return try {
                val intent = packageManager.getLaunchIntentForPackage("com.sampleapp")
                if (intent != null) {
                    startActivity(intent)
                    showToast("배달의민족 앱을 실행합니다")
                    true
                } else {
                    // 앱이 설치되어 있지 않은 경우 Play Store로 이동
                    openBaeminPlayStore()
                    false
                }
            } catch (e: Exception) {
                android.util.Log.e("WebApp", "Failed to open Baemin app", e)
                showToast("배달의민족 앱 실행에 실패했습니다")
                false
            }
        }

        @JavascriptInterface
        fun openBaeminPlayStore(): Boolean {
            return try {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse("market://details?id=com.sampleapp")
                    setPackage("com.android.vending")
                }
                startActivity(intent)
                showToast("배달의민족 앱 설치 페이지로 이동합니다")
                true
            } catch (e: Exception) {
                // Play Store 앱이 없는 경우 웹 브라우저로 이동
                try {
                    val webIntent = Intent(Intent.ACTION_VIEW).apply {
                        data = Uri.parse("https://play.google.com/store/apps/details?id=com.sampleapp")
                    }
                    startActivity(webIntent)
                    showToast("배달의민족 설치 페이지로 이동합니다")
                    true
                } catch (e2: Exception) {
                    android.util.Log.e("WebApp", "Failed to open Play Store", e2)
                    showToast("Play Store 실행에 실패했습니다")
                    false
                }
            }
        }

        @JavascriptInterface
        fun isBaeminInstalled(): Boolean {
            return try {
                packageManager.getPackageInfo("com.sampleapp", 0)
                true
            } catch (e: PackageManager.NameNotFoundException) {
                false
            }
        }

        @JavascriptInterface
        fun openDeliveryApp(packageName: String): Boolean {
            return try {
                val intent = packageManager.getLaunchIntentForPackage(packageName)
                if (intent != null) {
                    startActivity(intent)
                    showToast("배달 앱을 실행합니다")
                    true
                } else {
                    showToast("해당 앱이 설치되어 있지 않습니다")
                    false
                }
            } catch (e: Exception) {
                android.util.Log.e("WebApp", "Failed to open delivery app: $packageName", e)
                showToast("앱 실행에 실패했습니다")
                false
            }
        }

        @JavascriptInterface
        fun getInstalledDeliveryApps(): String {
            val deliveryApps = mapOf(
                "com.sampleapp" to "배달의민족",
                "com.yogiyo.android" to "요기요",
                "kr.co.coupang.mobile" to "쿠팡이츠",
                "com.ubercab.eats" to "우버이츠",
                "com.daesung.baedaltong" to "배달통",
                "com.tmon.delivery" to "티몬배달",
                "com.daangn.karrot" to "당근마켓"
            )

            val installedApps = mutableListOf<String>()

            deliveryApps.forEach { (packageName, appName) ->
                try {
                    packageManager.getPackageInfo(packageName, 0)
                    installedApps.add("$packageName:$appName")
                } catch (e: PackageManager.NameNotFoundException) {
                    // 앱이 설치되어 있지 않음
                }
            }

            return installedApps.joinToString(",")
        }

        // ========== 앱 관리 API ==========
        @JavascriptInterface
        fun startOverlayService(): Boolean {
            return try {
                // 오버레이 권한 확인
                if (!Settings.canDrawOverlays(this@WebActivity)) {
                    showToast("오버레이 권한이 필요합니다. 권한을 먼저 허용해주세요.")
                    requestOverlayPermission()
                    return false
                }
                
                val intent = Intent(this@WebActivity, WebOverlayService::class.java)
                startService(intent)
                showToast("웹 기반 음성 주문 도우미를 시작합니다")
                
                // 메인 액티비티를 백그라운드로 이동
                moveTaskToBack(true)
                
                android.util.Log.d("WebActivity", "WebOverlayService started successfully")
                true
            } catch (e: Exception) {
                android.util.Log.e("WebActivity", "Failed to start WebOverlayService", e)
                showToast("오버레이 서비스 시작에 실패했습니다: ${e.message}")
                false
            }
        }

        @JavascriptInterface
        fun stopOverlayService(): Boolean {
            return try {
                val intent = Intent(this@WebActivity, WebOverlayService::class.java)
                stopService(intent)
                showToast("주문 도우미를 종료했습니다")
                
                android.util.Log.d("WebActivity", "WebOverlayService stopped successfully")
                true
            } catch (e: Exception) {
                android.util.Log.e("WebActivity", "Failed to stop WebOverlayService", e)
                showToast("오버레이 서비스 종료에 실패했습니다: ${e.message}")
                false
            }
        }

        @JavascriptInterface
        fun closeApp() {
            finish()
        }

        @JavascriptInterface
        fun getDeviceInfo(): String {
            return "${android.os.Build.MANUFACTURER},${android.os.Build.MODEL},${android.os.Build.VERSION.RELEASE}"
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
                        runOnUiThread { 
                            webView.evaluateJavascript("onCameraError('카메라 권한이 필요합니다');", null)
                        }
                        return@launch
                    }
                    
                    // 카메라 초기화
                    cameraManager.initializeCamera(this@WebActivity).fold(
                        onSuccess = {
                            // 사진 촬영
                            cameraManager.takePhotoToBitmap().fold(
                                onSuccess = { bitmap ->
                                    // OCR 분석
                                    val resizedBitmap = cameraManager.resizeBitmapForOcr(bitmap)
                                    analyzeImageWithOcr(resizedBitmap, filterType)
                                },
                                onFailure = { error ->
                                    runOnUiThread { 
                                        webView.evaluateJavascript("onCameraError('사진 촬영 실패: ${error.message}');", null)
                                    }
                                }
                            )
                        },
                        onFailure = { error ->
                            runOnUiThread { 
                                webView.evaluateJavascript("onCameraError('카메라 초기화 실패: ${error.message}');", null)
                            }
                        }
                    )
                } catch (e: Exception) {
                    runOnUiThread { 
                        webView.evaluateJavascript("onCameraError('카메라 오류: ${e.message}');", null)
                    }
                }
            }
        }
        
        private suspend fun analyzeImageWithOcr(bitmap: android.graphics.Bitmap, filterType: String) {
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
                            
                            runOnUiThread { 
                                webView.evaluateJavascript("onOcrResults('$filterType', $jsonResults);", null)
                            }
                        } else {
                            runOnUiThread { 
                                webView.evaluateJavascript("onOcrError('OCR 분석 실패: ${response.message ?: "알 수 없는 오류"}');", null)
                            }
                        }
                    },
                    onFailure = { error ->
                        runOnUiThread { 
                            webView.evaluateJavascript("onOcrError('OCR API 오류: ${error.message}');", null)
                        }
                    }
                )
            } catch (e: Exception) {
                runOnUiThread { 
                    webView.evaluateJavascript("onOcrError('OCR 분석 중 오류: ${e.message}');", null)
                }
            }
        }
        
        @JavascriptInterface
        fun refineTextWithOcr(text: String, extractType: String) {
            lifecycleScope.launch {
                try {
                    ocrApiService.extractFromText(text, extractType).fold(
                        onSuccess = { result ->
                            runOnUiThread { 
                                webView.evaluateJavascript("onTextRefined('$extractType', '$result');", null)
                            }
                        },
                        onFailure = { error ->
                            runOnUiThread { 
                                webView.evaluateJavascript("onTextRefineError('텍스트 정제 실패: ${error.message}');", null)
                            }
                        }
                    )
                } catch (e: Exception) {
                    runOnUiThread { 
                        webView.evaluateJavascript("onTextRefineError('텍스트 정제 중 오류: ${e.message}');", null)
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
                            runOnUiThread { 
                                webView.evaluateJavascript("onOcrHealthCheck(${health.ocr}, '${health.status}');", null)
                            }
                        },
                        onFailure = { error ->
                            runOnUiThread { 
                                webView.evaluateJavascript("onOcrHealthCheck(false, 'OCR 서비스 연결 실패: ${error.message}');", null)
                            }
                        }
                    )
                } catch (e: Exception) {
                    runOnUiThread { 
                        webView.evaluateJavascript("onOcrHealthCheck(false, 'OCR 상태 확인 중 오류: ${e.message}');", null)
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
    }

    override fun onBackPressed() {
        if (currentPage != "main") {
            currentPage = "main"
            webView.evaluateJavascript("navigateToPage('main');", null)
        } else {
            super.onBackPressed()
        }
    }
}