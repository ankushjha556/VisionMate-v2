# Setup — nothing to do!

All six models ship inside the app (`app/src/main/assets/models/`).
The release APK (`VisionMate-release.apk`, shipped next to the project zip)
contains them too. There is no copy step, no Drive download, no Colab.

## Optional: regenerating or re-quantizing models

The models were produced from your Phase-1 float32 exports with weight-only
int8 quantization (float32 IO — CPU-safe). To reproduce:

```python
pip install ai-edge-quantizer

from ai_edge_quantizer import quantizer

q = quantizer.Quantizer(
    "clip_vision_float32.tflite",   # your Drive export
    "…/ai_edge_quantizer/recipes/dynamic_wi8_afp32_recipe.json",
)
q.quantize(serialize_to_path="clip_vision_w8.tflite")
```

Repeat for `captioning_decoder_float32.tflite` → `caption_decoder_w8.tflite`
and `midas_small_float32.tflite` → `midas_small_w8.tflite`.

## ⚠️ Never use the float16 exports

`*_float16.tflite` from Drive crash TFLite's CPU kernels
(`batch_mat_mul.cc: lhs type not supported`). Only float32-IO variants run
on-device. Details: ../MODELS_SETUP.md.
