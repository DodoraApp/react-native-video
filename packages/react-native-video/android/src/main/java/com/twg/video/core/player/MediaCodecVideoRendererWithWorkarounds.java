package com.twg.video.core.player;

import android.util.Pair;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.CodecSpecificDataUtil;
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil;
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer;

import java.util.Arrays;
import java.util.List;

/**
 * {@link MediaCodecVideoRenderer} with workarounds for devices that cannot decode
 * Dolby Vision profile 7 (DvheDtb) content — the renderer falls back to HEVC decoding.
 *
 * <p>Ported from the DodoStream fork (v6).
 */
public class MediaCodecVideoRendererWithWorkarounds extends MediaCodecVideoRenderer {

  private final boolean enableWorkarounds;

  protected MediaCodecVideoRendererWithWorkarounds(Builder builder, boolean enableWorkarounds) {
    super(builder);

    this.enableWorkarounds = enableWorkarounds;
  }

  @Override
  protected int supportsFormat(MediaCodecSelector mediaCodecSelector, Format format)
      throws MediaCodecUtil.DecoderQueryException {
    if (enableWorkarounds && isDolbyVisionProfile7WorkaroundRequired(format)) {
      return C.FORMAT_HANDLED;
    }
    return super.supportsFormat(mediaCodecSelector, format);
  }

  @Override
  protected List<MediaCodecInfo> getDecoderInfos(
      MediaCodecSelector mediaCodecSelector, Format format, boolean requiresSecureDecoder)
      throws MediaCodecUtil.DecoderQueryException {
    if (enableWorkarounds && isDolbyVisionProfile7WorkaroundRequired(format)) {
      // Fallback to HEVC
      return MediaCodecUtil.getDecoderInfos(MimeTypes.VIDEO_H265, requiresSecureDecoder, false);
    }
    return super.getDecoderInfos(mediaCodecSelector, format, requiresSecureDecoder);
  }

  private boolean isDolbyVisionProfile7WorkaroundRequired(Format format)
      throws MediaCodecUtil.DecoderQueryException {
    if (!MimeTypes.VIDEO_DOLBY_VISION.equals(format.sampleMimeType)) {
      return false;
    }

    boolean isSupported =
        isDvProfileLevelSupported(
            android.media.MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheDtb);
    if (isSupported) {
      return false;
    }

    Pair<Integer, Integer> profileAndLevel = CodecSpecificDataUtil.getCodecProfileAndLevel(format);
    return profileAndLevel != null
        && profileAndLevel.first
            == android.media.MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheDtb;
  }

  private static boolean isDvProfileLevelSupported(int checkedProfileLevel)
      throws MediaCodecUtil.DecoderQueryException {
    List<MediaCodecInfo> dvDecoders =
        MediaCodecUtil.getDecoderInfos(
            MimeTypes.VIDEO_DOLBY_VISION,
            false /* requiresSecure */,
            false /* requiresTunneling */);

    return dvDecoders.stream()
        .anyMatch(
            decoder -> {
              android.media.MediaCodecInfo.CodecProfileLevel[] profileLevels =
                  decoder.getProfileLevels();
              return Arrays.stream(profileLevels)
                  .anyMatch(profileLevel -> profileLevel.level == checkedProfileLevel);
            });
  }
}
