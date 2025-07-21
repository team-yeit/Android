package team.yeet.yeetapplication

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log

class KeyboardInputService : AccessibilityService() {

    private val TAG = "KeyboardInputService"

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "team.yeet.yeetapplication.SEND_TEXT" -> {
                    val text = intent.getStringExtra("text") ?: ""
                    sendTextToFocusedElement(text)
                }
                "team.yeet.yeetapplication.SEND_KEY" -> {
                    val keyCode = intent.getIntExtra("keyCode", 0)
                    sendKeyEvent(keyCode)
                }
                "team.yeet.yeetapplication.CLEAR_TEXT" -> {
                    clearFocusedElement()
                }
                "team.yeet.yeetapplication.CLICK_ELEMENT" -> {
                    clickFocusedElement()
                }
                "team.yeet.yeetapplication.FIND_AND_CLICK" -> {
                    val text = intent.getStringExtra("text") ?: ""
                    findAndClickElementByText(text)
                }
                "team.yeet.yeetapplication.TYPE_IN_SEARCH" -> {
                    val searchText = intent.getStringExtra("searchText") ?: ""
                    val boxHint = intent.getStringExtra("boxHint") ?: ""
                    typeInSearchBox(searchText, boxHint)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        // BroadcastReceiver 등록
        val filter = IntentFilter().apply {
            addAction("team.yeet.yeetapplication.SEND_TEXT")
            addAction("team.yeet.yeetapplication.SEND_KEY")
            addAction("team.yeet.yeetapplication.CLEAR_TEXT")
            addAction("team.yeet.yeetapplication.CLICK_ELEMENT")
            addAction("team.yeet.yeetapplication.FIND_AND_CLICK")
            addAction("team.yeet.yeetapplication.TYPE_IN_SEARCH")
        }
        registerReceiver(broadcastReceiver, filter)

        Log.d(TAG, "KeyboardInputService created")
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(broadcastReceiver)
        Log.d(TAG, "KeyboardInputService destroyed")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 접근성 이벤트 처리 (필요에 따라 구현)
        Log.d(TAG, "Accessibility event: ${event?.eventType}")
    }

    override fun onInterrupt() {
        Log.d(TAG, "Service interrupted")
    }

