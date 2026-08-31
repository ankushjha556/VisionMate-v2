# Model Setup

VisionMate v2 expects its mobile model files under:

```text
app/src/main/assets/models/
```

The exact filenames must match the fallback names used by `ModelManager.kt` in the Android source. Keep large model files out of ordinary Git commits; document how an authorized developer can obtain them instead.

## Expected components

| Component | Purpose |
| --- | --- |
| YOLOv11n LiteRT/TFLite model | Object detection for obstacle analysis. |
| MiDaS Small LiteRT/TFLite model | Relative depth estimation. |
| CLIP vision LiteRT/TFLite model | Image patch features for captioning. |
| Caption decoder LiteRT/TFLite model | Caption-token generation from CLIP features. |
| BPE tokenizer files | Converts caption token IDs into text. |

ML Kit OCR is an Android dependency, so it does not need a manually copied model file. Android `SpeechRecognizer` is the speech input implementation in v2.2; `whisper.cpp` is not currently packaged in the app.

## Loading behaviour

Models are loaded lazily. The app reports model availability in Settings, and a missing optional model should produce a readable status message instead of crashing. A source build without every model can open, but the feature tied to a missing model will be unavailable.

## Compatibility note

The v2.2 captioning models use an explicit-dequantization, weight-only quantization path because earlier hybrid quantization produced incorrect caption tokens on some older Android TFLite runtimes. Do not replace these assets with the earlier hybrid exports without testing on a physical device.

## Do not commit

Do not commit model copies larger than GitHub's normal file limit, model archives, checkpoints, downloaded datasets, or signing keys. Store release APKs on GitHub Releases and keep source-model distribution separate unless Git LFS and licensing are intentionally configured.
