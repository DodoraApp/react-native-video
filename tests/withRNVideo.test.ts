import fs from 'fs';
import os from 'os';
import path from 'path';
import {compileModsAsync, type ExpoConfig} from '@expo/config-plugins';
import withRNVideo from '../src/expo-plugins/withRNVideo';

/**
 * Creates a minimal Expo project on disk and runs withRNVideo through the
 * real mod compiler (same path as `expo prebuild`). Assertions are made on
 * the generated Android/iOS files, so the tests defend the app-observable
 * plugin contract rather than implementation details.
 */

function createProject(): {root: string; config: ExpoConfig} {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'rnv-plugin-test-'));
  fs.mkdirSync(path.join(root, 'android', 'app', 'src', 'main'), {
    recursive: true,
  });
  fs.writeFileSync(
    path.join(root, 'android', 'app', 'src', 'main', 'AndroidManifest.xml'),
    [
      '<manifest xmlns:android="http://schemas.android.com/apk/res/android">',
      '  <application>',
      '    <activity android:name=".MainActivity" />',
      '  </application>',
      '</manifest>',
    ].join('\n'),
  );
  fs.writeFileSync(
    path.join(root, 'android', 'gradle.properties'),
    '# empty\n',
  );
  fs.mkdirSync(path.join(root, 'ios', 'testapp'), {recursive: true});
  fs.writeFileSync(
    path.join(root, 'ios', 'Podfile'),
    [
      "require File.join(File.dirname(`node --print \"require.resolve('@expo/react-native-adapter/package.json')\"`), 'scripts/autolinking')",
      "platform :ios, '13.0'",
      '',
    ].join('\n'),
  );
  fs.writeFileSync(
    path.join(root, 'ios', 'testapp', 'Info.plist'),
    [
      '<?xml version="1.0" encoding="UTF-8"?>',
      '<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">',
      '<plist version="1.0">',
      '<dict/>',
      '</plist>',
      '',
    ].join('\n'),
  );
  return {root, config: {name: 'testapp', slug: 'test-app'}};
}

async function applyPlugin(
  props: Record<string, unknown>,
): Promise<{root: string}> {
  const {root, config} = createProject();
  const result = withRNVideo(config, props);
  await compileModsAsync(result, {
    projectRoot: root,
    platforms: ['android', 'ios'],
  });
  return {root};
}

function readFile(root: string, rel: string): string {
  return fs.readFileSync(path.join(root, rel), 'utf8');
}

describe('withRNVideo expo config plugin', () => {
  it('maps enableCacheExtension to the iOS caching podfile flag', async () => {
    const {root} = await applyPlugin({enableCacheExtension: true});
    const podfile = readFile(root, 'ios/Podfile');
    expect(podfile).toContain('$RNVideoUseVideoCaching = true');
  });

  it('does not treat the misspelled option as the cache option', async () => {
    const {root} = await applyPlugin({enablFeCacheExtension: true});
    const podfile = readFile(root, 'ios/Podfile');
    expect(podfile).not.toContain('RNVideoUseVideoCaching');
  });

  it('adds foreground-service permissions and playback service for notification controls', async () => {
    const {root} = await applyPlugin({enableNotificationControls: true});
    const manifest = readFile(root, 'android/app/src/main/AndroidManifest.xml');
    expect(manifest).toContain('android.permission.FOREGROUND_SERVICE');
    expect(manifest).toContain(
      'android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK',
    );
    expect(manifest).toContain('com.brentvatne.exoplayer.VideoPlaybackService');
  });

  it('enables Android picture-in-picture on the main activity', async () => {
    const {root} = await applyPlugin({enableAndroidPictureInPicture: true});
    const manifest = readFile(root, 'android/app/src/main/AndroidManifest.xml');
    expect(manifest).toContain('android:supportsPictureInPicture="true"');
  });

  it('writes ExoPlayer extension flags for androidExtensions', async () => {
    const {root} = await applyPlugin({
      androidExtensions: {useExoplayerDash: true, useExoplayerHls: false},
    });
    const gradleProperties = readFile(root, 'android/gradle.properties');
    expect(gradleProperties).toContain('RNVideo_useExoplayerDash=true');
    expect(gradleProperties).toContain('RNVideo_useExoplayerHls=false');
    expect(gradleProperties).toContain('RNVideo_useExoplayerRtsp=false');
  });

  it('writes the IMA flag for enableADSExtension', async () => {
    const {root} = await applyPlugin({enableADSExtension: true});
    const gradleProperties = readFile(root, 'android/gradle.properties');
    expect(gradleProperties).toContain('RNVideo_useExoplayerIMA=true');
  });

  it('adds background audio mode to the iOS Info.plist', async () => {
    const {root} = await applyPlugin({enableBackgroundAudio: true});
    const plist = readFile(root, 'ios/testapp/Info.plist');
    expect(plist).toContain('<key>UIBackgroundModes</key>');
    expect(plist).toContain('<string>audio</string>');
  });

  it('leaves the project untouched when no options are provided', async () => {
    const {root} = await applyPlugin({});
    expect(readFile(root, 'ios/Podfile')).not.toContain(
      'RNVideoUseVideoCaching',
    );
    const manifest = readFile(root, 'android/app/src/main/AndroidManifest.xml');
    expect(manifest).not.toContain('VideoPlaybackService');
    expect(manifest).not.toContain('FOREGROUND_SERVICE');
    expect(readFile(root, 'android/gradle.properties')).not.toContain(
      'RNVideo_useExoplayerIMA',
    );
  });
});
