import React from 'react';
import {act, create, ReactTestRenderer} from 'react-test-renderer';
import Video from '../src/Video';

// Captures the props passed to the native host component so tests can assert
// the fork-only DodoStream props/events are forwarded by the JS bridge.
let lastNativeProps: Record<string, unknown> | undefined;

jest.mock('../src/specs/VideoNativeComponent', () => {
  // eslint-disable-next-line @typescript-eslint/no-var-requires
  const ReactRuntime = require('react');
  // eslint-disable-next-line @typescript-eslint/no-var-requires
  const {View} = require('react-native');
  const MockNativeComponent = ReactRuntime.forwardRef(
    function MockNativeComponent(props: Record<string, unknown>, ref: unknown) {
      lastNativeProps = props;
      return ReactRuntime.createElement(View, {ref});
    },
  );
  return {__esModule: true, default: MockNativeComponent};
});

const SOURCE = {uri: 'https://example.com/video.mp4'};

function renderVideo(props: Record<string, unknown>): ReactTestRenderer {
  let tree!: ReactTestRenderer;
  act(() => {
    tree = create(React.createElement(Video, props));
  });
  return tree;
}

describe('Video custom DodoStream props/events', () => {
  afterEach(() => {
    lastNativeProps = undefined;
  });

  it('forwards the six custom playback props to the native component', () => {
    renderVideo({
      source: SOURCE,
      tunneled: true,
      audioPassthrough: true,
      enableWorkarounds: true,
      enableVideoSoftwareDecoding: true,
      reportStatistics: true,
      matchFrameRate: true,
    });

    expect(lastNativeProps).toMatchObject({
      tunneled: true,
      audioPassthrough: true,
      enableWorkarounds: true,
      enableVideoSoftwareDecoding: true,
      reportStatistics: true,
      matchFrameRate: true,
    });
  });

  it('leaves custom props unset when not provided (native defaultBoolean=false applies)', () => {
    renderVideo({source: SOURCE});

    expect(lastNativeProps?.tunneled).toBeUndefined();
    expect(lastNativeProps?.audioPassthrough).toBeUndefined();
    expect(lastNativeProps?.enableWorkarounds).toBeUndefined();
    expect(lastNativeProps?.enableVideoSoftwareDecoding).toBeUndefined();
    expect(lastNativeProps?.reportStatistics).toBeUndefined();
    expect(lastNativeProps?.matchFrameRate).toBeUndefined();
  });

  it('forwards statistics and chapters events with unchanged payloads', () => {
    const onVideoStatistics = jest.fn();
    const onChapters = jest.fn();

    renderVideo({source: SOURCE, onVideoStatistics, onChapters});

    const nativeProps = lastNativeProps as unknown as {
      onVideoStatistics: (e: {nativeEvent: unknown}) => void;
      onChapters: (e: {nativeEvent: unknown}) => void;
    };
    expect(typeof nativeProps.onVideoStatistics).toBe('function');
    expect(typeof nativeProps.onChapters).toBe('function');

    const statisticsPayload = {
      streamType: 'HLS',
      container: 'MP4 (video/mp4)',
      bitrate: 'Video 4.20 Mbps, Audio 192 kbps',
    };
    act(() => {
      nativeProps.onVideoStatistics({nativeEvent: statisticsPayload});
    });
    expect(onVideoStatistics).toHaveBeenLastCalledWith(statisticsPayload);

    // Optional statistics fields must be tolerated when absent.
    act(() => {
      nativeProps.onVideoStatistics({nativeEvent: {}});
    });
    expect(onVideoStatistics).toHaveBeenLastCalledWith({});

    const chaptersPayload = {
      chapters: [{title: 'Intro', startTime: 0, endTime: 62.5, type: 'INTRO'}],
    };
    act(() => {
      nativeProps.onChapters({nativeEvent: chaptersPayload});
    });
    expect(onChapters).toHaveBeenLastCalledWith(chaptersPayload);
  });

  it('does not attach event handlers when callbacks are absent', () => {
    renderVideo({source: SOURCE});

    expect(lastNativeProps?.onVideoStatistics).toBeUndefined();
    expect(lastNativeProps?.onChapters).toBeUndefined();
  });
});
