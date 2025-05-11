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

fun getRecordingSavedNotificationChannel (context: Context): NotificationChannel {
    return NotificationChannel(
        "freebug_recording_saved_notif",
        context.getString(R.string.notification_channel_saved),
        NotificationManager.IMPORTANCE_LOW
    ).apply {
        description = context.getString(R.string.notification_channel_saved_desc)
        lockscreenVisibility = Notification.VISIBILITY_SECRET
        enableVibration(false)
    }
}

fun getRecordingFailedNotificationChannel (context: Context): NotificationChannel {
    return NotificationChannel(
        "freebug_recording_failed_notif",
        context.getString(R.string.notification_channel_failed),
        NotificationManager.IMPORTANCE_HIGH
    ).apply {
        description = context.getString(R.string.notification_channel_failed_desc)
        lockscreenVisibility = Notification.VISIBILITY_SECRET
    }
}

fun getRecordingNotificationChannel (context: Context): NotificationChannel {
    return NotificationChannel(
        "freebug_recording_notif",
        context.getString(R.string.notification_channel_recording),
        NotificationManager.IMPORTANCE_MIN
    ).apply {
        description = context.getString(R.string.notification_channel_recording_desc)
        setSound(null, null)
        lockscreenVisibility = Notification.VISIBILITY_SECRET
        setShowBadge(false)
        enableVibration(false)
    }
}

class RecordingSession(
    private val context: Context,
    val contactInfo: String,
    val callingPackageName: String,
    var stopRequested: Boolean = false
) {
    private var mediaRecorder: MediaRecorder? = null
    private var startTime: ZonedDateTime? = null
    private var outputFile: File? = null
    private var isStopped = false

    private val recordingNotificationChannel = getRecordingNotificationChannel(context)
    private val recordingFailedNotificationChannel = getRecordingFailedNotificationChannel(context)
    private val recordingSavedNotificationChannel = getRecordingSavedNotificationChannel(context)

    @SuppressLint("MissingPermission")
    fun startRecording(onSuccess: () -> Unit, onError: (String) -> Unit) {
        if (isRecordingActive()) {
            return
        }

        try {
            mediaRecorder = MediaRecorder(context).apply {
                setAudioSource(MainActivity.AppPreferences.getAudioSource(context))
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
            onError(context.getString(R.string.error_start_recording, e.localizedMessage))
        }

        try {
            NotificationCompat.Builder(context, recordingNotificationChannel.id)
                .setContentTitle(context.getString(R.string.notification_recording_title))
                .setContentText(context.getString(
                    R.string.notification_recording_content,
                    contactInfo,
                    callingPackageName
                ))
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
            Log.e(TAG, context.getString(R.string.error_stop_recording, e.message))
        } finally {
            cleanup()
        }
    }

    fun isRecordingActive(): Boolean {
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
            .setContentTitle(context.getString(
                R.string.notification_saved_title,
                contactInfo
            ))
            .setContentText(context.getString(
                R.string.notification_saved_content,
                Environment.DIRECTORY_RECORDINGS
            ))
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
            .setContentTitle(context.getString(
                R.string.notification_failed_title,
                contactInfo
            ))
            .setContentText(context.getString(R.string.notification_failed_content))
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