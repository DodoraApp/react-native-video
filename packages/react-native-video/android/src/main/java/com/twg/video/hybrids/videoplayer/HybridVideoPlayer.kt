package com.margelo.nitro.video

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.MainThread
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Metadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.extractor.metadata.emsg.EventMessage
import androidx.media3.extractor.metadata.id3.Id3Frame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.ui.PlayerView
import com.facebook.proguard.annotations.DoNotStrip
import com.facebook.react.bridge.ReactApplicationContext
import com.margelo.nitro.NitroModules
import com.margelo.nitro.core.Promise
import com.twg.video.core.player.DisplayModeHelper
import com.twg.video.core.player.MediaInfo
import com.twg.video.core.player.MediaInfoLoader
import com.twg.video.core.player.RNVPlayerStatisticsListener
import com.twg.video.core.player.RNVVideoRenderersFactory
import java.util.concurrent.Executors
import com.twg.video.core.LibraryError
import com.twg.video.core.PlayerError
import com.twg.video.core.VideoManager
import com.twg.video.core.extensions.updateService
import com.twg.video.core.player.OnAudioFocusChangedListener
import com.twg.video.core.recivers.AudioBecomingNoisyReceiver
import com.twg.video.core.services.playback.VideoPlaybackService
import com.twg.video.core.services.playback.VideoPlaybackServiceConnection
import com.twg.video.core.utils.TextTrackUtils
import com.twg.video.core.utils.Threading.mainThreadProperty
import com.twg.video.core.utils.Threading.runOnMainThread
import com.twg.video.core.utils.Threading.runOnMainThreadSync
import com.twg.video.core.utils.VideoOrientationUtils
import com.twg.video.view.VideoView
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

internal fun Player.setPlaybackRate(rate: Double) {
  if (rate <= 0.0) {
    pause()
    return
  }

  playbackParameters = playbackParameters.withSpeed(rate.toFloat())
}

@UnstableApi
@DoNotStrip
class HybridVideoPlayer() : HybridVideoPlayerSpec(), AutoCloseable {
  override lateinit var source: HybridVideoPlayerSourceSpec
  override var eventEmitter = HybridVideoPlayerEventEmitter()
    set(value) {
      if (field != value) {
        audioFocusChangedListener.setEventEmitter(value)
        audioBecomingNoisyReceiver.setEventEmitter(value)
      }
      field = value
    }

  private var allocator: DefaultAllocator? = null
  private var context = NitroModules.applicationContext
    ?: run {
    throw LibraryError.ApplicationContextNotFound
  }

  var player: ExoPlayer = runOnMainThreadSync {
    // Build Temporary player that will be replaced when source is loaded
    return@runOnMainThreadSync ExoPlayer.Builder(context).build()
  }

  var loadedWithSource = false
  private val releaseStarted = AtomicBoolean(false)
  internal val isReleaseStarted: Boolean
    get() = releaseStarted.get()
  private var currentPlayerView: WeakReference<PlayerView>? = null

  var wasAutoPaused = false

  // Buffer Config
  private var bufferConfig: BufferConfig? = null
    get() = source.config.bufferConfig

  // Time updates
  private val progressHandler = Handler(Looper.getMainLooper())
  private var progressRunnable: Runnable? = null

  // Listeners
  private val audioFocusChangedListener = OnAudioFocusChangedListener()
  private val audioBecomingNoisyReceiver = AudioBecomingNoisyReceiver()

  // Service Connection
  private val videoPlaybackServiceConnection = VideoPlaybackServiceConnection(WeakReference(this), context)

  // Text track selection state
  private var selectedExternalTrackIndex: Int? = null

  // DodoStream fork: playback statistics and chapters/media-info extraction
  private var statisticsListener: RNVPlayerStatisticsListener? = null
  private val displayModeHelper = DisplayModeHelper()
  private var extractedMediaInfo: MediaInfo? = null
  private var mediaInfoLoadGeneration = 0
  private val mediaInfoExecutor = Executors.newSingleThreadExecutor()

