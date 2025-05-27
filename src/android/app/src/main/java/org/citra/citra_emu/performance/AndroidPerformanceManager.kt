package org.citra.citra_emu.performance

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import org.citra.citra_emu.NativeLibrary
import org.citra.citra_emu.performance.AndroidShaderCacheManager
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Manages Android-specific performance optimizations for the Citra emulator
 * Dynamically adjusts emulation settings based on device state, battery level, and thermal conditions
 */
class AndroidPerformanceManager(private val context: Context) {
    companion object {
        private const val TAG = "CitraPerformance"
        private const val MONITORING_INTERVAL_MS = 2000L

        // Battery level thresholds
        private const val CRITICAL_BATTERY_THRESHOLD = 15
        private const val LOW_BATTERY_THRESHOLD = 30
        private const val GOOD_BATTERY_THRESHOLD = 60

        // Performance levels
        const val PERFORMANCE_LEVEL_MAXIMUM = 0
        const val PERFORMANCE_LEVEL_HIGH = 1
        const val PERFORMANCE_LEVEL_BALANCED = 2
        const val PERFORMANCE_LEVEL_POWER_SAVE = 3
        const val PERFORMANCE_LEVEL_ULTRA_SAVE = 4
    }    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    private val shaderCacheManager = AndroidShaderCacheManager(context)

    private var performanceExecutor: ScheduledExecutorService? = null
    private var currentPerformanceLevel = PERFORMANCE_LEVEL_BALANCED
    private var isMonitoring = false
    private var lastThermalState = -1

    // Performance metrics tracking
    private var lastFrameTime = 0L
    private var frameTimeHistory = mutableListOf<Long>()
    private val maxHistorySize = 30    fun startPerformanceMonitoring() {
        if (isMonitoring) return

        // Initialize shader cache manager
        shaderCacheManager.initialize()

        isMonitoring = true
        performanceExecutor = Executors.newSingleThreadScheduledExecutor()

        performanceExecutor?.scheduleAtFixedRate({
            try {
                updatePerformanceMetrics()
                adjustPerformanceLevel()
            } catch (e: Exception) {
                Log.e(TAG, "Error in performance monitoring", e)
            }
        }, 0, MONITORING_INTERVAL_MS, TimeUnit.MILLISECONDS)

        Log.i(TAG, "Performance monitoring started")
    }    fun stopPerformanceMonitoring() {
        isMonitoring = false
        performanceExecutor?.shutdown()
        performanceExecutor = null
        shaderCacheManager.shutdown()
        Log.i(TAG, "Performance monitoring stopped")
    }

    private fun updatePerformanceMetrics() {
        // Track frame timing for adaptive optimization
        val currentTime = SystemClock.elapsedRealtime()
        if (lastFrameTime > 0) {
            val frameTime = currentTime - lastFrameTime
            frameTimeHistory.add(frameTime)

            if (frameTimeHistory.size > maxHistorySize) {
                frameTimeHistory.removeAt(0)
            }
        }
        lastFrameTime = currentTime
    }    private fun adjustPerformanceLevel() {
        val newLevel = calculateOptimalPerformanceLevel()

        if (newLevel != currentPerformanceLevel) {
            Log.i(TAG, "Adjusting performance level from $currentPerformanceLevel to $newLevel")
            applyPerformanceLevel(newLevel)
            shaderCacheManager.adaptToPerformanceLevel(newLevel)
            currentPerformanceLevel = newLevel
        }
    }

    private fun calculateOptimalPerformanceLevel(): Int {
        val batteryLevel = getBatteryLevel()
        val isCharging = isDeviceCharging()
        val memoryInfo = getMemoryInfo()
        val thermalState = getThermalState()
        val isPowerSaveMode = powerManager.isPowerSaveMode

        // Critical conditions - force ultra power save
        if (batteryLevel < CRITICAL_BATTERY_THRESHOLD && !isCharging) {
            return PERFORMANCE_LEVEL_ULTRA_SAVE
        }

        if (isPowerSaveMode || thermalState >= 3) { // THERMAL_STATUS_MODERATE or higher
            return PERFORMANCE_LEVEL_POWER_SAVE
        }

        // Low memory conditions
        val availableMemoryRatio = memoryInfo.availMem.toFloat() / memoryInfo.totalMem.toFloat()
        if (availableMemoryRatio < 0.2f) {
            return PERFORMANCE_LEVEL_POWER_SAVE
        }

        // Battery-based optimization
        return when {
            batteryLevel < LOW_BATTERY_THRESHOLD && !isCharging -> PERFORMANCE_LEVEL_POWER_SAVE
            batteryLevel < GOOD_BATTERY_THRESHOLD && !isCharging -> PERFORMANCE_LEVEL_BALANCED
            isCharging || batteryLevel > GOOD_BATTERY_THRESHOLD -> {
                if (availableMemoryRatio > 0.4f) PERFORMANCE_LEVEL_HIGH else PERFORMANCE_LEVEL_BALANCED
            }
            else -> PERFORMANCE_LEVEL_BALANCED
        }
    }

