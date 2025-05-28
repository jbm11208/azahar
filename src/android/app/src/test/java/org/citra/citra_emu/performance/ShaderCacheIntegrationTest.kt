package org.citra.citra_emu.performance

import android.content.Context
import android.content.SharedPreferences
import org.citra.citra_emu.NativeLibrary
import org.citra.citra_emu.features.settings.model.BooleanSetting
import org.citra.citra_emu.features.settings.model.IntSetting
import org.citra.citra_emu.features.settings.model.Settings
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import io.mockk.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay

/**
 * Integration test for the complete Android shader cache optimization system
 * Tests the interaction between AndroidShaderCacheManager and AndroidPerformanceManager
 */
class ShaderCacheIntegrationTest {

    private lateinit var context: Context
    private lateinit var sharedPrefs: SharedPreferences
    private lateinit var performanceManager: AndroidPerformanceManager
    private lateinit var shaderCacheManager: AndroidShaderCacheManager

    @Before
    fun setUp() {
        // Mock Android dependencies
        context = mockk<Context>(relaxed = true)
        sharedPrefs = mockk<SharedPreferences>(relaxed = true)

        every { context.getSharedPreferences(any(), any()) } returns sharedPrefs
        every { context.cacheDir } returns mockk(relaxed = true)

        // Mock NativeLibrary
        mockkStatic(NativeLibrary::class)
        every { NativeLibrary.setJitOptimizationLevel(any()) } just Runs
        every { NativeLibrary.setAudioLatencyMode(any()) } just Runs
        every { NativeLibrary.setFrameLimit(any()) } just Runs
        every { NativeLibrary.setResolutionScale(any()) } just Runs
        every { NativeLibrary.setAnisotropicFiltering(any()) } just Runs
        every { NativeLibrary.setMemoryOptimizationLevel(any()) } just Runs
        every { NativeLibrary.setShaderCacheDirectory(any()) } just Runs
        every { NativeLibrary.setShaderCacheMaxSize(any()) } just Runs
        every { NativeLibrary.setShaderCachePrecompileEnabled(any()) } just Runs
        every { NativeLibrary.setShaderCacheBackgroundCompilation(any()) } just Runs
        every { NativeLibrary.setShaderCacheCompressionLevel(any()) } just Runs
        every { NativeLibrary.clearShaderCache() } just Runs
        every { NativeLibrary.triggerBackgroundShaderCompilation() } just Runs
        every { NativeLibrary.precompileCommonShaders() } just Runs
        every { NativeLibrary.getShaderCacheStatistics() } returns "{\"totalCacheSize\": 2048, \"totalFiles\": 25}"

        // Create managers
        performanceManager = AndroidPerformanceManager(context)
        shaderCacheManager = AndroidShaderCacheManager(context)
    }

    @Test
    fun testCompleteSystemInitialization() {
        // Test full system initialization
        performanceManager.initialize()
        shaderCacheManager.initialize()

        // Verify both systems are initialized
        assertTrue("Performance manager should be initialized", performanceManager.isInitialized())
        assertTrue("Shader cache manager should be initialized", shaderCacheManager.isInitialized())

        // Verify shader cache was configured
        verify { NativeLibrary.setShaderCacheDirectory(any()) }
        verify { NativeLibrary.setShaderCacheMaxSize(any()) }
    }

    @Test
    fun testPerformanceLevelShaderCacheAdaptation() {
        performanceManager.initialize()
        shaderCacheManager.initialize()

        // Test HIGH_PERFORMANCE level
        performanceManager.setPerformanceLevel(AndroidPerformanceManager.PerformanceLevel.HIGH_PERFORMANCE)
        shaderCacheManager.onPerformanceLevelChanged(AndroidPerformanceManager.PerformanceLevel.HIGH_PERFORMANCE)

        // Should enable aggressive shader caching
        verify { NativeLibrary.setShaderCachePrecompileEnabled(true) }
        verify { NativeLibrary.setShaderCacheBackgroundCompilation(true) }

        clearMocks(NativeLibrary)

        // Test BATTERY_SAVING level
        performanceManager.setPerformanceLevel(AndroidPerformanceManager.PerformanceLevel.BATTERY_SAVING)
        shaderCacheManager.onPerformanceLevelChanged(AndroidPerformanceManager.PerformanceLevel.BATTERY_SAVING)

        // Should disable expensive shader operations
        verify { NativeLibrary.setShaderCachePrecompileEnabled(false) }
        verify { NativeLibrary.setShaderCacheBackgroundCompilation(false) }
    }

    @Test
    fun testThermalThrottlingWithShaderCache() {
        performanceManager.initialize()
        shaderCacheManager.initialize()

        // Simulate thermal throttling
        performanceManager.onThermalStateChanged(AndroidPerformanceManager.ThermalState.SEVERE)
        shaderCacheManager.onPerformanceLevelChanged(AndroidPerformanceManager.PerformanceLevel.BATTERY_SAVING)

        // Should reduce shader cache operations to reduce heat
        verify { NativeLibrary.setShaderCacheBackgroundCompilation(false) }

        // Recovery from thermal throttling
        clearMocks(NativeLibrary)
        performanceManager.onThermalStateChanged(AndroidPerformanceManager.ThermalState.NORMAL)
        shaderCacheManager.onPerformanceLevelChanged(AndroidPerformanceManager.PerformanceLevel.BALANCED)

        // Should restore normal shader cache operations
        verify { NativeLibrary.setShaderCacheBackgroundCompilation(true) }
    }

