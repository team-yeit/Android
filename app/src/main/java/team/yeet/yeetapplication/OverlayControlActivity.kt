package team.yeet.yeetapplication

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import team.yeet.yeetapplication.ui.theme.YeetApplicationTheme

class OverlayControlActivity : ComponentActivity() {
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        setContent {
            YeetApplicationTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    OverlayControlScreen(
                        modifier = Modifier.padding(innerPadding),
                        onStartOrder = { startOverlayService() },
                        onStopOrder = { stopOverlayService() },
                        onBack = { finish() }
                    )
                }
            }
        }
    }
    
    private fun startOverlayService() {
        val intent = Intent(this, WebOverlayService::class.java)
        startService(intent)
        Toast.makeText(this, "웹 기반 음성 주문 도우미를 시작합니다", Toast.LENGTH_SHORT).show()
    }
    
    private fun stopOverlayService() {
        val intent = Intent(this, WebOverlayService::class.java)
        stopService(intent)
        Toast.makeText(this, "주문 도우미를 종료했습니다", Toast.LENGTH_SHORT).show()
    }
}

@Composable
fun OverlayControlScreen(
    modifier: Modifier = Modifier,
    onStartOrder: () -> Unit,
    onStopOrder: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "🎯",
            style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.padding(bottom = 16.dp)
        )
        
        Text(
            text = "주문 준비 완료!",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 16.dp)
        )
        
        Text(
            text = "이제 웹 기반 음성 인터페이스로 쉽게 주문할 수 있습니다.\n주문을 시작하면 화면 위에 모던한 웹 도우미가 나타나서\n가게 이름을 물어봅니다.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = 48.dp)
        )
        
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "📝 사용 방법",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Text(
                    text = "1. '주문 시작하기' 버튼을 누르세요\n2. 화면에 나타나는 웹 도우미에게 가게 이름을 말하세요\n3. 원하는 메뉴를 음성으로 말하세요\n4. 검색 결과를 확인하세요\n5. 드래그로 위치 조정이 가능합니다",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        
        Button(
            onClick = onStartOrder,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(bottom = 16.dp)
        ) {
            Text(
                text = "주문 시작하기",
                style = MaterialTheme.typography.titleMedium
            )
        }
        
        OutlinedButton(
            onClick = onStopOrder,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(bottom = 16.dp)
        ) {
            Text("주문 도우미 중지")
        }
        
        TextButton(onClick = onBack) {
            Text("돌아가기")
        }
    }
}