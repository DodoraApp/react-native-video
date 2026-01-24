package com.brentvatne.exoplayer;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Container for extracted media information including video properties and chapters.
 */
public class MediaInfo {
    private final long durationMs;
    private final long videoWidth;
    private final long videoHeight;
    private final float frameRate;
    private final long bitrate;
    private final List<Chapter> chapters;

    public MediaInfo(long durationMs, long videoWidth, long videoHeight, float frameRate, long bitrate, List<Chapter> chapters) {
        this.durationMs = durationMs;
        this.videoWidth = videoWidth;
        this.videoHeight = videoHeight;
        this.frameRate = frameRate;
        this.bitrate = bitrate;
        this.chapters = chapters != null ? chapters : new ArrayList<>();
    }

    public long getDurationMs() {
        return durationMs;
    }

    public long getVideoWidth() {
        return videoWidth;
    }

    public long getVideoHeight() {
        return videoHeight;
    }

    public float getFrameRate() {
        return frameRate;
    }

    public long getBitrate() {
        return bitrate;
    }

    public List<Chapter> getChapters() {
        return chapters;
    }

    /**
     * Represents a chapter in the media.
     */
    public static class Chapter {
        private final String title;
        private final long startMs;
        private final long endMs;

        public Chapter(String title, long startMs, long endMs) {
            this.title = title != null ? title : "";
            this.startMs = startMs;
            this.endMs = endMs;
        }

        public String getTitle() {
            return title;
        }

        public long getStartMs() {
            return startMs;
        }

        public long getEndMs() {
            return endMs;
        }

        @Override
        public String toString() {
            return "Chapter{" +
                    "title='" + title + '\'' +
                    ", startMs=" + startMs +
                    ", endMs=" + endMs +
                    '}';
        }
    }

    @Override
    public String toString() {
        return "MediaInfo{" +
                "durationMs=" + durationMs +
                ", videoWidth=" + videoWidth +
                ", videoHeight=" + videoHeight +
                ", frameRate=" + frameRate +
                ", bitrate=" + bitrate +
                ", chapters=" + chapters.size() +
                '}';
    }
}
