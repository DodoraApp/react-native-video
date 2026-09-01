package com.twg.video.core.player

import android.app.ActivityManager
import android.content.Context
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.upstream.DefaultAllocator
import com.margelo.nitro.video.BufferConfig

/**
 * Memory-aware [DefaultLoadControl].
 *
 * <p>Ported from the DodoStream fork (v6). The app may configure a heap cap
 * (`maxHeapAllocationPercent`); the cap is only meaningful under a
 * memory-dependent buffering strategy, so when a cap is set the load control
 * defaults to it. Loading pauses once the heap cap is reached, or when the
 * configured memory reserve would be violated while enough media is buffered.
 */
class RNVLoadControl(
  allocator: DefaultAllocator,
  private val config: BufferConfig?,
  private val context: Context,
) : DefaultLoadControl(
    allocator,
    config?.minBufferMs?.toInt() ?: DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
    config?.maxBufferMs?.toInt() ?: DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
    config?.bufferForPlaybackMs?.toInt()
      ?: DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
    config?.bufferForPlaybackAfterRebufferMs?.toInt()
      ?: DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
    /* targetBufferBytes = */ -1,
    /* prioritizeTimeOverSizeThresholds = */ true,
    config?.backBufferDurationMs?.toInt() ?: DefaultLoadControl.DEFAULT_BACK_BUFFER_DURATION_MS,
    DefaultLoadControl.DEFAULT_RETAIN_BACK_BUFFER_FROM_KEYFRAME,
  ) {

  private val runtime = Runtime.getRuntime()
  private val availableHeapInBytes: Int

  init {
    val activityManager =
      context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    val maxHeap = config?.maxHeapAllocationPercent
      ?: DEFAULT_MAX_HEAP_ALLOCATION_PERCENT
    availableHeapInBytes =
      ((activityManager?.memoryClass ?: 0) * maxHeap * 1024 * 1024).toInt()
  }

  override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean {
    val bufferedMs = parameters.bufferedDurationUs / 1000
    // A configured heap cap (maxHeapAllocationPercent < 1) is only meaningful
    // under DependingOnMemory, so default to it when a cap is set.
    val dependsOnMemory = availableHeapInBytes > 0
    if (dependsOnMemory) {
      // The goal of this algorithm is to pause video loading (increasing the buffer)
      // when available memory on device become low.
      val loadedBytes = getAllocator().totalBytesAllocated
      val isHeapReached = loadedBytes >= availableHeapInBytes
      if (isHeapReached) {
        return false
      }
      val usedMemory = runtime.totalMemory() - runtime.freeMemory()
      val freeMemory = runtime.maxMemory() - usedMemory
      val minBufferMemoryReservePercent = config?.minBufferMemoryReservePercent
        ?: DEFAULT_MIN_BUFFER_MEMORY_RESERVE
      val reserveMemory = (minBufferMemoryReservePercent * runtime.maxMemory()).toLong()
      if (reserveMemory > freeMemory && bufferedMs > 2000) {
        // We don't have enough memory in reserve so we stop buffering to allow
        // other components to use it instead.
        return false
      }
    }
    return super.shouldContinueLoading(parameters)
  }

  private companion object {
    const val DEFAULT_MAX_HEAP_ALLOCATION_PERCENT = 1.0
    const val DEFAULT_MIN_BUFFER_MEMORY_RESERVE = 0.0
  }
}
