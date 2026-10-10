package com.perfoverlay

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.SystemClock
import android.view.Choreographer
import java.io.File
import java.io.RandomAccessFile

enum class MetricType(val displayName: String) {
    FPS("FPS 帧率"),
    CPU("CPU 处理器"),
    TEMP("TEMP 温度"),
    MEM("MEM 内存"),
    BAT("BAT 电池"),
    NET("NET 网络"),
    GPU("GPU 显卡")
}

class MetricsCollector(private val context: Context) {

    private val lastValues = HashMap<MetricType, Float>()

    // ---------- FPS ----------
    private var frameCount = 0
    private var lastFpsTick = 0L
    @Volatile private var fpsValue = 0
    private val choreographer: Choreographer by lazy { Choreographer.getInstance() }
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            frameCount++
            val now = SystemClock.elapsedRealtime()
            if (lastFpsTick == 0L) lastFpsTick = now
            if (now - lastFpsTick >= 1000L) {
                fpsValue = frameCount
                frameCount = 0
                lastFpsTick = now
            }
            choreographer.postFrameCallback(this)
        }
    }

    fun startFps() { choreographer.postFrameCallback(frameCallback) }
    fun stopFps() { choreographer.removeFrameCallback(frameCallback) }

    // ---------- CPU ----------
    private var lastCpuTotal = 0L
    private var lastCpuIdle = 0L

    private fun readCpu(): Float {
        try {
            val reader = RandomAccessFile("/proc/stat", "r")
            val line = reader.readLine() ?: return 0f
            reader.close()
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size < 8) return 0f
            val idle = parts[4].toLong()
            var total = 0L
            for (i in 1..7) total += parts[i].toLong()
            val dTotal = total - lastCpuTotal
            val dIdle = idle - lastCpuIdle
            lastCpuTotal = total
            lastCpuIdle = idle
            if (dTotal <= 0) return 0f
            return ((dTotal - dIdle).toFloat() / dTotal * 100f).coerceIn(0f, 100f)
        } catch (e: Exception) {
            return 0f
        }
    }

    // ---------- 温度 ----------
    private fun readCpuTemp(): Float {
        val keywords = listOf("cpu", "soc", "tsens", "ap", "bigcore", "littlecore", "cluster")
        return try {
            val dir = File("/sys/class/thermal")
            val files = dir.listFiles() ?: return 0f
            var best = 0f
            for (f in files) {
                if (!f.name.startsWith("thermal_zone")) continue
                val type = try {
                    File(f, "type").readText().trim().lowercase()
                } catch (e: Exception) { continue }
                if (keywords.any { type.contains(it) }) {
                    val raw = try {
                        File(f, "temp").readText().trim().toFloat()
                    } catch (e: Exception) { continue }
                    val celsius = if (raw > 1000f) raw / 1000f else raw
                    if (celsius in 1f..120f && celsius > best) best = celsius
                }
            }
            best
        } catch (e: Exception) { 0f }
    }

    // ---------- 内存 ----------
    private fun readMemPair(): Pair<Long, Long> {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            val used = info.totalMem - info.availMem
            used to info.totalMem
        } catch (e: Exception) { 0L to 0L }
    }

    private fun formatMem(bytes: Long): String {
        if (bytes <= 0) return "--"
        val gb = bytes.toFloat() / 1024f / 1024f / 1024f
        return if (gb >= 10f) String.format("%.0fG", gb)
        else String.format("%.1fG", gb)
    }

    // ---------- 电池 ----------
    private fun readBattery(): Pair<Int, Float> {
        return try {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            val t = (intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
            val pct = if (scale > 0 && level >= 0) (level * 100 / scale) else -1
            pct to t
        } catch (e: Exception) { -1 to 0f }
    }

    // ---------- 网络 ----------
    private var lastRxBytes = 0L
    private var lastTxBytes = 0L
    private var lastNetTime = 0L

    private fun readNetPair(): Pair<Long, Long> {
        return try {
            val now = SystemClock.elapsedRealtime()
            val rx = TrafficStats.getTotalRxBytes()
            val tx = TrafficStats.getTotalTxBytes()
            if (lastNetTime == 0L) {
                lastRxBytes = rx; lastTxBytes = tx; lastNetTime = now
                return 0L to 0L
            }
            val dRx = rx - lastRxBytes
            val dTx = tx - lastTxBytes
            val dTime = now - lastNetTime
            lastRxBytes = rx; lastTxBytes = tx; lastNetTime = now
            if (dTime <= 0) 0L to 0L
            else (dRx * 1000 / dTime) to (dTx * 1000 / dTime)
        } catch (e: Exception) { 0L to 0L }
    }

    private fun formatSpeed(bytesPerSec: Long): String {
        return when {
            bytesPerSec < 0 -> "--"
            bytesPerSec < 1024 -> "${bytesPerSec}B"
            bytesPerSec < 1024 * 1024 -> "${bytesPerSec / 1024}K"
            else -> String.format("%.1fM", bytesPerSec / 1024f / 1024f)
        }
    }

    // ---------- GPU ----------
    private fun readGpu(): Float {
        val paths = listOf(
            "/sys/class/kgsl/kgsl-3d0/gpubusy",
            "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",
            "/sys/class/kgsl/kgsl-3d0/devfreq/load",
            "/sys/kernel/gpu/gpu_busy",
            "/sys/kernel/gpu/gpu_loading",
            "/sys/devices/platform/kgsl-3d0.0/kgsl/kgsl-3d0/gpubusy",
            "/sys/devices/platform/gpu/load",
            "/sys/class/devfreq/gpufreq/load"
        )
        for (path in paths) {
            try {
                val f = File(path)
                if (!f.exists() || !f.canRead()) continue
                val content = f.readText().trim()
                if (content.isEmpty()) continue
                val parts = content.split(Regex("\\s+"))
                if (parts.size == 2) {
                    val busy = parts[0].toLongOrNull() ?: continue
                    val total = parts[1].toLongOrNull() ?: continue
                    if (total > 0) return (busy.toFloat() / total * 100f).coerceIn(0f, 100f)
                }
                val v = content.toFloatOrNull() ?: continue
                if (v in 0f..100f) return v
            } catch (e: Exception) { continue }
        }
        return -1f
    }

    // ---------- 原始数值（用于曲线和阈值） ----------
    fun queryRaw(type: MetricType): Float {
        val v = when (type) {
            MetricType.FPS -> fpsValue.toFloat()
            MetricType.CPU -> readCpu()
            MetricType.TEMP -> readCpuTemp()
            MetricType.MEM -> {
                val (used, total) = readMemPair()
                if (total > 0) used.toFloat() / total * 100f else 0f
            }
            MetricType.BAT -> {
                val (pct, _) = readBattery()
                if (pct < 0) 0f else pct.toFloat()
            }
            MetricType.NET -> {
                val (rx, tx) = readNetPair()
                (rx + tx) / 1024f
            }
            MetricType.GPU -> {
                val g = readGpu()
                if (g < 0f) 0f else g
            }
        }
        lastValues[type] = v
        return v
    }

    fun getRange(type: MetricType): Pair<Float, Float> = when (type) {
        MetricType.FPS -> 0f to 120f
        MetricType.CPU -> 0f to 100f
        MetricType.TEMP -> 20f to 80f
        MetricType.MEM -> 0f to 100f
        MetricType.BAT -> 0f to 100f
        MetricType.NET -> 0f to 4096f
        MetricType.GPU -> 0f to 100f
    }

    fun getSeverity(type: MetricType): Int {
        val v = lastValues[type] ?: queryRaw(type)
        return when (type) {
            MetricType.FPS -> when {
                v < 25f -> 2
                v < 45f -> 1
                else -> 0
            }
            MetricType.CPU -> when {
                v >= 90f -> 2
                v >= 80f -> 1
                else -> 0
            }
            MetricType.TEMP -> when {
                v >= 50f -> 2
                v >= 45f -> 1
                else -> 0
            }
            MetricType.MEM -> when {
                v >= 90f -> 2
                v >= 80f -> 1
                else -> 0
            }
            MetricType.BAT -> when {
                v <= 10f -> 2
                v <= 20f -> 1
                else -> 0
            }
            MetricType.GPU -> if (v >= 90f) 1 else 0
            MetricType.NET -> 0
        }
    }

    // ---------- 显示字符串 ----------
    fun query(type: MetricType): String = when (type) {
        MetricType.FPS -> fpsValue.toString()
        MetricType.CPU -> "${readCpu().toInt()}%"
        MetricType.TEMP -> "${readCpuTemp().toInt()}℃"
        MetricType.MEM -> {
            val (used, total) = readMemPair()
            "${formatMem(used)}/${formatMem(total)}"
        }
        MetricType.BAT -> {
            val (pct, temp) = readBattery()
            val pctStr = if (pct < 0) "--" else "$pct%"
            String.format("%s %.1f℃", pctStr, temp)
        }
        MetricType.NET -> {
            val (rx, tx) = readNetPair()
            "↓${formatSpeed(rx)} ↑${formatSpeed(tx)}"
        }
        MetricType.GPU -> {
            val g = readGpu()
            if (g < 0f) "不可用" else "${g.toInt()}%"
        }
    }
}
