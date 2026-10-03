package com.androlink.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.androlink.app.service.AndrolinkForegroundService
import com.androlink.app.ui.theme.AndrolinkTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            AndrolinkTheme(dynamicColor = true) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MaterialYouScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MaterialYouScreen() {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    val prefs = remember { context.getSharedPreferences("androlink_prefs", Context.MODE_PRIVATE) }
    var pcIpInput by remember { mutableStateOf(prefs.getString("last_ip", "") ?: "") }
    var phoneIp by remember { mutableStateOf(AndrolinkForegroundService.getLocalIpAddress()) }

    var isRunning by remember { mutableStateOf(AndrolinkForegroundService.isRunning) }
    var isConnected by remember { mutableStateOf(AndrolinkForegroundService.isConnected) }
    var peerName by remember { mutableStateOf(AndrolinkForegroundService.currentPeerName) }
    var hasNotifPermission by remember { mutableStateOf(isNotificationServiceEnabled(context)) }

    // File picker launcher
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            val targetIp = pcIpInput.ifBlank { "10.168.123.249" }
            uploadFileToPc(context, uri, targetIp)
        }
    }

    DisposableEffect(Unit) {
        AndrolinkForegroundService.onStateChanged = {
            isRunning = AndrolinkForegroundService.isRunning
            isConnected = AndrolinkForegroundService.isConnected
            peerName = AndrolinkForegroundService.currentPeerName
            phoneIp = AndrolinkForegroundService.getLocalIpAddress()
        }
        onDispose {
            AndrolinkForegroundService.onStateChanged = null
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Androlink",
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Phone to PC integration",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.height(4.dp))

            // 1. Connection Status Card
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = if (isConnected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else if (isRunning) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    }
                )
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isConnected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceVariant
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (isConnected) Icons.Default.Laptop else Icons.Default.Devices,
                                contentDescription = null,
                                tint = if (isConnected) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(28.dp)
                            )
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (isConnected) "Connected to PC"
                                else if (isRunning) "Searching for PC..."
                                else "Ready to Link",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (isConnected) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = if (isConnected) peerName else "This device: $phoneIp",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isConnected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // Start/Stop Service Toggle Button
                    Button(
                        onClick = {
                            val intent = Intent(context, AndrolinkForegroundService::class.java)
                            if (isRunning) {
                                context.stopService(intent)
                            } else {
                                ContextCompat.startForegroundService(context, intent)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isRunning) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(
                            imageVector = if (isRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isRunning) "Disconnect & Stop" else "Start Auto-Discovery",
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            // 2. Direct Connect / Hotspot Mode Card
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.WifiTethering,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Column {
                            Text(
                                text = "Hotspot / Direct IP Connect",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Direct TCP connection over Hotspot",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    OutlinedTextField(
                        value = pcIpInput,
                        onValueChange = { pcIpInput = it.trim() },
                        label = { Text("PC IP Address (e.g. 10.168.123.249)") },
                        placeholder = { Text("Enter IP shown on laptop") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Ascii,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                focusManager.clearFocus()
                                if (pcIpInput.isNotBlank()) {
                                    val intent = Intent(context, AndrolinkForegroundService::class.java)
                                    if (!isRunning) ContextCompat.startForegroundService(context, intent)
                                    AndrolinkForegroundService.serviceInstance?.connectDirectly(pcIpInput)
                                }
                            }
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp)
                    )

                    FilledTonalButton(
                        onClick = {
                            focusManager.clearFocus()
                            if (pcIpInput.isNotBlank()) {
                                val intent = Intent(context, AndrolinkForegroundService::class.java)
                                if (!isRunning) ContextCompat.startForegroundService(context, intent)
                                AndrolinkForegroundService.serviceInstance?.connectDirectly(pcIpInput)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Connect Directly to PC")
                    }
                }
            }

            // 3. Quick Action Tools (KDE Connect Plugin Toolkit)
            Text(
                text = "Quick Tools",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Send File Button
                ElevatedCard(
                    onClick = { filePickerLauncher.launch("*/*") },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Outlined.UploadFile, null, tint = MaterialTheme.colorScheme.primary)
                        Text("Send File", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text("Beam directly to PC Downloads", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                // Ping Laptop Button
                ElevatedCard(
                    onClick = {
                        AndrolinkForegroundService.serviceInstance?.pingLaptop()
                        Toast.makeText(context, "Ping sent to Laptop!", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Outlined.NotificationsActive, null, tint = MaterialTheme.colorScheme.secondary)
                        Text("Ping Laptop", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text("Chimes your PC if lost", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // 4. Permissions Alert
            AnimatedVisibility(visible = !hasNotifPermission) {
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.outlinedCardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.NotificationsActive,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Notification Access Required",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.error
                            )
                            Text(
                                text = "Grant permission to mirror incoming messages to your laptop.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        FilledTonalButton(
                            onClick = {
                                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                            },
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Enable")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

private fun uploadFileToPc(context: Context, uri: Uri, pcIp: String) {
    CoroutineScope(Dispatchers.IO).launch {
        try {
            val contentResolver = context.contentResolver
            var fileName = "upload-${System.currentTimeMillis()}"
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1 && cursor.moveToFirst()) {
                    fileName = cursor.getString(nameIndex)
                }
            }

            val url = URL("http://$pcIp:8702/api/upload")
            val conn = url.openConnection() as HttpURLConnection
            conn.doOutput = true
            conn.requestMethod = "POST"
            conn.setRequestProperty("X-Filename", URLEncoder.encode(fileName, "UTF-8"))
            conn.setRequestProperty("X-Source", "phone")
            conn.setRequestProperty("Content-Type", "application/octet-stream")
            conn.setChunkedStreamingMode(8192)

            contentResolver.openInputStream(uri)?.use { input ->
                conn.outputStream.use { output ->
                    input.copyTo(output)
                }
            }

            val responseCode = conn.responseCode
            withContext(Dispatchers.Main) {
                if (responseCode == 200) {
                    Toast.makeText(context, "File sent to PC Downloads!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Upload failed: $responseCode", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Error sending file: ${e.message}", Toast.LENGTH_SHORT).show()
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
