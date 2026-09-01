# DodoStream Fork — v6 → v7 Migration Guide

This guide covers everything that changed when the fork moved from
**v6.19.2 + DodoStream customizations** to **v7.0.0-beta.11 + ported
customizations**: the API rewrite, the fork-specific options/events, the
toolchain requirements, and what to verify on devices before shipping.

---

## 1. Summary of changes

| Area | Before (v6 fork) | After (v7 fork, `master`) |
|---|---|---|
| API model | `<Video>` component, imperative props/ref | `useVideoPlayer(source)` + `<VideoView>` player model |
| Fork playback options | 6 component props | 7 source-config options (same names + `enableDynamicScheduling`) |
| Fork events | `onVideoStatistics`, `onChapters` props | same names via `useEvent(player, …)` |
| DRM | built-in `drm` prop | `@react-native-video/drm` plugin + `source.drm` |
| Android media stack | media3 1.8.0, nextlib 1.8.0-0.9.0 | **media3 1.10.1, nextlib 1.10.1-0.13.0** |
| Kotlin / RN / SDK | Kotlin 1.9.24, RN ≥ 0.75, compileSdk 35 | **Kotlin 2.1.20, RN ≥ 0.80, compileSdk 36** |
| Git | custom work on `scratched-megaraptor` | ported on `master`; `support/6.x.x` and `scratched-megaraptor` untouched |

---

## 2. Requirements — do this first

The Android dependency bump forced a toolchain floor:

| Requirement | Minimum | Why |
|---|---|---|
| **React Native** | **0.80** | nextlib 0.13 ships Kotlin 2.2 metadata; RN's gradle plugin must provide Kotlin ≥ 2.1. RN 0.79 still pins Kotlin 2.0.21 and cannot consume it |
| **Kotlin** (`kotlinVersion` in your root `build.gradle`) | **2.1.20** | reads nextlib 0.13 metadata (2.2.0); RN ≥ 0.80 already defaults to 2.1.20 |
| **compileSdk / targetSdk** | **36** | media3 1.10.1 requires API 36 |
| **Gradle wrapper** | **8.11.1** (8.13 tested) | RN 0.80's AGP |
| iOS | — | run `pod install` after upgrading; no Swift API changes for the fork options (Android-only) |

**If you stay on RN < 0.80**, the module will not compile with nextlib 0.13
(metadata error). You can stay on RN 0.77 only by pinning nextlib back to
1.8.0-0.9.0 + media3 1.8.0 — not recommended; it is the state before this
migration.

---

## 3. API migration table

### 3.1 Fork-specific options

| v6 prop | v7 source config | Notes |
|---|---|---|
| `tunneled` | `tunneled` | tunnelled (secure) playback |
| `audioPassthrough` | `audioPassthrough` | TrueHD/DTS-HD/DTS/E-AC3/AC3/AC4 passthrough + audio offload |
| `enableWorkarounds` | `enableWorkarounds` | DV Profile 7 → HEVC fallback (still required, see §6) |
| `enableVideoSoftwareDecoding` | `enableVideoSoftwareDecoding` | FFmpeg software video decode (nextlib) |
| `reportStatistics` | `reportStatistics` | enables `onVideoStatistics` |
| `matchFrameRate` | `matchFrameRate` | AFR: display refresh-rate matching |
| — | `enableDynamicScheduling` **(new)** | media3 1.10 dynamic scheduling; lower CPU/power on low-end devices |
| `bufferConfig.maxHeapAllocationPercent` | `bufferConfig.maxHeapAllocationPercent` | heap cap; memory-dependent buffering when set |
| `bufferConfig.minBufferMemoryReservePercent` | `bufferConfig.minBufferMemoryReservePercent` | memory reserve; default 0 |
| `bufferingStrategy` prop | — | **gone**; memory strategy is implied by `maxHeapAllocationPercent` |

### 3.2 Standard v6 → v7 API (upstream)