    private fun sendTextToFocusedElement(text: String) {
        try {
            val rootNode = rootInActiveWindow ?: return
            val focusedNode = findFocusedEditableNode(rootNode)

            if (focusedNode != null) {
                val arguments = Bundle()
                arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                Log.d(TAG, "Text sent to focused element: $text")
            } else {
                // 포커스된 요소를 찾을 수 없으면 클립보드 사용
                Log.d(TAG, "No focused element found, using clipboard")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send text", e)
        }
    }

    private fun sendKeyEvent(keyCode: Int) {
        try {
            when (keyCode) {
                4 -> performGlobalAction(GLOBAL_ACTION_BACK) // KEYCODE_BACK
                66 -> { // KEYCODE_ENTER
                    val rootNode = rootInActiveWindow
                    val focusedNode = findFocusedEditableNode(rootNode)
                    focusedNode?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
                67 -> { // KEYCODE_DEL
                    val rootNode = rootInActiveWindow
                    val focusedNode = findFocusedEditableNode(rootNode)
                    if (focusedNode != null) {
                        val text = focusedNode.text?.toString() ?: ""
                        if (text.isNotEmpty()) {
                            val newText = text.dropLast(1)
                            val arguments = Bundle()
                            arguments.putCharSequence(
                                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                                newText
                            )
                            focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                        }
                    }
                }
            }
            Log.d(TAG, "Key event sent: $keyCode")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send key event", e)
        }
    }

    private fun clearFocusedElement() {
        try {
            val rootNode = rootInActiveWindow ?: return
            val focusedNode = findFocusedEditableNode(rootNode)

            if (focusedNode != null) {
                val arguments = Bundle()
                arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
                focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                Log.d(TAG, "Focused element cleared")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear focused element", e)
        }
    }

    private fun clickFocusedElement() {
        try {
            val rootNode = rootInActiveWindow ?: return
            val focusedNode = rootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)

            if (focusedNode != null && focusedNode.isClickable) {
                focusedNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Log.d(TAG, "Focused element clicked")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to click focused element", e)
        }
    }

    private fun findAndClickElementByText(text: String) {
        try {
            val rootNode = rootInActiveWindow ?: return
            val targetNode = findNodeByText(rootNode, text)

            if (targetNode != null && targetNode.isClickable) {
                targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Log.d(TAG, "Element with text '$text' clicked")
            } else {
                Log.d(TAG, "Element with text '$text' not found or not clickable")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to find and click element", e)
        }
    }

    private fun typeInSearchBox(searchText: String, boxHint: String) {
        try {
            val rootNode = rootInActiveWindow ?: return
            val searchBox = if (boxHint.isNotEmpty()) {
                findNodeByHint(rootNode, boxHint)
            } else {
                findSearchBox(rootNode)
            }

            if (searchBox != null) {
                // 검색창에 포커스 주기
                searchBox.performAction(AccessibilityNodeInfo.ACTION_FOCUS)

                // 텍스트 입력
                val arguments = Bundle()
                arguments.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    searchText
                )
                searchBox.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)

                Log.d(TAG, "Search text entered: $searchText")
            } else {
                Log.d(TAG, "Search box not found")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to type in search box", e)
        }
    }

    private fun findFocusedEditableNode(rootNode: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (rootNode == null) return null

        // 현재 포커스된 입력 가능한 노드 찾기
        val focusedNode = rootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focusedNode != null && focusedNode.isEditable) {
            return focusedNode
        }

        // 재귀적으로 모든 자식 노드 검사
        for (i in 0 until rootNode.childCount) {
            val childNode = rootNode.getChild(i)
            val result = findFocusedEditableNode(childNode)
            if (result != null) return result
        }

        return null
    }

    private fun findNodeByText(rootNode: AccessibilityNodeInfo?, targetText: String): AccessibilityNodeInfo? {
        if (rootNode == null) return null

        val nodeText = rootNode.text?.toString() ?: ""
        val contentDescription = rootNode.contentDescription?.toString() ?: ""

        if (nodeText.contains(targetText, ignoreCase = true) ||
            contentDescription.contains(targetText, ignoreCase = true)) {
            return rootNode
        }

        for (i in 0 until rootNode.childCount) {
            val childNode = rootNode.getChild(i)
            val result = findNodeByText(childNode, targetText)
            if (result != null) return result
        }

        return null
    }

    private fun findNodeByHint(rootNode: AccessibilityNodeInfo?, hint: String): AccessibilityNodeInfo? {
        if (rootNode == null) return null

        if (rootNode.isEditable) {
            val nodeHint = rootNode.hintText?.toString() ?: ""
            val contentDescription = rootNode.contentDescription?.toString() ?: ""

            if (nodeHint.contains(hint, ignoreCase = true) ||
                contentDescription.contains(hint, ignoreCase = true)) {
                return rootNode
            }
        }

        for (i in 0 until rootNode.childCount) {
            val childNode = rootNode.getChild(i)
            val result = findNodeByHint(childNode, hint)
            if (result != null) return result
        }

        return null
    }

    private fun findSearchBox(rootNode: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (rootNode == null) return null

        if (rootNode.isEditable) {
            val hint = rootNode.hintText?.toString()?.toLowerCase() ?: ""
            val contentDescription = rootNode.contentDescription?.toString()?.toLowerCase() ?: ""

            if (hint.contains("검색") || hint.contains("search") ||
                contentDescription.contains("검색") || contentDescription.contains("search")) {
                return rootNode
            }
        }

        for (i in 0 until rootNode.childCount) {
            val childNode = rootNode.getChild(i)
            val result = findSearchBox(childNode)
            if (result != null) return result
        }

        return null
    }
}