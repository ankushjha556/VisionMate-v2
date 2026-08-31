# MODELS_SETUP.md — the model files, and how to regenerate or replace them

VisionMate runs **all inference on-device**. The six model files live in
`app/src/main/assets/models/` and are **committed to this repo**, so a fresh
clone builds and runs as-is. Nothing downloads at runtime, no account needed.
There is no setup step — the short version lives in [`setup/README.md`](setup/README.md).

## Files in app/src/main/assets/models/

| File | What it is | Size |
|---|---|---|
| `yolo11n_w8a32.tflite` | YOLOv11n object detection, dynamic-range int8 weights, float32 IO, 320×320 | 2.9 MB |
| `midas_small_w8.tflite` | MiDaS-small relative depth, weight-only int8, float32 IO | 17 MB |
| `clip_vision_w8.tflite` | CLIP ViT-B/32 patch encoder, weight-only int8, float32 IO | 85 MB |
| `caption_decoder_w8.tflite` | PatchCaptionDecoder — trained on VizWiz (my trained artifact), weight-only int8, float32 IO | 26.8 MB |
| `tokenizer_vocab.json` | BPE vocab for the decoder | ~50 KB |
| `tokenizer_merges.txt` | BPE merges for the decoder | ~30 KB |

The decoder and tokenizer are the **trained artifacts of this project** — the
part that came out of training on VizWiz. The other three are converted open
models. If you retrain or fine-tune the captioning side, you produce new
versions of the last three files and drop them in.

### Verification numbers (PyTorch vs TFLite, measured during export)

- **YOLO**: matched cars on a real street photo, IoU 0.83–0.93, confidence
  deltas ≤ 0.1. Input range tested both ways: 0..1 reproduces PyTorch,
  0..255 produces garbage → the app feeds 0..1.
- **MiDaS**: Pearson correlation **0.9999** on random-image depth maps;
  inverse-depth ordering preserved.
- **CLIP**: patch features cosine similarity **0.9992** (mean abs diff 0.017).
- **Decoder**: cosine similarity **0.99998** vs the fp32 original after the
  weight-only int8 requant (the first fp16 attempt crashed the TFLite CPU
  delegate — `batch_mat_mul.cc: lhs type not supported` — which is why the
  shipped file is int8-with-float32-IO, see PROJECT.md).

All quantized models keep **float32 input/output**; the Kotlin wrappers never
do manual quantize/dequantize.

## Regenerating the quantized models (optional)

The models were produced from the Phase-1 float32 exports with weight-only
int8 quantization (float32 IO — CPU-safe) using `ai-edge-quantizer`:

```python
pip install ai-edge-quantizer

from ai_edge_quantizer import quantizer

q = quantizer.Quantizer(
    "clip_vision_float32.tflite",   # the float32 export
    ".../ai_edge_quantizer/recipes/dynamic_wi8_afp32_recipe.json",
)
q.quantize(serialize_to_path="clip_vision_w8.tflite")
```

Repeat for `captioning_decoder_float32.tflite` → `caption_decoder_w8.tflite`
and `midas_small_float32.tflite` → `midas_small_w8.tflite`.

The decoder and tokenizer are my trained artifacts — they cannot be
regenerated from public weights, only retrained (training details in
PROJECT.md §3).

## ⚠️ Never use the float16 exports

`*_float16.tflite` exports crash TFLite's CPU kernels
(`batch_mat_mul.cc: lhs type not supported`). Only float32-IO variants run
on-device.

## Input/output contracts (must match the Kotlin wrappers)

| Model | Input | Output |
|---|---|---|
| YOLO | `float32 [1,H,W,3]` NHWC, RGB **0..1** (H=W=320 or 640, auto-detected) | `[1,84,N]` xywh(normalized) + 80 scores |
| MiDaS | `float32 [1,256,256,3]` NHWC, RGB, ImageNet-norm | `[1,256,256]` inverse depth |
| CLIP | `float32 [1,224,224,3]` NHWC, RGB, CLIP-norm | `[1,49,768]` patch feats (CLS stripped) |
| Decoder | `patch_feats float32 [1,49,768]`, `caption_ids int64 [1,25]` | `logits float32 [1,25,4004]` |
