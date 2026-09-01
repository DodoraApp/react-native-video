import fs from 'fs';
import os from 'os';
import path from 'path';
import { compileModsAsync, type ExpoConfig } from '@expo/config-plugins';
import withRNVideo from '../src/expo-plugins/withReactNativeVideo';

/**
 * Runs the v7 expo config plugin through the real mod compiler (same path as
 * `expo prebuild`) against a minimal project on disk, and asserts on the
 * generated Android/iOS files — the app-observable plugin contract.
 */

function createProject(): { root: string; config: ExpoConfig } {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'rnv-plugin-test-'));
  fs.mkdirSync(path.join(root, 'android', 'app', 'src', 'main'), {
    recursive: true,
  });
  fs.writeFileSync(
    path.join(root, 'android', 'app', 'src', 'main', 'AndroidManifest.xml'),
    [
      '<manifest xmlns:android="http://schemas.android.com/apk/res/android">',
      '  <application android:name=".MainApplication">',
      '    <activity android:name=".MainActivity" />',
      '  </application>',
      '</manifest>',
    ].join('\n')
  );
  fs.writeFileSync(
    path.join(root, 'android', 'gradle.properties'),
    '# empty\n'
  );
  fs.mkdirSync(path.join(root, 'ios', 'testapp'), { recursive: true });
  fs.writeFileSync(
    path.join(root, 'ios', 'testapp', 'Info.plist'),
    [
      '<?xml version="1.0" encoding="UTF-8"?>',
      '<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">',
      '<plist version="1.0">',
      '<dict/>',
      '</plist>',
      '',
    ].join('\n')
  );
  return { root, config: { name: 'testapp', slug: 'test-app' } };
}

async function applyPlugin(
  props: Record<string, unknown>
): Promise<{ root: string }> {
  const { root, config } = createProject();
  const result = withRNVideo(config, props);
  await compileModsAsync(result, {
    projectRoot: root,
    platforms: ['android', 'ios'],
  });
  return { root };
}

function readFile(root: string, rel: string): string {
  return fs.readFileSync(path.join(root, rel), 'utf8');
}

describe('withRNVideo expo config plugin', () => {
  it('writes ExoPlayer extension flags for androidExtensions', async () => {
    const { root } = await applyPlugin({
      androidExtensions: { useExoplayerDash: true, useExoplayerHls: false },
    });
    const gradleProperties = readFile(root, 'android/gradle.properties');
    expect(gradleProperties).toContain('RNVideo_useExoplayerDash=true');
    expect(gradleProperties).toContain('RNVideo_useExoplayerHls=false');
  });

  it('enables Android picture-in-picture on the main activity', async () => {
    const { root } = await applyPlugin({ enableAndroidPictureInPicture: true });
    const manifest = readFile(root, 'android/app/src/main/AndroidManifest.xml');
    expect(manifest).toContain('android:supportsPictureInPicture="true"');
  });

  it('adds background audio mode to the iOS Info.plist', async () => {
    const { root } = await applyPlugin({ enableBackgroundAudio: true });
    const plist = readFile(root, 'ios/testapp/Info.plist');
    expect(plist).toContain('<key>UIBackgroundModes</key>');
    expect(plist).toContain('<string>audio</string>');
  });

  it('always adds the playback service and foreground-service permissions', async () => {
    const { root } = await applyPlugin({});
    const manifest = readFile(root, 'android/app/src/main/AndroidManifest.xml');
    expect(manifest).toContain(
      'com.twg.video.core.services.playback.VideoPlaybackService'
    );
    expect(manifest).toContain('android.permission.FOREGROUND_SERVICE');
    expect(manifest).toContain(
      'android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK'
    );
  });

  it('leaves the project untouched when no options are provided', async () => {
    const { root } = await applyPlugin({});
    expect(readFile(root, 'android/gradle.properties')).not.toContain(
      'RNVideo_useExoplayerDash'
    );
    const manifest = readFile(root, 'android/app/src/main/AndroidManifest.xml');
    expect(manifest).not.toContain('supportsPictureInPicture');
    const plist = readFile(root, 'ios/testapp/Info.plist');
    expect(plist).not.toContain('UIBackgroundModes');
  });
});