    @Test
    fun testMemoryPressureHandling() {
        performanceManager.initialize()
        shaderCacheManager.initialize()

        // Simulate memory pressure
        performanceManager.onMemoryPressure()
        shaderCacheManager.onMemoryPressure()

        // Both systems should optimize for memory
        verify { NativeLibrary.setMemoryOptimizationLevel(any()) }
        verify { NativeLibrary.setShaderCacheMaxSize(any()) }
    }

    @Test
    fun testBatteryOptimization() {
        performanceManager.initialize()
        shaderCacheManager.initialize()

        // Enable battery optimization
        performanceManager.setBatteryOptimizationEnabled(true)
        shaderCacheManager.setCacheStrategy(AndroidShaderCacheManager.CacheStrategy.CONSERVATIVE)

        // Should reduce power-consuming operations
        verify { NativeLibrary.setShaderCachePrecompileEnabled(false) }
        verify { NativeLibrary.setShaderCacheBackgroundCompilation(false) }

        // Disable battery optimization
        clearMocks(NativeLibrary)
        performanceManager.setBatteryOptimizationEnabled(false)
        shaderCacheManager.setCacheStrategy(AndroidShaderCacheManager.CacheStrategy.BALANCED)

        // Should restore normal operations
        verify { NativeLibrary.setShaderCachePrecompileEnabled(true) }
        verify { NativeLibrary.setShaderCacheBackgroundCompilation(true) }
    }

    @Test
    fun testGameSpecificOptimization() = runBlocking {
        performanceManager.initialize()
        shaderCacheManager.initialize()

        // Simulate starting a shader-intensive game
        val gameId = "0004000000055D00" // Example game ID

        // Should trigger shader precompilation
        shaderCacheManager.precompileCommonShaders()
        verify { NativeLibrary.precompileCommonShaders() }

        // Wait for background compilation to start
        delay(100)

        // Should trigger background compilation
        shaderCacheManager.triggerBackgroundCompilation()
        verify { NativeLibrary.triggerBackgroundShaderCompilation() }
    }

    @Test
    fun testCacheStatisticsMonitoring() {
        performanceManager.initialize()
        shaderCacheManager.initialize()

        // Get cache statistics
        val stats = shaderCacheManager.getCacheStatistics()
        assertNotNull("Cache statistics should not be null", stats)
        assertTrue("Cache statistics should contain size info", stats.contains("totalCacheSize"))

        verify { NativeLibrary.getShaderCacheStatistics() }

        // Statistics should influence performance decisions
        performanceManager.updatePerformanceMetrics()
    }

    @Test
    fun testStorageSpaceOptimization() {
        performanceManager.initialize()
        shaderCacheManager.initialize()

        // Simulate low storage space
        shaderCacheManager.optimizeForStorage()

        // Should reduce cache size and clear old files
        verify { NativeLibrary.setShaderCacheMaxSize(any()) }
    }

    @Test
    fun testSettingsIntegration() {
        // Mock settings values for aggressive caching
        mockkStatic(BooleanSetting::class)
        mockkStatic(IntSetting::class)

        every { BooleanSetting.SHADER_CACHE_ENABLED.getBoolean() } returns true
        every { BooleanSetting.SHADER_CACHE_AGGRESSIVE.getBoolean() } returns true
        every { BooleanSetting.SHADER_CACHE_CONSERVATIVE.getBoolean() } returns false
        every { IntSetting.SHADER_CACHE_MAX_SIZE_MB.getInt() } returns 512
        every { IntSetting.SHADER_CACHE_COMPRESSION_LEVEL.getInt() } returns 3

        performanceManager.initialize()
        shaderCacheManager.initialize()

        // Apply settings
        shaderCacheManager.applySettings()

        // Verify settings were applied
        verify { NativeLibrary.setShaderCacheMaxSize(512 * 1024 * 1024) }
        verify { NativeLibrary.setShaderCacheCompressionLevel(3) }
        verify { NativeLibrary.setShaderCachePrecompileEnabled(true) }
        verify { NativeLibrary.setShaderCacheBackgroundCompilation(true) }
    }

    @Test
    fun testErrorHandlingAndRecovery() {
        // Simulate native library errors
        every { NativeLibrary.setShaderCacheDirectory(any()) } throws RuntimeException("Native error")

        // System should handle errors gracefully
        performanceManager.initialize()
        shaderCacheManager.initialize()

        // Should still be initialized despite errors
        assertTrue("Performance manager should handle native errors", performanceManager.isInitialized())
        assertTrue("Shader cache manager should handle native errors", shaderCacheManager.isInitialized())
    }

    @Test
    fun testBackgroundMaintenanceCycle() = runBlocking {
        performanceManager.initialize()
        shaderCacheManager.initialize()

        // Start background maintenance
        shaderCacheManager.startBackgroundMaintenance()
        assertTrue("Background maintenance should be active", shaderCacheManager.isBackgroundMaintenanceActive())

        // Wait for maintenance cycle
        delay(200)

        // Should perform cache cleanup
        verify(timeout = 1000) { NativeLibrary.getShaderCacheStatistics() }

        // Stop background maintenance
        shaderCacheManager.stopBackgroundMaintenance()
        assertFalse("Background maintenance should be stopped", shaderCacheManager.isBackgroundMaintenanceActive())
    }

    @Test
    fun testSystemShutdown() {
        performanceManager.initialize()
        shaderCacheManager.initialize()

        // Test graceful shutdown
        shaderCacheManager.shutdown()
        performanceManager.shutdown()

        assertFalse("Shader cache manager should be shut down", shaderCacheManager.isInitialized())
        assertFalse("Performance manager should be shut down", performanceManager.isInitialized())
        assertFalse("Background maintenance should be stopped", shaderCacheManager.isBackgroundMaintenanceActive())
    }
}