    private fun applyPerformanceLevel(level: Int) {
        when (level) {
            PERFORMANCE_LEVEL_MAXIMUM -> applyMaximumPerformance()
            PERFORMANCE_LEVEL_HIGH -> applyHighPerformance()
            PERFORMANCE_LEVEL_BALANCED -> applyBalancedPerformance()
            PERFORMANCE_LEVEL_POWER_SAVE -> applyPowerSavePerformance()
            PERFORMANCE_LEVEL_ULTRA_SAVE -> applyUltraPowerSave()
        }
    }

    private fun applyMaximumPerformance() {
        // JIT optimization
        NativeLibrary.setJitOptimizationLevel(2)

        // Audio settings
        NativeLibrary.setAudioLatencyMode(0) // Low latency

        // Graphics settings
        NativeLibrary.setFrameLimit(60)
        NativeLibrary.setResolutionScale(2.0f)
        NativeLibrary.setAnisotropicFiltering(16)

        // Memory settings
        NativeLibrary.setMemoryOptimizationLevel(0)

        Log.d(TAG, "Applied maximum performance settings")
    }

    private fun applyHighPerformance() {
        NativeLibrary.setJitOptimizationLevel(2)
        NativeLibrary.setAudioLatencyMode(1) // Balanced latency
        NativeLibrary.setFrameLimit(60)
        NativeLibrary.setResolutionScale(1.5f)
        NativeLibrary.setAnisotropicFiltering(8)
        NativeLibrary.setMemoryOptimizationLevel(0)

        Log.d(TAG, "Applied high performance settings")
    }

    private fun applyBalancedPerformance() {
        NativeLibrary.setJitOptimizationLevel(1)
        NativeLibrary.setAudioLatencyMode(1)
        NativeLibrary.setFrameLimit(60)
        NativeLibrary.setResolutionScale(1.0f)
        NativeLibrary.setAnisotropicFiltering(4)
        NativeLibrary.setMemoryOptimizationLevel(1)

        Log.d(TAG, "Applied balanced performance settings")
    }

    private fun applyPowerSavePerformance() {
        NativeLibrary.setJitOptimizationLevel(0)
        NativeLibrary.setAudioLatencyMode(2) // High latency, low power
        NativeLibrary.setFrameLimit(30)
        NativeLibrary.setResolutionScale(0.75f)
        NativeLibrary.setAnisotropicFiltering(2)
        NativeLibrary.setMemoryOptimizationLevel(2)

        Log.d(TAG, "Applied power save performance settings")
    }

    private fun applyUltraPowerSave() {
        NativeLibrary.setJitOptimizationLevel(0)
        NativeLibrary.setAudioLatencyMode(3) // Maximum power saving
        NativeLibrary.setFrameLimit(20)
        NativeLibrary.setResolutionScale(0.5f)
        NativeLibrary.setAnisotropicFiltering(1)
        NativeLibrary.setMemoryOptimizationLevel(3)

        Log.d(TAG, "Applied ultra power save settings")
    }

    private fun getBatteryLevel(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        } else {
            val batteryStatus = context.registerReceiver(
                null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            )
            val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level != -1 && scale != -1) {
                (level * 100 / scale.toFloat()).toInt()
            } else {
                50 // Default to 50% if unknown
            }
        }
    }

    private fun isDeviceCharging(): Boolean {
        val batteryStatus = context.registerReceiver(
            null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
        val chargePlug = batteryStatus?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
        return chargePlug == BatteryManager.BATTERY_PLUGGED_USB ||
               chargePlug == BatteryManager.BATTERY_PLUGGED_AC ||
               chargePlug == BatteryManager.BATTERY_PLUGGED_WIRELESS
    }

    private fun getMemoryInfo(): ActivityManager.MemoryInfo {
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        return memoryInfo
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun getThermalState(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            powerManager.currentThermalStatus
        } else {
            0 // Unknown thermal state for older devices
        }
    }    fun getPerformanceStats(): Map<String, Any> {
        val batteryLevel = getBatteryLevel()
        val isCharging = isDeviceCharging()
        val memoryInfo = getMemoryInfo()
        val thermalState = getThermalState()
        val shaderCacheStats = shaderCacheManager.getCacheStatistics()

        return mapOf(
            "currentPerformanceLevel" to currentPerformanceLevel,
            "batteryLevel" to batteryLevel,
            "isCharging" to isCharging,
            "availableMemoryMB" to (memoryInfo.availMem / (1024 * 1024)),
            "totalMemoryMB" to (memoryInfo.totalMem / (1024 * 1024)),
            "thermalState" to thermalState,
            "isPowerSaveMode" to powerManager.isPowerSaveMode,
            "averageFrameTime" to if (frameTimeHistory.isNotEmpty()) {
                frameTimeHistory.average()
            } else 0.0,
            "shaderCache" to shaderCacheStats
        )
    }    fun forcePerformanceLevel(level: Int) {
        if (level in PERFORMANCE_LEVEL_MAXIMUM..PERFORMANCE_LEVEL_ULTRA_SAVE) {
            applyPerformanceLevel(level)
            shaderCacheManager.adaptToPerformanceLevel(level)
            currentPerformanceLevel = level
            Log.i(TAG, "Forced performance level to $level")
        }
    }

    // Shader cache management functions
    fun clearShaderCache() {
        shaderCacheManager.clearCache()
    }

    fun precompileCommonShaders() {
        shaderCacheManager.precompileCommonShaders()
    }

    fun getShaderCacheStatistics(): Map<String, Any> {
        return shaderCacheManager.getCacheStatistics()
    }
}
