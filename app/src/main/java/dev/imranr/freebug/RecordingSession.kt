package dev.imranr.freebug

import android.annotation.SuppressLint
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

class RecordingSession(
    private val context: Context,
    private val contactInfo: String
) {
    private var mediaRecorder: MediaRecorder? = null
    private var startTime: Long = 0
    private var outputFile: File? = null
    private var isStopped = false
    private var duration: Long = 0

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
                startTime = System.currentTimeMillis()
                isStopped = false
                onSuccess()
            }
        } catch (e: Exception) {
            cleanup()
            onError("Failed to start recording: ${e.localizedMessage}")
        }
    }

    fun stopRecording() {
        if (isStopped) return
        isStopped = true

        duration = System.currentTimeMillis() - startTime

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
            val fileName = sanitizeFileName("Call_${contactInfo}_${System.currentTimeMillis()}")
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
        NotificationCompat.Builder(context, notif_channel_id)
            .setContentTitle("Call Saved Successfully")
            .setContentText("Saved to ${Environment.DIRECTORY_RECORDINGS}/CallRecordings")
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
        return input.replace(Regex("[^a-zA-Z0-9_\\-.]"), "_")
    }

    @SuppressLint("MissingPermission")
    private fun showErrorNotification() {
        NotificationCompat.Builder(context, notif_channel_id)
            .setContentTitle("Recording Failed")
            .setContentText("Could not save call recording")
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
    }
}