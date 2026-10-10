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
        const val KEY_SELECTED = "selected_metrics"
        const val KEY_SHOW_CHART = "show_chart"
        const val KEY_X = "pos_x"
        const val KEY_Y = "pos_y"
        @Volatile var isRunning = false

        private const val MAX_SELECTED = 3
        private val SERIES_COLORS = intArrayOf(
            Color.parseColor("#4CAF50"),  // 绿
            Color.parseColor("#42A5F5"),  // 蓝
            Color.parseColor("#FF9800")   // 橙
        )

        private const val METRIC_GROUP_W = 64
        private const val NAME_W = 26
        private const val VALUE_W = 36
        private const val ARROW_W = 22
        private const val CHART_BTN_W = 22
    }

    private lateinit var wm: WindowManager
    private lateinit var rootView: LinearLayout
    private lateinit var menuContainer: LinearLayout
    private lateinit var metricsContainer: LinearLayout
    private lateinit var arrowText: TextView
    private lateinit var chartBtn: TextView
    private lateinit var chartView: MultiSparklineView
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var prefs: SharedPreferences
    private lateinit var collector: MetricsCollector

    private val handler = Handler(Looper.getMainLooper())
    private val selectedMetrics = ArrayList<MetricType>()
    private val valueViews = HashMap<MetricType, TextView>()
    private var showChart = false
    private var menuExpanded = false

    private var initialX = 0
    private var initialY = 0
    private var downX = 0f
    private var downY = 0f
    private var dragging = false

    private var lastScreenW = 0
    private var lastScreenH = 0

    private val colorNormal = Color.WHITE
    private val colorWarn = Color.parseColor("#FFB300")
    private val colorDanger = Color.parseColor("#FF5252")
    private val colorSelected = Color.parseColor("#4FC3F7")

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

    private fun shortName(m: MetricType): String = when (m) {
        MetricType.FPS -> "FPS"
        MetricType.CPU -> "CPU"
        MetricType.TEMP -> "温度"
        MetricType.MEM -> "内存"
        MetricType.BAT -> "电池"
        MetricType.NET -> "网络"
        MetricType.GPU -> "GPU"
    }

    private fun refreshAll() {
        if (valueViews.isEmpty()) return

        val chartValues = ArrayList<Float>()
        for (m in selectedMetrics) {
            val raw = collector.queryRaw(m)
            val text = collector.query(m)
            val sev = collector.getSeverity(m)
            valueViews[m]?.text = text
            valueViews[m]?.setTextColor(severityColor(sev))
            chartValues.add(raw)
        }

        if (showChart && chartValues.isNotEmpty()) {
            chartView.addValues(chartValues)
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        loadSelectedMetrics()
        showChart = prefs.getBoolean(KEY_SHOW_CHART, false)

        startForeground(NOTIF_ID, buildNotification())
        collector = MetricsCollector(this)
        collector.startFps()
        buildOverlay()
        handler.post(updateRunnable)
    }

    private fun loadSelectedMetrics() {
        val saved = prefs.getString(KEY_SELECTED, "FPS") ?: "FPS"
        selectedMetrics.clear()
        saved.split(",").forEach { name ->
            try {
                val m = MetricType.valueOf(name.trim())
                if (selectedMetrics.size < MAX_SELECTED && !selectedMetrics.contains(m)) {
                    selectedMetrics.add(m)
                }
            } catch (e: Exception) {}
        }
        if (selectedMetrics.isEmpty()) selectedMetrics.add(MetricType.FPS)
    }

    private fun saveSelectedMetrics() {
        prefs.edit().putString(
            KEY_SELECTED,
            selectedMetrics.joinToString(",") { it.name }
        ).apply()
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

    /**
     * 统一触摸处理：拖动 + 单击
     * 拖动时暂停刷新，避免布局抖动
     */
    private fun createTouchListener(onClick: (() -> Unit)?): View.OnTouchListener {
        return View.OnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    downX = event.rawX
                    downY = event.rawY
                    dragging = false
                    handler.removeCallbacks(updateRunnable)   // 暂停刷新
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
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        prefs.edit().putInt(KEY_X, params.x).putInt(KEY_Y, params.y).apply()
                    } else if (onClick != null) {
                        onClick.invoke()
                    }
                    handler.removeCallbacks(updateRunnable)
                    handler.postDelayed(updateRunnable, 150L)   // 稍后恢复刷新
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        prefs.edit().putInt(KEY_X, params.x).putInt(KEY_Y, params.y).apply()
                    }
                    handler.removeCallbacks(updateRunnable)
                    handler.postDelayed(updateRunnable, 150L)
                    true
                }
                else -> false
            }
        }
    }

    private fun buildOverlay() {
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        rootView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#CC000000"))
                cornerRadius = dp(12).toFloat()
            }
            setPadding(dp(8), dp(4), dp(6), dp(4))
        }

        // ---------- 参数菜单 ----------
        menuContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        menuContainer.setOnTouchListener(createTouchListener(null))
        rootView.addView(menuContainer)

        // ---------- 主行：参数 + ▼ + 📈 ----------
        val mainRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        metricsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        metricsContainer.setOnTouchListener(createTouchListener { toggleMenu() })
        mainRow.addView(metricsContainer)

        arrowText = TextView(this).apply {
            text = "▼"
            setTextColor(Color.parseColor("#CCFFFFFF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            gravity = Gravity.CENTER
            width = dp(ARROW_W)
            height = dp(28)
        }
        arrowText.setOnTouchListener(createTouchListener { toggleMenu() })
        mainRow.addView(arrowText)

        chartBtn = TextView(this).apply {
            text = "📈"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            gravity = Gravity.CENTER
            width = dp(CHART_BTN_W)
            height = dp(28)
            setTextColor(if (showChart) colorSelected else Color.parseColor("#CCFFFFFF"))
        }
        chartBtn.setOnTouchListener(createTouchListener { toggleChart() })
        mainRow.addView(chartBtn)

        rootView.addView(mainRow)

        // ---------- 曲线区 ----------
        chartView = MultiSparklineView(this).apply {
            visibility = if (showChart) View.VISIBLE else View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(34)
            ).apply {
                topMargin = dp(2)
                bottomMargin = dp(2)
            }
        }
        // ★ 关键：曲线区也能拖动
        chartView.setOnTouchListener(createTouchListener(null))
        rootView.addView(chartView)

        rebuildMetricsRow()
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
                x = resources.displayMetrics.widthPixels - dp(160)
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

    /**
     * 重建参数行
     * 每个参数的 name 用曲线颜色，value 用 severity 颜色
     * 每个参数视图固定宽度 → 布局不会因为数据变化而抖动
     */
    private fun rebuildMetricsRow() {
        metricsContainer.removeAllViews()
        valueViews.clear()

        for ((index, m) in selectedMetrics.withIndex()) {
            val group = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    dp(METRIC_GROUP_W), dp(28)
                )
            }

            val nameTv = TextView(this).apply {
                text = shortName(m)
                setTextColor(SERIES_COLORS[index % SERIES_COLORS.size])  // 曲线同色
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                width = dp(NAME_W)
                maxLines = 1
            }

            val valueTv = TextView(this).apply {
                text = "--"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                typeface = Typeface.MONOSPACE
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                width = dp(VALUE_W)
                maxLines = 1
            }

            group.addView(nameTv)
            group.addView(valueTv)
            valueViews[m] = valueTv

            group.setOnTouchListener(createTouchListener { toggleMenu() })
            metricsContainer.addView(group)
        }

        updateChartSeries()
    }

    private fun updateChartSeries() {
        if (!::chartView.isInitialized) return
        val series = selectedMetrics.mapIndexed { i, m ->
            val (min, max) = collector.getRange(m)
            MultiSparklineView.Series(SERIES_COLORS[i % SERIES_COLORS.size], min, max)
        }
        chartView.setSeries(series)
        if (showChart) {
            val values = selectedMetrics.map { collector.queryRaw(it) }
            chartView.prefillAll(values)
        }
    }

    private fun toggleChart() {
        showChart = !showChart
        prefs.edit().putBoolean(KEY_SHOW_CHART, showChart).apply()
        chartView.visibility = if (showChart) View.VISIBLE else View.GONE
        chartBtn.setTextColor(if (showChart) colorSelected else Color.parseColor("#CCFFFFFF"))
        if (showChart) {
            updateChartSeries()
        }
    }

    private fun buildMenuItems() {
        menuContainer.removeAllViews()

        for (m in MetricType.values()) {
            val selected = selectedMetrics.contains(m)
            val tv = TextView(this).apply {
                text = if (selected) "✓ ${m.displayName}" else "   ${m.displayName}"
                setTextColor(if (selected) colorSelected else Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(dp(4), dp(8), dp(16), dp(8))
            }
            tv.setOnTouchListener(createTouchListener { onMenuItemClick(m) })
            menuContainer.addView(tv)
        }
    }

    private fun onMenuItemClick(m: MetricType) {
        if (selectedMetrics.contains(m)) {
            if (selectedMetrics.size <= 1) {
                android.widget.Toast.makeText(
                    applicationContext,
                    "至少保留一个参数",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                return
            }
            selectedMetrics.remove(m)
        } else {
            if (selectedMetrics.size >= MAX_SELECTED) {
                android.widget.Toast.makeText(
                    applicationContext,
                    "最多选择 $MAX_SELECTED 个参数，先取消一个",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                return
            }
            selectedMetrics.add(m)
        }
        saveSelectedMetrics()
        rebuildMetricsRow()
        buildMenuItems()
        refreshAll()
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

            val viewW = if (rootView.width > 0) rootView.width else dp(160)
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
