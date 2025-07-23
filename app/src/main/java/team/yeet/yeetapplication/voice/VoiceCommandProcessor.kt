package team.yeet.yeetapplication.voice

import android.content.Context
import android.util.Log
import team.yeet.yeetapplication.ocr.OcrApiService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class VoiceCommandProcessor(private val context: Context) {
    
    private val ocrApiService = OcrApiService()
    
    companion object {
        private const val TAG = "VoiceCommandProcessor"
        
        // 음성 명령어 패턴들
        private val DELIVERY_COMMANDS = mapOf(
            "배달앱열어" to "OPEN_DELIVERY_APP",
            "배달앱실행" to "OPEN_DELIVERY_APP",
            "배민열어" to "OPEN_BAEMIN",
            "배달의민족열어" to "OPEN_BAEMIN",
            "요기요열어" to "OPEN_YOGIYO",
            "쿠팡이츠열어" to "OPEN_COUPANG_EATS"
        )
        
        private val SCREEN_COMMANDS = mapOf(
            "화면분석" to "ANALYZE_SCREEN",
            "화면보기" to "ANALYZE_SCREEN",
            "스크린분석" to "ANALYZE_SCREEN",
            "가게찾기" to "FIND_STORE",
            "가게찾아" to "FIND_STORE",
            "메뉴찾기" to "FIND_MENU",
            "메뉴찾아" to "FIND_MENU",
            "메뉴보기" to "FIND_MENU"
        )
        
        private val ORDER_COMMANDS = mapOf(
            "주문하기" to "PLACE_ORDER",
            "주문할게" to "PLACE_ORDER",
            "주문해줘" to "PLACE_ORDER",
            "이걸로주문" to "PLACE_ORDER",
            "장바구니" to "GO_TO_CART",
            "장바구니보기" to "GO_TO_CART",
            "결제하기" to "CHECKOUT",
            "결제할게" to "CHECKOUT"
        )
        
        private val HELP_COMMANDS = mapOf(
            "도움말" to "SHOW_HELP",
            "도와줘" to "SHOW_HELP",
            "뭘할수있어" to "SHOW_HELP",
            "사용법" to "SHOW_HELP",
            "어떻게써" to "SHOW_HELP"
        )
    }
    
    data class VoiceCommand(
        val originalText: String,
        val command: String,
        val parameters: Map<String, String> = emptyMap(),
        val confidence: Float = 1.0f
    )
    
    /**
     * 음성 텍스트를 분석하여 명령어 추출
     */
    suspend fun processVoiceCommand(voiceText: String): VoiceCommand? = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Processing voice command: $voiceText")
            
            // 1. 공백 제거 및 소문자 변환
            val cleanText = voiceText.replace("\\s".toRegex(), "").lowercase()
            
            // 2. 직접 명령어 매칭
            val directCommand = findDirectCommand(cleanText)
            if (directCommand != null) {
                return@withContext directCommand
            }
            
            // 3. OCR API로 명령어 정제 시도
            val refinedCommand = tryOcrRefinement(voiceText)
            if (refinedCommand != null) {
                return@withContext refinedCommand
            }
            
            // 4. 부분 매칭 시도
            val partialCommand = findPartialCommand(cleanText, voiceText)
            if (partialCommand != null) {
                return@withContext partialCommand
            }
            
            Log.d(TAG, "No command found for: $voiceText")
            return@withContext null
            
        } catch (e: Exception) {
            Log.e(TAG, "Error processing voice command", e)
            return@withContext null
        }
    }
    
    /**
     * 직접 명령어 매칭
     */
    private fun findDirectCommand(cleanText: String): VoiceCommand? {
        // 배달 앱 명령어 확인
        DELIVERY_COMMANDS.forEach { (pattern, command) ->
            if (cleanText.contains(pattern)) {
                return VoiceCommand(cleanText, command)
            }
        }
        
        // 화면 관련 명령어 확인
        SCREEN_COMMANDS.forEach { (pattern, command) ->
            if (cleanText.contains(pattern)) {
                return VoiceCommand(cleanText, command)
            }
        }
        
        // 주문 관련 명령어 확인
        ORDER_COMMANDS.forEach { (pattern, command) ->
            if (cleanText.contains(pattern)) {
                return VoiceCommand(cleanText, command)
            }
        }
        
        // 도움말 명령어 확인
        HELP_COMMANDS.forEach { (pattern, command) ->
            if (cleanText.contains(pattern)) {
                return VoiceCommand(cleanText, command)
            }
        }
        
        return null
    }
    
    /**
     * OCR API를 통한 명령어 정제
     */
    private suspend fun tryOcrRefinement(voiceText: String): VoiceCommand? {
        return try {
            // 가게 이름 추출 시도
            ocrApiService.extractFromText(voiceText, "store").fold(
                onSuccess = { storeName ->
                    if (storeName.isNotBlank()) {
                        VoiceCommand(
                            originalText = voiceText,
                            command = "SEARCH_STORE",
                            parameters = mapOf("store_name" to storeName)
                        )
                    } else null
                },
                onFailure = { null }
            )
        } catch (e: Exception) {
            Log.w(TAG, "OCR refinement failed", e)
            null
        }
    }
    
    /**
     * 부분 매칭으로 명령어 찾기
     */
    private fun findPartialCommand(cleanText: String, originalText: String): VoiceCommand? {
        // 숫자 추출 (수량, 가격 등)
        val numberPattern = "\\d+".toRegex()
        val numbers = numberPattern.findAll(originalText).map { it.value }.toList()
        
        // 가게 이름 부분 매칭
        val storeKeywords = listOf("맥도날드", "버거킹", "피자헛", "치킨매니아", "떡볶이왕", "짜장면집", "김밥천국")
        val matchedStore = storeKeywords.find { cleanText.contains(it) }
        
        if (matchedStore != null) {
            return VoiceCommand(
                originalText = originalText,
                command = "SEARCH_STORE", 
                parameters = mapOf("store_name" to matchedStore)
            )
        }
        
        // 메뉴 이름 부분 매칭
        val menuKeywords = listOf("빅맥", "와퍼", "피자", "치킨", "떡볶이", "짜장면", "김밥", "라면")
        val matchedMenu = menuKeywords.find { cleanText.contains(it) }
        
        if (matchedMenu != null) {
            val parameters = mutableMapOf("menu_name" to matchedMenu)
            if (numbers.isNotEmpty()) {
                parameters["quantity"] = numbers.first()
            }
            
            return VoiceCommand(
                originalText = originalText,
                command = "SEARCH_MENU",
                parameters = parameters
            )
        }
        
        return null
    }
    
    /**
     * 명령어 실행 결과 생성
     */
    fun generateCommandResponse(command: VoiceCommand): String {
        return when (command.command) {
            "OPEN_DELIVERY_APP" -> "배달 앱을 실행합니다"
            "OPEN_BAEMIN" -> "배달의민족을 실행합니다"
            "OPEN_YOGIYO" -> "요기요를 실행합니다"
            "OPEN_COUPANG_EATS" -> "쿠팡이츠를 실행합니다"
            
            "ANALYZE_SCREEN" -> "화면을 분석합니다"
            "FIND_STORE" -> "가게를 찾고 있습니다"
            "FIND_MENU" -> "메뉴를 찾고 있습니다"
            
            "SEARCH_STORE" -> {
                val storeName = command.parameters["store_name"]
                "네, '$storeName' 가게를 찾아드리겠습니다"
            }
            
            "SEARCH_MENU" -> {
                val menuName = command.parameters["menu_name"]
                val quantity = command.parameters["quantity"]
                if (quantity != null) {
                    "'$menuName' ${quantity}개를 찾아드리겠습니다"
                } else {
                    "'$menuName'을 찾아드리겠습니다"
                }
            }
            
            "PLACE_ORDER" -> "주문을 진행하겠습니다"
            "GO_TO_CART" -> "장바구니로 이동합니다"
            "CHECKOUT" -> "결제를 진행합니다"
            
            "SHOW_HELP" -> generateHelpMessage()
            
            else -> "명령을 처리하고 있습니다"
        }
    }
    
    /**
     * 도움말 메시지 생성
     */
    private fun generateHelpMessage(): String {
        return """
        🗣️ 사용 가능한 음성 명령어:
        
        📱 앱 실행:
        • "배민 열어" - 배달의민족 실행
        • "요기요 열어" - 요기요 실행
        
        🔍 검색:
        • "맥도날드" - 맥도날드 가게 찾기
        • "빅맥" - 빅맥 메뉴 찾기
        
        📱 화면 분석:
        • "화면 분석" - 현재 화면 분석
        • "가게 찾기" - 화면에서 가게 찾기
        • "메뉴 찾기" - 화면에서 메뉴 찾기
        
        🛒 주문:
        • "주문하기" - 선택한 메뉴 주문
        • "장바구니" - 장바구니 보기
        • "결제하기" - 결제 진행
        """.trimIndent()
    }
    
    /**
     * 명령어 유형 확인
     */
    fun getCommandCategory(command: String): String {
        return when (command) {
            in DELIVERY_COMMANDS.values -> "DELIVERY_APP"
            in SCREEN_COMMANDS.values -> "SCREEN_ANALYSIS"
            in ORDER_COMMANDS.values -> "ORDER_MANAGEMENT"
            "SEARCH_STORE", "SEARCH_MENU" -> "SEARCH"
            else -> "OTHER"
        }
    }
    
    /**
     * 명령어 신뢰도 계산
     */
    fun calculateConfidence(originalText: String, command: VoiceCommand): Float {
        val cleanOriginal = originalText.replace("\\s".toRegex(), "").lowercase()
        
        // 정확한 매칭
        if (DELIVERY_COMMANDS.keys.any { cleanOriginal.contains(it) } ||
            SCREEN_COMMANDS.keys.any { cleanOriginal.contains(it) } ||
            ORDER_COMMANDS.keys.any { cleanOriginal.contains(it) }) {
            return 1.0f
        }
        
        // 부분 매칭
        if (command.parameters.isNotEmpty()) {
            return 0.8f
        }
        
        return 0.6f
    }
}