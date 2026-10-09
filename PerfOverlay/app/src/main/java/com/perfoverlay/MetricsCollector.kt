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
    FPS("FPS"),
    CPU("CPU"),
    TEMP("TEMP"),
    MEM("MEM"),
    BAT("BAT"),
    NET("NET")
}

class MetricsCollector(private val context: Context) {

    // ---- FPS ----
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

    fun startFps() {
        choreographer.postFrameCallback(frameCallback)
    }

    fun stopFps() {
        choreographer.removeFrameCallback(frameCallback)
    }

    // ---- CPU ----
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

    // ---- CPU 温度 ----
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
                } catch (e: Exception) {
                    continue
                }
                if (keywords.any { type.contains(it) }) {
                    val raw = try {
                        File(f, "temp").readText().trim().toFloat()
                    } catch (e: Exception) {
                        continue
                    }
                    val celsius = if (raw > 1000f) raw / 1000f else raw
                    if (celsius in 1f..120f && celsius > best) best = celsius
                }
            }
            best
        } catch (e: Exception) {
            0f
        }
    }

    // ---- 内存 ----
    private fun readMem(): Float {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            val used = info.totalMem - info.availMem
            used.toFloat() / info.totalMem * 100f
        } catch (e: Exception) {
            0f
        }
    }

    // ---- 电池温度 ----
    private fun readBatteryTemp(): Float {
        return try {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val t = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
            t / 10f
        } catch (e: Exception) {
            0f
        }
    }

    // ---- 网速 ----
    private var lastRxBytes = 0L
    private var lastRxTime = 0L

    private fun readNet(): Long {
        return try {
            val now = SystemClock.elapsedRealtime()
            val rx = TrafficStats.getTotalRxBytes()
            if (lastRxTime == 0L) {
                lastRxBytes = rx
                lastRxTime = now
                return 0L
            }
            val dBytes = rx - lastRxBytes
            val dTime = now - lastRxTime
            lastRxBytes = rx
            lastRxTime = now
            if (dTime <= 0) 0L else dBytes * 1000 / dTime
        } catch (e: Exception) {
            0L
        }
    }

    private fun formatNet(bytesPerSec: Long): String {
        return when {
            bytesPerSec < 0 -> "--"
            bytesPerSec < 1024 -> "${bytesPerSec}B"
            bytesPerSec < 1024 * 1024 -> "${bytesPerSec / 1024}K"
            else -> String.format("%.1fM", bytesPerSec / 1024f / 1024f)
        }
    }

    fun query(type: MetricType): String {
        return when (type) {
            MetricType.FPS -> fpsValue.toString()
            MetricType.CPU -> "${readCpu().toInt()}"
            MetricType.TEMP -> "${readCpuTemp().toInt()}"
            MetricType.MEM -> "${readMem().toInt()}"
            MetricType.BAT -> String.format("%.1f", readBatteryTemp())
            MetricType.NET -> formatNet(readNet())
        }
    }
}