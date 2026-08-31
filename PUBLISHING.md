# Download and Install VisionMate v2

VisionMate v2 is distributed as an Android APK through this repository's **Releases** page.

## Download the app

1. Open the repository's **Releases** page.
2. Select the newest release of VisionMate v2.
3. Under **Assets**, download `VisionMate-v2.2.apk`.
4. Wait for the download to finish before opening the file.

The direct release link will be added here when the first public release is published:

```text
https://github.com/<your-github-username>/<your-repository-name>/releases/latest
```

## Install on Android

1. Open the downloaded `VisionMate-v2.2.apk` file.
2. Android may show a security message because the app was downloaded from GitHub rather than the Play Store.
3. Select **Settings** or **Allow from this source** for the browser or file manager you used to download the APK.
4. Return to the installer and select **Install**.
5. Open VisionMate after installation completes.

VisionMate requires Android 8.0 (API 26) or newer.

## First launch

When the app opens, allow these permissions:

- **Camera**: required for scene description, text reading, and obstacle analysis.
- **Microphone**: required for voice commands.

The app may also ask Android to use text-to-speech. The device's default speech engine is used for spoken results.

## Using the app

- **Describe Scene**: points the camera at a scene and speaks a generated description.
- **Read Text**: reads visible Latin or Devanagari text from the camera frame.
- **What's Ahead**: announces nearby detected objects using relative-depth warning tiers.
- **Talk**: starts voice-command listening.
- **Stop Voice**: immediately stops current spoken output.

Either volume key can also start voice listening. Commands can be given in English or Hindi.

## Troubleshooting

| Problem | What to try |
| --- | --- |
| APK will not install | Check that the device runs Android 8.0 or later and that installation from the download source is allowed. |
| Camera view is blank | Check that camera permission is enabled in Android Settings. |
| Voice commands do not start | Check microphone permission and make sure no other app is actively using the microphone. |
| No speech is heard | Raise media volume and check that text-to-speech is enabled on the phone. |
| A feature says its model is unavailable | Reinstall the official APK from the latest GitHub Release. |

## Important safety note

VisionMate v2 is a research and learning prototype. Its obstacle guidance is based on object detection and **relative** depth estimation, not measured physical distance. It should not be used as the only safety aid while walking near traffic, stairs, crowds, or other hazards.