  private companion object {
    const val PROGRESS_UPDATE_INTERVAL_MS = 250L
    private const val TAG = "HybridVideoPlayer"
    private const val DEFAULT_MIN_BUFFER_DURATION_MS = 5000
    private const val DEFAULT_MAX_BUFFER_DURATION_MS = 10000
    private const val DEFAULT_BUFFER_FOR_PLAYBACK_DURATION_MS = 1000
    private const val DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_DURATION_MS = 2000
    private const val DEFAULT_BACK_BUFFER_DURATION_MS = 0
  }

  override var status: VideoPlayerStatus = VideoPlayerStatus.IDLE
    set(value) {
      if (field != value) {
        eventEmitter.onStatusChange(value)
      }
      field = value
    }

  override var showNotificationControls: Boolean = false
    get() = runOnMainThreadSync { field }
    set(value) {
      runOnMainThreadSync {
        field = value
        VideoPlaybackService.updateService(videoPlaybackServiceConnection)
      }
    }

  // Player Properties
  override var currentTime: Double by mainThreadProperty(
    get = { player.currentPosition.toDouble() / 1000.0 },
    set = { value -> runOnMainThread { player.seekTo((value * 1000).toLong()) } }
  )

  // volume defined by user
  var userVolume: Double = 1.0

  override var volume: Double by mainThreadProperty(
    get = { player.volume.toDouble() },
    set = { value ->
      userVolume = value
      player.volume = value.toFloat()
    }
  )

  override val duration: Double by mainThreadProperty(
    get = {
      val duration = player.duration
      return@mainThreadProperty if (duration == C.TIME_UNSET) Double.NaN else duration.toDouble() / 1000.0
    }
  )

  override var loop: Boolean by mainThreadProperty(
    get = {
      player.repeatMode == Player.REPEAT_MODE_ONE
    },
    set = { value ->
      player.repeatMode = if (value) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    }
  )

  override var muted: Boolean by mainThreadProperty(
    get = {
      val playerVolume = player.volume.toDouble()
      return@mainThreadProperty playerVolume == 0.0
    },
    set = { value ->
      if (value) {
        userVolume = volume
        player.volume = 0f
      } else {
        player.volume = userVolume.toFloat()
      }
      eventEmitter.onVolumeChange(onVolumeChangeData(
        volume = player.volume.toDouble(),
        muted = muted
      ))
    }
  )

  override var rate: Double by mainThreadProperty(
    get = { player.playbackParameters.speed.toDouble() },
    set = { value ->
      player.setPlaybackRate(value)
    }
  )

  override var mixAudioMode: MixAudioMode = MixAudioMode.AUTO
    set(value) {
      VideoManager.audioFocusManager.requestAudioFocusUpdate()
      field = value
    }

  // iOS only property
  override var ignoreSilentSwitchMode: IgnoreSilentSwitchMode = IgnoreSilentSwitchMode.AUTO

  // iOS only property - no-op on Android
  override var disableAudioSessionManagement: Boolean = false

  override var playInBackground: Boolean = false
    get() = runOnMainThreadSync { field }
    set(value) {
      runOnMainThreadSync {
        field = value
        VideoPlaybackService.updateService(videoPlaybackServiceConnection)
      }
    }

  override var playWhenInactive: Boolean = false

  override var isPlaying: Boolean by mainThreadProperty(
    get = { player.isPlaying == true }
  )

  private fun initializePlayer() {
    if (NitroModules.applicationContext == null) {
      throw LibraryError.ApplicationContextNotFound
    }

    val hybridSource = source as? HybridVideoPlayerSource ?: throw PlayerError.InvalidSource
    val config = hybridSource.config

    // Initialize the allocator
    allocator = DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE)

