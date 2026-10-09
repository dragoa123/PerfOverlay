package com.perfoverlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import kotlin.math.abs
import kotlin.math.roundToInt

class OverlayService : Service() {

    companion object {
        private const val CHANNEL_ID = "perf_overlay"
        private const val NOTIF_ID = 1001
        const val PREFS = "perf_overlay_prefs"
        const val KEY_METRIC = "metric"
        const val KEY_X = "pos_x"
        const val KEY_Y = "pos_y"
    }

    private lateinit var wm: WindowManager
    private lateinit var rootView: LinearLayout
    private lateinit var menuContainer: LinearLayout
    private lateinit var nameText: TextView
    private lateinit var valueText: TextView
    private lateinit var arrowText: TextView
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var prefs: SharedPreferences

    private lateinit var collector: MetricsCollector
    private val handler = Handler(Looper.getMainLooper())
    private var currentMetric = MetricType.FPS
    private var menuExpanded = false

    private var initialX = 0
    private var initialY = 0
    private var downX = 0f
    private var downY = 0f
    private var dragging = false

    private val updateRunnable = object : Runnable {
        override fun run() {
            if (::valueText.isInitialized) {
                valueText.text = collector.query(currentMetric)
            }
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        currentMetric = try {
            MetricType.valueOf(prefs.getString(KEY_METRIC, MetricType.FPS.name) ?: MetricType.FPS.name)
        } catch (e: Exception) {
            MetricType.FPS
        }

        startForeground(NOTIF_ID, buildNotification())
        collector = MetricsCollector(this)
        collector.startFps()
        buildOverlay()
        handler.post(updateRunnable)
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "性能悬浮窗", NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            nm.createNotificationChannel(ch)
        }
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("性能悬浮窗运行中")
            .setContentText("点击打开 App")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).roundToInt()

    private fun buildOverlay() {
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        rootView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#CC000000"))
                cornerRadius = dp(12).toFloat()
            }
            setPadding(dp(12), dp(6), dp(10), dp(6))
        }

        menuContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        rootView.addView(menuContainer)

        val mainRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        nameText = TextView(this).apply {
            text = currentMetric.displayName
            setTextColor(Color.parseColor("#CCFFFFFF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        }

        valueText = TextView(this).apply {
            text = "--"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(10), 0, dp(6), 0)
            minWidth = dp(38)
            gravity = Gravity.END
            typeface = Typeface.MONOSPACE
        }

        arrowText = TextView(this).apply {
            text = "▼"
            setTextColor(Color.parseColor("#CCFFFFFF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setPadding(dp(6), dp(8), dp(6), dp(8))
            setOnClickListener { toggleMenu() }
        }

        mainRow.addView(nameText)
        mainRow.addView(valueText)
        mainRow.addView(arrowText)
        rootView.addView(mainRow)

        buildMenuItems()

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt(KEY_X, -1)
            y = prefs.getInt(KEY_Y, 300)
            if (x == -1) {
                x = resources.displayMetrics.widthPixels - dp(130)
            }
        }

        mainRow.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    downX = event.rawX
                    downY = event.rawY
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!dragging && (abs(dx) > dp(6) || abs(dy) > dp(6))) {
                        dragging = true
                    }
                    if (dragging) {
                        params.x = initialX + dx.toInt()
                        params.y = initialY + dy.toInt()
                        try { wm.updateViewLayout(rootView, params) } catch (e: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        prefs.edit().putInt(KEY_X, params.x).putInt(KEY_Y, params.y).apply()
                    }
                    true
                }
                else -> false
            }
        }

        try {
            wm.addView(rootView, params)
        } catch (e: Exception) {
            android.widget.Toast.makeText(
                applicationContext,
                "悬浮窗添加失败: ${e.message}",
                android.widget.Toast.LENGTH_LONG
            ).show()
            stopSelf()
        }
    }

    private fun buildMenuItems() {
        menuContainer.removeAllViews()
        for (m in MetricType.values()) {
            val tv = TextView(this).apply {
                text = m.displayName
                setTextColor(if (m == currentMetric) Color.parseColor("#4FC3F7") else Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(dp(4), dp(8), dp(16), dp(8))
                setOnClickListener {
                    currentMetric = m
                    prefs.edit().putString(KEY_METRIC, m.name).apply()
                    nameText.text = m.displayName
                    menuExpanded = false
                    menuContainer.visibility = View.GONE
                    arrowText.text = "▼"
                    buildMenuItems()
                    valueText.text = collector.query(currentMetric)
                }
            }
            menuContainer.addView(tv)
        }
    }

    private fun toggleMenu() {
        menuExpanded = !menuExpanded
        menuContainer.visibility = if (menuExpanded) View.VISIBLE else View.GONE
        arrowText.text = if (menuExpanded) "▲" else "▼"
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(updateRunnable)
        try { collector.stopFps() } catch (e: Exception) {}
        if (::rootView.isInitialized) {
            try { wm.removeView(rootView) } catch (e: Exception) {}
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
}
