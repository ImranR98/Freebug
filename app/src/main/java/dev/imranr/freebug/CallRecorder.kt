package dev.imranr.freebug

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.lifecycle.Observer
import java.util.concurrent.ConcurrentHashMap

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
            is RecordingEvent.Start -> handleStartRecording(
                event.key,
                event.contactInfo,
                event.callingPackageName
            )

            is RecordingEvent.PotentialStop -> handleStopRecording(event.key)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        RecordingEventBus.events.observeForever(eventObserver)
    }

    private fun handleStartRecording(key: String, contactInfo: String, callingPackageName: String) {
        val matchExactSession = activeSessions.get(key)
        if (matchExactSession != null) {
            // This can happen when an existing call notification is updated
            // In this case, we cancel any pending stop for the existing session and just keep recording
            Log.w(
                TAG,
                "Asked to start recording while already recording (key match): $contactInfo, $callingPackageName"
            )
            if (matchExactSession.stopRequested) {
                matchExactSession.stopRequested = false
                Log.w(
                    TAG,
                    "Cancelled pending stop for recording (key match): $contactInfo, $callingPackageName"
                )
            }
            return
        }
        val matchingExistingSessionsAboutToStop =
            activeSessions.filter { s -> s.value.contactInfo == contactInfo && s.value.callingPackageName == callingPackageName && s.value.stopRequested }
        if (matchingExistingSessionsAboutToStop.isNotEmpty()) {
            // This can also happen when an existing call notification is updated
            // In this case, the notification key is different
            // So we cancel the pending stop for the existing session, add it's new key to the map and just keep recording
            Log.w(
                TAG,
                "Asked to start recording while about to stop (data match): $contactInfo, $callingPackageName"
            )
            matchingExistingSessionsAboutToStop.forEach { // There should never be more than 1 but we use forEach anyways
                it.value.stopRequested = false
                activeSessions[key] = it.value
                Log.w(
                    TAG,
                    "Cancelled pending stop for recording and added to list (data match): $contactInfo, $callingPackageName"
                )
            }
            return
        }

        Log.d(TAG, "Starting recording: $key")
        RecordingSession(this, contactInfo, callingPackageName).apply {
            startRecording(
                onSuccess = { activeSessions[key] = this },
                onError = { error -> Log.e(TAG, "Recording error: $error") }
            )
        }
    }

    private fun stopRecording(key: String) {
        if (!activeSessions.containsKey(key)) {
            Log.w(TAG, "Asked to stop recording while already not recording: $key")
            return
        }
        Log.d(TAG, "Stopping recording: $key")
        val removedSession = activeSessions.remove(key)
        removedSession?.stopRecording()
        // Clean up any dangling sessions left behind by "false stop" cases
        activeSessions.entries.removeAll { (_, session) -> session.isRecordingActive() == false }
    }

    private fun handleStopRecording(key: String) {
        // In some cases, a notification for an ongoing call may disappear and immediately show up again
        // This can create false stop events
        // To work around this we only truly stop if a start event for the same contact+app is not triggered within 2 seconds of a stop event
        activeSessions[key]?.stopRequested = true
        Handler(Looper.getMainLooper()).postDelayed({
            if (activeSessions[key]?.stopRequested == true) {
                stopRecording(key)
            } else if (activeSessions.containsKey(key)) {
                Log.w(TAG, "Stop recording was requested but cancelled: $key")
            }
        }, 2000)
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