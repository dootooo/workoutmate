package com.example.workoutmate

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.File

/**
 * Debug-only screen recorder used to collect labelled test videos.
 *
 * One MediaProjection consent is obtained when MainActivity is opened. The
 * projection is kept alive across sessions; every start/stop pair creates a
 * new MP4 containing the app UI, timer and count. The projection is released
 * when the counting Activity finishes.
 */
class ScreenRecordService : Service() {

    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var recorder: MediaRecorder? = null
    private var currentFile: File? = null
    private var pendingStart = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_INIT -> initProjection(
                intent.getIntExtra(EXTRA_RESULT_CODE, 0),
                intent.getParcelableExtra(EXTRA_RESULT_DATA)
            )
            ACTION_START -> startRecordingInternal()
            ACTION_STOP -> stopRecordingInternal()
            ACTION_RELEASE -> releaseAll()
        }
        return START_NOT_STICKY
    }

    private fun initProjection(resultCode: Int, data: Intent?) {
        if (data == null || resultCode != android.app.Activity.RESULT_OK) {
            Log.w(TAG, "Screen capture permission not granted")
            stopSelf()
            return
        }
        if (projection != null) {
            projectionReady = true
            return
        }
        // The foreground service must be active before MediaProjection is
        // created, otherwise Android throws a SecurityException.
        createNotificationChannel()
        startForegroundInternal()
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mediaProjection = manager.getMediaProjection(resultCode, data)
        if (mediaProjection == null) {
            Log.w(TAG, "MediaProjection unavailable")
            stopSelf()
            return
        }
        projection = mediaProjection
        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                Log.d(TAG, "MediaProjection stopped")
                projectionReady = false
                releaseRecorder()
                projection = null
                stopSelf()
            }
        }
        projectionCallback = callback
        mediaProjection.registerCallback(callback, Handler(Looper.getMainLooper()))
        projectionReady = true
        if (pendingStart) {
            pendingStart = false
            startRecordingInternal()
        }
    }

    private fun startForegroundInternal() {
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("WorkoutMate 录屏")
            .setContentText("正在记录测试视频")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startRecordingInternal() {
        if (recorder != null) return
        val mediaProjection = projection
        if (mediaProjection == null) {
            pendingStart = true
            return
        }
        try {
            val directory = File(getExternalFilesDir(null), "screen-recordings").apply { mkdirs() }
            val file = File(directory, "screen_${System.currentTimeMillis()}.mp4")
            val metrics = resources.displayMetrics
            var width = metrics.widthPixels
            var height = metrics.heightPixels
            // Keep the recording small enough for MediaRecorder/H.264.
            val maxWidth = 720
            if (width > maxWidth) {
                height = (height * maxWidth / width).coerceAtLeast(2)
                width = maxWidth
            }
            width = width / 2 * 2
            height = height / 2 * 2

            val mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            mediaRecorder.setVideoSize(width, height)
            mediaRecorder.setVideoFrameRate(30)
            mediaRecorder.setVideoEncodingBitRate(4_000_000)
            mediaRecorder.setOutputFile(file.absolutePath)
            mediaRecorder.prepare()

            val display = mediaProjection.createVirtualDisplay(
                "WorkoutMateScreen",
                width,
                height,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                mediaRecorder.surface,
                null,
                null
            )
            mediaRecorder.start()
            virtualDisplay = display
            recorder = mediaRecorder
            currentFile = file
            Log.d(TAG, "Started screen recording: ${file.absolutePath}")
        } catch (error: Throwable) {
            Log.e(TAG, "Failed to start screen recording", error)
            releaseRecorder()
        }
    }

    private fun stopRecordingInternal() {
        if (recorder == null) return
        val file = currentFile
        try {
            val display = virtualDisplay
            virtualDisplay = null
            display?.release()
            recorder?.stop()
            Log.d(TAG, "Stopped screen recording: ${file?.absolutePath}")
        } catch (error: Throwable) {
            Log.e(TAG, "Failed to stop screen recording", error)
        } finally {
            recorder?.reset()
            recorder?.release()
            recorder = null
            currentFile = null
        }
    }

    private fun releaseRecorder() {
        stopRecordingInternal()
    }

    private fun releaseAll() {
        stopRecordingInternal()
        projectionCallback?.let { callback ->
            try {
                projection?.unregisterCallback(callback)
            } catch (_: Throwable) {
            }
        }
        projectionCallback = null
        try {
            projection?.stop()
        } catch (_: Throwable) {
        }
        projection = null
        projectionReady = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        releaseAll()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Screen recording",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    companion object {
        private const val TAG = "ScreenRecordService"
        private const val CHANNEL_ID = "screen_recording"
        private const val NOTIFICATION_ID = 4101
        const val ACTION_INIT = "init"
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val ACTION_RELEASE = "release"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        @Volatile
        var projectionReady = false
            private set

        fun init(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, ScreenRecordService::class.java).apply {
                action = ACTION_INIT
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun start(context: Context) {
            context.startService(
                Intent(context, ScreenRecordService::class.java).setAction(ACTION_START)
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, ScreenRecordService::class.java).setAction(ACTION_STOP)
            )
        }

        fun release(context: Context) {
            context.startService(
                Intent(context, ScreenRecordService::class.java).setAction(ACTION_RELEASE)
            )
        }
    }
}
