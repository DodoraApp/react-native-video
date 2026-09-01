package com.twg.video.core.player

import android.os.Handler
import android.os.Looper
import android.util.Pair
import androidx.annotation.Nullable
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import com.margelo.nitro.video.HybridVideoPlayerEventEmitter
import com.margelo.nitro.video.VideoStatisticsData

/**
 * Aggregates playback statistics from Media3 Analytics callbacks and emits a merged,
 * debounced `onVideoStatistics` event.
 *
 * <p>Ported from the DodoStream fork (v6).
 */
class RNVPlayerStatisticsListener(
  private val eventEmitter: HybridVideoPlayerEventEmitter,
) : AnalyticsListener {

  private companion object {
    const val TAG = "RNVPlayerStatisticsListener"
    const val DEFAULT_DEBOUNCE_MS = 350L
  }

  private val handler = Handler(Looper.getMainLooper())

  private var enabled = false
  private var debounceMs = DEFAULT_DEBOUNCE_MS
  private var posting = false

  private val state = StatsState()

  @Nullable
  private var extractedMediaInfo: MediaInfo? = null

  private var lastSignature: String? = null

  private val emitRunnable = Runnable { emitIfChanged() }

  fun setEnabled(enabled: Boolean) {
    this.enabled = enabled
    if (!enabled) {
      handler.removeCallbacks(emitRunnable)
    }
  }

  fun setDebounceMs(debounceMs: Long) {
    this.debounceMs = debounceMs.coerceAtLeast(0)
  }

  fun reset() {
    emitIfChanged()
    handler.removeCallbacks(emitRunnable)
    state.reset()
    lastSignature = null
  }

  fun setStreamType(streamType: String?) {
    state.streamType = streamType
    scheduleEmit()
  }

  /** Sets the extracted media info as a fallback for missing format information. */
  fun setExtractedMediaInfo(mediaInfo: MediaInfo?) {
    this.extractedMediaInfo = mediaInfo
    scheduleEmit()
  }

  private fun scheduleEmit() {
    if (!enabled || posting) {
      return
    }
    handler.removeCallbacks(emitRunnable)
    handler.postDelayed(emitRunnable, debounceMs)
    posting = true
  }

  private fun emitIfChanged() {
    if (!enabled) {
      return
    }

    posting = false
    val signature = state.signature()
    if (signature == lastSignature) {
      return
    }
    lastSignature = signature
    eventEmitter.onVideoStatistics(state.toVideoStatisticsData(extractedMediaInfo))
  }

  // --- AnalyticsListener ---

  override fun onMediaItemTransition(
    eventTime: AnalyticsListener.EventTime,
    mediaItem: MediaItem?,
    reason: Int,
  ) {
    // New item => stats should not leak across.
    reset()
  }

  override fun onAudioDecoderInitialized(
    eventTime: AnalyticsListener.EventTime,
    decoderName: String,
    initializedTimestampMs: Long,
    initializationDurationMs: Long,
  ) {
    state.audioDecoder = decoderName
    scheduleEmit()
  }

  override fun onVideoDecoderInitialized(
    eventTime: AnalyticsListener.EventTime,
    decoderName: String,
    initializedTimestampMs: Long,
    initializationDurationMs: Long,
  ) {
    state.videoDecoder = decoderName
    scheduleEmit()
  }

  override fun onAudioInputFormatChanged(
    eventTime: AnalyticsListener.EventTime,
    format: Format,
    decoderReuseEvaluation: DecoderReuseEvaluation?,
  ) {
    state.audioFormat = format

    // Container is often present on either format.
    if (!format.containerMimeType.isNullOrEmpty()) {
      state.containerMimeType = format.containerMimeType
    }

    scheduleEmit()
  }

  override fun onVideoInputFormatChanged(
    eventTime: AnalyticsListener.EventTime,
    format: Format,
    decoderReuseEvaluation: DecoderReuseEvaluation?,
  ) {
    state.videoFormat = format

    if (!format.containerMimeType.isNullOrEmpty()) {
      state.containerMimeType = format.containerMimeType
    }

    scheduleEmit()
  }

  // --- State + formatting ---

  private class StatsState {
    @Nullable
    var streamType: String? = null

    @Nullable
    var containerMimeType: String? = null

    @Nullable
    var videoFormat: Format? = null
    @Nullable
    var videoDecoder: String? = null

    @Nullable
    var audioFormat: Format? = null
    @Nullable
    var audioDecoder: String? = null

    fun reset() {
      containerMimeType = null
      videoFormat = null
      videoDecoder = null
      audioFormat = null
      audioDecoder = null
    }

    fun signature(): String {
      // Stable, cheap signature to implement "only on change".
      return join(
        streamType,
        containerMimeType,
        videoDecoder,
        audioDecoder,
        formatSignature(videoFormat),
        formatSignature(audioFormat),
      )
    }

    private fun formatSignature(format: Format?): String? {
      if (format == null) return null
      val colorInfo = format.colorInfo
      return join(
        format.sampleMimeType,
        format.codecs,
        format.width.toString(),
        format.height.toString(),
        format.bitrate.toString(),
        format.frameRate.toString(),
        format.channelCount.toString(),
        format.sampleRate.toString(),
        format.containerMimeType,
        colorInfo?.colorTransfer?.toString(),
        colorInfo?.colorSpace?.toString(),
        colorInfo?.colorRange?.toString(),
      )
    }

    fun toVideoStatisticsData(extractedInfo: MediaInfo?): VideoStatisticsData {
      val videoFormat = videoFormat
      val audioFormat = audioFormat

      var frameRate: String? = null
      var resolution: String? = null
      var bitrate: String? = null

      if (videoFormat != null) {
        // Use extracted media info framerate as fallback if ExoPlayer format has no framerate
        var fps = videoFormat.frameRate
        if (fps <= 0 && extractedInfo != null && extractedInfo.frameRate > 0) {
          fps = extractedInfo.frameRate
        }
        frameRate = VideoMetadataUtils.getFrameRateDisplayString(fps)
        resolution = VideoMetadataUtils.getResolutionDisplayString(videoFormat.width, videoFormat.height)

        // Use extracted bitrate as fallback
        var videoBitrate = videoFormat.bitrate
        if (videoBitrate <= 0 && extractedInfo != null && extractedInfo.bitrate > 0) {
          videoBitrate = extractedInfo.bitrate.toInt()
        }
        bitrate = VideoMetadataUtils.getCombinedBitrateDisplayString(
          videoBitrate,
          audioFormat?.bitrate ?: Format.NO_VALUE,
        )
      } else if (extractedInfo != null) {
        // Use extracted info if no ExoPlayer format available
        resolution = VideoMetadataUtils.getResolutionDisplayString(
          extractedInfo.videoWidth.toInt(),
          extractedInfo.videoHeight.toInt(),
        )
        frameRate = VideoMetadataUtils.getFrameRateDisplayString(extractedInfo.frameRate)
      }

      return VideoStatisticsData(
        streamType = streamType,
        container = VideoMetadataUtils.getContainerDisplayString(containerMimeType),
        videoCodecName = videoFormat?.let { VideoMetadataUtils.getCodecDisplayString(it.sampleMimeType) },
        audioCodecName = audioFormat?.let { VideoMetadataUtils.getCodecDisplayString(it.sampleMimeType) },
        resolution = resolution,
        frameRate = frameRate,
        bitrate = bitrate,
        profileLevel = getProfileLevelSummary(videoFormat),
        decodedVideoFormat = videoFormat?.let { getDecodedFormatDisplayString(it) },
        decodedAudioFormat = audioFormat?.let { getDecodedFormatDisplayString(it) },
        decodedAudioChannels = audioFormat?.let { VideoMetadataUtils.getAudioChannelsDisplayString(it.channelCount) },
        audioLayout = audioFormat?.let { VideoMetadataUtils.getAudioLayoutDisplayString(it.channelCount) },
        videoDecoder = videoDecoder,
        audioDecoder = audioDecoder,
      )
    }

    private fun getProfileLevelSummary(format: Format?): String? {
      if (format == null) {
        return null
      }

      val dynamicRange = VideoMetadataUtils.getDynamicRangeDisplayName(format.sampleMimeType, format.colorInfo)

      val profileLevel: Pair<Int, Int>? = try {
        MediaCodecUtil.getCodecProfileAndLevel(format)
      } catch (ignored: Exception) {
        null
      }

      val codecProfileLevel: String? = if (profileLevel != null) {
        VideoMetadataUtils.getCodecProfileLevelDisplayString(
          format.sampleMimeType,
          profileLevel.first,
          profileLevel.second,
        )
      } else {
        null
      }

      if (!codecProfileLevel.isNullOrEmpty() && !dynamicRange.isNullOrEmpty()) {
        return "$dynamicRange, $codecProfileLevel"
      }
      if (!codecProfileLevel.isNullOrEmpty()) {
        return codecProfileLevel
      }
      return dynamicRange
    }

    private fun getDecodedFormatDisplayString(format: Format): String? {
      val codec = VideoMetadataUtils.getCodecDisplayString(format.sampleMimeType)

      val profileLevel: Pair<Int, Int>? = try {
        MediaCodecUtil.getCodecProfileAndLevel(format)
      } catch (ignored: Exception) {
        null
      }

      if (profileLevel == null) {
        return codec
      }

      val pl = VideoMetadataUtils.getCodecProfileLevelDisplayString(
        format.sampleMimeType,
        profileLevel.first,
        profileLevel.second,
      )
      if (pl.isNullOrEmpty()) {
        return codec
      }
      return "$codec, $pl"
    }

    private fun join(vararg parts: String?): String {
      val sb = StringBuilder()
      for ((i, part) in parts.withIndex()) {
        if (i > 0) sb.append('\u0001')
        sb.append(part ?: "")
      }
      return sb.toString()
    }
  }
}