    // Create a LoadControl with the allocator
    val loadControl = DefaultLoadControl.Builder()
      .setAllocator(allocator!!)
      .setBufferDurationsMs(
        bufferConfig?.minBufferMs?.toInt() ?: DEFAULT_MIN_BUFFER_DURATION_MS, // minBufferMs
        bufferConfig?.maxBufferMs?.toInt() ?: DEFAULT_MAX_BUFFER_DURATION_MS, // maxBufferMs
        bufferConfig?.bufferForPlaybackMs?.toInt()
          ?: DEFAULT_BUFFER_FOR_PLAYBACK_DURATION_MS, // bufferForPlaybackMs
        bufferConfig?.bufferForPlaybackAfterRebufferMs?.toInt()
          ?: DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_DURATION_MS // bufferForPlaybackAfterRebufferMs
      )
      .setBackBuffer(
        bufferConfig?.backBufferDurationMs?.toInt()
          ?: DEFAULT_BACK_BUFFER_DURATION_MS, // backBufferDurationMs,
        false // retainBackBufferFromKeyframe
      )
      .build()

    // Build the player with the LoadControl
    player = ExoPlayer.Builder(context)
      .setLoadControl(loadControl)
      .setLooper(Looper.getMainLooper())
      .setRenderersFactory(buildRenderersFactory(config))
      .setTrackSelector(buildTrackSelector(config))
      .build()

    loadedWithSource = true

    player.addListener(playerListener)
    player.addAnalyticsListener(analyticsListener)

    // Playback statistics (DodoStream fork)
    extractedMediaInfo = null
    mediaInfoLoadGeneration++
    val statsListener = RNVPlayerStatisticsListener(eventEmitter)
    statsListener.setEnabled(config.reportStatistics == true)
    statsListener.setStreamType(streamTypeFromUri(hybridSource.uri))
    statisticsListener = statsListener
    player.addAnalyticsListener(statsListener)

    // Automatic frame-rate matching (DodoStream fork)
    displayModeHelper.setEnabled(config.matchFrameRate == true)
    displayModeHelper.setActivity(currentActivity())

    player.setMediaSource(hybridSource.mediaSource)
    ensureNotReleased()

