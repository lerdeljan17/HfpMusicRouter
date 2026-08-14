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

    private var s25KeepAliveTrack: AudioTrack? = null
    private var s25KeepAliveWorker: Thread? = null
    private val s25KeepAliveRunning = AtomicBoolean(false)

    private val running = AtomicBoolean(false)

    private var changedAudioMode = false
    private var usedCommunicationDeviceRoute = false
    private var usedLegacyScoRoute = false
    private var directS25Mode = false

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AudioManager::class.java)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_CAPTURE -> startCapture(intent)
            ACTION_START_DIRECT -> startDirectS25Routing()
            ACTION_STOP -> {
                stopRouting()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startDirectS25Routing() {
        if (running.get()) return

        directS25Mode = true
        startForegroundNotification(usesMediaProjection = false)

        usedCommunicationDeviceRoute = false
        usedLegacyScoRoute = false
        changedAudioMode = false

        val hfpDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.availableCommunicationDevices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            }
        } else {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            }
        }

        // Samsung S25 drops a bare off-call SCO connection after a short idle period.
        // Use the modern communication-device API and keep a real (silent)
        // VOICE_COMMUNICATION track active so the HFP session remains alive.
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        changedAudioMode = true

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && hfpDevice != null) {
            usedCommunicationDeviceRoute = audioManager.setCommunicationDevice(hfpDevice)
        }

        // Fallback only if the modern route was not accepted or no communication device
        // was exposed. This keeps older/vendor-specific firmware usable.
        if (!usedCommunicationDeviceRoute) {
            @Suppress("DEPRECATION")
            audioManager.startBluetoothSco()
            usedLegacyScoRoute = true
        }

        startS25SilentKeepAlive(hfpDevice)
        running.set(true)
    }

    private fun startS25SilentKeepAlive(hfpDevice: AudioDeviceInfo?) {
        val sampleRate = 16000
        val minOutput = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = maxOf(minOutput, sampleRate / 5 * 2)

        s25KeepAliveTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        if (hfpDevice != null) {
            s25KeepAliveTrack?.setPreferredDevice(hfpDevice)
        }

        val silence = ByteArray(640) // 20 ms of 16 kHz mono PCM16 silence.
        s25KeepAliveRunning.set(true)
        s25KeepAliveTrack?.play()

        s25KeepAliveWorker = thread(name = "S25HfpKeepAlive") {
            while (s25KeepAliveRunning.get()) {
                val written = s25KeepAliveTrack?.write(
                    silence,
                    0,
                    silence.size,
                    AudioTrack.WRITE_BLOCKING
                ) ?: break
                if (written < 0) break
            }
        }
    }

    private fun startCapture(intent: Intent) {
        if (running.get()) return

        directS25Mode = false
        startForegroundNotification(usesMediaProjection = true)

        val hfpDevice = startHfpRoutingForCapture()

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

    private fun startHfpRoutingForCapture(): AudioDeviceInfo? {
        usedCommunicationDeviceRoute = false
        usedLegacyScoRoute = false
        changedAudioMode = false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val hfpDevice = audioManager.availableCommunicationDevices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            } ?: audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            }

            if (hfpDevice != null) {
                usedCommunicationDeviceRoute = audioManager.setCommunicationDevice(hfpDevice)
            }
            return hfpDevice
        }

        changedAudioMode = true
        usedLegacyScoRoute = true
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        @Suppress("DEPRECATION")
        audioManager.startBluetoothSco()

        return audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        }
    }

    private fun stopRouting() {
        running.set(false)

        s25KeepAliveRunning.set(false)
        try { s25KeepAliveTrack?.stop() } catch (_: Exception) {}
        s25KeepAliveWorker?.interrupt()
        s25KeepAliveWorker = null
        s25KeepAliveTrack?.release()
        s25KeepAliveTrack = null

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

        if (usedCommunicationDeviceRoute && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        }

        if (usedLegacyScoRoute) {
            @Suppress("DEPRECATION")
            audioManager.stopBluetoothSco()
        }

        if (changedAudioMode) {
            audioManager.mode = AudioManager.MODE_NORMAL
        }

        usedCommunicationDeviceRoute = false
        usedLegacyScoRoute = false
        changedAudioMode = false
        directS25Mode = false
    }

    private fun startForegroundNotification(usesMediaProjection: Boolean) {
        val routeDescription = if (directS25Mode) {
            "Galaxy S25 stable direct HFP routing is active"
        } else {
            "Routing captured audio over hands-free Bluetooth"
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("HFP Music Router")
            .setContentText(routeDescription)
            .setOngoing(true)
            .build()

        val foregroundTypes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            if (usesMediaProjection) {
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            }
            types
        } else 0

        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, foregroundTypes)
    }

    override fun onDestroy() {
        stopRouting()
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
        const val ACTION_START_DIRECT = "com.lazar.hfpmusicrouter.START_DIRECT"
        const val ACTION_STOP = "com.lazar.hfpmusicrouter.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL_ID = "hfp_routing"
        private const val NOTIFICATION_ID = 1001
    }
}
