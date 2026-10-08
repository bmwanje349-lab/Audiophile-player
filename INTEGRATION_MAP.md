# Seamless Audiophile Player integration map — current production path

## 1. Sound hub

Keep the existing Sound/DSP hierarchy unchanged. The AI Vocal Remover and Live Karaoke entries are separate features; PEQ and Stereo Widener remain independent.

## 2. Dedicated offline screen

Use the supplied programmatic `VocalRemoverUi` screen. It prepares the pinned MDX-Net model on a background executor and renders a separate instrumental WAV copy. It does not mutate the live `DspRuntime`.

## 3. Offline neural path

`Current track -> MediaCodec -> bounded stereo PCM blocks -> MDX-Net 9482 -> PremiumVocalRemoverDSP -> streaming WAV writer -> Media3`

The production neural backend is `LIGHT_9482` from the vendored `UVR_MDXNET_9482.onnx` asset.

## 4. Live Karaoke path

`MediaExtractor/MediaCodec -> bounded decoded-block queue -> 44.1 kHz resampler -> UVR-compatible MDX streaming windows -> ONNX Runtime -> iSTFT -> source-rate restoration -> PremiumVocalRemoverDSP -> bounded PCM queue -> AudioTrack`

Live Karaoke starts only after a bounded startup buffer. Neural throughput is measured before playback, the required safety buffer is calculated once and frozen, and sustained slowdown/thermal pressure can switch to Fast Live.

## 5. Neural-to-Fast handoff

When Fast Live is selected after neural playback has begun, pending source PCM that has already passed through the neural timeline but is still waiting for its matching vocal stem is drained into the Fast processor. The existing AudioTrack and decoder timeline are not restarted.

## 6. Seeking, pause, stop and runner lifecycle

- Pause gates decode/inference as well as AudioTrack playback.
- Seek creates a new session with model-window preroll and reuses the cached ONNX session.
- Stop cancels the session without synchronously destroying the ONNX runner.
- The neural streaming separator/native DSP is owned by the inference worker after submission and is closed only after that worker exits, preventing stop/seek use-after-close races.
- Final ONNX session teardown happens only when the service/engine is closed.

## 7. Existing DSP isolation

The Live/Offline MDX layer feeds the existing PremiumVocalRemoverDSP. The player PEQ and Stereo Widener remain separate native targets and are not replaced by the vocal-remover implementation.

## 8. Bounded memory

The live PCM queue, decoder queue, MDX/STFT scratch buffers, resampler buffers and native alignment queues are all bounded. The production Live and offline streaming paths do not materialize a full-song FloatArray.

## 9. Model variants

The current repository contains one production neural separator: MDX-Net 9482. An active HT-Demucs model/runtime is not currently present in the Gradle module or APK asset set, so the project does not falsely expose an unimplemented HT-Demucs option.