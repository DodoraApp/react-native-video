package com.brentvatne.exoplayer;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import androidx.annotation.Nullable;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.exoplayer.DecoderReuseEvaluation;
import androidx.media3.exoplayer.analytics.AnalyticsListener;

import com.brentvatne.common.react.VideoEventEmitter;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.WritableMap;

import java.util.Locale;

/**
 * Aggregates playback statistics from Media3 Analytics callbacks and emits a merged, debounced event.
 */
public final class RNVPlayerStatisticsListener implements AnalyticsListener {

    private static final long DEFAULT_DEBOUNCE_MS = 350;

    private final VideoEventEmitter eventEmitter;
    private final Handler handler;

    private boolean enabled;
    private long debounceMs = DEFAULT_DEBOUNCE_MS;
    private boolean posting;

    private final StatsState state = new StatsState();

    private @Nullable String lastSignature;
    private final Runnable emitRunnable = this::emitIfChanged;

    public RNVPlayerStatisticsListener(VideoEventEmitter eventEmitter) {
        this.eventEmitter = eventEmitter;
        this.handler = new Handler(Looper.getMainLooper());
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) {
            handler.removeCallbacks(emitRunnable);
        }
    }

    public void setDebounceMs(long debounceMs) {
        this.debounceMs = Math.max(0, debounceMs);
    }

    public void reset() {
        emitIfChanged();
        handler.removeCallbacks(emitRunnable);
        state.reset();
        lastSignature = null;
    }

    public void setStreamType(@Nullable String streamType) {
        state.streamType = streamType;
        scheduleEmit();
    }

    private void scheduleEmit() {
        if (!enabled || posting) {
            return;
        }
        handler.removeCallbacks(emitRunnable);
        handler.postDelayed(emitRunnable, debounceMs);
        this.posting = true;
    }

    private void emitIfChanged() {
        if (!enabled) {
            return;
        }

        this.posting = false;
        WritableMap stats = state.toWritableMap();
        String signature = state.signature();
        if (signature.equals(lastSignature)) {
            return;
        }
        lastSignature = signature;
        eventEmitter.onVideoStatistics.invoke(stats);
    }

    // --- AnalyticsListener ---

    @Override
    public void onMediaItemTransition(EventTime eventTime, @Nullable MediaItem mediaItem, int reason) {
        // New item => stats should not leak across.
        reset();
    }

    @Override
    public void onAudioDecoderInitialized(EventTime eventTime, String decoderName, long initializedTimestampMs, long initializationDurationMs) {
        state.audioDecoder = decoderName;
        scheduleEmit();
    }

    @Override
    public void onVideoDecoderInitialized(EventTime eventTime, String decoderName, long initializedTimestampMs, long initializationDurationMs) {
        state.videoDecoder = decoderName;
        scheduleEmit();
    }

    @Override
    public void onAudioInputFormatChanged(EventTime eventTime, Format audioFormat, @Nullable DecoderReuseEvaluation decoderReuseEvaluation) {
        state.audioFormat = audioFormat;

        // Container is often present on either format.
        if (!TextUtils.isEmpty(audioFormat.containerMimeType)) {
            state.containerMimeType = audioFormat.containerMimeType;
        }

        scheduleEmit();
    }

    @Override
    public void onVideoInputFormatChanged(EventTime eventTime, Format videoFormat, @Nullable DecoderReuseEvaluation decoderReuseEvaluation) {
        state.videoFormat = videoFormat;

        if (!TextUtils.isEmpty(videoFormat.containerMimeType)) {
            state.containerMimeType = videoFormat.containerMimeType;
        }

        scheduleEmit();
    }

    // --- State + formatting ---

    private static final class StatsState {
        @Nullable String streamType;

        @Nullable String containerMimeType;

        @Nullable Format videoFormat;
        @Nullable String videoDecoder;

        @Nullable Format audioFormat;
        @Nullable String audioDecoder;

        void reset() {
            containerMimeType = null;
            videoFormat = null;
            videoDecoder = null;
            audioFormat = null;
            audioDecoder = null;
        }

        String signature() {
            // Stable, cheap signature to implement "only on change".
            return join(
                    streamType,
                    containerMimeType,
                    videoDecoder,
                    audioDecoder,
                    formatSignature(videoFormat),
                    formatSignature(audioFormat)
            );
        }

        private static @Nullable String formatSignature(@Nullable Format format) {
            if (format == null) return null;
            ColorInfo colorInfo = format.colorInfo;
            return join(
                    format.sampleMimeType,
                    format.codecs,
                    String.valueOf(format.width),
                    String.valueOf(format.height),
                    String.valueOf(format.bitrate),
                    String.valueOf(format.frameRate),
                    String.valueOf(format.channelCount),
                    String.valueOf(format.sampleRate),
                    format.containerMimeType,
                    colorInfo == null ? null : String.valueOf(colorInfo.colorTransfer),
                    colorInfo == null ? null : String.valueOf(colorInfo.colorSpace),
                    colorInfo == null ? null : String.valueOf(colorInfo.colorRange)
            );
        }

        WritableMap toWritableMap() {
            WritableMap out = Arguments.createMap();

            // --- New, human-readable fields ---
            out.putString("streamType", streamType);

            String container = VideoMetadataUtils.getContainerDisplayString(containerMimeType);
            if (container != null) {
                out.putString("container", container);
            }

            if (videoFormat != null) {
                out.putString("videoCodecName", VideoMetadataUtils.getCodecDisplayString(videoFormat.sampleMimeType));
                out.putString("resolution", VideoMetadataUtils.getResolutionDisplayString(videoFormat.width, videoFormat.height));
                out.putString("frameRate", VideoMetadataUtils.getFrameRateDisplayString(videoFormat.frameRate));
                out.putString("decodedVideoFormat", getDecodedFormatDisplayString(videoFormat));
            }

            if (audioFormat != null) {
                out.putString("audioCodecName", VideoMetadataUtils.getCodecDisplayString(audioFormat.sampleMimeType));
                out.putString("decodedAudioFormat", getDecodedFormatDisplayString(audioFormat));
                out.putString("audioLayout", VideoMetadataUtils.getAudioLayoutDisplayString(audioFormat.channelCount));
                out.putString("decodedAudioChannels", VideoMetadataUtils.getAudioChannelsDisplayString(audioFormat.channelCount));
            }

            out.putString("videoDecoder", videoDecoder);
            out.putString("audioDecoder", audioDecoder);

            out.putString(
                    "bitrate",
                    VideoMetadataUtils.getCombinedBitrateDisplayString(
                            videoFormat != null ? videoFormat.bitrate : Format.NO_VALUE,
                            audioFormat != null ? audioFormat.bitrate : Format.NO_VALUE
                    )
            );

            out.putString("profileLevel", getProfileLevelSummary());

            return out;
        }

        private @Nullable String getProfileLevelSummary() {
            if (videoFormat == null) {
                return null;
            }

            String dynamicRange = VideoMetadataUtils.getDynamicRangeDisplayName(videoFormat.sampleMimeType, videoFormat.colorInfo);

            android.util.Pair<Integer, Integer> profileLevel = null;
            try {
                profileLevel = androidx.media3.exoplayer.mediacodec.MediaCodecUtil.getCodecProfileAndLevel(videoFormat);
            } catch (Exception ignored) {
                // ignore
            }

            String codecProfileLevel = null;
            if (profileLevel != null) {
                codecProfileLevel = VideoMetadataUtils.getCodecProfileLevelDisplayString(
                        videoFormat.sampleMimeType,
                        profileLevel.first,
                        profileLevel.second
                );
            }

            if (!TextUtils.isEmpty(codecProfileLevel) && !TextUtils.isEmpty(dynamicRange)) {
                return String.format(Locale.US, "%s, %s", dynamicRange, codecProfileLevel);
            }
            if (!TextUtils.isEmpty(codecProfileLevel)) {
                return codecProfileLevel;
            }
            if (!TextUtils.isEmpty(dynamicRange)) {
                return dynamicRange;
            }
            return null;
        }

        private static @Nullable String getDecodedFormatDisplayString(Format format) {
            if (format == null) return null;

            String codec = VideoMetadataUtils.getCodecDisplayString(format.sampleMimeType);

            android.util.Pair<Integer, Integer> profileLevel = null;
            try {
                profileLevel = androidx.media3.exoplayer.mediacodec.MediaCodecUtil.getCodecProfileAndLevel(format);
            } catch (Exception ignored) {
                // ignore
            }

            if (profileLevel == null) {
                return codec;
            }

            String pl = VideoMetadataUtils.getCodecProfileLevelDisplayString(
                    format.sampleMimeType,
                    profileLevel.first,
                    profileLevel.second
            );
            if (TextUtils.isEmpty(pl)) {
                return codec;
            }
            return codec + ", " + pl;
        }

        private static String join(@Nullable String... parts) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < parts.length; i++) {
                if (i > 0) sb.append('\u0001');
                sb.append(parts[i] == null ? "" : parts[i]);
            }
            return sb.toString();
        }
    }
}
