package com.perfoverlay

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var toggleBtn: Button
    private lateinit var detailBtn: Button
    private lateinit var prefs: SharedPreferences

    companion object {
        private const val REQ_NOTIF = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("perf_overlay_prefs", MODE_PRIVATE)
        statusText = findViewById(R.id.tvStatus)
        toggleBtn = findViewById(R.id.btnToggle)
        detailBtn = findViewById(R.id.btnDetail)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQ_NOTIF
                )
            }
        }

        toggleBtn.setOnClickListener {
            if (!hasOverlayPermission()) {
                Toast.makeText(this, "请先授予悬浮窗权限", Toast.LENGTH_SHORT).show()
                requestOverlayPermission()
                return@setOnClickListener
            }
            val running = prefs.getBoolean("is_running", false)
            if (running) {
                stopService(Intent(this, OverlayService::class.java))
                prefs.edit().putBoolean("is_running", false).apply()
                Toast.makeText(this, "已停止", Toast.LENGTH_SHORT).show()
            } else {
                try {
                    val intent = Intent(this, OverlayService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(intent)
                    } else {
                        startService(intent)
                    }
                    prefs.edit().putBoolean("is_running", true).apply()
                    Toast.makeText(this, "已启动", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "启动失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
            statusText.postDelayed({ refreshStatus() }, 500)
        }

        detailBtn.setOnClickListener {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun hasOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                Settings.canDrawOverlays(this)
            } catch (e: Exception) {
                false
            }
        } else true
    }

    private fun requestOverlayPermission() {
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "无法跳转，请手动到设置里开", Toast.LENGTH_LONG).show()
        }
    }

    private fun refreshStatus() {
        val granted = hasOverlayPermission()
        val running = prefs.getBoolean("is_running", false)
        statusText.text = when {
            !granted -> "❌ 未授权悬浮窗权限"
            running -> "✅ 悬浮窗运行中"
            else -> "✅ 已授权，可开启悬浮窗"
        }
        toggleBtn.text = if (running) "停止悬浮窗" else "开启悬浮窗"
    }
}
