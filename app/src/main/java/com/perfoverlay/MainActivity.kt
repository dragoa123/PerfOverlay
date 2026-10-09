package com.perfoverlay

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var toggleBtn: Button
    private var isRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.tvStatus)
        toggleBtn = findViewById(R.id.btnToggle)

        isRunning = OverlayService.isRunning

        toggleBtn.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
                return@setOnClickListener
            }
            if (isRunning) {
                stopService(Intent(this, OverlayService::class.java))
                isRunning = false
            } else {
                val intent = Intent(this, OverlayService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
                isRunning = true
            }
            refreshStatus()
        }
    }

    override fun onResume() {
        super.onResume()
        isRunning = OverlayService.isRunning
        refreshStatus()
    }

    private fun refreshStatus() {
        val granted = Settings.canDrawOverlays(this)
        statusText.text = when {
            !granted -> "❌ 未授权悬浮窗权限"
            isRunning -> "✅ 悬浮窗运行中"
            else -> "✅ 已授权，可开启悬浮窗"
        }
        toggleBtn.text = if (isRunning) "停止悬浮窗" else "开启悬浮窗"
    }
}