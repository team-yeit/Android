package team.yeet.yeetapplication

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import team.yeet.yeetapplication.ocr.OcrApiService
import team.yeet.yeetapplication.screenshot.ScreenshotManager
import team.yeet.yeetapplication.voice.VoiceCommandProcessor
import java.util.*

class OverlayService : LifecycleService() {
    
    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private var currentStep = STEP_STORE_SEARCH
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    
    // OCR, 스크린샷 및 음성 명령 서비스
    private lateinit var ocrApiService: OcrApiService
    private lateinit var screenshotManager: ScreenshotManager
    private lateinit var voiceCommandProcessor: VoiceCommandProcessor
    
    private var pulseAnimator: AnimatorSet? = null
    private var waveAnimator: AnimatorSet? = null
    private var micButtonFrame: FrameLayout? = null
    private var waveIndicator: LinearLayout? = null
    private var statusTextView: TextView? = null
    
    private val hardcodedStores = mapOf(
        "맥도날드" to listOf("빅맥", "치킨버거", "감자튀김", "콜라", "맥너겟"),
        "버거킹" to listOf("와퍼", "치킨킹", "어니언링", "사이다", "프라이"),
        "피자헛" to listOf("슈퍼슈프림", "치즈피자", "페퍼로니", "콜라", "갈릭브레드"),
        "치킨매니아" to listOf("후라이드치킨", "양념치킨", "간장치킨", "맥주", "무"),
        "떡볶이왕" to listOf("떡볶이", "순대", "튀김", "김밥", "어묵"),
        "짜장면집" to listOf("짜장면", "짬뽕", "탕수육", "군만두", "볶음밥"),
        "김밥천국" to listOf("참치김밥", "불고기김밥", "계란김밥", "라면", "우동")
    )
    
    companion object {
        const val STEP_STORE_SEARCH = 1
        const val STEP_MENU_SEARCH = 2
    }
    
    private var selectedStore = ""
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    
    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        
        // OCR, 스크린샷 및 음성 명령 서비스 초기화
        ocrApiService = OcrApiService()
        screenshotManager = ScreenshotManager(this)
        voiceCommandProcessor = VoiceCommandProcessor(this)
        
