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
        const val KEY_MODE = "display_mode"
        @Volatile var isRunning = false
    }

    private lateinit var wm: WindowManager
    private lateinit var rootView: LinearLayout
    private lateinit var menuContainer: LinearLayout
    private lateinit var singleRow: LinearLayout
    private lateinit var wideRow: LinearLayout
    private lateinit var nameText: TextView
    private lateinit var valueText: TextView
    private lateinit var arrowText: TextView
    private lateinit var sparkline: SparklineView
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var prefs: SharedPreferences

    private lateinit var collector: MetricsCollector
    private val handler = Handler(Looper.getMainLooper())
    private var currentMetric = MetricType.FPS
    private var menuExpanded = false
    private var displayMode = 0  // 0=单行, 1=单行+曲线, 2=宽条

    private val wideViews = HashMap<MetricType, TextView>()
    private val wideMetrics = listOf(MetricType.FPS, MetricType.CPU, MetricType.TEMP)

    private var initialX = 0
    private var initialY = 0
    private var downX = 0f
    private var downY = 0f
    private var dragging = false

    private var lastScreenW = 0
    private var lastScreenH = 0

    // 颜色
    private val colorNormal = Color.WHITE
    private val colorWarn = Color.parseColor("#FFB300")
    private val colorDanger = Color.parseColor("#FF5252")

    private val updateRunnable = object : Runnable {
        override fun run() {
            refreshAll()
            checkScreenChange()
            handler.postDelayed(this, 1000L)
        }
    }

    private fun severityColor(sev: Int): Int = when (sev) {
        2 -> colorDanger
        1 -> colorWarn
        else -> colorNormal
    }

    private fun refreshAll() {
        if (!::valueText.isInitialized) return
        val raw = collector.queryRaw(currentMetric)
        val text = collector.query(currentMetric)
        val sev = collector.getSeverity(currentMetric)
        val color = severityColor(sev)

        valueText.text = text
        valueText.setTextColor(color)
        sparkline.setColor(color)

        if (displayMode == 1) {
            sparkline.addValue(raw)
        }

        // 宽条模式
        if (displayMode == 2) {
            for (m in wideMetrics) {
                val tv = wideViews[m] ?: continue
                val raw2 = collector.queryRaw(m)
                tv.text = collector.query(m)
                tv.setTextColor(severityColor(collector.getSeverity(m)))
                // 未使用的 raw2 警告忽略
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        currentMetric = try {
            MetricType.valueOf(prefs.getString(KEY_METRIC, MetricType.FPS.name) ?: MetricType.FPS.name)
        } catch (e: Exception) { MetricType.FPS }
        displayMode = prefs.getInt(KEY_MODE, 0)

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

        // 菜单
        menuContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        rootView.addView(menuContainer)

        // 单行
        singleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        nameText = TextView(this).apply {
            text = currentMetric.displayName
            setTextColor(Color.parseColor("#CCFFFFFF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(dp(4), dp(8), dp(4), dp(8))
            setOnClickListener { cycleDisplayMode() }
        }
        valueText = TextView(this).apply {
            text = "--"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(10), dp(8), dp(6), dp(8))
            minWidth = dp(38)
            gravity = Gravity.END
            typeface = Typeface.MONOSPACE
            setOnClickListener { cycleDisplayMode() }
        }
        arrowText = TextView(this).apply {
            text = "▼"
            setTextColor(Color.parseColor("#CCFFFFFF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setPadding(dp(6), dp(10), dp(6), dp(10))
            setOnClickListener { toggleMenu() }
        }
        singleRow.addView(nameText)
        singleRow.addView(valueText)
        singleRow.addView(arrowText)
        rootView.addView(singleRow)

        // 曲线
        sparkline = SparklineView(this).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(dp(110), dp(24)).apply {
                topMargin = dp(2)
                bottomMargin = dp(2)
            }
        }
        rootView.addView(sparkline)

        // 宽条
        wideRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }
        for (m in wideMetrics) {
            val labelTv = TextView(this).apply {
                text = m.displayName.split(" ")[0]
                setTextColor(Color.parseColor("#99FFFFFF"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
                setPadding(dp(6), dp(10), dp(2), dp(10))
            }
            val valueTv = TextView(this).apply {
                text = "--"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                typeface = Typeface.MONOSPACE
                setPadding(dp(2), dp(10), dp(4), dp(10))
            }
            wideViews[m] = valueTv
            wideRow.addView(labelTv)
            wideRow.addView(valueTv)
        }
        wideRow.setOnClickListener { cycleDisplayMode() }
        rootView.addView(wideRow)

        buildMenuItems()
        applyDisplayMode()

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
                x = resources.displayMetrics.widthPixels - dp(140)
            }
        }

        val touchListener = View.OnTouchListener { _, event ->
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
        singleRow.setOnTouchListener(touchListener)
        wideRow.setOnTouchListener(touchListener)

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

    private fun cycleDisplayMode() {
        displayMode = (displayMode + 1) % 3
        prefs.edit().putInt(KEY_MODE, displayMode).apply()
        applyDisplayMode()
    }

    private fun applyDisplayMode() {
        when (displayMode) {
            0 -> {
                singleRow.visibility = View.VISIBLE
                sparkline.visibility = View.GONE
                wideRow.visibility = View.GONE
            }
            1 -> {
                singleRow.visibility = View.VISIBLE
                sparkline.visibility = View.VISIBLE
                wideRow.visibility = View.GONE
                sparkline.clear()
                val (minV, maxV) = collector.getRange(currentMetric)
                sparkline.configure(minV, maxV)
            }
            2 -> {
                singleRow.visibility = View.GONE
                sparkline.visibility = View.GONE
                wideRow.visibility = View.VISIBLE
            }
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
                    if (displayMode == 1) {
                        sparkline.clear()
                        val (minV, maxV) = collector.getRange(m)
                        sparkline.configure(minV, maxV)
                    }
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

    private fun checkScreenChange() {
        if (!::rootView.isInitialized || !::params.isInitialized) return
        val dm = resources.displayMetrics
        if (lastScreenW == 0) {
            lastScreenW = dm.widthPixels
            lastScreenH = dm.heightPixels
            return
        }
        if (dm.widthPixels != lastScreenW || dm.heightPixels != lastScreenH) {
            lastScreenW = dm.widthPixels
            lastScreenH = dm.heightPixels

            val viewW = if (rootView.width > 0) rootView.width else dp(140)
            val viewH = if (rootView.height > 0) rootView.height else dp(40)
            val maxX = Math.max(0, dm.widthPixels - viewW)
            val maxY = Math.max(0, dm.heightPixels - viewH)

            params.x = params.x.coerceIn(0, maxX)
            params.y = params.y.coerceIn(0, maxY)
            try { wm.updateViewLayout(rootView, params) } catch (e: Exception) {}
            prefs.edit().putInt(KEY_X, params.x).putInt(KEY_Y, params.y).apply()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        handler.removeCallbacks(updateRunnable)
        try { collector.stopFps() } catch (e: Exception) {}
        if (::rootView.isInitialized) {
            try { wm.removeView(rootView) } catch (e: Exception) {}
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
}
