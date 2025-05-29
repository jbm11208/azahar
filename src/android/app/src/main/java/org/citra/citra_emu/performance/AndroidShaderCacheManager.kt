package org.citra.citra_emu.performance

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.util.Log
import androidx.annotation.RequiresApi
import org.citra.citra_emu.NativeLibrary
import org.citra.citra_emu.features.settings.model.BooleanSetting
import org.citra.citra_emu.features.settings.model.IntSetting
import org.citra.citra_emu.features.settings.model.Settings
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Android-optimized shader cache management for Citra emulator
 * Provides memory-efficient caching, background compilation, and adaptive cache sizing
 */
class AndroidShaderCacheManager(private val context: Context) {
    companion object {
        private const val TAG = "CitraShaderCache"
        private const val CACHE_CLEANUP_INTERVAL_MS = 10000L // 10 seconds
        private const val BACKGROUND_COMPILE_DELAY_MS = 1000L // 1 second

        // Cache size limits (in MB)
        private const val MAX_CACHE_SIZE_HIGH_END = 512L
        private const val MAX_CACHE_SIZE_MID_RANGE = 256L
        private const val MAX_CACHE_SIZE_LOW_END = 128L
        private const val MIN_CACHE_SIZE = 64L

        // Device classification thresholds
        private const val HIGH_END_RAM_GB = 6
        private const val MID_RANGE_RAM_GB = 4

        // Storage thresholds
        private const val MIN_FREE_STORAGE_GB = 2L
        private const val CRITICAL_STORAGE_GB = 1L
    }

    private val settings = Settings()
    private var backgroundExecutor: ScheduledExecutorService? = null
    private var isInitialized = false
    private var deviceClass = DeviceClass.UNKNOWN
    private var maxCacheSize = MAX_CACHE_SIZE_MID_RANGE

    private val cacheDirectory: File
        get() = File(context.cacheDir, "shaders")

    private val precompiledCacheDirectory: File
        get() = File(context.cacheDir, "shaders_precompiled")

    enum class DeviceClass {
        HIGH_END,    // 6GB+ RAM, high-end SoC
        MID_RANGE,   // 4-6GB RAM, mid-range SoC
        LOW_END,     // <4GB RAM, low-end SoC
        UNKNOWN
    }

    enum class CacheStrategy {
        AGGRESSIVE,   // Precompile and cache aggressively
        BALANCED,     // Balance between performance and storage
        CONSERVATIVE, // Minimal caching, save storage/memory
        DISABLED      // No shader caching
    }

    fun initialize() {
        if (isInitialized) return

        try {
            classifyDevice()
            createCacheDirectories()
            determineOptimalCacheSize()
            initializeNativeCache()
            startBackgroundMaintenance2()

            isInitialized = true
            Log.i(TAG, "Android shader cache manager initialized - Device: $deviceClass, Max cache: ${maxCacheSize}MB")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize shader cache manager", e)
        }
    }

    fun shutdown() {
        backgroundExecutor?.shutdown()
        backgroundExecutor = null
        isInitialized = false
        Log.i(TAG, "Shader cache manager shut down")
    }

    private fun classifyDevice() {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val memoryInfo = android.app.ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)

        val totalMemoryGB = memoryInfo.totalMem / (1024L * 1024L * 1024L)

        deviceClass = when {
            totalMemoryGB >= HIGH_END_RAM_GB -> DeviceClass.HIGH_END
            totalMemoryGB >= MID_RANGE_RAM_GB -> DeviceClass.MID_RANGE
            else -> DeviceClass.LOW_END
        }

