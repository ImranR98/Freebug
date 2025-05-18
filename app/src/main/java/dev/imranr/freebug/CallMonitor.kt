package dev.imranr.freebug

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import java.util.concurrent.ConcurrentHashMap

sealed class RecordingEvent {
    data class Start(val key: String, val contactInfo: String, val callingPackageName: String) :
        RecordingEvent()

    data class PotentialStop(val key: String) : RecordingEvent()
}

object RecordingEventBus {
    private val _events = MutableLiveData<RecordingEvent>()
    val events: LiveData<RecordingEvent> = _events

    fun postEvent(event: RecordingEvent) {
        _events.postValue(event)
    }
}

class CallMonitor : NotificationListenerService() {
    private val activeSessions = ConcurrentHashMap<String, RecordingSession>()

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "CallMonitor service created.")
    }

    private fun isCallNotification(notification: StatusBarNotification): Boolean {
        val flags = notification.notification.flags
        val extras = notification.notification.extras ?: return false
        val textIndicatesCall = listOf(
            extras.getString(Notification.EXTRA_TITLE),
            extras.getString(Notification.EXTRA_TEXT),
            extras.getString(Notification.EXTRA_SUB_TEXT)
        ).any { text ->
            text?.contains(
                Regex(
                    getString(R.string.call_text_detection_label),
                    RegexOption.IGNORE_CASE
                )
            ) == true
        }
        val hasCallActions = notification.notification.actions?.any { action ->
            action.title.toString().lowercase().let {
                it.contains(
                    getString(R.string.hangup_button_detection_label),
                    ignoreCase = true
                ) || it.contains(
                    getString(R.string.speaker_button_detection_label), ignoreCase = true
                ) || it.contains(
                    getString(R.string.mute_button_detection_label), ignoreCase = true
                ) || it.contains(
                    getString(R.string.leave_button_detection_label), ignoreCase = true
                )
            }
        } == true
        return (
                textIndicatesCall &&
                        hasCallActions &&
                        (flags and Notification.FLAG_ONGOING_EVENT != 0)
                )
    }

    private fun extractContactInfo(notification: StatusBarNotification): String {
        val extras = notification.notification.extras ?: return "Unknown"
        return listOfNotNull(
            extras.getString(Notification.EXTRA_TITLE),
            extras.getString(Notification.EXTRA_TEXT),
            extras.getString(Notification.EXTRA_SUB_TEXT)
        ).firstOrNull { it.contains(Regex("""(\+?\d+)|([\p{L}\s]+)""")) } ?: "Unknown"
    }

    private fun handleCallStart(notification: StatusBarNotification) {
        val contactInfo = extractContactInfo(notification)
        RecordingEventBus.postEvent(
            RecordingEvent.Start(notification.key, contactInfo, notification.packageName)
        )
    }

    private fun handleCallEnd(key: String) {
        RecordingEventBus.postEvent(RecordingEvent.PotentialStop(key))
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn?.takeIf { isCallNotification(it) }?.let { handleCallStart(it) }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn?.let { handleCallEnd(it.key) }
    }

    override fun onDestroy() {
        activeSessions.values.forEach { it.stopRecording() }
        super.onDestroy()
    }
}