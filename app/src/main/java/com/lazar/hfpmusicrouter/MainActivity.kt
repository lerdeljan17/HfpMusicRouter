package com.lazar.hfpmusicrouter

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.lazar.hfpmusicrouter.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var mediaPlayer: MediaPlayer? = null
    private var selectedUri: Uri? = null

    private val audioPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            selectedUri = uri
            preparePlayer(uri)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val denied = permissions.filterValues { !it }.keys
        binding.statusText.text = if (denied.isEmpty()) {
            "Permissions granted. Connect to the Audi and start routing."
        } else {
            "Bluetooth/notification permission was denied. Routing may not work."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        requestNeededPermissions()

        binding.routeButton.setOnClickListener {
            val intent = Intent(this, HfpRoutingService::class.java).apply {
                action = HfpRoutingService.ACTION_START
            }
            ContextCompat.startForegroundService(this, intent)
            binding.statusText.text = "Routing requested. Wait a few seconds, then press Play."
        }

        binding.pickButton.setOnClickListener {
            audioPicker.launch(arrayOf("audio/*"))
        }

        binding.playPauseButton.setOnClickListener {
            val player = mediaPlayer ?: return@setOnClickListener
            if (player.isPlaying) {
                player.pause()
                binding.playPauseButton.text = "Play"
            } else {
                player.start()
                binding.playPauseButton.text = "Pause"
            }
        }

        binding.stopButton.setOnClickListener {
            mediaPlayer?.pause()
            binding.playPauseButton.text = "Play"
            startService(Intent(this, HfpRoutingService::class.java).apply {
                action = HfpRoutingService.ACTION_STOP
            })
            binding.statusText.text = "Routing stopped."
        }
    }

    private fun preparePlayer(uri: Uri) {
        mediaPlayer?.release()
        mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            setDataSource(this@MainActivity, uri)
            setOnPreparedListener {
                binding.playPauseButton.isEnabled = true
                binding.statusText.text = "Audio loaded. Start HFP routing, then press Play."
            }
            setOnCompletionListener {
                binding.playPauseButton.text = "Play"
            }
            setOnErrorListener { _, what, extra ->
                binding.statusText.text = "Playback error: $what / $extra"
                true
            }
            prepareAsync()
        }
    }

    private fun requestNeededPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            permissions += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }
        if (permissions.isNotEmpty()) permissionLauncher.launch(permissions.toTypedArray())
    }

    override fun onDestroy() {
        mediaPlayer?.release()
        mediaPlayer = null
        super.onDestroy()
    }
}
