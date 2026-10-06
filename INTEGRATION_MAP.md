# Seamless Audiophile Player integration map — MDX revision

## 1. Sound hub

Keep the existing Sound/DSP hierarchy unchanged. Add the AI Vocal Remover entry after Stereo Widener.

## 2. Dedicated screen

Use the supplied `VocalRemoverUi` programmatic screen. It owns model preparation and render-to-copy controls. It must not mutate the live `DspRuntime`.

## 3. Neural path

`Current track -> decode PCM -> MDX-Net -> PremiumVocalRemoverDSP -> write instrumental copy -> Media3`

Default neural backend: `LIGHT_9482`.

Optional higher-quality backend: `VOC_FT`.

## 4. Existing DSP isolation

No edits were made to:

- `PremiumVocalRemoverDSP.h`
- `native_vocal_remover.cpp`
- existing player EQ DSP
- existing stereo-widener DSP

The MDX layer only replaces the neural separator that feeds the already-repaired premium vocal DSP.

## 5. Long tracks

The supplied `MdxSeparatorCore` uses fixed-size model chunks and 10% crossfade. The present end-to-end orchestration is still whole-buffer. A future streaming render path can be added without changing the model contract or native premium DSP.

## 6. Cache

Cache processed instrumental copies using source-media-id + model-profile + settings hash. Leave the original media object untouched.
