package com.lazar.hfpmusicrouter

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.lazar.hfpmusicrouter.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var projectionManager: MediaProjectionManager

    private val capturePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val serviceIntent = Intent(this, HfpRoutingService::class.java).apply {
                action = HfpRoutingService.ACTION_START_CAPTURE
                putExtra(HfpRoutingService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(HfpRoutingService.EXTRA_RESULT_DATA, result.data)
            }
            ContextCompat.startForegroundService(this, serviceIntent)
            binding.statusText.text = "Routing device audio to the Bluetooth hands-free connection. Start audio in YouTube or any eligible player."
        } else {
            binding.statusText.text = "Audio capture permission was cancelled."
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val denied = permissions.filterValues { !it }.keys
        if (denied.isNotEmpty()) {
            binding.statusText.text = "Bluetooth or notification permission was denied. Routing may not work."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        projectionManager = getSystemService(MediaProjectionManager::class.java)

        requestNeededPermissions()

        binding.routeButton.setOnClickListener {
            capturePermissionLauncher.launch(projectionManager.createScreenCaptureIntent())
        }

        binding.stopButton.setOnClickListener {
            startService(Intent(this, HfpRoutingService::class.java).apply {
                action = HfpRoutingService.ACTION_STOP
            })
            binding.statusText.text = "Routing stopped."
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
}