        initializeSpeechRecognizer()
        showStoreSearchOverlay()
    }
    
    // onBind is already implemented by LifecycleService
    
    private fun initializeSpeechRecognizer() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                isListening = true
                startListeningAnimation()
                updateStatusUI("🎤 듣고 있습니다...", "#4CAF50")
            }
            
            override fun onBeginningOfSpeech() {
                updateStatusUI("🗣️ 말씀하세요...", "#4CAF50")
            }
            
            override fun onRmsChanged(rmsdB: Float) {
                animateWaveBasedOnVolume(rmsdB)
            }
            
            override fun onBufferReceived(buffer: ByteArray?) {}
            
            override fun onEndOfSpeech() {
                isListening = false
                stopListeningAnimation()
                updateStatusUI("🔄 처리 중입니다...", "#FF9800")
            }
            
            override fun onError(error: Int) {
                isListening = false
                stopListeningAnimation()
                val errorMessage = when (error) {
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "⚠️ 네트워크 연결을 확인해주세요"
                    SpeechRecognizer.ERROR_NO_MATCH -> "❓ 음성을 인식하지 못했어요. 다시 시도해주세요"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "⏰ 음성 입력 시간이 초과되었어요"
                    else -> "❌ 음성 인식에 문제가 있어요. 다시 시도해주세요"
                }
                updateStatusUI(errorMessage, "#FF5722")
                
                Handler(Looper.getMainLooper()).postDelayed({
                    resetToInitialState()
                }, 3000)
            }
            
            override fun onResults(results: Bundle?) {
                isListening = false
                stopListeningAnimation()
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (matches != null && matches.isNotEmpty()) {
                    val recognizedText = matches[0]
                    handleSpeechResult(recognizedText)
                }
            }
            
            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (matches != null && matches.isNotEmpty()) {
                    val partialText = matches[0]
                    updateStatusUI("듣는 중: \"$partialText...\"", "#2196F3")
                }
            }
            
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }
    
    private fun showStoreSearchOverlay() {
        removeOverlay()
        currentStep = STEP_STORE_SEARCH
        
        val inflater = LayoutInflater.from(this)
        overlayView = inflater.inflate(R.layout.overlay_main, null)
        
        val params = createOverlayParams()
        setupDragToMove(params)
        setupStoreSearchViews()
        
        windowManager.addView(overlayView, params)
        
        overlayView?.alpha = 0f
        overlayView?.animate()?.alpha(1f)?.setDuration(300)?.start()
    }
    
    private fun showMenuSearchOverlay() {
        removeOverlay()
        currentStep = STEP_MENU_SEARCH
        
        val inflater = LayoutInflater.from(this)
        overlayView = inflater.inflate(R.layout.overlay_menu, null)
        
        val params = createOverlayParams()
        setupDragToMove(params)
        setupMenuSearchViews()
        
        windowManager.addView(overlayView, params)
        
        overlayView?.alpha = 0f
        overlayView?.animate()?.alpha(1f)?.setDuration(300)?.start()
    }
    
    private fun createOverlayParams(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 50
            y = 200
        }
    }
    
    private fun setupDragToMove(params: WindowManager.LayoutParams) {
        overlayView?.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(overlayView, params)
                    true
                }
                else -> false
            }
        }
    }
    
    private fun setupStoreSearchViews() {
        micButtonFrame = overlayView?.findViewById(R.id.micButtonFrame)
        waveIndicator = overlayView?.findViewById(R.id.waveIndicator)
        statusTextView = overlayView?.findViewById(R.id.tvStoreResult)
        
        micButtonFrame?.setOnClickListener {
            if (!isListening) {
                startVoiceRecognition()
            } else {
                stopVoiceRecognition()
            }
        }
        
        overlayView?.findViewById<TextView>(R.id.btnClose)?.setOnClickListener {
            animateClose()
        }
        
        // 화면 캡처 버튼들
        overlayView?.findViewById<TextView>(R.id.btnCaptureStore)?.setOnClickListener {
            captureAndAnalyzeScreen("store")
        }
        
        overlayView?.findViewById<TextView>(R.id.btnCaptureMenu)?.setOnClickListener {
            captureAndAnalyzeScreen("food")
        }
        
        resetToInitialState()
    }
    
    private fun setupMenuSearchViews() {
        micButtonFrame = overlayView?.findViewById(R.id.micButtonFrame)
        waveIndicator = overlayView?.findViewById(R.id.waveIndicator)
        statusTextView = overlayView?.findViewById(R.id.tvMenuResult)
        
        val tvSelectedStore = overlayView?.findViewById<TextView>(R.id.tvSelectedStore)
        tvSelectedStore?.text = "선택된 가게: $selectedStore"
        
        micButtonFrame?.setOnClickListener {
            if (!isListening) {
                startVoiceRecognition()
            } else {
                stopVoiceRecognition()
            }
        }
        
        overlayView?.findViewById<TextView>(R.id.btnBack)?.setOnClickListener {
            showStoreSearchOverlay()
        }
        
        overlayView?.findViewById<TextView>(R.id.btnClose)?.setOnClickListener {
            animateClose()
        }
        
        resetToInitialState()
    }
    
    private fun startVoiceRecognition() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.KOREAN)
            putExtra(RecognizerIntent.EXTRA_PROMPT, if (currentStep == STEP_STORE_SEARCH) "가게 이름을 말씀해주세요" else "메뉴 이름을 말씀해주세요")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        
        speechRecognizer?.startListening(intent)
    }
    
    private fun stopVoiceRecognition() {
        speechRecognizer?.cancel()
        isListening = false
        stopListeningAnimation()
        resetToInitialState()
    }
    
    private fun startListeningAnimation() {
        micButtonFrame?.setBackgroundResource(R.drawable.mic_button_listening)
        
        startPulseAnimation()
        startWaveAnimation()
    }
    
    private fun stopListeningAnimation() {
        micButtonFrame?.setBackgroundResource(R.drawable.mic_button_selector)
        
        pulseAnimator?.cancel()
        waveAnimator?.cancel()
        waveIndicator?.visibility = View.GONE
        
        overlayView?.findViewById<View>(R.id.pulseCircle1)?.visibility = View.GONE
        overlayView?.findViewById<View>(R.id.pulseCircle2)?.visibility = View.GONE
    }
    
    private fun startPulseAnimation() {
        val pulse1 = overlayView?.findViewById<View>(R.id.pulseCircle1)
        val pulse2 = overlayView?.findViewById<View>(R.id.pulseCircle2)
        
        pulse1?.visibility = View.VISIBLE
        pulse2?.visibility = View.VISIBLE
        
        val animator1 = createPulseAnimator(pulse1)
        val animator2 = createPulseAnimator(pulse2, 500)
        
        pulseAnimator = AnimatorSet().apply {
            playTogether(animator1, animator2)
            start()
        }
    }
    
    private fun createPulseAnimator(view: View?, startDelay: Long = 0): AnimatorSet {
        val scaleX = ObjectAnimator.ofFloat(view, "scaleX", 1f, 1.3f, 1f).apply {
            duration = 1500
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
        }
        val scaleY = ObjectAnimator.ofFloat(view, "scaleY", 1f, 1.3f, 1f).apply {
            duration = 1500
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
        }
        val alpha = ObjectAnimator.ofFloat(view, "alpha", 0.8f, 0f, 0.8f).apply {
            duration = 1500
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
        }
        
        return AnimatorSet().apply {
            playTogether(scaleX, scaleY, alpha)
            this.startDelay = startDelay
        }
    }
    
    private fun startWaveAnimation() {
        waveIndicator?.visibility = View.VISIBLE
        
        val waves = listOf(
            overlayView?.findViewById<View>(R.id.wave1),
            overlayView?.findViewById<View>(R.id.wave2),
            overlayView?.findViewById<View>(R.id.wave3),
            overlayView?.findViewById<View>(R.id.wave4),
            overlayView?.findViewById<View>(R.id.wave5)
        )
        
        val animators = waves.mapIndexed { index, wave ->
            ObjectAnimator.ofFloat(wave, "scaleY", 0.5f, 1.5f, 0.5f).apply {
                duration = 800
                repeatCount = ValueAnimator.INFINITE
                startDelay = index * 100L
                interpolator = AccelerateDecelerateInterpolator()
            }
        }
        
        waveAnimator = AnimatorSet().apply {
            playTogether(animators)
            start()
        }
    }
    
    private fun animateWaveBasedOnVolume(rmsdB: Float) {
        val normalizedVolume = (rmsdB + 10f) / 10f
        val scale = 0.5f + normalizedVolume * 1f
        
        overlayView?.findViewById<View>(R.id.wave3)?.animate()
            ?.scaleY(scale)
            ?.setDuration(100)
            ?.start()
    }
    
    private fun handleSpeechResult(recognizedText: String) {
        updateStatusUI("들은 내용: \"$recognizedText\"", "#2196F3")
        
        // 스마트 음성 명령어 처리
        lifecycleScope.launch {
            try {
                val voiceCommand = voiceCommandProcessor.processVoiceCommand(recognizedText)
                
                if (voiceCommand != null) {
                    handleVoiceCommand(voiceCommand)
                } else {
                    // 기존 단계별 처리
                    when (currentStep) {
                        STEP_STORE_SEARCH -> refineAndSearchStore(recognizedText)
                        STEP_MENU_SEARCH -> refineAndSearchMenu(recognizedText)
                    }
                }
            } catch (e: Exception) {
                Log.e("OverlayService", "Voice command processing error", e)
                // 오류 시 기존 방식으로 fallback
                when (currentStep) {
                    STEP_STORE_SEARCH -> refineAndSearchStore(recognizedText)
                    STEP_MENU_SEARCH -> refineAndSearchMenu(recognizedText)
                }
            }
        }
    }
    
    // 음성 명령어 실행
    private fun handleVoiceCommand(command: VoiceCommandProcessor.VoiceCommand) {
        val responseMessage = voiceCommandProcessor.generateCommandResponse(command)
        updateStatusUI("🤖 $responseMessage", "#4CAF50")
        
        when (command.command) {
            "OPEN_BAEMIN" -> {
                Handler(Looper.getMainLooper()).postDelayed({
                    openBaeminApp()
                }, 1000)
            }
            
            "ANALYZE_SCREEN" -> {
                Handler(Looper.getMainLooper()).postDelayed({
                    captureAndAnalyzeScreen("all")
                }, 1000)
            }
            
            "FIND_STORE" -> {
                Handler(Looper.getMainLooper()).postDelayed({
                    captureAndAnalyzeScreen("store")
                }, 1000)
            }
            
            "FIND_MENU" -> {
                Handler(Looper.getMainLooper()).postDelayed({
                    captureAndAnalyzeScreen("food")
                }, 1000)
            }
            
            "SEARCH_STORE" -> {
                val storeName = command.parameters["store_name"] ?: ""
                Handler(Looper.getMainLooper()).postDelayed({
                    searchStore(storeName)
                }, 1000)
            }
            
            "SEARCH_MENU" -> {
                val menuName = command.parameters["menu_name"] ?: ""
                Handler(Looper.getMainLooper()).postDelayed({
                    searchMenu(menuName)
                }, 1000)
            }
            
            "PLACE_ORDER" -> {
                Handler(Looper.getMainLooper()).postDelayed({
                    simulateOrderPlacement()
                }, 1000)
            }
            
            "SHOW_HELP" -> {
                updateStatusUI(responseMessage, "#2196F3")
                Handler(Looper.getMainLooper()).postDelayed({
                    resetToInitialState()
                }, 5000)
            }
            
            else -> {
                // 기본 처리
                Handler(Looper.getMainLooper()).postDelayed({
                    resetToInitialState()
                }, 2000)
            }
        }
    }
    
    // 배달의민족 앱 실행
    private fun openBaeminApp() {
        try {
            val intent = this.packageManager.getLaunchIntentForPackage("com.sampleapp")
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                updateStatusUI("✅ 배달의민족이 실행되었습니다\n화면을 확인해주세요", "#4CAF50")
            } else {
                updateStatusUI("❌ 배달의민족 앱이 설치되어 있지 않습니다", "#FF5722")
            }
        } catch (e: Exception) {
            updateStatusUI("❌ 앱 실행에 실패했습니다", "#FF5722")
        }
        
        Handler(Looper.getMainLooper()).postDelayed({
            resetToInitialState()
        }, 3000)
    }
    
    // 주문 진행 시뮬레이션
    private fun simulateOrderPlacement() {
        updateStatusUI("🛒 주문을 진행합니다...", "#FF9800")
        
        Handler(Looper.getMainLooper()).postDelayed({
            updateStatusUI("✅ 주문이 완료되었습니다!\n감사합니다", "#4CAF50")
            
            Handler(Looper.getMainLooper()).postDelayed({
                resetToInitialState()
            }, 3000)
        }, 2000)
    }
    
    private fun refineAndSearchStore(rawText: String) {
        lifecycleScope.launch {
            try {
                updateStatusUI("🔄 음성을 분석 중입니다...", "#FF9800")
                
                // OCR API로 가게이름 정제
                ocrApiService.refineStoreName(rawText).fold(
                    onSuccess = { refinedStoreName ->
                        if (refinedStoreName.isNotBlank()) {
                            updateStatusUI("정제된 가게명: \"$refinedStoreName\"", "#2196F3")
                            searchStore(refinedStoreName)
                        } else {
                            // 정제 결과가 없으면 원본으로 검색
                            updateStatusUI("원본 텍스트로 검색: \"$rawText\"", "#FF9800")
                            searchStore(rawText)
                        }
                    },
                    onFailure = { error ->
                        android.util.Log.w("OverlayService", "OCR refine failed: ${error.message}")
                        // OCR API 실패 시 원본으로 검색
                        updateStatusUI("원본 텍스트로 검색: \"$rawText\"", "#FF9800")
                        searchStore(rawText)
                    }
                )
            } catch (e: Exception) {
                android.util.Log.e("OverlayService", "Speech refine error", e)
                // 예외 발생 시 원본으로 검색
                updateStatusUI("원본 텍스트로 검색: \"$rawText\"", "#FF9800")
                searchStore(rawText)
            }
        }
    }
    
    private fun refineAndSearchMenu(rawText: String) {
        lifecycleScope.launch {
            try {
                updateStatusUI("🔄 음성을 분석 중입니다...", "#FF9800")
                
                // OCR API로 음식이름 정제
                ocrApiService.refineFoodName(rawText).fold(
                    onSuccess = { refinedFoodName ->
                        if (refinedFoodName.isNotBlank()) {
                            updateStatusUI("정제된 메뉴명: \"$refinedFoodName\"", "#2196F3")
                            searchMenu(refinedFoodName)
                        } else {
                            // 정제 결과가 없으면 원본으로 검색
                            updateStatusUI("원본 텍스트로 검색: \"$rawText\"", "#FF9800")
                            searchMenu(rawText)
                        }
                    },
                    onFailure = { error ->
                        android.util.Log.w("OverlayService", "OCR refine failed: ${error.message}")
                        // OCR API 실패 시 원본으로 검색
                        updateStatusUI("원본 텍스트로 검색: \"$rawText\"", "#FF9800")
                        searchMenu(rawText)
                    }
                )
            } catch (e: Exception) {
                android.util.Log.e("OverlayService", "Speech refine error", e)
                // 예외 발생 시 원본으로 검색
                updateStatusUI("원본 텍스트로 검색: \"$rawText\"", "#FF9800")
                searchMenu(rawText)
            }
        }
    }
    
    // 화면 캡처 및 분석
    private fun captureAndAnalyzeScreen(filterType: String) {
        if (!screenshotManager.hasScreenshotPermission()) {
            updateStatusUI("⚠️ 화면 캡처 권한이 필요합니다\n설정에서 권한을 허용해주세요", "#FF5722")
            
            // 권한 요청 (데모용으로 간단히 구현)
            Handler(Looper.getMainLooper()).postDelayed({
                updateStatusUI("🔄 권한 요청을 진행합니다...", "#FF9800")
                
                // 실제로는 권한 요청 액티비티를 실행해야 함
                // 데모용으로 5초 후 성공 상태로 변경
                Handler(Looper.getMainLooper()).postDelayed({
                    performScreenCapture(filterType)
                }, 3000)
            }, 2000)
            return
        }
        
        performScreenCapture(filterType)
    }
    
    private fun performScreenCapture(filterType: String) {
        lifecycleScope.launch {
            try {
                updateStatusUI("📱 화면을 캡처하고 있습니다...", "#2196F3")
                
                // 데모용 더미 데이터 (실제 스크린샷 대신)
                val demoResults = generateDemoScreenResults(filterType)
                
                if (demoResults.isNotEmpty()) {
                    val resultText = when (filterType) {
                        "store" -> {
                            selectedStore = demoResults.first()
                            "✅ 화면에서 '${demoResults.first()}' 가게를 찾았습니다!\n잠시 후 메뉴 선택으로 넘어갑니다"
                        }
                        "food" -> {
                            "✅ 화면에서 메뉴를 찾았습니다:\n${demoResults.take(3).joinToString(", ")}"
                        }
                        else -> {
                            "✅ 화면 분석 완료:\n${demoResults.take(5).joinToString(", ")}"
                        }
                    }
                    
                    updateStatusUI(resultText, "#4CAF50")
                    
                    if (filterType == "store" && demoResults.isNotEmpty()) {
                        Handler(Looper.getMainLooper()).postDelayed({
                            showMenuSearchOverlay()
                        }, 3000)
                    }
                } else {
                    updateStatusUI("❌ 화면에서 ${if(filterType == "store") "가게" else "메뉴"}를 찾을 수 없었습니다\n다른 방법을 시도해보세요", "#FF5722")
                    Handler(Looper.getMainLooper()).postDelayed({
                        resetToInitialState()
                    }, 3000)
                }
                
            } catch (e: Exception) {
                Log.e("OverlayService", "Screen capture error", e)
                updateStatusUI("❌ 화면 분석 중 오류가 발생했습니다\n음성 인식을 사용해보세요", "#FF5722")
                Handler(Looper.getMainLooper()).postDelayed({
                    resetToInitialState()
                }, 3000)
            }
        }
    }
    
    // 데모용 화면 분석 결과 생성
    private fun generateDemoScreenResults(filterType: String): List<String> {
        return when (filterType) {
            "store" -> listOf("맥도날드", "버거킹", "피자헛").shuffled().take(1)
            "food" -> listOf("빅맥세트", "치킨버거", "감자튀김", "콜라", "맥너겟", "와퍼", "치킨킹").shuffled().take(4)
            else -> listOf("맥도날드", "빅맥세트", "8,500원", "주문하기", "장바구니").shuffled().take(5)
        }
    }
    
    private fun searchStore(storeName: String) {
        val foundStore = hardcodedStores.keys.find { 
            it.contains(storeName, ignoreCase = true) || storeName.contains(it, ignoreCase = true)
        }
        
        if (foundStore != null) {
            selectedStore = foundStore
            updateStatusUI("✅ $foundStore 을(를) 찾았습니다!\n잠시 후 메뉴 선택으로 넘어갑니다", "#4CAF50")
            
            Handler(Looper.getMainLooper()).postDelayed({
                showMenuSearchOverlay()
            }, 2500)
        } else {
            updateStatusUI("❌ '$storeName' 가게를 찾을 수 없어요\n다른 이름으로 다시 말씀해주세요", "#FF5722")
            Handler(Looper.getMainLooper()).postDelayed({
                resetToInitialState()
            }, 3000)
        }
    }
    
    private fun searchMenu(menuName: String) {
        val storeMenus = hardcodedStores[selectedStore] ?: emptyList()
        val foundMenus = storeMenus.filter { 
            it.contains(menuName, ignoreCase = true) || menuName.contains(it, ignoreCase = true)
        }
        
        if (foundMenus.isNotEmpty()) {
            updateStatusUI("✅ 찾은 메뉴:\n${foundMenus.joinToString("\n• ", "• ")}", "#4CAF50")
        } else {
            updateStatusUI("❌ '$menuName' 메뉴를 찾을 수 없어요\n다른 이름으로 다시 말씀해주세요", "#FF5722")
            Handler(Looper.getMainLooper()).postDelayed({
                resetToInitialState()
            }, 3000)
        }
    }
    
    private fun updateStatusUI(message: String, color: String) {
        statusTextView?.text = message
    }
    
    private fun resetToInitialState() {
        val initialMessage = if (currentStep == STEP_STORE_SEARCH) {
            "마이크 버튼을 터치해서 가게 이름을 말씀해주세요"
        } else {
            "마이크 버튼을 터치해서 메뉴 이름을 말씀해주세요"
        }
        updateStatusUI(initialMessage, "#666666")
    }
    
    private fun animateClose() {
        overlayView?.animate()
            ?.alpha(0f)
            ?.scaleX(0.8f)
            ?.scaleY(0.8f)
            ?.setDuration(200)
            ?.withEndAction {
                stopSelf()
            }
            ?.start()
    }
    
    private fun removeOverlay() {
        stopListeningAnimation()
        overlayView?.let {
            windowManager.removeView(it)
            overlayView = null
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        speechRecognizer?.destroy()
        removeOverlay()
    }
}