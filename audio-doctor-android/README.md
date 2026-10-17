# Audio Doctor Studio — standalone Android app

This is an independent Android Gradle project in audio-doctor-android/. Its Android application ID is com.example.audiodoctor. The APK is built only from this directory; it does not depend on, package, launch, or link any Audiophile Player activities, code, native libraries, model files, or resources.

## What it does

- Opens the Android system audio picker so you can select one song at a time.
- Decodes the selected track using Android MediaExtractor / MediaCodec.
- Draws a waveform overview and a frequency spectrum.
- Computes sample peak, RMS level, crest factor, near-full-scale sample counts, flat-top indicators, DC offset, leading/trailing silence, short-term level spread, stereo correlation, mono-sum level change, estimated tempo, and a heuristic musical-key estimate.
- Estimates 4x interpolated peak and EBU-style K-weighted integrated loudness. These are labeled estimates rather than certified mastering measurements.
- Displays source properties/tags where Android exposes them and exports a JSON report with measurement limitations.
- Uses streaming decode and fixed-size waveform/spectrum buffers instead of retaining a whole song in memory.
- Requires no internet permission and sends no music to any service.

## Build from GitHub Actions

Open Actions, choose Audio Doctor Studio APK, and run the workflow on branch standalone-audio-doctor-android, or push a change under audio-doctor-android/. The workflow builds this Gradle project and uploads audio-doctor-studio-debug-apk as a downloadable artifact. The APK is an installable debug build; it is not a ZIP of the entire source repository.

## Build locally

Use JDK 17, Android SDK Platform 35, Build Tools 35.0.0, and Gradle 8.9:

    gradle -p audio-doctor-android assembleDebug
    gradle -p audio-doctor-android testDebugUnitTest

Output:

    audio-doctor-android/app/build/outputs/apk/debug/app-debug.apk

## Important accuracy notes

The Python example shared in chat ended at the NumPy dependency check; its analysis functions were not included. This Android implementation follows the checks described in that header and reimplements them natively so the installed app remains compact and offline.

Android's built-in decoders are used instead of bundling FFmpeg binaries or neural models. Integrated LUFS uses an EBU-style K-weighting/gating approximation, while true peak is a four-times linear interpolation estimate. These should not be presented as certified BS.1770 / ITU-R BS.1770 compliant mastering readings. Tempo and key are heuristic estimates. Clipping reconstruction and audio repair are not included in this analysis-only app.

The source directory lives inside the existing GitHub repository because the available GitHub connection can write to existing repositories but cannot create new repositories. It is a separate Gradle project and produces a separate APK with no Audiophile Player dependency or code packaged in the APK.
