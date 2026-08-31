# VisionMate

An Android app for blind and low-vision users. Point the phone camera forward and the app tells you out loud what is in front of you — a description of the scene, the text on a sign or packet, or a warning when something is blocking your path. Everything runs on the phone. No internet, no account, no data going anywhere.

I built this as my college project because most apps in this space either need a constant internet connection or are closed source. A navigation app that stops working in a basement or on 2G network is not really usable for the person who depends on it, so my main goal was: everything on-device.

Full build story (training, quantization, every bug I hit) is in [PROJECT.md](PROJECT.md).

---

## What it can do

| Feature | What actually happens |
|---|---|
| **Describe scene** | Captions what the camera sees, e.g. *"a person sitting on a chair with a table in front"*. Works because of a captioning model I trained on VizWiz (a dataset collected by blind users, so the photos look like real blind-user photos — blurry, off-center, close-up). |
| **Read text** | ML Kit OCR (Latin + Devanagari), fully on-device. Speaks whatever text is readable — signboards, medicine labels, printed pages. |
| **Obstacle warning** | Runs in a background loop every ~1.5 s. YOLOv11n finds objects, MiDaS estimates depth, and the app says things like *"chair ahead, about 3 steps"* or *"stop, person immediately ahead"* — with left/right direction when the path is blocked. |
| **Path guidance** | When something blocks the way, it checks both sides of the frame and says which side has free space. |
| **Voice control** | Tap the big mic button, or press either **volume key** — then just speak. Commands work in English and Hindi (mixed Hinglish too, like *"aage kya hai"*). The reply language can match your command. |
| **TalkBack friendly** | Every button has a content description, big touch targets, and everything spoken via TTS. |

Everything speaks through Android TTS. The response language can be English, Hindi, or auto.

---

## Install the app (no build needed)

If you just want to use the app:

1. Go to [**Releases**](https://github.com/ankushjha556/VisionMate-v2/releases/latest)
2. Download the `.apk` file
3. Open it on your phone. Android will ask to allow installs from unknown sources for your browser — allow it once
4. Install. Needs **Android 8.0 (API 26)** or newer

The APK has all 6 model files bundled inside it (about 130 MB total, so the download is large — that is the price of running fully offline).

---

## Build from source

You need Android Studio (any recent version) with SDK 34 installed.

```
git clone https://github.com/ankushjha556/VisionMate-v2.git
```

Then open the folder in Android Studio, let it sync, and press Run. The six model files are already in the repo under `app/src/main/assets/models/`, so it builds as-is — no extra setup, no keys, no downloads.

For a release build, use Gradle's `assembleRelease` — the project includes a convenience signing config, or you can create your own keystore.

---

## How it works inside

One camera frame goes through different paths depending on the feature:

```
                     ┌─ Describe ──> CLIP encoder ──> caption decoder ──> TTS
camera frame ────────┼─ Read text ─> ML Kit OCR ──────────────────────> TTS
                     └─ Obstacles ─> YOLO (what) + MiDaS (how far) ───> TTS
                                        │
                                        └─> PathGuidance (which side is free)

mic button / volume key ──> speech recognition ──> CommandRouter ──> runs a feature
```

The models, all quantized to fit a phone:

| File | Size | Job |
|---|---|---|
| `clip_vision_w8.tflite` | 85 MB | CLIP ViT-B/32 vision encoder — turns the image into embeddings the decoder can condition on |
| `caption_decoder_w8.tflite` | 27 MB | The captioning decoder I trained (PatchCaptionDecoder, greedy decode on-device) |
| `midas_small_w8.tflite` | 17 MB | MiDaS-small — relative depth estimation |
| `yolo11n_w8a32.tflite` | 2.9 MB | YOLOv11n — object detection at 320×320 |
| `tokenizer_vocab.json` + `tokenizer_merges.txt` | ~150 KB | BPE tokenizer for the decoder |

All quantized models keep **float32 input/output**, so the Kotlin side feeds them normal tensors (the fp16 exports crashed TFLite's CPU kernels — that story is in PROJECT.md). I verified each one against its PyTorch original before shipping. Distance in "steps" is estimated from the MiDaS depth map and the camera's rough chest height, then calibrated to an average stride. It is an estimate, not a measurement.

---

## Project structure

```
app/src/main/
├── java/com/ankushjha/visionmate/
│   ├── ml/            ModelManager, YoloDetector, MiDaSDepth, ClipEncoder,
│   │                  CaptionDecoder, BpeTokenizer, OcrEngine
│   ├── obstacle/      ObstacleWarningEngine, PathGuidance
│   ├── camera/        FrameAnalyzer, DetectionOverlayView
│   ├── voice/         TtsSpeaker, SpeechListener, CommandRouter
│   ├── util/          Prefs, Types (bilingual response templates)
│   ├── MainActivity, SettingsActivity, App
├── assets/models/     the 6 model files listed above
└── res/               layouts, themes (dark + light), strings
setup/                 notes + recipe for regenerating the quantized models
```

---

## Limitations (being honest)

- The caption decoder is int8 quantized and trained on VizWiz — captions are short and simple, and it can be plain wrong in bad lighting. It is a helper, not a guarantee.
- Distance warnings come from monocular depth, so they are rough. For anything safety-critical this app supplements, not replaces, a cane or a guide.
- Devanagari OCR reads printed text much better than stylised fonts or handwriting.
- Speech recognition uses Android's built-in SpeechRecognizer, which on some devices routes through online services. A fully-offline STT (whisper.cpp) is the next thing I want to add.
- Tested mainly on one mid-range Android phone. More device testing is on my list.

---

## Docs

- **[PROJECT.md](PROJECT.md)** — the whole build from idea to working app: model training, TFLite conversion failures, quantization results, the Android side, and every major bug (including the week the app would only say "gininini...").
- **[MODELS_SETUP.md](MODELS_SETUP.md)** — details on the model files and how to regenerate/replace them.

## License

MIT — do whatever you want with it, attribution appreciated. If this code helps you build something that helps someone, that is the best outcome.
