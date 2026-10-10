# Audiophile-player: loudness, widener, vocal remover fixes

## Root causes found
1. **Low volume** – the widener's true-peak limiter assumed the ceiling could fall to -15 dB at any moment, so its real ceiling was ~-7 dBFS (a -1 dBFS peak came out ~7 dB lower). On top of that: 16-bit pipeline between stages, stacked ceilings (-1 dB, +0.25/0.1 dB safety margins on several limiters), and no gain/loudness stage at all.
2. **Widener did nothing >100%** – extra width was made only from the *side* signal, so near-mono material (most vocals/instruments) had nothing to expand; expansion was also gated down and auto-level fought it.
3. **Vocal remover "not live", vocals remain** – the only quick path was a threshold mask; the neural path needs 5–24 s start-up and often falls back permanently to that weak mask.

## What changed (all under app/src/main)
- `cpp/ProfessionalStereoWidenerDSP_v10.h`
  - limiter ceiling bug fixed (no more -7 dB loss)
  - widener now decorrelates the **mid** (all-pass chains) and injects it into the side field + boosts existing side. Mono sum is unchanged (energy lands in S). Host test, near-mono noise: L/R correlation 0.999 → 0.69 (150%) → 0.15 (200%) → -0.25 (250%).
  - auto-level range -2/+1 dB (was ±1.5 and fought the widening)
- `cpp/PremiumVocalRemoverDSP.h` – new streaming Wiener-style centre extractor with harmonic/percussive protection, band weighting, 2048/512 STFT, look-ahead smoothing, bit-exact bypass at depth 0, 4224-sample latency. Real clip: vocal -11.7 dB, accompaniment -2.3 dB, SDR vs MDX-Net instrumental 8.3 dB (old fallback: 4.6 dB SDR, accompaniment -6.9 dB).
- `java/com/example/audio/LoudnessProcessor.kt` (new) – `ToFloatProcessor` (everything float), `LoudnessProcessor`: preamp, K-weighted smart loudness (lifts quiet songs to target, up to max boost, headroom-aware), look-ahead brick-wall limiter with proven ceiling (JVM test: never exceeded, even at +12 dB preamp / 3x clipped input).
- `PlaybackService.kt` – float output; chain = ToFloat → PEQ → Widener → Loudness.
- `EqSettingsStore.kt` – ceilings -0.3 dB, safety 0.05 dB (one-time migration for existing installs).
- `DspActivity.kt` – "Loudness / Power" section (preamp ±12 dB, smart loudness, target, max boost, ceiling); width slider to 250%.
- `livekaraoke/*` – Live Karaoke now starts instantly with the new DSP separator (truly live, 1 s buffer); an "AI mode" switch selects the MDX-Net neural path (better, slow start). Default ceiling -0.5 dB.
- `NO_DSP_TAMPERING.md` – new hashes.
- `host_tests/` – the host harnesses used to measure the above (optional).

## Not done / honest caveats
- Nothing was run on a phone and the Android SDK was unreachable here, so Kotlin was only checked against stubs (loudness processor) – let CI be the compile check and listen on a device.
- DSP centre-cancel has a physical limit (~8 dB oracle on the test clip, 11.7 dB vocal attenuation here on the weighted result); for near-total removal use AI mode.
- The dynamic-time-axis MDX model (faster neural live) and a seamless DSP→AI hand-off were prototyped but not shipped.
- Root-level `ProfessionalStereoWidenerDSP_v10.h` is a stale duplicate (not built); delete it.
