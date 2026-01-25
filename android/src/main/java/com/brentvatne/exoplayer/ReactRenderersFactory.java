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
    private boolean enableVideoSoftwareDecoding = false;

    public ReactRenderersFactory(Context context, boolean enableWorkarounds, boolean enableVideoSoftwareDecoding) {
        super(context);

        this.enableWorkarounds = enableWorkarounds;
        this.enableVideoSoftwareDecoding = enableVideoSoftwareDecoding;
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

        // 2. Remove FFmpeg video renderer if software decoding is disabled
        if (!enableVideoSoftwareDecoding) {
            // Remove FFmpeg video renderer (typically added by NextRenderersFactory)
            // FFmpeg renderer is usually not a MediaCodecVideoRenderer instance
            out.removeIf(renderer -> 
                !(renderer instanceof MediaCodecVideoRenderer) && 
                renderer.getClass().getName().contains("FfmpegVideoRenderer")
            );
        }

        // 3. Find the standard MediaCodecVideoRenderer in the list and replace with our custom one
        int rendererIndex = -1;
        for (int i = 0; i < out.size(); i++) {
            if (out.get(i) instanceof MediaCodecVideoRenderer) {
                rendererIndex = i;
                break;
            }
        }

        // 4. Replace hardware renderer with our custom one that has workarounds
        if (rendererIndex != -1) {
            out.remove(rendererIndex);

            MediaCodecVideoRenderer.Builder mediaCodecVideoRendererBuilder = new MediaCodecVideoRenderer.Builder(context)
                    .setMediaCodecSelector(mediaCodecSelector)
                    .setAllowedJoiningTimeMs(allowedVideoJoiningTimeMs)
                    .setEnableDecoderFallback(enableDecoderFallback)
                    .setEventHandler(eventHandler)
                    .setEventListener(eventListener)
                    .setMaxDroppedFramesToNotify(MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY);

            // Add at first position to prefer hardware over software decoding
            out.add(0, new MediaCodecVideoRendererWithWorkarounds(mediaCodecVideoRendererBuilder, enableWorkarounds));
        }
    }
}
