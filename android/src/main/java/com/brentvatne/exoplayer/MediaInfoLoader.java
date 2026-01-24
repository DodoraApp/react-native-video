package com.brentvatne.exoplayer;

import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.brentvatne.common.toolbox.DebugLog;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import io.github.anilbeesetti.nextlib.mediainfo.MediaInfoBuilder;
import io.github.anilbeesetti.nextlib.mediainfo.VideoStream;

/**
 * Loads detailed media information from video files using FFmpeg-based analysis.
 * Similar to Stremio's MediaInfoLoader implementation.
 */
public class MediaInfoLoader {
    private static final String TAG = "MediaInfoLoader";
    
    // Pattern to detect live streaming URLs (don't try to analyze these)
    private static final Pattern LIVE_EXT_PATTERN = Pattern.compile("\\.(m3u8?|mpd)\\b", Pattern.CASE_INSENSITIVE);
    
    /**
     * Loads media information from a video source URL.
     * This method performs file analysis and should be called on a background thread.
     * 
     * @param streamUrl The URL or path to the video source
     * @return MediaInfo object with extracted information, or null if extraction failed
     */
    @WorkerThread
    @Nullable
    public static MediaInfo loadMediaInfo(String streamUrl) {
        if (streamUrl == null || streamUrl.isEmpty()) {
            DebugLog.w(TAG, "Stream URL is null or empty");
            return null;
        }
        
        // Don't try to analyze live streams or manifests
        if (LIVE_EXT_PATTERN.matcher(streamUrl).find()) {
            DebugLog.d(TAG, "Skipping media info extraction for live stream: " + streamUrl);
            return null;
        }
        
        io.github.anilbeesetti.nextlib.mediainfo.MediaInfo nativeMediaInfo = null;
        
        try {
            DebugLog.d(TAG, "Starting media info extraction for: " + streamUrl);
            
            // Build and extract media info using nextlib
            nativeMediaInfo = new MediaInfoBuilder()
                    .from(streamUrl)
                    .build();
            
            if (nativeMediaInfo == null) {
                DebugLog.w(TAG, "MediaInfo extraction returned null");
                return null;
            }
            
            // Extract basic properties
            long duration = nativeMediaInfo.getDuration();
            
            // Extract video stream properties
            VideoStream videoStream = nativeMediaInfo.getVideoStream();
            long videoWidth = -1;
            long videoHeight = -1;
            float frameRate = -1.0f;
            long bitrate = -1;
            
            if (videoStream != null) {
                videoWidth = videoStream.getFrameWidth();
                videoHeight = videoStream.getFrameHeight();
                frameRate = (float) videoStream.getFrameRate();
                bitrate = videoStream.getBitRate();
                
                DebugLog.d(TAG, "Extracted video properties: " +
                        videoWidth + "x" + videoHeight + "@" + frameRate + " fps, " +
                        "bitrate: " + bitrate);
            } else {
                DebugLog.w(TAG, "No video stream found in media info");
            }
            
            // Extract chapters
            List<MediaInfo.Chapter> chapters = new ArrayList<>();
            List<io.github.anilbeesetti.nextlib.mediainfo.Chapter> nativeChapters = nativeMediaInfo.getChapters();
            
            if (nativeChapters != null && !nativeChapters.isEmpty()) {
                DebugLog.d(TAG, "Found " + nativeChapters.size() + " chapters");
                
                for (io.github.anilbeesetti.nextlib.mediainfo.Chapter nativeChapter : nativeChapters) {
                    String title = nativeChapter.getTitle();
                    if (title == null) {
                        title = "";
                    }
                    
                    long startMs = nativeChapter.getStart();
                    long endMs = nativeChapter.getEnd();
                    
                    // Adjust end time if it matches duration exactly
                    if (endMs == duration && endMs > 0) {
                        endMs = duration;
                    }
                    
                    chapters.add(new MediaInfo.Chapter(title, startMs, endMs));
                }
            }
            
            MediaInfo result = new MediaInfo(duration, videoWidth, videoHeight, frameRate, bitrate, chapters);
            DebugLog.d(TAG, "Media info extraction successful: " + result);
            
            return result;
            
        } catch (Exception e) {
            DebugLog.e(TAG, "Failed to extract media info: " + e.getMessage());
            e.printStackTrace();
            return null;
        } finally {
            // Release native resources
            if (nativeMediaInfo != null) {
                try {
                    nativeMediaInfo.release();
                } catch (Exception e) {
                    DebugLog.w(TAG, "Error releasing media info: " + e.getMessage());
                }
            }
        }
    }
    
    /**
     * Checks if media info extraction is supported for the given URL.
     * 
     * @param streamUrl The URL to check
     * @return true if extraction is supported, false otherwise
     */
    public static boolean isExtractionSupported(String streamUrl) {
        if (streamUrl == null || streamUrl.isEmpty()) {
            return false;
        }
        
        // Don't support live streams
        if (LIVE_EXT_PATTERN.matcher(streamUrl).find()) {
            return false;
        }

        return true;
    }
}
