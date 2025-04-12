package dev.imranr.freebug

import android.Manifest
import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.imranr.freebug.ui.theme.FreebugTheme

const val TAG = "RecordingSession"
const val notif_channel_id = "freebug_notifs"

class MainActivity : ComponentActivity() {
    val hasPowerException = mutableStateOf(true)
    val hasNotificationPermission = mutableStateOf(true)
    val hasSensitiveNotificationsPermission = mutableStateOf(true)
    val hasNotificationPostPermission = mutableStateOf(true)
    val hasMicrophonePermission = mutableStateOf(true)
    val hasAccessibilityPermission = mutableStateOf(true)

    private fun startServices() {
        startService(Intent(this, CallMonitor::class.java))
        startService(Intent(this, CallRecorder::class.java))
    }

    private fun hasAllRequiredPermissions(): Boolean {
        return hasNotificationPermission.value &&
                hasMicrophonePermission.value &&
                hasNotificationPostPermission.value &&
                hasSensitiveNotificationsPermission.value &&
//                hasPowerException.value && // This is not strictly necessary
                hasAccessibilityPermission.value
    }

    private lateinit var permissionLauncher: ActivityResultLauncher<String>

    private fun updatePermissionStates() {
        hasPowerException.value =
            (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(
                packageName
            ) == true
        hasNotificationPermission.value = NotificationManagerCompat.getEnabledListenerPackages(this)
            .contains(packageName)
        hasNotificationPostPermission.value =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            else
                true
        hasMicrophonePermission.value = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        hasSensitiveNotificationsPermission.value = checkSensitiveNotificationsPermission()
        hasAccessibilityPermission.value = checkAccessibilityPermission()
        if (hasAllRequiredPermissions()) {
            startServices()
        }
    }

    private fun checkAccessibilityPermission(): Boolean {
        val service = ComponentName(this, CallRecorder::class.java)
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
        return enabledServices?.contains(service.flattenToString()) == true
    }

    fun checkSensitiveNotificationsPermission(): Boolean {
        return try {
            val appOpsManager = getSystemService(AppOpsManager::class.java)
            val opStr = "android:receive_sensitive_notifications" // Direct string

            // Use unsafeCheckOpNoThrow (replaces deprecated checkOpNoThrow)
            val method = AppOpsManager::class.java.getMethod(
                "unsafeCheckOpNoThrow",
                String::class.java,
                Int::class.javaPrimitiveType,
                String::class.java
            )

            method.invoke(
                appOpsManager,
                opStr,
                Process.myUid(),
                packageName
            ) as Int == AppOpsManager.MODE_ALLOWED
        } catch (_: Exception) {
            false
        }
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStates()
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(
            NotificationChannel(
                notif_channel_id,
                "Freebug",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Freebug call recording notification"
                setSound(null, null)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
                setShowBadge(false)
                enableVibration(false)
            })
        permissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
                if (!isGranted) {
                    Toast.makeText(
                        this,
                        "Please grant this permission in the settings app.",
                        Toast.LENGTH_LONG
                    ).show()
                }
                updatePermissionStates()
            }
        updatePermissionStates()
        enableEdgeToEdge()
        setContent {
            FreebugTheme {
                var showADBDialog = remember { mutableStateOf(false) }
                MainPage(showADBPermissionDialog = { showADBDialog.value = true })
                if (showADBDialog.value) {
                    ADBMessageDialog(
                        onDismiss = { showADBDialog.value = false },
                        onConfirm = {
                            // Handle action (e.g., delete item)
                            showADBDialog.value = false
                            updatePermissionStates()
                        }
                    )
                }
            }
        }
    }

    @Composable
    fun ADBMessageDialog(
        onDismiss: () -> Unit,
        onConfirm: () -> Unit
    ) {
        val adbCommand = "adb shell appops set $packageName RECEIVE_SENSITIVE_NOTIFICATIONS allow"
        AlertDialog(
            onDismissRequest = onDismiss, // Handle outside taps/back press
            title = { Text("Special Permission") },
            text = {
                Column {
                    Text("This permission must be granted via ADB:")
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        adbCommand, fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .clickable {
                                val clipboard =
                                    getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText("Copied ADB command", adbCommand)
                                clipboard.setPrimaryClip(clip)
                            },
                    )
                }
            },
            confirmButton = {
                Button(onClick = onConfirm) {
                    Text("Okay")
                }
            }
        )
    }

    @Composable
    fun MainPage(showADBPermissionDialog: () -> Unit) {
        Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
            Column(
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                Text(
                    "Freebug",
                    modifier = Modifier.padding(
                        innerPadding.calculateRightPadding(
                            LocalLayoutDirection.current
                        ), innerPadding.calculateBottomPadding() * 3
                    ),
                    fontSize = LocalTextStyle.current.fontSize * 2,
                    fontWeight = FontWeight.Bold
                )
                Button(
                    {
                        askForMicrophonePermission()
                    },
                    enabled = !hasMicrophonePermission.value,
                ) { Text("Allow Microphone Access") }
                Button(
                    {
                        askForBatteryOptimizations()
                    },
                    enabled = !hasPowerException.value,
                ) { Text("Disable Battery Optimizations") }
                Button(
                    {
                        askForNotificationPostPermission()
                    },
                    enabled = !hasNotificationPostPermission.value,
                ) { Text("Allow Notification Posting") }
                Button(
                    {
                        if (!hasSensitiveNotificationsPermission.value) {
                            showADBPermissionDialog()
                        }
                    },
                    enabled = !hasSensitiveNotificationsPermission.value,
                ) { Text("Allow Sensitive Notification Access") }
                Button(
                    {
                        askForNotificationAccess()
                    },
                    enabled = !hasNotificationPermission.value && hasSensitiveNotificationsPermission.value,
                ) { Text("Allow General Notification Access") }
                Button(
                    {
                        askForAccessibilityPermission()
                    },
                    enabled = !hasAccessibilityPermission.value && hasSensitiveNotificationsPermission.value && hasSensitiveNotificationsPermission.value,
                ) { Text("Enable Accessibility Service") }
                Text(
                    if (hasAllRequiredPermissions()) "Service is ready." else "Missing permissions.",
                    modifier = Modifier.padding(
                        innerPadding.calculateRightPadding(
                            LocalLayoutDirection.current
                        ), innerPadding.calculateTopPadding() * 3
                    ),
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
