# DSP integrity check

The premium vocal-removal DSP was not altered in the MDX replacement.

Verified SHA-256:

- `PremiumVocalRemoverDSP.h`: `bb59b94169e974bd02dcd011228775862340b6ee07487790e01c3d6cb05c1cb7`
- `native_vocal_remover.cpp`: `416a52b1cdb0182b5b88a82e892b5014798f6f8483e43a6e6211d9c4a09dac0b`
- `ProfessionalStereoWidenerDSP_v10.h`: `46da8a0ace5e2e47153f0341bab5e521f9f0206c1138dba6e3df7672b987387b`

The app's existing EQ and widener libraries are outside this package and must remain separate targets. The MDX backend does not replace or wrap them.
