package com.androlink.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.androlink.app.service.AndrolinkForegroundService
import com.androlink.app.service.NotificationListener

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF6366F1),
                    background = Color(0xFF0F172A),
                    surface = Color(0xFF1E293B),
                    onBackground = Color(0xFFF8FAFC),
                    onSurface = Color(0xFFF8FAFC)
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen()
                }
            }
        }
    }
}

@Composable
fun MainScreen() {
    val context = LocalContext.current
    var isRunning by remember { mutableStateOf(AndrolinkForegroundService.isRunning) }
    var isConnected by remember { mutableStateOf(AndrolinkForegroundService.isConnected) }
    var peerName by remember { mutableStateOf(AndrolinkForegroundService.currentPeerName) }
    var hasNotifPermission by remember { mutableStateOf(isNotificationServiceEnabled(context)) }

    DisposableEffect(Unit) {
        AndrolinkForegroundService.onStateChanged = {
            isRunning = AndrolinkForegroundService.isRunning
            isConnected = AndrolinkForegroundService.isConnected
            peerName = AndrolinkForegroundService.currentPeerName
        }
        onDispose {
            AndrolinkForegroundService.onStateChanged = null
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Androlink",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = "Seamless Android to Windows integration",
            fontSize = 14.sp,
            color = Color(0xFF94A3B8)
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Connection Status Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertilingAlignment,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Computer,
                        contentDescription = null,
                        tint = if (isConnected) Color(0xFF22C55E) else Color(0xFF94A3B8),
                        modifier = Modifier.size(32.dp)
                    )
                    Column {
                        Text(
                            text = if (isConnected) "Connected to PC" else if (isRunning) "Searching for PC..." else "Service Stopped",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp
                        )
                        Text(
                            text = if (isConnected) peerName else "Make sure your PC and phone are on the same Wi-Fi",
                            fontSize = 13.sp,
                            color = Color(0xFF94A3B8)
                        )
                    }
                }

                Button(
                    onClick = {
                        val intent = Intent(context, AndrolinkForegroundService::class.java)
                        if (isRunning) {
                            context.stopService(intent)
                        } else {
                            ContextCompat.startForegroundService(context, intent)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isRunning) Color(0xFFEF4444) else MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.PowerSettingsNew,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (isRunning) "Stop Service" else "Start Service")
                }
            }
        }

        // Permissions Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = if (hasNotifPermission) Icons.Default.CheckCircle else Icons.Default.Notifications,
                        contentDescription = null,
                        tint = if (hasNotifPermission) Color(0xFF22C55E) else Color(0xFFEAB308)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "Notification Access", fontWeight = FontWeight.SemiBold)
                        Text(
                            text = if (hasNotifPermission) "Granted" else "Required to mirror notifications",
                            fontSize = 12.sp,
                            color = Color(0xFF94A3B8)
                        )
                    }
                    if (!hasNotifPermission) {
                        OutlinedButton(
                            onClick = {
                                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                            },
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Grant", fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

fun isNotificationServiceEnabled(context: Context): Boolean {
    val pkgName = context.packageName
    val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
    val names = flat.split(":")
    for (name in names) {
        val cn = ComponentName.unflattenFromString(name)
        if (cn != null && cn.packageName == pkgName) {
            return true
        }
    }
    return false
}
