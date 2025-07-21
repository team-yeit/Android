package team.yeet.yeetapplication

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import team.yeet.yeetapplication.ui.theme.YeetApplicationTheme

class PermissionActivity : ComponentActivity() {
    
    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            checkOverlayPermission()
        } else {
            Toast.makeText(this, "음성 권한이 필요합니다", Toast.LENGTH_SHORT).show()
        }
    }
    
    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { 
        if (Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "모든 권한이 허용되었습니다!", Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, OverlayControlActivity::class.java))
            finish()
        } else {
            Toast.makeText(this, "오버레이 권한이 필요합니다", Toast.LENGTH_SHORT).show()
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        setContent {
            YeetApplicationTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    PermissionScreen(
                        modifier = Modifier.padding(innerPadding),
                        onRequestPermissions = { requestPermissions() },
                        onBack = { finish() }
                    )
                }
            }
        }
        
        requestPermissions()
    }
    
    private fun requestPermissions() {
        val hasAudioPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        
        if (!hasAudioPermission) {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            checkOverlayPermission()
        }
    }
    
    private fun checkOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
        } else {
            startActivity(Intent(this, OverlayControlActivity::class.java))
            finish()
        }
    }
}

@Composable
fun PermissionScreen(
    modifier: Modifier = Modifier,
    onRequestPermissions: () -> Unit,
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
            text = "🔐",
            style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.padding(bottom = 16.dp)
        )
        
        Text(
            text = "권한 설정",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 16.dp)
        )
        
        Text(
            text = "원활한 서비스 이용을 위해\n아래 권한들이 필요합니다:",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = 24.dp)
        )
        
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                PermissionItem("🎤", "음성 권한", "가게와 메뉴 이름을 음성으로 입력")
                Spacer(modifier = Modifier.height(12.dp))
                PermissionItem("📱", "오버레이 권한", "다른 앱 위에 주문 도우미 표시")
                Spacer(modifier = Modifier.height(12.dp))
                PermissionItem("📹", "화면 권한", "배민 앱 화면 분석 (추후 업데이트)")
            }
        }
        
        Button(
            onClick = onRequestPermissions,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(bottom = 16.dp)
        ) {
            Text(
                text = "권한 허용하기",
                style = MaterialTheme.typography.titleMedium
            )
        }
        
        TextButton(onClick = onBack) {
            Text("나중에 하기")
        }
    }
}

@Composable
fun PermissionItem(icon: String, title: String, description: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = icon,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(end = 12.dp)
        )
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}