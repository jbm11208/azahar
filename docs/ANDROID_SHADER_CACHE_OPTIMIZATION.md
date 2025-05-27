# Android Shader Cache Optimization for Azahar Citra Emulator

## Overview

The Android Shader Cache Optimization system provides intelligent, mobile-optimized shader caching for the Azahar Citra emulator. This system is designed to improve performance while being mindful of Android device constraints such as limited storage, memory, and battery life.

## Key Features

### 1. Device-Aware Optimization
- **Automatic Device Classification**: Classifies devices into High-End, Mid-Range, or Low-End based on RAM and capabilities
- **Adaptive Cache Sizing**: Dynamically adjusts cache size based on device class and available storage
- **Performance Level Integration**: Seamlessly integrates with the AndroidPerformanceManager for holistic optimization

### 2. Storage-Efficient Caching
- **Configurable Cache Sizes**: 
  - High-End devices: Up to 512MB
  - Mid-Range devices: Up to 256MB  
  - Low-End devices: Up to 128MB
- **Intelligent Cleanup**: Automatic removal of old cache files when limits are exceeded
- **Emergency Storage Management**: Reduces cache size when device storage is critically low

### 3. Multiple Caching Strategies
- **Aggressive**: Maximum precompilation and caching for high-end devices
- **Balanced**: Optimal balance between performance and resource usage
- **Conservative**: Minimal caching for low-end devices or storage-constrained situations
- **Disabled**: Completely disable caching to save resources

### 4. Background Processing
- **Asynchronous Compilation**: Shaders are compiled in background threads to avoid stuttering
- **Precompilation**: Common shaders can be precompiled during idle time
- **Maintenance Tasks**: Automatic cache cleanup and optimization

## Architecture

### Core Components

#### AndroidShaderCacheManager
- **Primary Class**: `AndroidShaderCacheManager.kt`
- **Purpose**: Main controller for all shader cache operations
- **Key Methods**:
  - `initialize()`: Sets up cache directories and determines optimal settings
  - `adaptToPerformanceLevel()`: Adjusts strategy based on current performance mode
  - `clearCache()`: Removes all cached shaders
  - `getCacheStatistics()`: Returns detailed cache usage information

#### Integration with AndroidPerformanceManager
- **Automatic Adaptation**: Cache strategy automatically adjusts when performance level changes
- **Unified Statistics**: Shader cache statistics are included in performance monitoring
- **Lifecycle Management**: Cache manager is started/stopped with performance monitoring

#### Native JNI Functions
- **Cache Control**: 10 new JNI functions for controlling native shader cache behavior
- **Directory Management**: Separate directories for regular and precompiled caches
- **Compression**: Configurable compression levels for storage optimization

### Settings Integration

#### New Settings Added:
**Boolean Settings:**
- `SHADER_CACHE_ENABLED`: Master enable/disable switch
- `SHADER_CACHE_AGGRESSIVE`: Enable aggressive caching mode
- `SHADER_CACHE_CONSERVATIVE`: Enable conservative caching mode

**Integer Settings:**
- `SHADER_CACHE_MAX_SIZE_MB`: Maximum cache size in megabytes
- `SHADER_CACHE_COMPRESSION_LEVEL`: Compression level (1-3)

#### UI Integration:
- New "Shader Cache Settings" section in Performance settings
- Intuitive controls for all cache options
- Real-time feedback on cache size and storage impact

## Usage Guide

### Automatic Operation
The shader cache system works automatically once enabled:

1. **First Launch**: System analyzes device capabilities and sets optimal defaults
2. **During Gameplay**: Shaders are cached as they're compiled
3. **Background Processing**: Common shaders are precompiled during idle time
4. **Adaptive Optimization**: Settings adjust based on device conditions

### Manual Configuration

#### Basic Setup:
1. Navigate to Settings → Performance → Shader Cache Settings
2. Enable "Enable Shader Cache"
3. Choose strategy:
   - **Aggressive**: For high-end devices with plenty of storage
   - **Balanced**: Recommended for most devices (default)
   - **Conservative**: For devices with limited storage

#### Advanced Configuration:
- **Maximum Cache Size**: Adjust based on available storage (64MB - 512MB)
- **Compression Level**: Higher compression saves storage but uses more CPU

### Performance Impact

#### Benefits:
- **Reduced Stuttering**: Cached shaders load instantly
- **Faster Game Loading**: Common shaders are pre-ready
- **Improved Frame Consistency**: Less compilation during gameplay

