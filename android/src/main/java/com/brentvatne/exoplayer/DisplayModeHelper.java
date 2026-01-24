package com.brentvatne.exoplayer;

import android.app.Activity;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import com.brentvatne.common.toolbox.DebugLog;

/**
 * Helper class for automatic framerate matching on supported devices (API 23+).
 * 
 * Sets the display mode to match the video's framerate during playback,
 * and restores the original display mode when playback stops.
 */
public class DisplayModeHelper {
    private static final String TAG = "DisplayModeHelper";
    
    /** Tolerance for matching framerates (handles 23.976 vs 24, 29.97 vs 30, etc.) */
    private static final float FRAME_RATE_TOLERANCE = 0.1f;
    
    /** Tolerance for detecting frame rate multiples */
    private static final float MULTIPLE_TOLERANCE = 0.05f;
    
    /** Delay to wait after display mode change before invoking callback */
    private static final long MODE_CHANGE_SETTLE_DELAY_MS = 500;
    
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    
    private boolean enabled = false;
    private boolean displayModeChanged = false;
    private boolean isWaitingForModeSettle = false;
    
    @Nullable private Activity activity;
    @Nullable private DisplayManager displayManager;
    @Nullable private Display.Mode originalMode;
    @Nullable private DisplayManager.DisplayListener displayListener;
    @Nullable private Runnable onModeChangeSettledCallback;

    /**
     * Sets the activity used to access the display.
     */
    public void setActivity(@Nullable Activity activity) {
        if (this.activity != null && activity != this.activity && displayModeChanged) {
            DebugLog.d(TAG, "Activity changed while display mode active, restoring");
            restoreOriginalDisplayMode();
        }
        
        this.activity = activity;
        this.displayManager = (activity != null) 
            ? (DisplayManager) activity.getSystemService(Context.DISPLAY_SERVICE)
            : null;
    }
    
    /**
     * Enables or disables automatic framerate matching.
     */
    public void setEnabled(boolean enabled) {
        if (this.enabled == enabled) return;
        
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
     */
    public boolean isModeChangeInProgress() {
        return isWaitingForModeSettle;
    }
    
    /**
     * Sets a callback to be invoked after a display mode change has settled.
     * The callback will be invoked immediately if no mode change is in progress.
     */
    public void setOnModeChangeSettledCallback(@Nullable Runnable callback) {
        this.onModeChangeSettledCallback = callback;
        if (!isWaitingForModeSettle && callback != null) {
            callback.run();
        }
    }
    
    /**
     * Sets the display mode to match the video parameters.
     * 
     * @param frameRate The video's frame rate
     * @param width The video's width in pixels
     * @param height The video's height in pixels
     */
    public void setDisplayMode(float frameRate, int width, int height) {
        if (!enabled) return;
        
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            DebugLog.d(TAG, "Display mode switching requires API 23+");
            return;
        }
        
        if (frameRate <= 0 || width <= 0 || height <= 0) {
            DebugLog.w(TAG, "Invalid parameters: " + width + "x" + height + "@" + frameRate);
            return;
        }
        
        applyDisplayMode(frameRate, width, height);
    }
    
    @RequiresApi(api = Build.VERSION_CODES.M)
    private void applyDisplayMode(float frameRate, int width, int height) {
        if (!isActivityValid()) {
            DebugLog.w(TAG, "Activity invalid, skipping display mode change");
            return;
        }
        
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
        
        // Store original mode on first call
        if (originalMode == null) {
            originalMode = display.getMode();
            DebugLog.d(TAG, "Stored original mode: " + formatMode(originalMode));
        }
        
        Display.Mode bestMode = findBestMode(supportedModes, frameRate, width, height);
        if (bestMode == null) {
            DebugLog.d(TAG, "No suitable mode for " + width + "x" + height + "@" + frameRate);
            return;
        }
        
        Display.Mode currentMode = display.getMode();
        if (currentMode != null && currentMode.getModeId() == bestMode.getModeId()) {
            DebugLog.d(TAG, "Already in optimal mode: " + formatMode(bestMode));
            return;
        }
        
        DebugLog.d(TAG, "Switching to " + formatMode(bestMode) + " for video " + 
                width + "x" + height + "@" + frameRate);
        
        isWaitingForModeSettle = true;
        final Display.Mode targetMode = bestMode;
        
        // Unregister any existing listener
        unregisterDisplayListener();
        
        // Register display listener to detect when mode change completes
        if (displayManager != null) {
            displayListener = new DisplayManager.DisplayListener() {
                @Override
                public void onDisplayAdded(int displayId) {}
                
                @Override
                public void onDisplayRemoved(int displayId) {}
                
                @Override
                public void onDisplayChanged(int displayId) {
                    DebugLog.d(TAG, "Display changed callback received");
                    unregisterDisplayListener();
                    onModeChangeComplete(targetMode);
                }
            };
            displayManager.registerDisplayListener(displayListener, mainHandler);
        }
        
        try {
            WindowManager.LayoutParams params = window.getAttributes();
            params.preferredDisplayModeId = bestMode.getModeId();
            window.setAttributes(params);
            displayModeChanged = true;
            
            // Fallback: if listener doesn't fire, settle after delay
            mainHandler.postDelayed(() -> {
                if (isWaitingForModeSettle) {
                    DebugLog.d(TAG, "Mode change settle timeout, completing");
                    unregisterDisplayListener();
                    onModeChangeComplete(targetMode);
                }
            }, MODE_CHANGE_SETTLE_DELAY_MS * 2);
            
        } catch (Exception e) {
            DebugLog.e(TAG, "Failed to set display mode: " + e.getMessage());
            isWaitingForModeSettle = false;
        }
    }
    
