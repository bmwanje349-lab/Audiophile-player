# Audiophile Player

Android Studio project for a local music player with a custom PCM DSP chain:

`Media3 decoder → 10-band Graphic EQ → 16-band Parametric EQ → curve makeup → v10 Stereo Widener → audio output`

## UI / visual direction

The app has been redesigned as a conventional premium music player rather than a DSP control panel.

### Midnight Audiophile theme

- Near-black background
- Graphite/charcoal surfaces
- Soft-white primary text
- Muted gray secondary text
- Cool cyan-blue accent
- Rounded, restrained cards and controls
- Minimal animation and no neon/RGB-heavy treatment

### Main navigation

- **Home** — greeting, continue listening, recently added music, and a compact DSP-engine explanation.
- **Library** — local MediaStore music, scan/rescan, track list, and persistent mini-player.
- **Sound** — separate entry point for the three audio processors.
- **Settings** — playback, library, audio/output, DSP, appearance/theme information, notifications, and about.

### Now Playing

The full player opens from the persistent mini-player. It contains:

- Large artwork area
- Track / artist information
- Seek bar and timestamps
- Previous / play / next
- Shuffle / repeat / queue
- Direct **Open Sound** access
- Playback-engine information

The mini-player is hidden on the full Now Playing screen and remains visible on the other app sections.

## Sound architecture

Sound shaping is intentionally kept out of Settings. The Sound area has three separate screens:

### Graphic Equalizer

10 fixed bands:

`31 / 62 / 125 / 250 / 500 / 1k / 2k / 4k / 8k / 16k Hz`

Fixed Q 1.40, ±12 dB, presets, enable/bypass control, and a dedicated response graph.

### Parametric Equalizer

16 independent bands with:

- Bell / shelf / high-pass / low-pass / notch filtering
- Frequency, gain and Q controls
- Per-band enable
- Minimum-phase processing
- Optional 257 / 513 / 1025-tap linear-phase FIR
- Independent curve-makeup control

### Stereo Widener

The professional v10 engine is presented through musical controls rather than exposing its internal engineering details.

Core controls:

- Width
- Effect mix
- Bass mono/protection point

Advanced controls:

- Low/high crossover
- Haas delay and mix
- Output gain
- Limiter ceiling
- Automatic level matching
- Current DSP latency

## DSP

The native sources in `app/src/main/cpp/` contain the corrected PEQ/GEQ engine and the ProfessionalStereoWidenerDSP_v10 integration used by the player service.
The native CMake targets use 16-KB ELF load-segment alignment so the packaged `.so` libraries are prepared for Android devices using 16-KB memory pages.

The playback service uses Media3 `MediaSessionService` so playback and the custom DSP runtime live outside the Activity.

## Verification / build status

The native DSP regression and safety checks used during development passed before packaging. This environment does **not** contain a complete Android SDK/Gradle repository-access environment, so a full Android APK build was not claimed or performed here. The included GitHub Actions workflow provisions JDK 17, Android SDK API 36 (compileSdk 36, targetSdk 35), CMake 3.22.1, NDK r27d, accepts SDK licenses, and runs the Gradle 8.9 wrapper.

The UI source was also passed through Kotlin compiler parsing checks; expected Android/Media3/Material symbols cannot be resolved here because those Android dependencies are not installed in this environment.

Open the project in Android Studio or push it to GitHub. The repository includes the required Gradle Wrapper files; GitHub Actions also provisions the required Android SDK/NDK/CMake toolchain automatically.


Build validation release: 0.3.1.
