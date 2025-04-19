package dev.imranr.freebug

import android.Manifest
import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.imranr.freebug.ui.theme.FreebugTheme
import androidx.core.net.toUri

const val TAG = "RecordingSession"

class MainActivity : ComponentActivity() {
    private val hasPowerException = mutableStateOf(true)
    private val hasNotificationPermission = mutableStateOf(true)
    private val hasSensitiveNotificationsPermission = mutableStateOf(true)
    private val hasNotificationPostPermission = mutableStateOf(true)
    private val hasMicrophonePermission = mutableStateOf(true)
    private val hasAccessibilityPermission = mutableStateOf(true)
    private var showADBDialog = mutableStateOf(false)
    private var currentExplanation = mutableStateOf<PermissionConfig?>(null)

    private lateinit var permissionLauncher: ActivityResultLauncher<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupNotificationChannels()
        setupPermissionLauncher()
        enableEdgeToEdge()

        setContent {
            FreebugTheme {
                MainContent()
                ExplanationDialog()
                ADBDialog()
            }
        }
    }

    private data class PermissionConfig(
        val title: String,
        val explanation: String
    )

    private val permissions = listOf(
        PermissionConfig(
            "Allow Microphone Access",
            "This is required to record call audio."
        ),
        PermissionConfig(
            "Disable Battery Optimizations",
            "This ensures that Freebug is always ready to record calls."
        ),
        PermissionConfig(
            "Allow Notification Posting",
            "This enables Freebug to notify you of recording status and results."
        ),
        PermissionConfig(
            "Allow Sensitive Notification Access",
            "This allows Freebug to read all notification text. That enables accurate call detection and contact name/number extraction."
        ),
        PermissionConfig(
            "Allow General Notification Access",
            "This allows Freebug to monitor notifications, which is required to tell when calls start and end."
        ),
        PermissionConfig(
            "Enable Accessibility Service",
            "This is required for Freebug to record calls even when it is not the currently open app."
        )
    )

    @Composable
    private fun MainContent() {
        Scaffold { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                verticalArrangement = Arrangement.SpaceEvenly,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                HeaderText()
                Column {
                    permissions.forEachIndexed { index, config ->
                        PermissionRow(
                            config = config,
                            enabled = when (index) {
                                0 -> !hasMicrophonePermission.value
                                1 -> !hasPowerException.value
                                2 -> !hasNotificationPostPermission.value
                                3 -> !hasSensitiveNotificationsPermission.value
                                4 -> !hasNotificationPermission.value && hasSensitiveNotificationsPermission.value
                                5 -> !hasAccessibilityPermission.value && hasSensitiveNotificationsPermission.value && hasNotificationPermission.value
                                else -> false
                            },
                            onClick = {
                                when (index) {
                                    0 -> askForMicrophonePermission()
                                    1 -> askForBatteryOptimizations()
                                    2 -> askForNotificationPostPermission()
                                    3 -> showADBDialog.value = true
                                    4 -> askForNotificationAccess()
                                    5 -> askForAccessibilityPermission()
                                }
                            }
                        )
                    }
                }
                StatusText()
            }
        }
    }

    @Composable
    private fun PermissionRow(
        config: PermissionConfig,
        enabled: Boolean,
        onClick: () -> Unit
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(vertical = 2.dp, horizontal = 12.dp)
        ) {
            Button(
                onClick = onClick,
                enabled = enabled,
                modifier = Modifier.weight(1f)
            ) {
                Text(config.title)
            }
            Spacer(modifier = Modifier.size(12.dp))
            IconButton(
                onClick = { currentExplanation.value = config },
                Modifier.size(24.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = "Permission info",
                    tint = MaterialTheme.colorScheme.primary.copy(
                        alpha = if (enabled) 1f else 0.5f
                    )
                )
            }
        }
    }

    @Composable
    private fun ExplanationDialog() {
        currentExplanation.value?.let { config ->
            AlertDialog(
                onDismissRequest = { currentExplanation.value = null },
                title = { Text(config.title) },
                text = { Text(config.explanation) },
                confirmButton = {
                    Button(onClick = { currentExplanation.value = null }) {
                        Text("OK")
                    }
                }
            )
        }
    }

    @Composable
    private fun ADBDialog() {
        if (showADBDialog.value) {
            val context = LocalContext.current
            val adbCommand = "adb shell appops set $packageName RECEIVE_SENSITIVE_NOTIFICATIONS allow"

            AlertDialog(
                onDismissRequest = { showADBDialog.value = false; updatePermissionStates() },
                title = { Text("ADB Permission Required") },
                text = {
                    Column {
                        Text("Run this command using Android Debug Bridge:")
                        Spacer(Modifier.height(8.dp))
                        Text(
                            adbCommand,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.clickable {
                                copyToClipboard("ADB Command", adbCommand)
                            }
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "Click here to learn more about ADB",
                            color = MaterialTheme.colorScheme.primary,
                            style = TextStyle(textDecoration = TextDecoration.Underline),
                            modifier = Modifier.clickable {
                                context.startActivity(Intent(
                                    Intent.ACTION_VIEW,
                                    "https://developer.android.com/studio/command-line/adb".toUri()
                                ))
                            }
                        )
                    }
                },
                confirmButton = {
                    Button(onClick = { showADBDialog.value = false; updatePermissionStates() }) {
                        Text("Done")
                    }
                }
            )
        }
    }

    private fun setupNotificationChannels() {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(recordingNotificationChannel)
        notificationManager.createNotificationChannel(recordingSavedNotificationChannel)
    }

    private fun setupPermissionLauncher() {
        permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            if (!it) showPermissionWarning()
            updatePermissionStates()
        }
    }

    private fun updatePermissionStates() {
        hasPowerException.value = (getSystemService(POWER_SERVICE) as PowerManager)
            .isIgnoringBatteryOptimizations(packageName)
        hasNotificationPermission.value = NotificationManagerCompat.getEnabledListenerPackages(this)
            .contains(packageName)
        hasNotificationPostPermission.value = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else true
        hasMicrophonePermission.value = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        hasSensitiveNotificationsPermission.value = checkSensitiveNotificationsPermission()
        hasAccessibilityPermission.value = checkAccessibilityPermission()
        if (hasAllRequiredPermissions()) startServices()
    }

    private fun hasAllRequiredPermissions() =
        hasNotificationPermission.value &&
                hasMicrophonePermission.value &&
                hasNotificationPostPermission.value &&
                hasSensitiveNotificationsPermission.value &&
