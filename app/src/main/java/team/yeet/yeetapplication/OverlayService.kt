package team.yeet.yeetapplication

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.util.*

class OverlayService : Service() {
    
    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private var currentStep = STEP_STORE_SEARCH
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    
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
        initializeSpeechRecognizer()
        showStoreSearchOverlay()
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
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
        when (currentStep) {
            STEP_STORE_SEARCH -> {
                updateStatusUI("들은 내용: \"$recognizedText\"", "#2196F3")
                searchStore(recognizedText)
            }
            STEP_MENU_SEARCH -> {
                updateStatusUI("들은 내용: \"$recognizedText\"", "#2196F3")
                searchMenu(recognizedText)
            }
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