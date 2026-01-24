package com.brentvatne.exoplayer;

import android.content.Context;
import android.media.MediaCodecList;
import android.os.Handler;
import androidx.annotation.Nullable;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil;
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer;
import androidx.media3.exoplayer.video.VideoRendererEventListener;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory;

public class ReactRenderersFactory extends NextRenderersFactory {

    private boolean enableWorkarounds = false;

    public ReactRenderersFactory(Context context, boolean enableWorkarounds) {
        super(context);

        this.enableWorkarounds = enableWorkarounds;
    }

    @Override
    protected void buildVideoRenderers(
            Context context,
            int extensionRendererMode,
            MediaCodecSelector mediaCodecSelector,
            boolean enableDecoderFallback,
            Handler eventHandler,
            VideoRendererEventListener eventListener,
            long allowedVideoJoiningTimeMs,
            ArrayList<Renderer> out) {
        // 1. Let NextRenderersFactory populate the list with standard renderers (including FFmpeg extensions)
        super.buildVideoRenderers(
                context,
                extensionRendererMode,
                mediaCodecSelector,
                enableDecoderFallback,
                eventHandler,
                eventListener,
                allowedVideoJoiningTimeMs,
                out);

        // 2. Find the standard MediaCodecVideoRenderer in the list
        int rendererIndex = -1;
        for (int i = 0; i < out.size(); i++) {
            if (out.get(i) instanceof MediaCodecVideoRenderer) {
                rendererIndex = i;
                break;
            }
        }

        // 3. If found, replace it with our custom one
        if (rendererIndex != -1) {
            out.remove(rendererIndex);

            MediaCodecVideoRenderer.Builder mediaCodecVideoRendererBuilder = new MediaCodecVideoRenderer.Builder(context)
                    .setMediaCodecSelector(mediaCodecSelector)
                    .setAllowedJoiningTimeMs(allowedVideoJoiningTimeMs)
                    .setEnableDecoderFallback(enableDecoderFallback)
                    .setEventHandler(eventHandler)
                    .setEventListener(eventListener)
                    .setMaxDroppedFramesToNotify(MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY);

            // Add at first position to prefer over ffmpeg decoding
            out.add(0, new MediaCodecVideoRendererWithWorkarounds(mediaCodecVideoRendererBuilder, enableWorkarounds));
        }
    }
}