        Log.d(TAG, "Device classified as: $deviceClass (${totalMemoryGB}GB RAM)")
    }

    private fun createCacheDirectories() {
        listOf(cacheDirectory, precompiledCacheDirectory).forEach { dir ->
            if (!dir.exists()) {
                if (dir.mkdirs()) {
                    Log.d(TAG, "Created cache directory: ${dir.absolutePath}")
                } else {
                    Log.w(TAG, "Failed to create cache directory: ${dir.absolutePath}")
                }
            }
        }
    }

    private fun determineOptimalCacheSize() {
        val availableStorage = getAvailableStorageGB()
        val targetSize = when (deviceClass) {
            DeviceClass.HIGH_END -> MAX_CACHE_SIZE_HIGH_END
            DeviceClass.MID_RANGE -> MAX_CACHE_SIZE_MID_RANGE
            DeviceClass.LOW_END -> MAX_CACHE_SIZE_LOW_END
            DeviceClass.UNKNOWN -> MAX_CACHE_SIZE_LOW_END
        }

        // Adjust based on available storage
        maxCacheSize = when {
            availableStorage < CRITICAL_STORAGE_GB -> MIN_CACHE_SIZE
            availableStorage < MIN_FREE_STORAGE_GB -> (targetSize * 0.5f).toLong().coerceAtLeast(MIN_CACHE_SIZE)
            else -> targetSize
        }

        Log.d(TAG, "Optimal cache size determined: ${maxCacheSize}MB (Storage: ${availableStorage}GB)")
    }

    private fun getAvailableStorageGB(): Long {
        return try {
            val statFs = StatFs(Environment.getDataDirectory().path)
            val availableBytes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                statFs.availableBytes
            } else {
                @Suppress("DEPRECATION")
                statFs.availableBlocks.toLong() * statFs.blockSize.toLong()
            }
            availableBytes / (1024L * 1024L * 1024L)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get available storage", e)
            MIN_FREE_STORAGE_GB // Conservative fallback
        }
    }

    private fun initializeNativeCache() {
        // Set cache directories in native code
        NativeLibrary.setShaderCacheDirectory(cacheDirectory.absolutePath)
        NativeLibrary.setPrecompiledShaderCacheDirectory(precompiledCacheDirectory.absolutePath)

        // Configure cache settings based on device class and user preferences
        val cacheStrategy = getCurrentCacheStrategy()
        applyCacheStrategy(cacheStrategy)
    }

    private fun getCurrentCacheStrategy(): CacheStrategy {
        val isEnabled = BooleanSetting.SHADER_CACHE_ENABLED.boolean
        if (!isEnabled) return CacheStrategy.DISABLED

        val aggressiveMode = BooleanSetting.SHADER_CACHE_AGGRESSIVE.boolean
        val conservativeMode = BooleanSetting.SHADER_CACHE_CONSERVATIVE.boolean

        return when {
            conservativeMode -> CacheStrategy.CONSERVATIVE
            aggressiveMode && deviceClass == DeviceClass.HIGH_END -> CacheStrategy.AGGRESSIVE
            else -> CacheStrategy.BALANCED
        }
    }

    private fun applyCacheStrategy(strategy: CacheStrategy) {
        when (strategy) {
            CacheStrategy.AGGRESSIVE -> {
                NativeLibrary.setShaderCacheMaxSize((maxCacheSize * 1024 * 1024).toInt())
                NativeLibrary.setShaderCachePrecompileEnabled(true)
                NativeLibrary.setShaderCacheBackgroundCompilation(true)
                NativeLibrary.setShaderCacheCompressionLevel(3) // High compression
                Log.d(TAG, "Applied aggressive cache strategy")
            }
            CacheStrategy.BALANCED -> {
                NativeLibrary.setShaderCacheMaxSize(((maxCacheSize * 0.75f) * 1024 * 1024).toInt())
                NativeLibrary.setShaderCachePrecompileEnabled(deviceClass != DeviceClass.LOW_END)
                NativeLibrary.setShaderCacheBackgroundCompilation(true)
                NativeLibrary.setShaderCacheCompressionLevel(2) // Balanced compression
                Log.d(TAG, "Applied balanced cache strategy")
            }
            CacheStrategy.CONSERVATIVE -> {
                NativeLibrary.setShaderCacheMaxSize(((maxCacheSize * 0.5f) * 1024 * 1024).toInt())
                NativeLibrary.setShaderCachePrecompileEnabled(false)
                NativeLibrary.setShaderCacheBackgroundCompilation(false)
                NativeLibrary.setShaderCacheCompressionLevel(1) // Light compression
                Log.d(TAG, "Applied conservative cache strategy")
            }
            CacheStrategy.DISABLED -> {
                NativeLibrary.setShaderCacheMaxSize(0)
                NativeLibrary.setShaderCachePrecompileEnabled(false)
                NativeLibrary.setShaderCacheBackgroundCompilation(false)
                Log.d(TAG, "Shader cache disabled")
            }
        }
    }

    private fun startBackgroundMaintenance2() {
        backgroundExecutor = Executors.newSingleThreadScheduledExecutor()

        // Schedule periodic cache cleanup
        backgroundExecutor?.scheduleAtFixedRate({
            try {
                performCacheMaintenance()
            } catch (e: Exception) {
                Log.e(TAG, "Error during cache maintenance", e)
            }
        }, CACHE_CLEANUP_INTERVAL_MS, CACHE_CLEANUP_INTERVAL_MS, TimeUnit.MILLISECONDS)

        // Schedule delayed background compilation
        backgroundExecutor?.schedule({
            try {
                if (getCurrentCacheStrategy() != CacheStrategy.DISABLED) {
                    NativeLibrary.triggerBackgroundShaderCompilation()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error triggering background compilation", e)
            }
        }, BACKGROUND_COMPILE_DELAY_MS, TimeUnit.MILLISECONDS)
    }

    private fun performCacheMaintenance() {
        val currentCacheSize = getCurrentCacheSizeBytes()
        val maxCacheSizeBytes = maxCacheSize * 1024 * 1024

        if (currentCacheSize > maxCacheSizeBytes) {
            Log.d(TAG, "Cache size (${currentCacheSize / (1024 * 1024)}MB) exceeds limit, cleaning up")
            cleanupOldCacheFiles()
        }

        // Check storage availability and adjust if needed
        val availableStorage = getAvailableStorageGB()
        if (availableStorage < CRITICAL_STORAGE_GB) {
            Log.w(TAG, "Critical storage level detected, reducing cache size")
            emergencyCleanup()
        }
    }

    private fun getCurrentCacheSizeBytes(): Long {
        return listOf(cacheDirectory, precompiledCacheDirectory).sumOf { dir ->
            dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }
    }

    private fun cleanupOldCacheFiles() {
        listOf(cacheDirectory, precompiledCacheDirectory).forEach { dir ->
            val files = dir.listFiles()?.toList() ?: return@forEach

            // Sort by last modified time (oldest first)
            val sortedFiles = files.sortedBy { it.lastModified() }

            var totalSize = files.sumOf { it.length() }
            val targetSize = (maxCacheSize * 0.8f * 1024 * 1024).toLong() // 80% of max

            for (file in sortedFiles) {
                if (totalSize <= targetSize) break

                if (file.delete()) {
                    totalSize -= file.length()
                    Log.v(TAG, "Deleted cache file: ${file.name}")
                }
            }
        }
    }

    private fun emergencyCleanup() {
        // Clear 50% of cache files in emergency
        listOf(cacheDirectory, precompiledCacheDirectory).forEach { dir ->
            val files = dir.listFiles()?.toList() ?: return@forEach
            val filesToDelete = files.sortedBy { it.lastModified() }.take(files.size / 2)

            filesToDelete.forEach { file ->
                if (file.delete()) {
                    Log.v(TAG, "Emergency deleted: ${file.name}")
                }
            }
        }

        // Reduce max cache size temporarily
        maxCacheSize = (maxCacheSize * 0.5f).toLong().coerceAtLeast(MIN_CACHE_SIZE)
        NativeLibrary.setShaderCacheMaxSize((maxCacheSize * 1024 * 1024).toInt())
    }

    fun adaptToPerformanceLevel(performanceLevel: Int) {
        if (!isInitialized) return

        val strategy = when (performanceLevel) {
            AndroidPerformanceManager.PERFORMANCE_LEVEL_MAXIMUM,
            AndroidPerformanceManager.PERFORMANCE_LEVEL_HIGH -> {
                if (deviceClass == DeviceClass.HIGH_END) CacheStrategy.AGGRESSIVE else CacheStrategy.BALANCED
            }
            AndroidPerformanceManager.PERFORMANCE_LEVEL_BALANCED -> CacheStrategy.BALANCED
            AndroidPerformanceManager.PERFORMANCE_LEVEL_POWER_SAVE,
            AndroidPerformanceManager.PERFORMANCE_LEVEL_ULTRA_SAVE -> CacheStrategy.CONSERVATIVE
            else -> CacheStrategy.BALANCED
        }

        applyCacheStrategy(strategy)
        Log.d(TAG, "Adapted cache strategy to performance level $performanceLevel: $strategy")
    }

    fun clearCache() {
        try {
            listOf(cacheDirectory, precompiledCacheDirectory).forEach { dir ->
                dir.deleteRecursively()
                dir.mkdirs()
            }

            NativeLibrary.clearShaderCache()
            Log.i(TAG, "Shader cache cleared")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear shader cache", e)
        }
    }

    fun getCacheStatistics(): Map<String, Any> {
        val cacheSize = getCurrentCacheSizeBytes()
        val cacheFiles = listOf(cacheDirectory, precompiledCacheDirectory).sumOf { dir ->
            dir.listFiles()?.size ?: 0
        }

        return mapOf(
            "deviceClass" to deviceClass.name,
            "currentCacheStrategy" to getCurrentCacheStrategy().name,
            "cacheSizeMB" to (cacheSize / (1024 * 1024)),
            "maxCacheSizeMB" to maxCacheSize,
            "cacheFileCount" to cacheFiles,
            "availableStorageGB" to getAvailableStorageGB(),
            "cacheDirectory" to cacheDirectory.absolutePath,
            "precompiledCacheDirectory" to precompiledCacheDirectory.absolutePath
        )
    }

    fun precompileCommonShaders() {
        if (!isInitialized || getCurrentCacheStrategy() == CacheStrategy.DISABLED) return

        backgroundExecutor?.execute {
            try {
                Log.d(TAG, "Starting precompilation of common shaders")
                NativeLibrary.precompileCommonShaders()
                Log.d(TAG, "Common shader precompilation completed")
            } catch (e: Exception) {
                Log.e(TAG, "Error during shader precompilation", e)
            }
        }
    }

    // Additional methods for testing and API completeness
    fun isInitialized(): Boolean = isInitialized

    fun getMaxCacheSize(): Long = maxCacheSize

    fun destroy() {
        shutdown()
    }

    fun onMemoryPressure() {
        if (!isInitialized) return

        Log.i(TAG, "Memory pressure detected, optimizing shader cache")

        // Reduce cache size temporarily
        val reducedCacheSize = (maxCacheSize * 0.5).toLong()
        NativeLibrary.setShaderCacheMaxSize((reducedCacheSize * 1024 * 1024).toInt())

        // Clear some cache if needed
        try {
            val stats = NativeLibrary.getShaderCacheStatistics()
            Log.d(TAG, "Cache stats during memory pressure: $stats")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get cache statistics during memory pressure", e)
        }
    }

    fun optimizeForStorage() {
        if (!isInitialized) return

        try {
            val availableStorage = getAvailableStorageGB()
            if (availableStorage < CRITICAL_STORAGE_GB * 1024L * 1024L * 1024L) {
                Log.w(TAG, "Critical storage space, applying aggressive cache optimization")
                applyCacheStrategy(CacheStrategy.CONSERVATIVE)

                // Reduce cache size significantly
                val criticalCacheSize = MIN_CACHE_SIZE / 2
                NativeLibrary.setShaderCacheMaxSize((criticalCacheSize * 1024 * 1024).toInt())

                // Clear some cache data
                clearOldCacheFiles()
            } else if (availableStorage < MIN_FREE_STORAGE_GB * 1024L * 1024L * 1024L) {
                Log.i(TAG, "Low storage space, optimizing cache usage")
                applyCacheStrategy(CacheStrategy.CONSERVATIVE)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to optimize for storage", e)
        }
    }

    fun isBackgroundMaintenanceActive(): Boolean {
        return backgroundExecutor?.isShutdown?.not() ?: false
    }

    fun startBackgroundMaintenance() {
        if (backgroundExecutor?.isShutdown != false) {
            backgroundExecutor = Executors.newSingleThreadScheduledExecutor()
        }

        backgroundExecutor?.scheduleWithFixedDelay({
            startBackgroundMaintenance2()
        }, CACHE_CLEANUP_INTERVAL_MS, CACHE_CLEANUP_INTERVAL_MS, TimeUnit.MILLISECONDS)

        Log.d(TAG, "Background maintenance started")
    }

    fun stopBackgroundMaintenance() {
        backgroundExecutor?.shutdown()
        Log.d(TAG, "Background maintenance stopped")
    }

    fun applySettings() {
        if (!isInitialized) return

        try {
            // Apply shader cache settings from the settings system
            val cacheEnabled = BooleanSetting.SHADER_CACHE_ENABLED
            val aggressiveCache = BooleanSetting.SHADER_CACHE_AGGRESSIVE
            val conservativeCache = BooleanSetting.SHADER_CACHE_CONSERVATIVE
            val maxSizeMB = IntSetting.SHADER_CACHE_MAX_SIZE_MB
            val compressionLevel = IntSetting.SHADER_CACHE_COMPRESSION_LEVEL

            // Apply cache strategy based on settings
            val strategy = when {
                !cacheEnabled -> CacheStrategy.DISABLED
                aggressiveCache -> CacheStrategy.AGGRESSIVE
                conservativeCache -> CacheStrategy.CONSERVATIVE
                else -> CacheStrategy.BALANCED
            }

            applyCacheStrategy(strategy)

            if (cacheEnabled && maxSizeMB > 0) {
                NativeLibrary.setShaderCacheMaxSize(maxSizeMB * 1024 * 1024)
            }

            NativeLibrary.setShaderCacheCompressionLevel(compressionLevel)

            Log.i(TAG, "Applied shader cache settings - Strategy: $strategy, Max size: ${maxSizeMB}MB, Compression: $compressionLevel")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply shader cache settings", e)
        }
    }

    private fun clearOldCacheFiles() {
        try {
            val cacheDir = cacheDirectory
            if (cacheDir.exists()) {
                val files = cacheDir.listFiles() ?: return
                val now = System.currentTimeMillis()
                val maxAge = 7 * 24 * 60 * 60 * 1000L // 7 days

                files.filter { it.lastModified() < now - maxAge }
                    .forEach { file ->
                        if (file.delete()) {
                            Log.d(TAG, "Deleted old cache file: ${file.name}")
                        }
                    }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear old cache files", e)
        }
    }
}