    // Emit onLoadStart
    val sourceType = if (hybridSource.uri.startsWith("http")) SourceType.NETWORK else SourceType.LOCAL
    val sourceData = VideoPlayerSourceBase(uri = hybridSource.uri, config = hybridSource.config) {
      Promise.async { hybridSource.getAssetInformationAsync() }
    }
    eventEmitter.onLoadStart(onLoadStartData(sourceType = sourceType, source = sourceData))
    ensureNotReleased()
    status = VideoPlayerStatus.LOADING
    ensureNotReleased()
    startProgressUpdates()
  }

  /**
   * Track selector: tunnelled playback and audio passthrough (DodoStream fork).
   */
  private fun buildTrackSelector(config: NativeVideoConfig): DefaultTrackSelector {
    return DefaultTrackSelector(context).apply {
      val parametersBuilder = buildUponParameters()
        .setTunnelingEnabled(config.tunneled == true)

      if (config.audioPassthrough == true) {
        // Configure preferred audio MIME types for passthrough formats
        parametersBuilder.setPreferredAudioMimeTypes(
          MimeTypes.AUDIO_TRUEHD,
          MimeTypes.AUDIO_DTS_HD,
          MimeTypes.AUDIO_DTS,
          MimeTypes.AUDIO_E_AC3,
          MimeTypes.AUDIO_AC3,
          MimeTypes.AUDIO_AC4
        )

        // Enable audio offload for true passthrough/bitstreaming to external devices
        parametersBuilder.setAudioOffloadPreferences(
          TrackSelectionParameters.AudioOffloadPreferences.Builder()
            .setAudioOffloadMode(
              TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED
            )
            .setIsGaplessSupportRequired(false)
            .setIsSpeedChangeSupportRequired(false)
            .build()
        )
      }

      parameters = parametersBuilder.build()
    }
  }

  /**
   * Renderers factory: FFmpeg software decoding (nextlib), DV-P7 workarounds,
   * software-decoding toggle (DodoStream fork).
   */
  private fun buildRenderersFactory(config: NativeVideoConfig): DefaultRenderersFactory {
    return RNVVideoRenderersFactory(
      context,
      config.enableWorkarounds == true,
      config.enableVideoSoftwareDecoding == true
    )
      .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
      .setEnableDecoderFallback(true)
      .forceEnableMediaCodecAsynchronousQueueing()
  }

  private fun ensureNotReleased() {
    if (releaseStarted.get()) {
      throw PlayerError.Cancelled
    }
  }

  override fun initialize(): Promise<Unit> {
    return Promise.async {
      return@async runOnMainThreadSync {
        ensureNotReleased()
        initializePlayer()
        player.prepare()
        ensureNotReleased()
      }
    }
  }

  constructor(source: HybridVideoPlayerSource) : this() {
    this.source = source

    runOnMainThreadSync {
      try {
        if (source.config.initializeOnCreation == true) {
          initializePlayer()
          player.prepare()
        }
        VideoManager.registerPlayer(this)
      } catch (_: PlayerError.Cancelled) {
        // Initialization was cancelled by release.
      }
    }
  }

  override fun play() {
    runOnMainThread {
      player.play()
    }
  }

  override fun pause() {
    runOnMainThread {
      player.pause()
    }
  }

  override fun seekBy(time: Double) {
    currentTime = (currentTime + time).coerceIn(0.0, duration)
  }

  override fun seekTo(time: Double) {
    currentTime = time.coerceIn(0.0, duration)
  }

  override fun replaceSourceAsync(source: Variant_NullType_HybridVideoPlayerSourceSpec?): Promise<Unit> {
    return Promise.async {
      val source = source?.asSecondOrNull()

      if (source == null) {
        release()
        return@async
      }

      runOnMainThreadSync {
        ensureNotReleased()
        val hybridSource = source as? HybridVideoPlayerSource ?: throw PlayerError.InvalidSource
        val oldSource = this.source as? HybridVideoPlayerSource
        oldSource?.sourceLoader?.cancel()

        this.source = source
        player.setMediaSource(hybridSource.mediaSource)
        ensureNotReleased()

        // DodoStream fork: stats/chapters are per-source
        extractedMediaInfo = null
        mediaInfoLoadGeneration++
        statisticsListener?.reset()
        statisticsListener?.setStreamType(streamTypeFromUri(hybridSource.uri))

        player.prepare()
        ensureNotReleased()
      }
    }
  }

  override fun preload(): Promise<Unit> {
    return Promise.async {
      runOnMainThreadSync {
        ensureNotReleased()
        if (!loadedWithSource) {
          initializePlayer()
        }

        if (player.playbackState != Player.STATE_IDLE) {
          return@runOnMainThreadSync
        }

        player.prepare()
        ensureNotReleased()
      }
    }
  }

  override fun release() {
    if (!releaseStarted.compareAndSet(false, true)) {
      return
    }

    // Defer teardown until the current main-thread callback chain has finished.
    progressHandler.post { completeRelease() }
  }

  @MainThread
  private fun completeRelease() {
    VideoPlaybackService.updateService(videoPlaybackServiceConnection)

    try {
      VideoManager.unregisterPlayer(this)
    } finally {
      stopProgressUpdates()
      loadedWithSource = false

      eventEmitter.clearAllListeners()

      player.removeListener(playerListener)
      player.removeAnalyticsListener(analyticsListener)
      player.release() // Release player

      // DodoStream fork: invalidate in-flight media info loads and restore display mode
      mediaInfoLoadGeneration++
      statisticsListener?.let { player.removeAnalyticsListener(it) }
      statisticsListener = null
      extractedMediaInfo = null
      displayModeHelper.release()
      mediaInfoExecutor.shutdown()

      // Clean Listeners
      audioFocusChangedListener.removeEventEmitter()
      audioBecomingNoisyReceiver.removeEventEmitter()

      // Update status
      status = VideoPlayerStatus.IDLE
    }
  }

  fun movePlayerToVideoView(videoView: VideoView) {
    VideoManager.addViewToPlayer(videoView, this)

    runOnMainThreadSync {
      PlayerView.switchTargetView(player, currentPlayerView?.get(), videoView.playerView)
      currentPlayerView = WeakReference(videoView.playerView)
    }
  }

  override fun dispose() {
    release()
  }

  override fun close() {
    release()
  }

  override val memorySize: Long
    // 1 MiB by default
    get() = allocator?.totalBytesAllocated?.toLong() ?: (1024L * 1024L)

  private fun startProgressUpdates() {
    stopProgressUpdates() // Ensure no multiple runnables
    progressRunnable = object : Runnable {
      override fun run() {
        if (player.playbackState != Player.STATE_IDLE && player.playbackState != Player.STATE_ENDED) {
          val currentTimeSeconds = player.currentPosition / 1000.0
          val bufferedDurationSeconds = player.bufferedPosition / 1000.0
          // bufferDuration is the time from current time that is buffered.
          val playableDurationFromNow = max(0.0, bufferedDurationSeconds - currentTimeSeconds)

          eventEmitter.onProgress(
            onProgressData(
              currentTime = currentTimeSeconds,
              bufferDuration = playableDurationFromNow
            )
          )
          progressHandler.postDelayed(this, PROGRESS_UPDATE_INTERVAL_MS)
        }
      }
    }
    progressHandler.post(progressRunnable ?: return)
  }

  private fun stopProgressUpdates() {
    progressRunnable?.let { progressHandler.removeCallbacks(it) }
    progressRunnable = null
  }

  private val analyticsListener = object: AnalyticsListener {
    override fun onBandwidthEstimate(
      eventTime: AnalyticsListener.EventTime,
      totalLoadTimeMs: Int,
      totalBytesLoaded: Long,
      bitrateEstimate: Long
    ) {
      val videoFormat = player.videoFormat
      eventEmitter.onBandwidthUpdate(
        BandwidthData(
          bitrate = bitrateEstimate.toDouble(),
          width = if (videoFormat != null) videoFormat.width.toDouble() else null,
          height = if (videoFormat != null) videoFormat.height.toDouble() else null
        )
      )
    }

    override fun onVideoInputFormatChanged(
      eventTime: AnalyticsListener.EventTime,
      format: Format,
      decoderReuseEvaluation: DecoderReuseEvaluation?
    ) {
      // Called when the decoder starts receiving video data and the format is fully known.
      // Single entry point for chapters/media-info extraction.
      loadMediaInfoAndApplyDisplayMode(format)
    }
  }

  // MARK: - Media info extraction (chapters) + display mode (AFR)

  /**
   * Loads media info (if needed) and applies the appropriate display mode.
   * This is the single entry point for display mode changes.
   *
   * @param format The video format from ExoPlayer (may have incomplete framerate info)
   */
  private fun loadMediaInfoAndApplyDisplayMode(format: Format?) {
    // Start async media info loading if needed
    if (shouldLoadMediaInfo()) {
      startMediaInfoLoading(format)
      return
    }

    // Apply display mode synchronously if media info already available
    applyDisplayModeSync(format)
  }

  private fun shouldLoadMediaInfo(): Boolean {
    return extractedMediaInfo == null && MediaInfoLoader.isExtractionSupported(source.uri)
  }

  private fun startMediaInfoLoading(format: Format?) {
    val uri = source.uri
    Log.d(TAG, "Loading media info for chapters and metadata extraction")
    val generation = ++mediaInfoLoadGeneration
    mediaInfoExecutor.execute {
      val mediaInfo = MediaInfoLoader.loadMediaInfo(uri)
      runOnMainThread {
        if (generation == mediaInfoLoadGeneration && !releaseStarted.get()) {
          handleMediaInfoLoaded(mediaInfo, format)
        }
      }
    }
  }

  private fun handleMediaInfoLoaded(mediaInfo: MediaInfo?, format: Format?) {
    if (mediaInfo != null) {
      extractedMediaInfo = mediaInfo

      statisticsListener?.setExtractedMediaInfo(mediaInfo)

      emitChaptersIfAvailable(mediaInfo)
      applyDisplayModeFromMediaInfo(mediaInfo, format)
    } else {
      Log.w(TAG, "Failed to load media info")
      applyDisplayModeFromFormat(format, "MediaInfo loading failed")
    }
  }

  private fun applyDisplayModeFromMediaInfo(mediaInfo: MediaInfo, format: Format?) {
    if (source.config.matchFrameRate != true) {
      return
    }

    if (mediaInfo.frameRate > 0) {
      val width = getWidthFromFormatOrMediaInfo(format, mediaInfo)
      val height = getHeightFromFormatOrMediaInfo(format, mediaInfo)

      Log.d(
        TAG,
        "Applying display mode from MediaInfo: " + width + "x" + height + "@" + mediaInfo.frameRate + "fps"
      )
      displayModeHelper.setDisplayMode(mediaInfo.frameRate, width, height)
    } else {
      // MediaInfo has no framerate, fallback to format
      applyDisplayModeFromFormat(format, "MediaInfo has no framerate")
    }
  }

  private fun applyDisplayModeFromFormat(format: Format?, reason: String) {
    if (source.config.matchFrameRate != true || !isFormatValid(format)) {
      return
    }

    Log.d(TAG, reason + ", using ExoPlayer format for display mode: " + format!!.width + "x" + format.height + "@" + format.frameRate + "fps")
    displayModeHelper.setDisplayMode(format.frameRate, format.width, format.height)
  }

  private fun applyDisplayModeSync(format: Format?) {
    if (source.config.matchFrameRate != true) {
      Log.d(TAG, "Frame-rate matching is disabled")
      return
    }

    val mediaInfo = extractedMediaInfo
    if (mediaInfo != null && mediaInfo.frameRate > 0) {
      val width = getWidthFromFormatOrMediaInfo(format, mediaInfo)
      val height = getHeightFromFormatOrMediaInfo(format, mediaInfo)

      Log.d(TAG, "Using extracted MediaInfo for display mode: " + width + "x" + height + "@" + mediaInfo.frameRate + "fps")
      displayModeHelper.setDisplayMode(mediaInfo.frameRate, width, height)
    } else if (isFormatValid(format)) {
      Log.d(TAG, "Using ExoPlayer format as fallback for display mode: " + format!!.width + "x" + format.height + "@" + format.frameRate + "fps")
      displayModeHelper.setDisplayMode(format.frameRate, format.width, format.height)
    }
  }

  private fun isFormatValid(format: Format?): Boolean {
    return format != null && format.frameRate > 0 && format.width > 0 && format.height > 0
  }

  private fun getWidthFromFormatOrMediaInfo(format: Format?, mediaInfo: MediaInfo): Int {
    return if (format != null && format.width > 0) format.width else mediaInfo.videoWidth.toInt()
  }

  private fun getHeightFromFormatOrMediaInfo(format: Format?, mediaInfo: MediaInfo): Int {
    return if (format != null && format.height > 0) format.height else mediaInfo.videoHeight.toInt()
  }

  private fun currentActivity(): Activity? {
    return (context as? ReactApplicationContext)?.currentActivity
  }

  private fun emitChaptersIfAvailable(mediaInfo: MediaInfo) {
    if (mediaInfo.chapters.isEmpty()) {
      return
    }
    Log.d(TAG, "Emitting chapters event with ${mediaInfo.chapters.size} chapters")
    val chapters = mediaInfo.chapters.map { chapter ->
      Chapter(
        title = chapter.title,
        startTime = chapter.startMs / 1000.0,
        endTime = chapter.endMs / 1000.0,
        type = ChapterType.valueOf(chapter.type.name)
      )
    }.toTypedArray()
    eventEmitter.onChapters(onChaptersData(chapters = chapters))
  }

  private fun streamTypeFromUri(uri: String): String {
    val type = Util.inferContentType(uri.toUri())
    return when (type) {
      C.CONTENT_TYPE_DASH -> "DASH"
      C.CONTENT_TYPE_HLS -> "HLS"
      C.CONTENT_TYPE_SS -> "SmoothStreaming"
      C.CONTENT_TYPE_RTSP -> "RTSP"
      C.CONTENT_TYPE_OTHER -> "Progressive"
      else -> "Unknown"
    }
  }

  private val playerListener = object : Player.Listener {
    override fun onPlaybackStateChanged(playbackState: Int) {
      val isPlayingUpdate = player.isPlaying
      val isBufferingUpdate = playbackState == Player.STATE_BUFFERING

      eventEmitter.onPlaybackStateChange(
        onPlaybackStateChangeData(
          isPlaying = isPlayingUpdate,
          isBuffering = isBufferingUpdate
        )
      )

      when (playbackState) {
        Player.STATE_IDLE -> {
          status = VideoPlayerStatus.IDLE
          eventEmitter.onBuffer(false)
        }
        Player.STATE_BUFFERING -> {
          status = VideoPlayerStatus.LOADING
          eventEmitter.onBuffer(true)
        }
        Player.STATE_READY -> {
          status = VideoPlayerStatus.READYTOPLAY
          eventEmitter.onBuffer(false)

          val generalVideoFormat = player.videoFormat
          val currentTracks = player.currentTracks

          val selectedVideoTrackGroup = currentTracks.groups.find { group -> group.type == C.TRACK_TYPE_VIDEO && group.isSelected }
          val selectedVideoTrackFormat = if (selectedVideoTrackGroup != null && selectedVideoTrackGroup.length > 0) {
            selectedVideoTrackGroup.getTrackFormat(0)
          } else {
            null
          }

          val width = selectedVideoTrackFormat?.width ?: generalVideoFormat?.width ?: 0
          val height = selectedVideoTrackFormat?.height ?: generalVideoFormat?.height ?: 0
          val rotationDegrees = selectedVideoTrackFormat?.rotationDegrees ?: generalVideoFormat?.rotationDegrees

          eventEmitter.onLoad(
            onLoadData(
              currentTime = player.currentPosition / 1000.0,
              duration = if (player.duration == C.TIME_UNSET) Double.NaN else player.duration / 1000.0,
              width = width.toDouble(),
              height = height.toDouble(),
              orientation = VideoOrientationUtils.fromWHR(width, height, rotationDegrees)
            )
          )
          // If player becomes ready and is set to play, start progress updates
          if (player.playWhenReady) {
            startProgressUpdates()
          }

          eventEmitter.onReadyToDisplay()
        }
        Player.STATE_ENDED -> {
          status = VideoPlayerStatus.IDLE // Or a specific 'COMPLETED' status if you add one
          eventEmitter.onEnd()
          eventEmitter.onBuffer(false)
          stopProgressUpdates()
        }
      }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
      super.onIsPlayingChanged(isPlaying)
      eventEmitter.onPlaybackStateChange(
        onPlaybackStateChangeData(
          isPlaying = isPlaying,
          isBuffering = player.playbackState == Player.STATE_BUFFERING
        )
      )
      if (isPlaying) {
        VideoManager.setLastPlayedPlayer(this@HybridVideoPlayer)
        startProgressUpdates()
      } else {
        if (player.playbackState == Player.STATE_ENDED || player.playbackState == Player.STATE_IDLE) {
          stopProgressUpdates()
        }
      }
      // Keep the activity's auto-enter-PiP flag in sync with the last-played video.
      VideoManager.refreshPictureInPictureParams()
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
      super.onPlayWhenReadyChanged(playWhenReady, reason)

      // A resume cancels a pending auto-pause, so the next foreground won't force-resume it.
      if (playWhenReady) {
        this@HybridVideoPlayer.wasAutoPaused = false
      }

      // playWhenReady can change without isPlaying (pause while buffering), so refresh here too.
      VideoManager.refreshPictureInPictureParams()
    }

    override fun onPlayerError(error: PlaybackException) {
      status = VideoPlayerStatus.ERROR
      stopProgressUpdates()
    }

    override fun onPositionDiscontinuity(
      oldPosition: Player.PositionInfo,
      newPosition: Player.PositionInfo,
      reason: Int
    ) {
      if (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) {
        eventEmitter.onSeek(newPosition.positionMs / 1000.0)
      }
      // Update progress immediately after a discontinuity if needed by your logic
       val currentTimeSeconds = newPosition.positionMs / 1000.0
       val bufferedDurationSeconds = player.bufferedPosition / 1000.0
       eventEmitter.onProgress(
         onProgressData(
           currentTime = currentTimeSeconds,
           bufferDuration = max(0.0, bufferedDurationSeconds - currentTimeSeconds)
         )
       )
    }

    override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
      eventEmitter.onPlaybackRateChange(playbackParameters.speed.toDouble())
    }

    override fun onVolumeChanged(volume: Float) {
      // We get here device volume changes, and if
      // player is not muted we will sync it
      if (!muted) {
        this@HybridVideoPlayer.volume = volume.toDouble()
      }

      VideoManager.audioFocusManager.requestAudioFocusUpdate()
      eventEmitter.onVolumeChange(onVolumeChangeData(
        volume = volume.toDouble(),
        muted = muted
      ))
    }

    override fun onCues(cueGroup: CueGroup) {
      val texts = cueGroup.cues.mapNotNull { it.text?.toString() }
      if (texts.isNotEmpty()) {
        eventEmitter.onTextTrackDataChanged(texts.toTypedArray())
      }
    }

    override fun onMetadata(metadata: Metadata) {
      val timedMetadataObjects = mutableListOf<TimedMetadataObject>()
      for (i in 0 until metadata.length()) {
        val entry = metadata.get(i)

        when (entry) {
          is Id3Frame -> {
            var value = ""

            if (entry is TextInformationFrame) {
              value = entry.values.first()
            }

            timedMetadataObjects.add(TimedMetadataObject(entry.id, value))
          }
          is EventMessage ->
            timedMetadataObjects.add(TimedMetadataObject(entry.schemeIdUri, entry.value))
          else -> Log.d(TAG, "Unknown metadata: $entry")
        }
      }
      if (timedMetadataObjects.isNotEmpty()) {
        eventEmitter.onTimedMetadata(TimedMetadata(metadata = timedMetadataObjects.toTypedArray()))
      }
    }

    override fun onTracksChanged(tracks: Tracks) {
      super.onTracksChanged(tracks)
    }
  }

  // MARK: - Text Track Management

  override fun getAvailableTextTracks(): Array<TextTrack> {
    return TextTrackUtils.getAvailableTextTracks(player, source)
  }

  override fun selectTextTrack(textTrack: Variant_NullType_TextTrack?) {
    selectedExternalTrackIndex = TextTrackUtils.selectTextTrack(
      player = player,
      textTrack = textTrack?.asSecondOrNull(),
      source = source,
      onTrackChange = { track -> eventEmitter.onTrackChange(track) }
    )
  }

  override val selectedTrack: TextTrack?
    get() = TextTrackUtils.getSelectedTrack(player, source)
}
