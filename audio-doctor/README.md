# Audio Doctor Studio

A local-first analysis and repair workspace built around the Audio Doctor Python/FFmpeg engine. It lives in its own folder so it does not alter Audiophile Player's Android build.

## Features

- Responsive installable dashboard with waveform preview, spectrum chart, sample peak and RMS meters, crest factor, DC offset, clipping indicators, edge silence, channel correlation, mono-sum change, BPM estimate, and key estimate.
- Python engine with FFprobe/FFmpeg/NumPy measurements, decode diagnostics, loudness and true-peak measurement, tags/cover information, clipping/DC/silence checks, spectral-cliff heuristic, and before/after JSON reports.
- Best-effort clipping reconstruction if FFmpeg has adeclip; 10 Hz high-pass for measurable DC/subsonic offset; cautious edge-silence trimming with fades; EBU R128 normalization; 320 kbps MP3 or FLAC output.
- Local-only HTTP bridge. Starting it lets the dashboard call the Python engine and download a ZIP with repaired audio plus the full before/after JSON report.
- GitHub Actions unit/integration checks and a Pages deployment workflow for the browser-only dashboard.

## About the supplied source snippet

The code shared in chat ended after its docstring, imports, and NumPy dependency check; the actual analysis and repair functions were not included. This folder implements the behavior described in that docstring instead of claiming unseen functions were preserved.

## Run locally

Requirements: Python 3.10+, FFmpeg + FFprobe, and NumPy.

    cd audio-doctor
    python -m pip install -r requirements.txt
    python server.py

Open http://127.0.0.1:8765. The server binds to loopback only. Uploaded audio is processed locally and temporary files are removed after each request. Do not expose this server to the public internet.

## CLI

Analyze without writing audio:

    python audio_doctor.py song.mp3 --analyze-only --report song.analysis.json

Repair and create a before/after report:

    python audio_doctor.py song.mp3 --artist "Fik Fameica" --title "Sikiwa"

Choose FLAC and a -16 LUFS target:

    python audio_doctor.py song.mp3 --format flac --lufs -16 --true-peak -1.5 --write-bpm

Embed cover art:

    python audio_doctor.py song.mp3 --cover cover.jpg

Output defaults to <name>_fixed.mp3 or <name>_fixed.flac with a sidecar JSON report.

## Browser-only / Pages mode

The hosted dashboard can preview and analyze supported files entirely in the browser, without uploading them. Its displayed loudness is an RMS proxy, not LUFS. Deep FFmpeg/EBU R128 analysis and actual repair require the local server above.

## Limitations

- FFmpeg loudnorm performs LUFS/true-peak measurement and normalization when available.
- Loudness normalization changes level; it does not automatically improve the mix or reverse clipping.
- adeclip is best effort, not guaranteed reconstruction.
- FLAC and 320 kbps MP3 cannot recreate detail lost during an earlier lossy encode.
- BPM, key and spectral-cutoff estimates are heuristic; mono-sum/correlation are warning indicators, not complete mix diagnostics.
- The PCM memory guard can reject long/high-resolution files rather than risk an out-of-memory crash.

## Tests

    python -m unittest discover -s tests -v
