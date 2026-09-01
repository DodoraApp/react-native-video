import { NitroModules } from 'react-native-nitro-modules';
import { VideoPlayerEvents } from '../src/core/events/VideoPlayerEvents';
import type { VideoPlayerEventEmitterBase } from '../src/core/types/EventEmitter';
import { createSourceFromVideoConfig } from '../src/core/utils/sourceFactory';

// The v7 JS surface forwards the fork-only DodoStream source options and
// events through the Nitro layer. These tests defend that contract:
// config fields must reach the native factory unchanged, and the two new
// events must register listeners on the native event emitter.

jest.mock('react-native', () => ({
  Platform: { select: jest.fn() },
  Image: { resolveAssetSource: jest.fn() },
}));

jest.mock('react-native-nitro-modules', () => ({
  NitroModules: {
    createHybridObject: jest.fn(() => ({
      fromUri: jest.fn(),
      fromVideoConfig: jest.fn(),
    })),
  },
}));

const mockedCreateHybridObject = jest.mocked(
  NitroModules.createHybridObject
);

// The source factory module captures its hybrid object at import time.
const nativeFactory = mockedCreateHybridObject.mock
  .results[0]!.value as unknown as {
  fromUri: jest.Mock;
  fromVideoConfig: jest.Mock;
};

const SOURCE = { uri: 'https://example.com/video.mp4' };

describe('createSourceFromVideoConfig (fork source options)', () => {
  afterEach(() => {
    jest.clearAllMocks();
  });

  it('forwards the seven custom playback options to the native factory', () => {
    createSourceFromVideoConfig({
      ...SOURCE,
      tunneled: true,
      audioPassthrough: true,
      enableWorkarounds: true,
      enableVideoSoftwareDecoding: true,
      reportStatistics: true,
      matchFrameRate: true,
      enableDynamicScheduling: true,
    });

    expect(nativeFactory.fromVideoConfig).toHaveBeenCalledWith(
      expect.objectContaining({
        tunneled: true,
        audioPassthrough: true,
        enableWorkarounds: true,
        enableVideoSoftwareDecoding: true,
        reportStatistics: true,
        matchFrameRate: true,
        enableDynamicScheduling: true,
      })
    );
  });

  it('leaves custom options unset when not provided', () => {
    createSourceFromVideoConfig(SOURCE);

    const sentConfig = nativeFactory.fromVideoConfig.mock.calls[0]![0] as Record<
      string,
      unknown
    >;
    expect(sentConfig).not.toHaveProperty('tunneled');
    expect(sentConfig).not.toHaveProperty('audioPassthrough');
    expect(sentConfig).not.toHaveProperty('enableWorkarounds');
    expect(sentConfig).not.toHaveProperty('enableVideoSoftwareDecoding');
    expect(sentConfig).not.toHaveProperty('reportStatistics');
    expect(sentConfig).not.toHaveProperty('matchFrameRate');
    expect(sentConfig).not.toHaveProperty('enableDynamicScheduling');
  });

  it('forwards the memory-cap buffer config fields', () => {
    createSourceFromVideoConfig({
      ...SOURCE,
      bufferConfig: {
        maxHeapAllocationPercent: 0.5,
        minBufferMemoryReservePercent: 0.1,
      },
    });

    expect(nativeFactory.fromVideoConfig).toHaveBeenCalledWith(
      expect.objectContaining({
        bufferConfig: expect.objectContaining({
          maxHeapAllocationPercent: 0.5,
          minBufferMemoryReservePercent: 0.1,
        }),
      })
    );
  });
});

describe('VideoPlayerEvents (fork events)', () => {
  it('registers onChapters and onVideoStatistics listeners on the native emitter', () => {
    const emitter = {
      addOnChaptersListener: jest.fn(),
      addOnVideoStatisticsListener: jest.fn(),
      clearAllListeners: jest.fn(),
    } as unknown as VideoPlayerEventEmitterBase;
    const events = new VideoPlayerEvents(emitter);

    const chaptersCallback = jest.fn();
    const statisticsCallback = jest.fn();

    events.addEventListener('onChapters', chaptersCallback);
    events.addEventListener('onVideoStatistics', statisticsCallback);

    expect(emitter.addOnChaptersListener).toHaveBeenCalledWith(
      chaptersCallback
    );
    expect(emitter.addOnVideoStatisticsListener).toHaveBeenCalledWith(
      statisticsCallback
    );
  });
});
