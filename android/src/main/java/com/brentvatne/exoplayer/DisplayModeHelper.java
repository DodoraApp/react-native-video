package com.brentvatne.exoplayer;

import android.app.Activity;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.Format;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;

import com.brentvatne.common.toolbox.DebugLog;

/**
 * Helper class for automatic framerate and resolution matching on supported devices.
 * 
 * This sets the display mode to match the video's framerate and resolution during playback,
 * and restores the original display mode when playback stops.
 * 
 * IMPORTANT: To avoid the bug described in https://github.com/androidx/media/issues/2258
 * where changing display mode can interfere with audio passthrough (especially on Nvidia Shield),
 * this class provides a callback mechanism to delay audio-sensitive operations until the
 * display mode change has settled.
 */
public class DisplayModeHelper {
    private static final String TAG = "DisplayModeHelper";
    
    /**
     * Delay in milliseconds to wait after a display mode change before allowing
     * audio passthrough setup. This is a workaround for timing issues on devices
     * like the Nvidia Shield where audio passthrough can fail if initialized
     * during a display mode transition.
     */
    private static final long MODE_CHANGE_SETTLE_DELAY_MS = 500;
    
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    
    private boolean enabled = false;
    private boolean displayModeChanged = false;
    
    @Nullable
    private Activity activity;
    
    @Nullable
    private Display.Mode originalMode;
    
    @Nullable
    private Display.Mode currentVideoMode;
    
    @Nullable
    private Runnable onModeChangeSettledCallback;
    
    private boolean isWaitingForModeSettle = false;
    
    public DisplayModeHelper() {
    }
    
    /**
     * Sets the activity used to access the display.
     */
    public void setActivity(@Nullable Activity activity) {
        this.activity = activity;
    }
    
    /**
     * Enables or disables automatic framerate matching.
     */
    public void setEnabled(boolean enabled) {
        if (this.enabled == enabled) {
            return;
        }
        this.enabled = enabled;
        DebugLog.d(TAG, "Framerate matching " + (enabled ? "enabled" : "disabled"));
        
        if (!enabled) {
            restoreOriginalDisplayMode();
        }
    }
    
    public boolean isEnabled() {
        return enabled;
    }
    
    /**
     * Checks if a display mode change is currently in progress.
     * Use this to determine if audio-sensitive operations should be delayed.
     */
    public boolean isModeChangeInProgress() {
        return isWaitingForModeSettle;
    }
    
    /**
     * Sets a callback to be invoked after a display mode change has settled.
     * This is used to delay audio passthrough setup which can fail during mode transitions.
     * 
     * The callback will be invoked immediately if no mode change is in progress.
     */
    public void setOnModeChangeSettledCallback(@Nullable Runnable callback) {
        this.onModeChangeSettledCallback = callback;
        
        // If not waiting for mode change, invoke immediately
        if (!isWaitingForModeSettle && callback != null) {
            callback.run();
        }
    }
    
    /**
     * Attempts to set the display mode to match the video format.
     * Should be called when video is loaded/ready to play.
     * 
     * @param format The video format, or null if unknown
     */
    public void setVideoFormat(@Nullable Format format) {
        if (!enabled || activity == null) {
            return;
        }
        
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            DebugLog.d(TAG, "Display mode switching not supported on API < 23");
            return;
        }
        
        if (format == null) {
            DebugLog.d(TAG, "Video format is null, cannot set display mode");
            return;
        }
        
        float frameRate = format.frameRate;
        int width = format.width;
        int height = format.height;
        
        if (frameRate <= 0 || width <= 0 || height <= 0) {
            DebugLog.d(TAG, "Invalid video dimensions or framerate: " + width + "x" + height + "@" + frameRate);
            return;
        }
        
