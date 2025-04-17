package dev.imranr.freebug

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.media.MediaRecorder
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.io.File
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

val recordingSavedNotificationChannel = NotificationChannel(
    "freebug_recording_saved_notif",
    "Freebug Recording Saved Notification",
    NotificationManager.IMPORTANCE_LOW
).apply {
    description = "Indicates that Freebug successfully recorded a call"
    lockscreenVisibility = Notification.VISIBILITY_SECRET
    enableVibration(false)
}

val recordingFailedNotificationChannel = NotificationChannel(
    "freebug_recording_failed_notif",
    "Freebug Recording Failed Notification",
    NotificationManager.IMPORTANCE_HIGH
).apply {
    description = "Indicates that Freebug failed to record a call"
    lockscreenVisibility = Notification.VISIBILITY_SECRET
}

val recordingNotificationChannel = NotificationChannel(
    "freebug_recording_notif",
    "Freebug Recording Notification",
    NotificationManager.IMPORTANCE_MIN
).apply {
    description = "Indicates that Freebug is recording a call"
    setSound(null, null)
    lockscreenVisibility = Notification.VISIBILITY_SECRET
    setShowBadge(false)
    enableVibration(false)
}

class RecordingSession(
    private val context: Context,
    private val contactInfo: String,
    private val callingPackageName: String
) {
    private var mediaRecorder: MediaRecorder? = null
    private var startTime: ZonedDateTime? = null
    private var outputFile: File? = null
    private var isStopped = false

    @SuppressLint("MissingPermission")
    fun startRecording(onSuccess: () -> Unit, onError: (String) -> Unit) {
        if (isRecordingActive()) {
            return
        }

        try {
            mediaRecorder = MediaRecorder(context).apply {
                setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128000)
                setAudioSamplingRate(44100)

                outputFile = createTempFile().apply {
                    setOutputFile(absolutePath)
                }

                prepare()
                start()
                startTime = ZonedDateTime.now()
                isStopped = false
                onSuccess()
            }
        } catch (e: Exception) {
            cleanup()
            onError("Failed to start recording: ${e.localizedMessage}")
        }

        try {
            NotificationCompat.Builder(context, recordingNotificationChannel.id)
                .setContentTitle("Recording call")
                .setContentText("Call with $contactInfo in $callingPackageName")
                .setSmallIcon(R.drawable.ic_notification)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .build()
                .also {
                    NotificationManagerCompat.from(context)
                        .notify(startTime.hashCode(), it)
                }
        } catch (e: Exception) {
            Log.e(TAG, "Could not post recording notification: $e")
        }
    }

    fun stopRecording() {
        if (isStopped) return
        isStopped = true

        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
            saveRecording()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop recording: ${e.message}")
        } finally {
            cleanup()
        }
    }

    private fun isRecordingActive(): Boolean {
        return mediaRecorder != null && !isStopped
    }

    private fun saveRecording() {
        try {
            val fileName = sanitizeFileName("Call_${contactInfo}_${callingPackageName}_${startTime?.format(
                DateTimeFormatter.ofPattern("yyyyMMddHHmmssXX"))}")
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "audio/mp4")
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    "${Environment.DIRECTORY_RECORDINGS}/CallRecordings"
                )
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, contentValues)
                ?: throw IOException("Failed to create media entry")
            resolver.openOutputStream(uri)?.use { output ->
                outputFile?.inputStream()?.use { input ->
                    input.copyTo(output)
                }
            } ?: throw IOException("Failed to open output stream")

            showSuccessNotification()
        } catch (e: Exception) {
            Log.e(TAG, "Save failed: ${e.message}")
            showErrorNotification()
        } finally {
            cleanup()
        }
    }

    @SuppressLint("MissingPermission")
    private fun showSuccessNotification() {
        NotificationCompat.Builder(context, recordingSavedNotificationChannel.id)
            .setContentTitle("Saved call with $contactInfo")
            .setContentText("Saved to /${Environment.DIRECTORY_RECORDINGS}/CallRecordings")
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
            .also {
                NotificationManagerCompat.from(context)
                    .notify(System.currentTimeMillis().toInt(), it)
            }
    }

    private fun createTempFile(): File {
        return File(context.cacheDir, "temp_rec_${System.currentTimeMillis()}.mp4").apply {
            createNewFile()
        }
    }

    private fun sanitizeFileName(input: String): String {
        val invalidCharsRegex = Regex("[\\\\/:*?\"<>|\\x00-\\x1F\\s]")
        var sanitized = input.replace(invalidCharsRegex, "-")
        sanitized = sanitized.replace(Regex("-+"), "-")
        sanitized = sanitized.replace(Regex("^-+"), "")
        sanitized = sanitized.replace(Regex("[-. ]+$"), "")
        return sanitized.ifEmpty { "unnamed" }
    }

    @SuppressLint("MissingPermission")
    private fun showErrorNotification() {
        NotificationCompat.Builder(context, recordingFailedNotificationChannel.id)
            .setContentTitle("Failed to save call with $contactInfo")
            .setContentText("An error occurred")
            .setSmallIcon(R.drawable.ic_error)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
            .also { notification ->
                NotificationManagerCompat.from(context)
                    .notify(System.currentTimeMillis().toInt(), notification)
            }
    }

    private fun cleanup() {
        outputFile?.delete()
        outputFile = null
        try {
            NotificationManagerCompat.from(context).cancel(startTime.hashCode())
        } catch (e: Exception) {
            Log.e(TAG, "Could not remove recording notification: $e")
        }
    }
}