//              hasPowerException.value && // This is not strictly necessary
                hasAccessibilityPermission.value

    private fun checkAccessibilityPermission(): Boolean {
        val service = ComponentName(this, CallRecorder::class.java)
        return Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )?.contains(service.flattenToString()) == true
    }

    private fun checkSensitiveNotificationsPermission(): Boolean {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                return true
            }
            val appOps = getSystemService(AppOpsManager::class.java)
            val method = AppOpsManager::class.java.getMethod(
                "unsafeCheckOpNoThrow",
                String::class.java,
                Int::class.javaPrimitiveType,
                String::class.java
            )
            return method.invoke(
                appOps,
                "android:receive_sensitive_notifications",
                Process.myUid(),
                packageName
            ) as Int == AppOpsManager.MODE_ALLOWED
        } catch (_: Exception) {
            return false
        }
    }

    private fun startServices() {
        startService(Intent(this, CallMonitor::class.java))
        startService(Intent(this, CallRecorder::class.java))
    }

    private fun showPermissionWarning() {
        Toast.makeText(
            this,
            "Please grant this permission in the system settings",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun copyToClipboard(label: String, text: String) {
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(
            ClipData.newPlainText(label, text)
        )
    }

    @Composable
    private fun HeaderText() {
        Text(
            "Freebug",
            fontSize = MaterialTheme.typography.headlineLarge.fontSize,
            fontWeight = FontWeight.Bold
        )
    }

    @Composable
    private fun StatusText() {
        Text(
            if (hasAllRequiredPermissions()) "Freebug is active!\nAll calls will be recorded." else "Freebug is not active.\nPlease grant the required permissions.",
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(8.dp)
        )
    }

    private fun askForBatteryOptimizations() {
        if (!hasPowerException.value) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun askForNotificationAccess() {
        if (!hasNotificationPermission.value) {
            startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
        }
    }

    private fun askForNotificationPostPermission() {
        if (!hasNotificationPostPermission.value && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun askForMicrophonePermission() {
        if (!hasMicrophonePermission.value) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun askForAccessibilityPermission() {
        if (!hasAccessibilityPermission.value) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
        }
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStates()
    }
}