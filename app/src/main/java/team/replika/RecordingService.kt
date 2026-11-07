package team.replika

import android.app.*
import android.content.Intent
import android.media.MediaRecorder
import android.os.IBinder
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

data class RecordingState(val active: Boolean = false, val file: String? = null, val error: String? = null)

class RecordingService : Service() {
    private var recorder: MediaRecorder? = null
    private var output: File? = null
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") { finishRecording(); stopSelf(); return START_NOT_STICKY }
        if (recorder != null) return START_NOT_STICKY
        val channel = NotificationChannel("recording", "Запись разговора", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        val stop = PendingIntent.getService(this, 1, Intent(this, RecordingService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        startForeground(10, Notification.Builder(this, "recording").setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Реплика записывает разговор").setContentText("Нажмите «Остановить», чтобы сохранить запись")
            .setContentIntent(open).setOngoing(true).addAction(android.R.drawable.ic_media_pause, "Остановить", stop).build())
        try {
            val directory = File(filesDir, "recordings").apply { mkdirs() }
            output = File(directory, "Разговор-${System.currentTimeMillis()}.m4a")
            val r = MediaRecorder()
            recorder = r
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(16000)
            r.setAudioEncodingBitRate(64000)
            r.setOutputFile(output!!.absolutePath)
            r.prepare(); r.start()
            state.value = RecordingState(active = true)
        } catch (e: Exception) {
            recorder?.release(); recorder = null; output?.delete()
            state.value = RecordingState(error = "Не удалось начать запись: ${e.message}")
            stopSelf()
        }
        return START_NOT_STICKY
    }
    private fun finishRecording() {
        val r = recorder ?: return
        recorder = null
        try {
            r.stop()
            state.value = RecordingState(file = output?.absolutePath)
        } catch (e: Exception) {
            output?.delete()
            state.value = RecordingState(error = "Запись слишком короткая или прервана: ${e.message}")
        } finally { r.release(); stopForeground(STOP_FOREGROUND_REMOVE) }
    }
    override fun onDestroy() { finishRecording(); super.onDestroy() }
    companion object { val state = MutableStateFlow(RecordingState()) }
}
