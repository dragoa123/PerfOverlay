package com.perfoverlay

import android.content.Intent
import android.content.SharedPreferences
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
    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("perf_overlay_prefs", MODE_PRIVATE)
        statusText = findViewById(R.id.tvStatus)
        toggleBtn = findViewById(R.id.btnToggle)

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
            val running = prefs.getBoolean("is_running", false)
            if (running) {
                stopService(Intent(this, OverlayService::class.java))
                prefs.edit().putBoolean("is_running", false).apply()
            } else {
                val intent = Intent(this, OverlayService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
                prefs.edit().putBoolean("is_running", true).apply()
            }
            statusText.postDelayed({ refreshStatus() }, 300)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val granted = Settings.canDrawOverlays(this)
        val running = prefs.getBoolean("is_running", false)
        statusText.text = when {
            !granted -> "❌ 未授权悬浮窗权限"
            running -> "✅ 悬浮窗运行中"
            else -> "✅ 已授权，可开启悬浮窗"
        }
        toggleBtn.text = if (running) "停止悬浮窗" else "开启悬浮窗"
    }
}
