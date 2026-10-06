# Native DSP smoke test

`v10_smoke.cpp` is a standalone development smoke test for the Professional Stereo Widener DSP.
It is not part of the Android app target.

Example host compile (adapt include path to your environment):

```bash
clang++ -std=c++17 -Wall -Wextra -Wpedantic -fno-fast-math \
  -fsanitize=address,undefined \
  tools/native_smoke/v10_smoke.cpp -o v10_smoke
```
