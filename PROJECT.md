# VisionMate v2: Project Build Log

## Overview

VisionMate started as an assistive-vision research prototype. The original work explored scene captioning, OCR, obstacle awareness, and bilingual voice interaction. Version 2 changes the practical question from “can this pipeline work in a notebook?” to “can the important parts run inside a real Android app using a live camera?”

The result is a Kotlin Android application that combines scene captioning, OCR, obstacle warnings, voice commands, text-to-speech, and accessibility-oriented interaction design. This document records the development path, including decisions that changed during implementation.

## Starting point: research prototype

The first VisionMate prototype was built in notebooks and focused on validating AI components. Its captioning model used frozen CLIP ViT-B/32 patch features and a Transformer decoder trained from scratch on VizWiz-Captions. The best tested configuration used patch-level attention, 15,000 training images, and beam search, achieving BLEU-4 0.1823 and METEOR 0.3674.

The prototype also used OCR, YOLO-based detection, MiDaS depth estimation, and speech processing. It showed the components could work together, but it was not yet a live, hands-free mobile product.

## Phase 1: converting models for mobile

The Android-focused work began by converting YOLOv11n, MiDaS Small, the CLIP vision encoder, and the caption decoder into formats usable on a phone. Each conversion was tested against the original PyTorch behaviour before Android integration.

### YOLOv11n

YOLOv11n was exported at a 320-pixel input size to keep object detection suitable for the repeated obstacle-warning loop. This lowers the pixel workload compared with a 640-pixel export while retaining useful detection behaviour for larger hazards. The Kotlin code handles letterboxing, output decoding, and non-maximum suppression.

### MiDaS Small

MiDaS supplies relative inverse depth. It does not provide calibrated physical distance in metres, so VisionMate uses it for closeness tiers rather than claiming exact distances. The app combines the depth signal with object-box size to place detections into middle, close, or stop tiers.

### CLIP and the caption decoder

CLIP provides 49 visual patch embeddings to the caption decoder. The decoder runs autoregressively, predicting one token at a time. Both components were checked against the original PyTorch path using real images and token comparisons, instead of only checking whether a converted file loaded.

The first quantized caption build revealed a device-only failure: hybrid quantization kernels generated repeated meaningless tokens on older Android TFLite runtimes, despite appearing correct in newer desktop verification. The models were re-quantized using a safer explicit-dequantization path and the byte-level BPE tokenizer was fixed so space markers decode normally. A degenerate-caption guard returns a safe failure message instead of speaking a repeated loop.

## OCR and speech strategy

Not every desktop component was converted directly. For OCR, the app uses Google ML Kit Text Recognition instead of converting EasyOCR. It is mobile-oriented, runs on-device, and supports Latin and Devanagari text recognition.

The earlier plan was to use `whisper.cpp` for offline speech-to-text. It was built and separately tested, including with Hindi speech. However, the shipped v2.2 app uses Android `SpeechRecognizer` behind a `SpeechListener` abstraction. This kept the first release smaller and simpler while preserving a clean replacement point for offline speech recognition later. The README deliberately does not claim offline Whisper support as a shipped feature.

## Phase 2: Android application

The Android app uses Kotlin, CameraX, Material 3, and TFLite/LiteRT-compatible model loading.

### Shared camera frame

CameraX provides the preview and image-analysis stream. The frame analyzer produces a correctly rotated bitmap with a size limit for predictable memory use. The latest frame becomes the shared input for captioning, OCR, and obstacle analysis, avoiding separate capture pipelines.

### Scene description and reading text

Describe Scene sends the current frame through CLIP and the caption decoder, then reads the result aloud. v2.2 uses greedy decoding because it is simpler and lower latency on a phone; beam search performed better in notebook research but is not yet implemented on-device.

Read Text sends the same current frame to ML Kit OCR and speaks the output. The recognizer covers Latin and Devanagari scripts.

### Obstacle awareness

What's Ahead runs YOLO and MiDaS together. The depth ratio around the lower-middle region of an object box is combined with the box-height fraction:

```text
STOP  : depth ratio >= 0.72 OR box-height fraction >= 0.55
CLOSE : depth ratio >= 0.48 OR box-height fraction >= 0.35
MID   : otherwise, visual overlay only
```

These are heuristics, not calibrated distance measurements. Stop warnings interrupt lower-priority speech and trigger haptics. Close warnings are rate-limited to reduce repetition. The left and right thirds of the frame are checked for close/stop detections to provide a simple path suggestion when possible.

### Voice and accessibility

The app provides large controls, TalkBack-readable labels, live status text, high-contrast themes, haptic feedback, and a stop control that interrupts speech. Either volume key can start listening. Commands are routed in English or Hindi based on Devanagari characters and recognised Hindi keywords. The app responds in Hindi where a suitable device voice is available and falls back to English rather than failing silently.

## Device testing and v2.2 fixes

The first device-testing pass exposed compile errors, missing model packaging, an incorrect YOLO tensor assumption, voice lifecycle problems, and the broken quantized caption path. The main fixes were:

- corrected Kotlin companion-object and coroutine issues;
- corrected ML Kit OCR API use;
- removed a wake-word dependency that required extra account setup;
- packaged the caption model and tokenizer with fallback loading;
- corrected YOLO NCHW preprocessing and normalized box unletterboxing;
- supported CLIP's multiple outputs and removed the CLS token before captioning;
- clamped decoder logits to the tokenizer vocabulary;
- re-quantized affected caption models with a runtime-compatible method;
- fixed the tokenizer byte-map inversion; and
- added repetition detection to prevent unusable speech.

These fixes came from running the system on a device, not just from notebook testing.

## Validation and limits

Validation included comparisons with the original PyTorch path, YOLO box checks on real images, CLIP-to-decoder caption checks, quantization-fidelity checks, and debug/release Android builds. One tested caption output was “A black dog is sitting on the floor” for a photo containing a black dog. That is an example validation result, not a broad benchmark of real-world performance.

The project still has important limits:

- Relative depth is not measured distance.
- The warning thresholds need wider testing with different phones, environments, and visually impaired users.
- The app does not yet include offline `whisper.cpp` speech-to-text.
- CPU inference can be slow on lower-end devices.
- The captioning path uses greedy decoding rather than the stronger research beam-search configuration.
- There has not yet been a structured usability study with intended users.

## Next steps

1. Add offline `whisper.cpp` using a Kotlin/JNI integration.
2. Measure device latency, battery use, thermal behaviour, and model-loading time.
3. Run usability tests with visually impaired participants and tune warnings from evidence.
4. Add an optional beam-search caption setting after measuring device latency.
5. Explore GPU/NNAPI acceleration only after correctness and reliability are stable.

## Scope

VisionMate v2 is a learning and research project. It demonstrates mobile ML deployment, Android camera integration, model conversion, and accessibility-aware interaction design. It should not be presented as a certified safety product or a replacement for established mobility aids.