| v6 | v7 |
|---|---|
| `<Video source={s} />` | `const player = useVideoPlayer(s)` + `<VideoView player={player} />` |
| `paused` prop | `player.pause()` / `player.play()` |
| `muted` / `volume` / `rate` / `repeat` | `player.muted` / `player.volume` / `player.rate` / `player.loop` |
| `resizeMode`, `controls` | same — `VideoView` props |
| `ref.seek(t)` | `player.seekTo(t)` (absolute) / `player.seekBy(t)` (relative) |
| `ref.pause()` / `resume()` | `player.pause()` / `player.play()` |
| `ref.setSource(s)` | `player.replaceSourceAsync(s)` |
| `ref.setFullScreen(true)` | `videoViewRef.enterFullscreen()` |
| `ref.enterPictureInPicture()` | `videoViewRef.enterPictureInPicture()` |
| `onLoad`/`onProgress`/… props | `useEvent(player, 'onLoad'/'onProgress', cb)` |
| `onPlaybackStateChanged` | `onPlaybackStateChange` (renamed) |
| `onReadyForDisplay` | `onReadyToDisplay` (renamed) |
| `drm` prop | `@react-native-video/drm` plugin; `source.drm` (field renames: `licenseServer` → `licenseUrl`, `multiDrm` → `multiSession`, `type` enum → string) |

---

## 4. Migration example

### v6 (before)

```tsx
import Video from 'react-native-video';

<Video
  ref={ref}
  source={{ uri: 'https://example.com/master.m3u8' }}
  tunneled
  audioPassthrough
  enableWorkarounds
  enableVideoSoftwareDecoding
  reportStatistics
  matchFrameRate
  bufferConfig={{
    maxHeapAllocationPercent: 0.5,
    minBufferMemoryReservePercent: 0.1,
  }}
  onVideoStatistics={(stats) => console.log('stats', stats)}
  onChapters={({ chapters }) => console.log('chapters', chapters)}
/>;
```

### v7 (after)

```tsx
import {
  useVideoPlayer,
  useEvent,
  VideoView,
} from 'react-native-video';

function Player() {
  const player = useVideoPlayer({
    uri: 'https://example.com/master.m3u8',
    tunneled: true,
    audioPassthrough: true,
    enableWorkarounds: true,
    enableVideoSoftwareDecoding: true,
    reportStatistics: true,
    matchFrameRate: true,
    enableDynamicScheduling: true, // new, optional
    bufferConfig: {
      maxHeapAllocationPercent: 0.5,
      minBufferMemoryReservePercent: 0.1,
    },
  });

  useEvent(player, 'onVideoStatistics', (stats) =>
    console.log('stats', stats)
  );
  useEvent(player, 'onChapters', ({ chapters }) =>
    console.log('chapters', chapters)
  );

  return (
    <VideoView
      player={player}
      controls
      resizeMode="contain"
      style={{ width: '100%', aspectRatio: 16 / 9 }}
    />
  );
}
```

---

## 5. Event payloads

### `onVideoStatistics` (Android, only when `reportStatistics`)

Debounced (~350 ms) and emitted only when the underlying state changes.
All fields optional strings:

```ts
type VideoStatisticsData = {
  streamType?: string;          // 'HLS' | 'DASH' | 'Progressive' | …
  container?: string;           // 'MP4 (video/mp4)', …
  videoCodecName?: string;      // 'HEVC (H.265)', …
  audioCodecName?: string;
  resolution?: string;          // '1920×1080'
  frameRate?: string;           // '29.97 fps'
  bitrate?: string;             // 'Video 4.20 Mbps, Audio 192 kbps'
  profileLevel?: string;        // 'HDR10 (PQ), Main 10@Main Tier 5.1'
  decodedVideoFormat?: string;
  decodedAudioFormat?: string;
  decodedAudioChannels?: string; // '6 (5.1)'
  audioLayout?: string;         // '5.1'
  videoDecoder?: string;        // 'c2.android.hevc.decoder'
  audioDecoder?: string;
};
```

