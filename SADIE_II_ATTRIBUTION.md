# SADIE II D1 spatial-audio data attribution

The immersion engine packages a modified subset of the SADIE II Database, measured by
AudioLab, Department of Electronic Engineering, University of York, United Kingdom.
The selected listener is D1 (KU100). Original source record:
https://doi.org/10.5281/zenodo.10886409

The original dataset is licensed under Apache License, Version 2.0:
https://www.apache.org/licenses/LICENSE-2.0
The dataset maintainers require that the original dataset be referenced whenever it is
used in original or modified form. Academic reference: https://doi.org/10.3390/app8112029.

The build script verifies these source archive MD5 checksums:
- D1_HRIR_SOFA.zip: 4850c1eb8e63e2d4f605edcdb4d5c883
- D1_BRIR_SOFA.zip: 7725508069e99fb8f519d452c156fdb3

The runtime asset generator preserves all 8,802 HRIR source positions and converts the
48 kHz HRIR coefficients to signed 16-bit FIR samples. It selects the measured D1 BRIR
responses at azimuth +45 and -45 degrees, elevation 0, preserves the 48 kHz room-tail
samples as float32, and zeroes the first 256 samples (5.33 ms) so the separate HRIR
stage supplies the direct path while the BRIR convolution supplies room reflections.