        setDisplayMode(frameRate, width, height);
    }
    
    /**
     * Attempts to set the display mode to match the video parameters.
     * 
     * @param videoFrameRate The video's frame rate
     * @param videoWidth The video's width in pixels
     * @param videoHeight The video's height in pixels
     */
    public void setDisplayMode(float videoFrameRate, int videoWidth, int videoHeight) {
        if (!enabled || activity == null) {
            return;
        }
        
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return;
        }
        
        try {
            Window window = activity.getWindow();
            if (window == null) {
                DebugLog.w(TAG, "Window is null");
                return;
            }
            
            Display display = window.getDecorView().getDisplay();
            if (display == null) {
                DebugLog.w(TAG, "Display is null");
                return;
            }
            
            Display.Mode[] supportedModes = display.getSupportedModes();
            if (supportedModes == null || supportedModes.length == 0) {
                DebugLog.d(TAG, "No supported display modes");
                return;
            }
            
            // Store original mode if not already stored
            if (originalMode == null) {
                originalMode = display.getMode();
                DebugLog.d(TAG, "Stored original mode: " + formatMode(originalMode));
            }
            
            Display.Mode bestMode = findBestMode(supportedModes, videoFrameRate, videoWidth, videoHeight);
            
            if (bestMode == null) {
                DebugLog.d(TAG, "No suitable display mode found for " + videoWidth + "x" + videoHeight + "@" + videoFrameRate);
                return;
            }
            
            Display.Mode currentMode = display.getMode();
            if (currentMode.getModeId() == bestMode.getModeId()) {
                DebugLog.d(TAG, "Display already in optimal mode: " + formatMode(bestMode));
                return;
            }
            
            DebugLog.d(TAG, "Switching display mode from " + formatMode(currentMode) + " to " + formatMode(bestMode) + 
                    " for video " + videoWidth + "x" + videoHeight + "@" + videoFrameRate);
            
            // Mark that we're waiting for mode change to settle
            isWaitingForModeSettle = true;
            
            WindowManager.LayoutParams layoutParams = window.getAttributes();
            layoutParams.preferredDisplayModeId = bestMode.getModeId();
            window.setAttributes(layoutParams);
            
            currentVideoMode = bestMode;
            displayModeChanged = true;
            
            // Show toast with the new display mode
            final String modeString = bestMode.getPhysicalWidth() + "x" + bestMode.getPhysicalHeight() + 
                    "@" + Math.round(bestMode.getRefreshRate());
            mainHandler.post(() -> {
                if (activity != null && !activity.isFinishing()) {
                    Toast.makeText(activity, modeString, Toast.LENGTH_SHORT).show();
                }
            });
            
            // Schedule callback after settle delay to handle bug #2258
            // This gives the display time to complete the mode switch before
            // audio passthrough is configured
            mainHandler.postDelayed(() -> {
                isWaitingForModeSettle = false;
                DebugLog.d(TAG, "Display mode change settled");
                if (onModeChangeSettledCallback != null) {
                    onModeChangeSettledCallback.run();
                }
            }, MODE_CHANGE_SETTLE_DELAY_MS);
            
        } catch (Exception e) {
            DebugLog.e(TAG, "Error setting display mode: " + e.getMessage());
            isWaitingForModeSettle = false;
        }
    }
    
    /**
     * Restores the original display mode. Should be called when playback stops.
     */
    public void restoreOriginalDisplayMode() {
        if (!displayModeChanged || activity == null || originalMode == null) {
            return;
        }
        
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return;
        }
        
        try {
            Window window = activity.getWindow();
            if (window == null) {
                return;
            }
            
            DebugLog.d(TAG, "Restoring original display mode: " + formatMode(originalMode));
            
            WindowManager.LayoutParams layoutParams = window.getAttributes();
            layoutParams.preferredDisplayModeId = originalMode.getModeId();
            window.setAttributes(layoutParams);
            
            displayModeChanged = false;
            currentVideoMode = null;
            
        } catch (Exception e) {
            DebugLog.e(TAG, "Error restoring display mode: " + e.getMessage());
        }
    }
    
    /**
     * Clears the stored original mode. Call when the view is destroyed.
     */
    public void release() {
        restoreOriginalDisplayMode();
        originalMode = null;
        currentVideoMode = null;
        displayModeChanged = false;
        isWaitingForModeSettle = false;
        mainHandler.removeCallbacksAndMessages(null);
        onModeChangeSettledCallback = null;
    }
    
    /**
     * Finds the best display mode for the given video parameters.
     * 
     * Priority:
     * 1. Exact framerate match with same or higher resolution
     * 2. Multiple of framerate (e.g., 24fps on 48Hz, 30fps on 60Hz) with same or higher resolution
     * 3. Closest framerate with same or higher resolution
     */
    @Nullable
    private Display.Mode findBestMode(Display.Mode[] modes, float videoFrameRate, int videoWidth, int videoHeight) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return null;
        }
        
        Display.Mode bestExactMatch = null;
        Display.Mode bestMultipleMatch = null;
        Display.Mode bestCloseMatch = null;
        float bestExactMatchScore = Float.MAX_VALUE;
        float bestMultipleMatchScore = Float.MAX_VALUE;
        float bestCloseMatchDiff = Float.MAX_VALUE;
        
        for (Display.Mode mode : modes) {
            int modeWidth = mode.getPhysicalWidth();
            int modeHeight = mode.getPhysicalHeight();
            float modeRefreshRate = mode.getRefreshRate();
            
            // Skip modes with lower resolution than the video
            // This prevents downscaling which would reduce quality
            if (modeWidth < videoWidth || modeHeight < videoHeight) {
                continue;
            }
            
            // Calculate resolution score (lower is better - prefer closer to video resolution)
            float resolutionScore = (float)(modeWidth - videoWidth) + (float)(modeHeight - videoHeight);
            
            // Check for exact framerate match (within tolerance)
            if (isFrameRateMatch(modeRefreshRate, videoFrameRate)) {
                if (bestExactMatch == null || resolutionScore < bestExactMatchScore) {
                    bestExactMatch = mode;
                    bestExactMatchScore = resolutionScore;
                }
            }
            // Check for multiple match (e.g., 24fps on 48Hz or 72Hz)
            else if (isFrameRateMultiple(modeRefreshRate, videoFrameRate)) {
                if (bestMultipleMatch == null || resolutionScore < bestMultipleMatchScore) {
                    bestMultipleMatch = mode;
                    bestMultipleMatchScore = resolutionScore;
                }
            }
            // Track closest match as fallback
            else {
                float diff = Math.abs(modeRefreshRate - videoFrameRate);
                if (diff < bestCloseMatchDiff || 
                    (diff == bestCloseMatchDiff && resolutionScore < bestCloseMatchDiff)) {
                    bestCloseMatch = mode;
                    bestCloseMatchDiff = diff;
                }
            }
        }
        
        // Return best match by priority
        if (bestExactMatch != null) {
            return bestExactMatch;
        }
        if (bestMultipleMatch != null) {
            return bestMultipleMatch;
        }
        return bestCloseMatch;
    }
    
    /**
     * Checks if the display refresh rate matches the video frame rate.
     * Uses a tolerance to handle floating point imprecision and common video standards
     * (e.g., 23.976 fps matching 24 Hz).
     */
    private boolean isFrameRateMatch(float displayRate, float videoRate) {
        // Tolerance for matching (handles 23.976 vs 24, 29.97 vs 30, etc.)
        float tolerance = 0.1f;
        return Math.abs(displayRate - videoRate) <= tolerance;
    }
    
    /**
     * Checks if the display refresh rate is a clean multiple of the video frame rate.
     * This allows for smooth playback with frame doubling/tripling.
     */
    private boolean isFrameRateMultiple(float displayRate, float videoRate) {
        if (videoRate <= 0) {
            return false;
        }
        
        float ratio = displayRate / videoRate;
        float roundedRatio = Math.round(ratio);
        
        // Check if ratio is close to an integer (2x, 3x, 4x, etc.)
        if (roundedRatio >= 2 && Math.abs(ratio - roundedRatio) < 0.05f) {
            return true;
        }
        
        return false;
    }
    
    /**
     * Formats a display mode for logging.
     */
    private String formatMode(@Nullable Display.Mode mode) {
        if (mode == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return "null";
        }
        return mode.getPhysicalWidth() + "x" + mode.getPhysicalHeight() + "@" + mode.getRefreshRate() + "Hz";
    }
}
