import type { BufferConfig } from './BufferConfig';
import type { DrmParams } from './DrmParams';

export type VideoSource = number | string;

export type VideoConfig = {
  /**
   * The uri of the video.
   * @example
   * ```ts
   * uri: 'https://example.com/video.mp4'
   * // or
   * uri: require('./assets/video.mp4')
   * ```
   */
  uri: VideoSource;
  /**
   * The headers to be sent with the request.
   */
  headers?: Record<string, string>;
  /**
   * The DRM parameters to be used.
   */
  drm?: DrmParams;
  /**
   * The player buffer configuration.
   */
  bufferConfig?: BufferConfig;
  /**
   * The custom metadata to be associated with the video.
   * This metadata can be used by the native player to show information about the video.
   */
  metadata?: CustomVideoMetadata;
  /**
   * The external subtitles to be used.
   * @note on iOS, only WebVTT (.vtt) subtitles are supported (for HLS streams and MP4 files).
   * @note on iOS, `label` can be overridden by player and there is no way to get around it.
   * @example
   * ```ts
   * externalSubtitles: [
   *   {
   *     uri: 'https://example.com/subtitles_en.vtt',
   *     label: 'English',
   *     type: 'vtt',
   *     language: 'en'
   *   },
   *   {
   *     uri: 'https://example.com/subtitles_es.vtt',
   *     label: 'Español',
   *     type: 'vtt',
   *     language: 'es'
   *   }
   * ]
   * ```
   */
  externalSubtitles?: ExternalSubtitle[];
  /**
   * when the player is created, this flag will determine if native player should be initialized immediately.
   * If set to true, the player will be initialized as soon as player is created
   * If set to false, the player will need be initialized manually later
   * @default true
   */
  initializeOnCreation?: boolean;

  // -------- Fork-specific Android options (DodoStream) --------

  /**
   * Enables ExoPlayer tunnelled (secure hardware) playback.
   * @default false
   * @platform android
   */
  tunneled?: boolean;
  /**
   * Enables audio passthrough (bitstreaming) for compressed audio formats
   * (Dolby TrueHD, DTS-HD, DTS, E-AC3, AC3, AC4) and audio offload.
   * Requires compatible audio hardware (HDMI receiver, soundbar, etc.).
   * @default false
   * @platform android
   */
  audioPassthrough?: boolean;
  /**
   * Enables workarounds for Dolby Vision profile 7 content on devices without
   * a DV profile 7 decoder (falls back to HEVC decoding).
   * @default false
   * @platform android
   */
  enableWorkarounds?: boolean;
  /**
   * Enables FFmpeg software video decoding (nextlib). Hardware decoding is
   * always preferred when available; when disabled the FFmpeg video renderer
   * is removed entirely.
   * @default false
   * @platform android
   */
  enableVideoSoftwareDecoding?: boolean;
  /**
   * Reports playback statistics through the `onVideoStatistics` event
   * (codecs, resolution, frame rate, bitrate, decoder names, …).
   * @default false
   * @platform android
   */
  reportStatistics?: boolean;
  /**
   * Matches the device display refresh rate to the video frame rate during
   * playback (automatic frame-rate switching, API 23+).
   * @default false
   * @platform android
   */
  matchFrameRate?: boolean;
  /**
   * Enables ExoPlayer dynamic scheduling with the video renderer reporting
   * its duration-to-progress, so the player only wakes the CPU when frame
   * progress can actually be made. Reduces power consumption and CPU idle
   * time, which is especially beneficial on low-end devices.
   *
   * Wraps the experimental media3 APIs
   * `ExoPlayer.Builder.experimentalSetDynamicSchedulingEnabled` and
   * `DefaultRenderersFactory.setEnableMediaCodecVideoRendererDurationToProgressUs`
   * (requires asynchronous codec queueing, which this library always enables).
   *
   * @default false
   * @platform android
   */
  enableDynamicScheduling?: boolean;
};

// @internal
export interface NativeVideoConfig extends VideoConfig {
  // The uri should be resolved to string before creating the source
  uri: string;
  externalSubtitles?: NativeExternalSubtitle[];
  drm?: NativeDrmParams;
}

/**
 * The type of the subtitle.
 * - `vtt` - WebVTT
 * - `srt` - SubRip
 * - `ssa` - SubStation Alpha
 * - `ass` - Advanced SubStation Alpha
 * - `auto` - Auto detect the subtitle type from the file extension
 *
 * @note `auto` is not available when uri have no extension.
 */
export type SubtitleType = 'vtt' | 'srt' | 'ssa' | 'ass' | 'auto';

interface ExternalSubtitleWithInferredType {
  /**
   * The uri of the subtitle.
   * @note the uri must end with the subtitle type.
   */
  uri: `${string}.${SubtitleType}`;
  /**
   * The label for the subtitle.
   */
  label: string;
  /**
   * The type of the subtitle.
   */
  type?: SubtitleType;
  /**
   * The language code for the subtitle (ISO 639-1 or ISO 639-2).
   * @example 'en', 'es', 'fr', 'de', 'zh-CN'
   * @default 'und' (undefined)
   */
  language?: string;
}

interface ExternalSubtitleWithCustomType {
  /**
   * The uri of the subtitle.
   */
  uri: string;
  /**
   * The label for the subtitle.
   */
  label: string;
  /**
   * The type of the subtitle.
   */
  type: Omit<SubtitleType, 'auto'>;
  /**
   * The language code for the subtitle (ISO 639-1 or ISO 639-2).
   * @example 'en', 'es', 'fr', 'de', 'zh-CN'
   * @default 'und' (undefined)
   */
  language?: string;
}

export type ExternalSubtitle =
  | ExternalSubtitleWithInferredType
  | ExternalSubtitleWithCustomType;

interface NativeExternalSubtitle {
  uri: string;
  label: string;
  type: SubtitleType;
  language: string;
}

interface NativeDrmParams extends DrmParams {
  type?: string;
}

export interface CustomVideoMetadata {
  title?: string;
  subtitle?: string;
  description?: string;
  artist?: string;
  imageUri?: string;
}
