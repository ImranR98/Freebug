package dev.imranr.freebug

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.lifecycle.Observer
import java.util.concurrent.ConcurrentHashMap
import android.content.Intent

class CallRecorder : AccessibilityService() {

    private fun startCallMonitor() {
        startService(Intent(this, CallMonitor::class.java))
    }

    private val handler = Handler(Looper.getMainLooper())
    private val periodicTask = object : Runnable {
        override fun run() {
            startCallMonitor()
            handler.postDelayed(this, 60000 * 60) // Run every 60 minutes
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "CallRecorder service created.")
        periodicTask.run()
        handler.post(periodicTask)
    }

    private val activeSessions = ConcurrentHashMap<String, RecordingSession>()

    private val eventObserver = Observer<RecordingEvent> { event ->
        when (event) {
            is RecordingEvent.Start -> handleStartRecording(event.key, event.contactInfo, event.callingPackageName)
            is RecordingEvent.Stop -> handleStopRecording(event.key)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        RecordingEventBus.events.observeForever(eventObserver)
    }

    private fun handleStartRecording(key: String, contactInfo: String, callingPackageName: String) {
        if (activeSessions.containsKey(key)) return

        Log.d(TAG, "Starting recording: $key")
        RecordingSession(this, contactInfo, callingPackageName).apply {
            startRecording(
                onSuccess = { activeSessions[key] = this },
                onError = { error -> Log.e(TAG, "Recording error: $error") }
            )
        }
    }

    private fun handleStopRecording(key: String) {
        Log.d(TAG, "Stopping recording: $key")
        activeSessions.remove(key)?.stopRecording()
    }

    override fun onDestroy() {
        RecordingEventBus.events.removeObserver(eventObserver)
        activeSessions.values.forEach { it.stopRecording() }
        handler.removeCallbacks(periodicTask)
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
}