### `onChapters` (Android)

Emitted once per source when FFmpeg-based extraction finds chapters.
`type` is a string union, not an enum — compare against string literals:

```ts
type ChapterType = 'RECAP' | 'PREVIEW' | 'INTRO' | 'CREDITS' | 'UNKNOWN';

type Chapter = {
  title: string;
  startTime: number; // seconds
  endTime: number;   // seconds
  type: ChapterType;
};

type onChaptersData = { chapters: Chapter[] };
```

If your v6 code used `ChapterType.RECAP` (enum), change it to
`chapter.type === 'RECAP'` or import the type and use the literal.

---

## 6. Behavior notes — what stayed, what changed

- **DV Profile 7 workaround: keep `enableWorkarounds`.** media3 1.10.1
  intentionally does NOT fall back to HEVC for `DvheDtb` (profile 7) — the
  upstream comment calls it "deprecated and not always backward compatible".
  The fork's renderer is still the only path for DV-P7 on devices without a
  DV-P7 decoder.
- **Fullscreen controls:** v6's "controls always visible in fullscreen" fix
  is native behavior in v7 (`FullscreenVideoFragment` force-enables the
  controller and restores it on exit). Nothing to configure.
- **memory/PGS fixes** ported as-is: heap-cap load control, raw PGS subtitle
  samples with legacy decoding.
- **media3 1.8 → 1.10** brings decoder-behavior changes — expect device-level
  differences; A/B against the v6 build before production.
- **nextlib FFmpeg is unchanged** between 0.9.0 and 0.13.0 (byte-identical
  `libavcodec.so`); the upgrade's value is the media3 pairing, not FFmpeg.
- **AFR** (`matchFrameRate`) behaves as before: exact refresh-rate match, then
  multiple, then closest mode; original display mode restored on release.
- **DRM** (if used): migrate to `@react-native-video/drm` — `enable()` at
  startup, `source.drm` with the renamed fields from §3.2.

---

## 7. Verification checklist (device QA)

- [ ] Tunnelled playback on secure streams (`tunneled`)
- [ ] Audio passthrough to AVR/soundbar: TrueHD, DTS-HD, E-AC3 (`audioPassthrough`)
- [ ] DV Profile 7 file: plays with `enableWorkarounds`, fails without (sanity)
- [ ] Unsupported-codec file plays via FFmpeg software decode (`enableVideoSoftwareDecoding`); disabled → no FFmpeg renderer
- [ ] `onVideoStatistics` payloads on HLS + progressive sources; fields absent when unknown
- [ ] `onChapters` on a file with chapters; correct `type` classification
- [ ] AFR: 24/25 fps content on a 60 Hz TV switches refresh rate and restores on stop (`matchFrameRate`)
- [ ] Large 4K file with PGS subtitles: heap stays bounded; unselected subtitle track doesn't blow memory
- [ ] `maxHeapAllocationPercent` cap pauses buffering when reached
- [ ] `enableDynamicScheduling`: playback identical, CPU/power lower (A/B)
- [ ] Fullscreen shows controls regardless of `controls`, restored on exit
- [ ] Background audio + notification controls still work

---

## 8. Rollback

- `support/6.x.x` (remote) and `scratched-megaraptor` (local) still carry the
  complete v6 fork with all customizations — untouched by this migration.
- The v7 work is on `master` (pushed). To revert an app to v6, point the
  dependency back to the v6 branch/tag and drop `enableDynamicScheduling`
  from configs (the only new option).
- Toolchain changes (RN 0.80, Kotlin 2.1.20, SDK 36) are required by the new
  module; they are not fork-internal and do not revert with the library.

---

## 9. Repo state

- `master` — v7.0.0-beta.11 + all ported features (6 feature commits + 2
  toolchain/dependency commits + `enableDynamicScheduling`).
- `support/6.x.x`, `scratched-megaraptor` — v6, untouched.
- Package: `packages/react-native-video` (monorepo, bun workspaces).
