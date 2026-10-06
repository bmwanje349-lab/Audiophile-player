# UI redesign — Midnight Audiophile

Applied in the Android project as the 0.3 UI revision.

## Main shell

- Premium dark "Midnight Audiophile" visual language.
- Near-black background, graphite surfaces, soft white text, muted gray secondary text, cyan-blue accent.
- Bottom navigation is now Home / Library / Sound / Settings.
- Full Now Playing is opened from the persistent mini-player instead of occupying a permanent bottom-nav slot.
- The mini-player is hidden while the full Now Playing page is open.

## Home

- Time-based greeting.
- Continue listening card.
- Recently added local tracks.
- Compact DSP-engine explanation.

## Library

- Cleaner music header with Rescan action.
- Track count/status.
- More restrained track cards and persistent mini-player.

## Now Playing

- Larger album-art area.
- Cleaner title/artist hierarchy.
- Accent seek controls.
- Previous / play / next.
- Shuffle / repeat / queue.
- Direct Open Sound action.
- Playback-engine information.

## Sound

- Sound is a dedicated destination rather than a giant mixed settings page.
- Graphic Equalizer, Parametric Equalizer, and Stereo Widener are separate processors with separate screens.
- Sound hub shows a visual preview of each processor and the processing order.

## Graphic EQ

- Dedicated 10-band presentation.
- Presets and flat reset.
- Response graph retained.
- Fixed Q / frequency architecture remains in the DSP layer.

## Parametric EQ

- Compact band cards.
- Per-band enable.
- Filter type, frequency, gain and Q.
- Automatic curve makeup and optional linear-phase FIR remain visible as advanced processing options.

## Stereo Widener

- Core controls emphasize width and effect mix.
- Bass protection is presented as a musical control rather than exposing internal M/S/correlation engineering.
- Crossovers, Haas, output gain, limiter ceiling and automatic level matching are grouped under Advanced.
- DSP latency remains visible as information, not as a primary control.

## Settings

Settings now describe app behavior and system-level audio behavior instead of duplicating the actual sound-shaping controls.

Sections:

- Playback
- Library
- Audio & Output
- DSP
- Appearance
- Notifications
- About

## Preservation

The redesign changes the presentation and navigation around the existing player/DSP implementation. The PEQ/GEQ and v10 native DSP source files were not replaced by a simpler DSP implementation.

## Verification note

No Android SDK/Gradle installation is available in this environment, so an APK build was not claimed. Kotlin source parsing checks were performed, and XML resources were parsed successfully. The final archive is intended for Android Studio with the project's Android SDK/NDK/CMake requirements installed.