    @RequiresApi(api = Build.VERSION_CODES.M)
    private void onModeChangeComplete(Display.Mode targetMode) {
        isWaitingForModeSettle = false;
        DebugLog.d(TAG, "Display mode change settled");

        // Show toast
        if (isActivityValid()) {
            String modeString = "Display Mode: " + formatMode(targetMode);
            Toast.makeText(activity, modeString, Toast.LENGTH_LONG).show();
        }

        // Invoke callback
        if (onModeChangeSettledCallback != null) {
            onModeChangeSettledCallback.run();
        }
    }
    
    private void unregisterDisplayListener() {
        if (displayListener != null && displayManager != null) {
            displayManager.unregisterDisplayListener(displayListener);
            displayListener = null;
        }
    }
    
    /**
     * Restores the original display mode.
     */
    public void restoreOriginalDisplayMode() {
        if (!displayModeChanged || originalMode == null) {
            return;
        }
        
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return;
        }
        
        if (!isActivityValid()) {
            DebugLog.d(TAG, "Activity invalid, clearing state without restore");
            displayModeChanged = false;
            return;
        }
        
        Window window = activity.getWindow();
        if (window == null) {
            displayModeChanged = false;
            return;
        }
        
        try {
            DebugLog.d(TAG, "Restoring original mode: " + formatMode(originalMode));
            WindowManager.LayoutParams params = window.getAttributes();
            params.preferredDisplayModeId = originalMode.getModeId();
            window.setAttributes(params);
        } catch (Exception e) {
            DebugLog.e(TAG, "Failed to restore display mode: " + e.getMessage());
        }
        
        displayModeChanged = false;
    }
    
    /**
     * Releases resources. Call when the view is destroyed.
     */
    public void release() {
        restoreOriginalDisplayMode();
        unregisterDisplayListener();
        originalMode = null;
        isWaitingForModeSettle = false;
        onModeChangeSettledCallback = null;
        mainHandler.removeCallbacksAndMessages(null);
    }
    
    private boolean isActivityValid() {
        return activity != null && !activity.isFinishing() && !activity.isDestroyed();
    }
    
    /**
     * Finds the best display mode for the given video parameters.
     * Priority: exact match > multiple match > closest match
     */
    @RequiresApi(api = Build.VERSION_CODES.M)
    @Nullable
    private Display.Mode findBestMode(Display.Mode[] modes, float videoFrameRate, int videoWidth, int videoHeight) {
        Display.Mode bestExact = null;
        Display.Mode bestMultiple = null;
        Display.Mode bestClose = null;
        float bestExactScore = Float.MAX_VALUE;
        float bestMultipleScore = Float.MAX_VALUE;
        float bestCloseDiff = Float.MAX_VALUE;
        
        for (Display.Mode mode : modes) {
            int modeWidth = mode.getPhysicalWidth();
            int modeHeight = mode.getPhysicalHeight();
            float modeRate = mode.getRefreshRate();
            
            // Skip modes with lower resolution
            if (modeWidth < videoWidth || modeHeight < videoHeight) {
                continue;
            }
            
            float resScore = (modeWidth - videoWidth) + (modeHeight - videoHeight);
            
            if (isFrameRateMatch(modeRate, videoFrameRate)) {
                if (bestExact == null || resScore < bestExactScore) {
                    bestExact = mode;
                    bestExactScore = resScore;
                }
            } else if (isFrameRateMultiple(modeRate, videoFrameRate)) {
                if (bestMultiple == null || resScore < bestMultipleScore) {
                    bestMultiple = mode;
                    bestMultipleScore = resScore;
                }
            } else {
                float diff = Math.abs(modeRate - videoFrameRate);
                if (diff < bestCloseDiff) {
                    bestClose = mode;
                    bestCloseDiff = diff;
                }
            }
        }
        
        if (bestExact != null) return bestExact;
        if (bestMultiple != null) return bestMultiple;
        return bestClose;
    }
    
    private boolean isFrameRateMatch(float displayRate, float videoRate) {
        return Math.abs(displayRate - videoRate) <= FRAME_RATE_TOLERANCE;
    }
    
    private boolean isFrameRateMultiple(float displayRate, float videoRate) {
        if (videoRate <= 0) return false;
        
        float ratio = displayRate / videoRate;
        float rounded = Math.round(ratio);
        
        return rounded >= 2 && Math.abs(ratio - rounded) < MULTIPLE_TOLERANCE;
    }
    
    @RequiresApi(api = Build.VERSION_CODES.M)
    private String formatMode(@Nullable Display.Mode mode) {
        if (mode == null) return "null";
        return mode.getPhysicalWidth() + "x" + mode.getPhysicalHeight() + "@" + mode.getRefreshRate() + "Hz";
    }
}
