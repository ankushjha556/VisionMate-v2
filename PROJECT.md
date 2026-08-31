# PROJECT.md — How VisionMate got built

This is the full record of the project, from the initial idea to the app you can install today. I am writing it the way it actually happened, including the parts where I was stuck for days, because that is where most of the learning was. If you are a student who wants to build something like this, read the failure sections carefully — they will save you time.

Timeline: roughly July 2026 to August 2026. I was doing this as my college project at IIT Patna.

---

## 1. Why this app

The starting point was a simple observation. Apps that exist for blind and low-vision users (the big one being Microsoft's Seeing AI) are good, but they are closed source and most of their heavy features depend on the cloud. For a user in India, that means two real problems: data cost, and reliability. If you are walking with a phone as your eyes, the app dying on a weak network is not a minor bug — it is the whole feature failing.

So I set a hard rule for myself early: **everything runs on-device.** Captioning, obstacle detection, OCR, wake word — all of it offline. That single decision drove every technical choice after it, mostly because on-device means small models, and small models means quantization, and quantization means pain. More on that later.

The second decision was who the captioning model is *for*. There are plenty of image captioning datasets, but most of them (COCO, Flickr) contain photos taken by sighted people with good framing. A blind user's photo is nothing like that — it is close-up, blurry, off-center, at a weird angle, half the object. So I picked **VizWiz** as the training dataset, which was crowdsourced from actual blind photographers. This mattered a lot: the model learned to caption the *kind* of photos the app will actually receive.

---

## 2. The plan

Before writing any app code I split the work into modules so each could be built and tested alone:

1. **Captioning model** (Python first) — CLIP vision encoder + a small transformer decoder I trained on VizWiz
2. **Obstacle warning** — YOLOv11n for "what is in front" + MiDaS-small for "how far"
3. **OCR** — ML Kit, because it runs on-device and handles Devanagari, which matters in India
4. **Voice layer** — wake word, speech recognition, TTS, and a command router that understands English + Hindi + Hinglish
5. **Android app** — wire all of the above into a camera app that a blind person can actually operate (big buttons, everything spoken, no visual-only feedback anywhere)

The rule for integration: every module has to prove itself in Python with real inputs before it touches Kotlin. This is the whole reason I could debug later — when the app misbehaved I always had a known-good Python reference to compare against.

---

## 3. Training the captioning model (Module 1)

The captioning model is a two-part thing:

- **Encoder**: the vision half of OpenAI's CLIP ViT-B/32 (frozen). It converts the image into patch embeddings.
- **Decoder**: a small transformer I trained myself, which takes those patch embeddings and generates a caption token by token. I called it `PatchCaptionDecoder`. It has a BPE tokenizer with 4004 tokens, its own vocabulary, built for short navigation-style captions.

Why CLIP instead of training my own CNN? Data efficiency. CLIP already knows what images "look like" from 400M image-text pairs. My decoder only had to learn the language side and the mapping, which VizWiz (~39k usable captions) is enough for. Training a full captioning stack from scratch on that much data would have produced something much worse.

The result was reasonable for the data size: **BLEU-4 of 0.182** on the VizWiz validation set. I want to be honest about this number — it is not close to state of the art on fancy benchmarks. But the captions it produces are navigation-useful ("a table with objects on it", "a person standing near a door"), and that is the metric that actually matters for this app. BLEU on VizWiz is also naturally low because the reference captions themselves are noisy.

First end-to-end demo was a Streamlit app in Python: webcam frame → caption → overlay. Seeing it caption my own room correctly for the first time was the moment I knew the idea worked. Everything after this was making it run on a phone.

---

## 4. Making the models small enough for a phone (Phase 1, the painful one)

The Python models were, in total, around **600 MB** of PyTorch weights. That is obviously not going into an APK that anyone will download. Target: get the whole set under ~150 MB with minimal accuracy loss.

### 4.1 The conversion route

PyTorch does not convert to TFLite directly, so the route was:

```
PyTorch → ONNX → onnx2tf (TensorFlow SavedModel) → ai-edge-quantizer → .tflite
```

Every single model had its own problem on this path. This section is basically a list of things that broke.

### 4.2 YOLOv11n

- Exported via ultralytics' **legacy ONNX exporter at opset 17 — exactly 17**. Not 18, not 16. The reason: ONNX 1.20's version-converter has a broken Resize adapter, and exporting at opset 17 skips the version-conversion entirely. Hours lost to this.
- Quantization: dynamic-range int8 weights, float32 IO. Final size **2.9 MB**.
- Verified against PyTorch on real street photos: matched detections at **IoU 0.83–0.93**, confidence deltas under 0.1. Also confirmed (both ways) that the input must be normalized to **0..1** — feeding 0..255 reproduces PyTorch output garbage. The app feeds 0..1.

### 4.3 MiDaS-small (depth)

- `onnxsim` const-folding is **required**, not optional. Without it, the dynamic Resize ops collapse the depth output to a [1,1,1] tensor. You get a single number as your "depth map" and nothing tells you why.
- Dynamic-range int8, float32 IO. **17.3 MB**.
- Verification: **Pearson correlation 0.9999** vs PyTorch on random images, inverse-depth ordering preserved. This was the smoothest conversion of the four.

### 4.4 CLIP vision encoder

- The interesting part: attention layers convert to `BATCH_MATMUL` ops in TFLite, and **the quantizer recipe must explicitly include that op** or nothing gets quantized — the file stayed at 333 MB until I added it, then dropped to **85 MB**.
- Weight-only int8 (ai-edge-quantizer), float32 IO. Cosine similarity vs PyTorch patch features: **0.9992**, mean abs diff 0.017. Good enough to feed the decoder.

### 4.5 The caption decoder — my favourite failure

My first attempt was fp16, because the decoder is transformer-heavy and fp16 usually "just works" on mobile. The file came out at ~85 MB and passed all my Python verification (cosine similarity vs fp32: **0.99998**). Then I put it in the app and TFLite CPU crashed instantly:

```
batch_mat_mul.cc: lhs type not supported
```

The CPU delegate on Android simply does not support fp16 BATCH_MATMUL. Nothing in the export pipeline warns you about this — the model is "valid", it just cannot run where I needed it to run. This is the kind of thing you only learn by deploying.

Fix: requantized the decoder to **weight-only int8 (w8) with float32 IO** instead. Final file: **26.8 MB**, and still cosine 0.99998 against the fp32 original. So the entire model set ended up:

| Model | Size | Quantization | Verification vs PyTorch |
|---|---|---|---|
| YOLOv11n | 2.9 MB | dynamic int8 (w8a32) | IoU 0.83–0.93 |
| MiDaS-small | 17 MB | weight-only int8 (w8) | Pearson 0.9999 |
| CLIP ViT-B/32 | 85 MB | weight-only int8 (w8) | cosine 0.9992 |
| Caption decoder | 26.8 MB | weight-only int8 (w8) | cosine 0.99998 |

Total ~131 MB. Bigger than I wanted, but every attempt to shrink CLIP further cost real accuracy, and the caption feature is the heart of the app, so I kept it. (The final files in the repo are `yolo11n_w8a32.tflite`, `midas_small_w8.tflite`, `clip_vision_w8.tflite`, `caption_decoder_w8.tflite` — the w8 suffix is the weight-only int8 recipe name from `ai-edge-quantizer`.)

**The lesson that repeated itself all through this project: a quantized model that verifies correctly in Python can still be broken on-device. Verification tells you the math survived; only running it on the phone tells you the runtime survived.**

---

## 5. The Android app (Phase 2)

Tech stack: **Kotlin**, CameraX for the frame stream, TensorFlow Lite (CPU interpreter) for all four models, ML Kit for OCR, and Android's built-in TTS and SpeechRecognizer.

I structured the code so that every model is wrapped in its own class that owns its preprocessing and postprocessing (`YoloDetector`, `MiDaSDepth`, `ClipEncoder`, `CaptionDecoder`, `OcrEngine`), all managed by a `ModelManager`. The voice side is separate (`TtsSpeaker` with a priority queue so urgent obstacle warnings interrupt captions, `SpeechListener`, `CommandRouter`). The obstacle loop runs every 1.5 s in a coroutine and has per-tier cooldowns so it does not spam "chair ahead, chair ahead, chair ahead" — that detail alone took a while, because a talking app that never shuts up is unusable.

One non-obvious detail worth recording: TFLite **mmaps** model files directly from assets, so the Gradle config has a `noCompress` list for `tflite`/`json`/`txt`. Compressed model assets break inference in a way that looks like random garbage output.

## 6. The bug war

The app went through roughly four rounds of "it compiles but does not work". I am listing the ones that taught me something.

**Round 1 — Kotlin does not let you be lazy.** About 40 compile errors across 9 files in the first serious build. Most of it was me writing Python-brained Kotlin: duplicate `companion object`s in one class (Kotlin allows exactly one), smart-cast failures on `var` fields (fixed by making them `val`), calling `suspend` functions outside coroutines, and — the dumbest one — a Gradle `Unresolved reference: util` that was fixed by a single top-level `import java.util.Properties` at the *top of build.gradle.kts*, above the `android {}` block, because script-level accessors do not resolve imports written inside blocks.

**Round 2 — the voice loop.** Fresh install, open app, and the phone starts speaking on repeat: "Ready. Tap the mic or say Hey VisionMate. Ready. Tap the mic..." on infinite loop. Cause: a status announcement that re-triggered itself through a listener chain. Any app for blind users has to talk, but this taught me the inverse rule: *speaking state changes is an event, not a loop*, and you need one owner for "what is currently being said".

**Round 3 — dead buttons and missing models.** Buttons that fired TTS but did nothing else, and features reporting "model not loaded". The models were in the APK all along; the loading order and the AssetFileDescriptor paths were wrong. The `ModelManager` needed to load sequentially on a background thread with clear per-model status (the Settings screen shows ready/not-loaded per model — I added that during this round and it paid off immediately).

**Round 4 — the big one: "ginininininiemmmmmm".** This is the bug that defined V2, so I will describe it properly.

Everything *looked* healthy: app installs, models load, buttons trigger, TTS speaks. But every descriptive feature — describe scene, what is ahead, read text — produced the same garbage: the phone literally saying *"gininininininiemmmmmm"*. The decoder was generating nonsense tokens. Obstacle warnings (YOLO + MiDaS) were fine, which told me the bug was in the captioning path specifically, not in the camera, TTS, or model loading.

The breakthrough was the Python reference I kept from Phase 1. I could run the *exact same .tflite files* through the *same intended pipeline* in Python and get a proper caption ("A screenshot of a captcha with a captcha code on it."). Same models, same weights — so the models were fine and the Kotlin side was wrong. That narrowed it to four suspects:

1. Image preprocessing before CLIP (resize, interpolation, normalization — any mismatch shifts the whole embedding space)
2. Embedding post-processing between CLIP and the decoder (projection / normalization — get this wrong and the decoder is conditioned on garbage, and a decoder conditioned on garbage does exactly one thing: repeats low-frequency tokens, which is exactly what "gininini..." is)
3. The greedy autoregressive loop (feeding tokens off by one, or a wrong attention mask, produces fluent nonsense)
4. The BPE tokenizer in Kotlin (a vocab/merges mismatch decodes valid tokens into wrong letters)

I bisected it by making the Kotlin side print its intermediate tensors and comparing numbers against the Python run for the same photo. Long story short: the Python chain and the app chain diverged in the pre-decoder stage — once the Kotlin pipeline was made to match the reference exactly (same preprocessing, same tensor shapes, same token-feeding loop, tokenizer clamped to the real vocab size), the captions came out correct. A related bug hiding underneath: the decoder's output head produces 4004 logits while the tokenizer has 4000 entries, so the argmax has to be clamped to the tokenizer's vocabulary, otherwise the loop can pick an un-decodable token index and derail.

I also re-verified "read text" during this round: it uses ML Kit OCR directly and speaks the recognized text; it was never routed through the caption decoder. The reason it spoke garbage earlier was a fallback that kicked in when the captioning path failed. Fixing the caption path fixed it everywhere.

That whole episode is why V2 exists as a version. V1 proved the plumbing; V2 is where inference actually matched the reference implementation. (Version history, for the record: v1 → v2.0 → v2.2.0 shipped here, `versionCode 4` — the version counter alone shows how many fix rounds this took.) The checklist I would give anyone doing this: **keep a known-good reference runner of your models, and diff intermediate tensors, not just final outputs.** Final output comparison tells you *that* it is broken; intermediate tensors tell you *where*.

## 7. Voice control details

- **Triggers**: the mic button (big, bottom-center) and **either volume key** — pressing a volume key through a pocket is the fastest hands-free trigger there is. The volume keys are intercepted in `onKeyDown` so they start listening without also changing the ringer volume.
- **Commands**: `CommandRouter` matches English, Hindi and Hinglish ("describe what is in front of me" / "aage kya hai" / "aage kya hai batao"). Unmatched speech gets a spoken help prompt instead of silence.
- **TTS priority**: obstacle warnings preempt captions, and status messages are lowest priority. A navigation app where "stop, person ahead" waits politely for a paragraph about the weather is a dangerous app.

A note on the wake word: an earlier version had "Hey VisionMate" detection via Porcupine, fully on-device. I ended up **dropping it in the final v2.2.0**, and it was a deliberate trade-off, not a failure: it required every user to register a Picovoice AccessKey, manage its monthly quota, and ship a keyword file — real friction for a college project people should just be able to clone and run. The mic button and volume-key triggers work with zero setup and are honestly faster to use. If I ever revive it, the plan is a self-contained small model, not a keyed SDK.

## 8. What I would do differently

- I would test on more than one phone before calling anything "done". Mid-range Realme was my only real test device.
- I would add logging + a debug screen from day one. The tensor-diff debugging in Round 4 would have taken hours instead of days.
- I would look at GPU delegate support per-model instead of running everything on CPU. The CLIP encoder especially could be faster.
- Distance estimation from monocular depth is rough. Real fix would be knowing camera intrinsics per-device, not assuming an average one.

## 9. What is still left

- **whisper.cpp for offline STT** — currently speech recognition uses Android's SpeechRecognizer (which may be online depending on the device). Fully-offline STT is the last piece of the "works in a basement" promise.
- More field testing with actual blind users — the app is built for them and they are the only real judges.
- A quantization-aware trained decoder (train with the quantization in the loop) to claw back some caption quality.

## 10. Things I learned, in one list

- An on-device ML project is 20% ML and 80% plumbing (camera rotation, bitmap formats, threading, TTS queues).
- fp16 models can pass every Python check and still hard-crash the TFLite CPU delegate. Always test the deployed runtime.
- Verify every quantized model numerically (cosine/Pearson/IoU) — and *then* still expect surprises on-device.
- For a talking app, mute discipline is a feature: cooldowns, priorities, and speaking changes-of-state only.
- A known-good reference runner is the best debugging tool I had. When output is garbage, diff tensors stage by stage; the first stage where numbers diverge is your bug.
- VizWiz photos look like blind-user photos because they *are* blind-user photos. Dataset choice was as important as architecture.

---

*Built by Ankush Jha, IIT Patna. MIT licensed — see [LICENSE](LICENSE). Main docs: [README](README.md) · [MODELS_SETUP.md](MODELS_SETUP.md)*
