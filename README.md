# VisionMate v2

[![Platform](https://img.shields.io/badge/platform-Android-3DDC84?logo=android&logoColor=white)](https://www.android.com/)
[![Language](https://img.shields.io/badge/language-Kotlin-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![Runtime](https://img.shields.io/badge/on--device-AI-0F9D58)](#how-it-works)
[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

VisionMate v2 is an Android assistive-vision prototype for people who are blind or have low vision. It uses the phone camera to describe a scene, read visible text, and provide proximity-based obstacle warnings. The app is designed around large controls, TalkBack labels, voice input, haptic feedback, and English/Hindi responses.

This repository contains the Android source code and project documentation. The installable app is distributed as an APK through the repository's **Releases** page, not as a file committed to the source tree.

## Get the app

1. Open the repository's **Releases** page.
2. Under the latest release, download `VisionMate-v2.2.apk`.
3. Open the downloaded APK on an Android device running Android 8.0 (API 26) or later.
4. If Android asks, allow the browser or file manager to install apps from unknown sources, then complete installation.
5. Open VisionMate and grant camera and microphone permissions.

Add your release-page URL here after publishing:

```text
https://github.com/<your-github-username>/<your-repository-name>/releases/latest
```

For the exact GitHub upload steps, see [PUBLISHING.md](PUBLISHING.md).

## What it can do

| Mode | What happens |
| --- | --- |
| Describe Scene | Generates a spoken scene caption from the current camera frame. |
| Read Text | Uses on-device ML Kit OCR to read Latin and Devanagari text. |
| What's Ahead | Combines object detection and relative depth to announce nearby obstacles and suggest a clearer side to pass. |
| Voice control | Supports voice commands in English or Hindi, volume-key activation, and an immediate `STOP VOICE` command. |

## How it works

```text
CameraX live frame
      |
      +-- Describe: CLIP vision encoder -> trained caption decoder -> text to speech
      +-- Read: ML Kit OCR -> correction layer -> text to speech
      +-- Ahead: YOLOv11n + MiDaS relative depth -> obstacle tiers -> speech and haptics
      |
Voice input -> SpeechRecognizer -> bilingual command routing -> action
```

The app keeps one upright current bitmap as the shared input for the vision features. Models are lazy-loaded, so a missing optional model shows a clear status message instead of crashing the app.

## Technical details

- Kotlin Android app using CameraX and Material 3.
- YOLOv11n detects objects; MiDaS Small provides relative depth, not metric distance.
- A frozen CLIP ViT-B/32 vision encoder supplies patch features to a Transformer caption decoder trained for VisionMate's earlier research prototype.
- The captioning decoder uses greedy on-device decoding for lower latency.
- OCR uses Google ML Kit Text Recognition for Latin and Devanagari scripts.
- The shipped v2.2 app uses Android `SpeechRecognizer` behind a `SpeechListener` interface. Offline `whisper.cpp` is planned work, not a current feature.
- TFLite/LiteRT models run on CPU with XNNPACK where supported.

## Safety and limitations

VisionMate is a student prototype for research and learning. It is **not** a certified mobility or safety product and is not a replacement for a cane, guide dog, orientation and mobility instruction, or situational awareness.

- MiDaS estimates relative depth, so warnings use heuristic proximity tiers rather than real metres.
- Stop/close thresholds were tuned on limited room and street scenes and require wider testing.
- Captions, OCR, detection, and speech recognition can be wrong, delayed, or affected by lighting, blur, noise, and device performance.
- Do not rely on this app as the only safety signal around traffic, stairs, crowds, or other hazards.

## Build from source

Requirements: Android Studio, JDK 17, an API 26+ device or emulator, and the model files under `app/src/main/assets/models/`.

```bash
git clone https://github.com/<your-github-username>/<your-repository-name>.git
cd <your-repository-name>
./gradlew assembleDebug
```

You can also open the project in Android Studio, select a device, and choose **Run**. See [MODELS_SETUP.md](MODELS_SETUP.md) before building from source.

## Repository layout

```text
.
├── app/                         # Android application module
├── gradle/
├── README.md                    # Overview and installation guide
├── PROJECT.md                   # Build journey, decisions, validation, limits
├── MODELS_SETUP.md              # Model asset setup
├── PUBLISHING.md                # Release the APK on GitHub
└── LICENSE
```

## Documentation

- [PROJECT.md](PROJECT.md): how VisionMate v2 was built from model conversion to Android integration and device fixes.
- [MODELS_SETUP.md](MODELS_SETUP.md): expected model assets and compatibility notes.
- [PUBLISHING.md](PUBLISHING.md): clear steps for giving users the Android app through GitHub Releases.

## Current status

VisionMate v2.2 builds as an Android APK and includes scene captioning, OCR, obstacle warnings, bilingual command routing, and accessibility-focused controls. Offline speech recognition, GPU acceleration, beam-search decoding, and structured usability testing are future improvements.

## Author

Ankush Jha  
BS, IIT Patna  
GitHub: [@ankushjha556](https://github.com/ankushjha556)
