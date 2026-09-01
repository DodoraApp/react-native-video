import {
  AndroidConfig,
  type ConfigPlugin,
  withAndroidManifest,
} from '@expo/config-plugins';

export const withAndroidNotificationControls: ConfigPlugin = (oldConfig) => {
  return withAndroidManifest(oldConfig, (config) => {
    const mainApplication = AndroidConfig.Manifest.getMainApplication(
      config.modResults
    );
    if (!mainApplication) {
      console.warn(
        'AndroidManifest.xml is missing an <activity android:name=".MainActivity" /> element - skipping adding Notification Controls related config.'
      );
      return config;
    }
    // The manifest usually has no <service> element yet; without it the
    // optional chain would silently skip registration.
    mainApplication.service = mainApplication.service ?? [];
    mainApplication.service.push({
      '$': {
        'android:name':
          'com.twg.video.core.services.playback.VideoPlaybackService',
        'android:exported': 'false',
        'android:foregroundServiceType': 'mediaPlayback',
      },
      'intent-filter': [
        {
          action: [
            {
              $: {
                'android:name': 'androidx.media3.session.MediaSessionService',
              },
            },
          ],
        },
      ],
    });
    AndroidConfig.Permissions.addPermission(
      config.modResults,
      'android.permission.FOREGROUND_SERVICE'
    );
    AndroidConfig.Permissions.addPermission(
      config.modResults,
      'android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK'
    );
    return config;
  });
};