#### Considerations:
- **Storage Usage**: Caches use disk space (managed automatically)
- **Initial Setup**: First-time compilation may take longer
- **Memory Usage**: Active caches use some RAM (optimized per device)

## Technical Implementation

### Cache Storage Structure
```
/data/data/org.citra.citra_emu/cache/
├── shaders/                    # Runtime compiled shaders
│   ├── opengl/                # OpenGL shader binaries
│   └── vulkan/                # Vulkan pipeline cache
└── shaders_precompiled/       # Precompiled common shaders
    ├── vertex/                # Vertex shader cache
    └── fragment/              # Fragment shader cache
```

### Device Classification Logic
```kotlin
val totalMemoryGB = memoryInfo.totalMem / (1024L * 1024L * 1024L)
deviceClass = when {
    totalMemoryGB >= 6 -> DeviceClass.HIGH_END      // 6GB+ RAM
    totalMemoryGB >= 4 -> DeviceClass.MID_RANGE     // 4-6GB RAM
    else -> DeviceClass.LOW_END                     // <4GB RAM
}
```

### Cache Strategy Application
- **Aggressive**: Full precompilation, maximum cache size, high compression
- **Balanced**: Selective precompilation, moderate cache size, balanced compression  
- **Conservative**: No precompilation, minimal cache size, light compression

### Integration Points

#### With AndroidPerformanceManager:
```kotlin
// Performance level changes trigger cache strategy updates
private fun adjustPerformanceLevel() {
    val newLevel = calculateOptimalPerformanceLevel()
    if (newLevel != currentPerformanceLevel) {
        applyPerformanceLevel(newLevel)
        shaderCacheManager.adaptToPerformanceLevel(newLevel) // Cache adapts
        currentPerformanceLevel = newLevel
    }
}
```

#### With Native Core:
- JNI functions bridge Kotlin cache management with C++ shader system
- Native cache directories are set by Android manager
- Compression and size limits enforced at native level

## Monitoring and Diagnostics

### Available Statistics:
- Current cache size and file count
- Device classification and strategy
- Available storage space
- Cache hit/miss ratios (when available)
- Precompilation progress

### Access Statistics:
```kotlin
val stats = performanceManager.getShaderCacheStatistics()
// Returns detailed cache information including:
// - deviceClass, currentCacheStrategy
// - cacheSizeMB, maxCacheSizeMB, cacheFileCount
// - availableStorageGB, cache directories
```

## Best Practices

### For Users:
1. **Enable on All Devices**: Even low-end devices benefit from basic caching
2. **Monitor Storage**: Check available space if experiencing issues
3. **Reset if Needed**: Clear cache if experiencing graphics glitches
4. **Update Strategy**: Adjust caching mode based on usage patterns

### For Developers:
1. **Respect Device Limits**: Always check available storage before expanding cache
2. **Background Processing**: Use separate threads for compilation and cleanup
3. **Graceful Degradation**: Fall back to no caching if initialization fails
4. **Performance Monitoring**: Track cache effectiveness and adjust algorithms

## Future Enhancements

### Planned Features:
- **Machine Learning**: AI-based prediction of frequently used shaders
- **Cloud Sync**: Share precompiled caches across devices
- **Game-Specific Optimization**: Per-game cache strategies
- **Thermal Integration**: Reduce caching during thermal throttling

### Potential Improvements:
- **Delta Compression**: Store only shader differences for updates
- **Priority Caching**: Cache shaders based on usage frequency
- **Network Caching**: Download precompiled caches for popular games
- **Advanced Analytics**: Detailed performance impact measurement

## Troubleshooting

### Common Issues:

#### Cache Not Working:
- Check if shader cache is enabled in settings
- Verify sufficient storage space (>1GB recommended)
- Try clearing cache and restarting

#### Performance Degradation:
- Reduce cache size if memory is limited  
- Switch to conservative mode
- Check thermal throttling status

#### Storage Issues:
- Monitor cache size in settings
- Enable automatic cleanup
- Clear old cache files manually if needed

### Debug Information:
All cache operations are logged with tag "CitraShaderCache" for debugging purposes.

## Conclusion

The Android Shader Cache Optimization system provides a comprehensive solution for improving Citra emulator performance on mobile devices while respecting platform constraints. Through intelligent device classification, adaptive caching strategies, and seamless integration with existing performance management, it delivers tangible benefits across the full spectrum of Android devices.
