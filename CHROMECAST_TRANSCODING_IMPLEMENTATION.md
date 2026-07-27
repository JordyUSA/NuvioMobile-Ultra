# Chromecast Hardware-Accelerated Transcoding Implementation

## Overview

This document describes the on-device hardware-accelerated transcoding system for Chromecast devices with limited codec support (Gen 1, Gen 2, Ultra).

## Architecture

### Components

1. **HardwareEncoderDetector** (`androidMain`)
   - Detects available hardware encoders on device
   - Queries MediaCodecList for capabilities
   - Prioritizes hardware-accelerated encoders

2. **MediaCodecTranscoder** (`androidMain`)
   - Performs actual transcoding using MediaCodec API
   - Extracts stream metadata
   - Handles encoding/decoding pipeline
   - Provides progress callbacks

3. **AndroidTranscodingService** (`androidMain`)
   - Manages transcoding jobs lifecycle
   - Handles job queue and cancellation
   - Provides progress tracking via Flow
   - Estimates transcoding time

4. **TranscodingViewModel** (`commonMain`)
   - Manages UI state
   - Observes progress updates
   - Handles user actions

5. **TranscodingProgressScreen** (`commonMain`)
   - Shows real-time transcoding progress
   - Displays device info and codec details
   - Allows cancellation

## Hardware Acceleration

### How It Works

1. **Encoder Detection**
   - Query system MediaCodecList for all available encoders
   - Check `isHardwareAccelerated()` flag
   - Prioritize hardware encoders over software

2. **Direct Hardware Encoding**
   - Use MediaCodec with hardware encoder (e.g., "OMX.qcom.video.encoder.avc")
   - GPU handles pixel processing
   - Significantly faster than software encoding

3. **Surface Input**
   - Use `COLOR_FormatSurface` for direct GPU pipeline
   - Reduces memory copies
   - Maximizes efficiency

### Performance Characteristics

**With Hardware Acceleration:**
- H.264 encoding: ~3-5x faster than software
- 1080p@30fps: Realizable in 5-15 minutes
- Battery impact: Moderate (GPU handles load)
- Quality: Full (no degradation)

**Without Hardware Acceleration:**
- H.264 encoding: 10-20x slower
- 1080p@30fps: 30-60+ minutes
- Battery impact: Very high (CPU maxed)
- Not recommended for older devices

## Supported Operations

### Resolution Downscaling
- 4K → 1080p (recommended for older Chromecast)
- 1080p → 720p (for very limited devices)
- Maintains quality while reducing processing load

### Codec Conversion
- H.265/HEVC → H.264/AVC (most important)
- VP9 → VP8 (if needed)
- Stereo downmix from 5.1/7.1

### Container Operations
- MKV → MP4 remuxing (fast, no re-encoding)
- Audio track selection/extraction

## Configuration

### Transcoding Parameters

```kotlin
TranscodingJob(
    targetCodec = VideoCodec.H264,      // Target codec
    targetResolution = Resolution.FULL_HD, // Resolution
    targetBitrate = 8_000_000L,         // 8 Mbps for Chromecast
    audioCodec = AudioCodec.AAC,        // Audio codec
    useHardwareAcceleration = true       // Enable HW accel
)
```

### Device-Specific Profiles

**Chromecast Gen 1/2:**
- Max: 1080p H.264, stereo AAC
- Bitrate: 5-8 Mbps
- Estimated time: 15-20 min for 2-hour movie

**Chromecast Ultra:**
- Max: 4K H.265, Dolby Atmos
- Bitrate: 15-25 Mbps
- Estimated time: 5-10 min for 2-hour movie

**Google TV Streamer:**
- Max: 4K AV1, Dolby Atmos
- Bitrate: 20-25 Mbps
- Estimated time: 3-5 min for 2-hour movie

## Usage Flow

1. **User attempts to cast incompatible stream**
   - Device detection identifies Chromecast Gen 2
   - Capability check shows H.265 not supported

2. **App offers transcoding option**
   - Show device name and limitations
   - Estimate transcoding time (10-15 minutes)
   - Ask user for confirmation

3. **Transcoding starts**
   - Download/copy source file to cache
   - Initialize MediaCodec with hardware encoder
   - Begin frame-by-frame encoding
   - Update progress UI every 1-2 seconds

4. **Streaming begins**
   - Once buffered, start casting to device
   - Continue transcoding for remaining content
   - Or wait for complete transcoding first

5. **Cleanup**
   - Delete temporary transcoded file after casting
   - Release MediaCodec resources

## Error Handling

### Out of Disk Space
- Check available cache space before starting
- Abort if < 2x source file size available
- Clear other cache if possible

### Encoder Unavailable
- Fall back to software encoder
- Warn user about extended time
- Or suggest server-side transcoding

### Process Crash/OOM
- Catch and report to user
- Suggest lower resolution
- Or use different source

### Network Interruption
- Pause transcoding if downloading
- Resume if possible
- Clean up partial files

## Testing

### Unit Tests
- Mock MediaCodecList
- Test encoder detection logic
- Verify capability matching

### Integration Tests
- Test on real devices:
  - Pixel 6/7/8 (baseline)
  - Older Snapdragon (performance)
  - Mediatek device (different hardware)
- Verify output playability

### Performance Tests
- Measure encoding speed per codec
- Check battery drain during transcoding
- Monitor memory usage

## Limitations & Future Work

### Current Limitations
1. Android-only (no iOS support yet)
2. Single-threaded transcoding
3. No resume capability if interrupted
4. Limited to local/cached files

### Future Enhancements
1. **Streaming transcoding:** Transcode while streaming to Chromecast
2. **Server offload:** Send to backend if device can't handle
3. **Parallel processing:** Multi-threaded frame encoding
4. **Resume capability:** Checkpoint transcoding progress
5. **iOS support:** Use VideoToolbox framework

## Dependencies

- Android Media Framework (API 21+)
- Google Cast SDK (`com.google.android.gms:play-services-cast-framework:21.4.0`)
- Kotlin Coroutines
- Compose Multiplatform

## Branch

**feature/chromecast-hw-transcoding**

This implementation lives in the testing branch and is ready for integration testing.

## Next Steps

1. Test on actual Chromecast devices
2. Fine-tune bitrate/resolution parameters
3. Add server-side transcoding as fallback
4. Implement iOS VideoToolbox support
5. Add progress persistence for resume capability
