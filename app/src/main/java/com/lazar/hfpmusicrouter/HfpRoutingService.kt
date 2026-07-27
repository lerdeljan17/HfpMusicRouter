package com.lazar.hfpmusicrouter

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class HfpRoutingService : Service() {
    private lateinit var audioManager: AudioManager
    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var worker: Thread? = null
    private val running = AtomicBoolean(false)
    private var changedLegacyAudioMode = false

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AudioManager::class.java)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_CAPTURE -> startCapture(intent)
            ACTION_STOP -> {
                stopCapture()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startCapture(intent: Intent) {
        if (running.get()) return
        startForegroundNotification()

        val hfpDevice = startHfpRouting()

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        } ?: run {
            stopSelf()
            return
        }

        val projectionManager = getSystemService(MediaProjectionManager::class.java)
        mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)

        val sampleRate = 16000
        val inputFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()

        val captureConfig = android.media.AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val minInput = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = maxOf(minInput, sampleRate)

        audioRecord = AudioRecord.Builder()
            .setAudioFormat(inputFormat)
            .setBufferSizeInBytes(bufferSize)
            .setAudioPlaybackCaptureConfig(captureConfig)
            .build()

        val outputFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()

        val minOutput = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(outputFormat)
            .setBufferSizeInBytes(maxOf(minOutput, bufferSize))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        // Route only this replay track to SCO. Do not globally move ordinary media
        // playback into communication mode on modern Android, as some Samsung devices
        // then send both the original media and this captured copy to the car.
        if (hfpDevice != null) {
            audioTrack?.setPreferredDevice(hfpDevice)
        }

        running.set(true)
        audioRecord?.startRecording()
        audioTrack?.play()

        worker = thread(name = "HfpPlaybackCapture") {
            val buffer = ByteArray(bufferSize)
            while (running.get()) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: break
                if (read > 0) audioTrack?.write(buffer, 0, read)
            }
        }
    }

    private fun startHfpRouting(): AudioDeviceInfo? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val hfpDevice = audioManager.availableCommunicationDevices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            }

            // setCommunicationDevice selects the communication route. We intentionally
            // leave AudioManager.mode unchanged so USAGE_MEDIA from YouTube/local players
            // is not also forced onto SCO by vendor audio policies.
            if (hfpDevice != null) {
                audioManager.setCommunicationDevice(hfpDevice)
            }
            hfpDevice
        } else {
            changedLegacyAudioMode = true
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            @Suppress("DEPRECATION")
            audioManager.startBluetoothSco()
            @Suppress("DEPRECATION")
            audioManager.isBluetoothScoOn = true

            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            }
        }
    }

    private fun stopCapture() {
        running.set(false)
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioTrack?.stop() } catch (_: Exception) {}
        worker?.interrupt()
        worker = null
        audioRecord?.release()
        audioRecord = null
        audioTrack?.release()
        audioTrack = null
        mediaProjection?.stop()
        mediaProjection = null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        } else {
            @Suppress("DEPRECATION")
            audioManager.isBluetoothScoOn = false
            @Suppress("DEPRECATION")
            audioManager.stopBluetoothSco()
            if (changedLegacyAudioMode) {
                audioManager.mode = AudioManager.MODE_NORMAL
                changedLegacyAudioMode = false
            }
        }
    }

    private fun startForegroundNotification() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("HFP Music Router")
            .setContentText("Routing one captured audio stream over hands-free Bluetooth")
            .setOngoing(true)
            .build()

        val foregroundTypes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        } else 0

        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, foregroundTypes)
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "HFP routing",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    companion object {
        const val ACTION_START_CAPTURE = "com.lazar.hfpmusicrouter.START_CAPTURE"
        const val ACTION_STOP = "com.lazar.hfpmusicrouter.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL_ID = "hfp_routing"
        private const val NOTIFICATION_ID = 1001
    }
}
