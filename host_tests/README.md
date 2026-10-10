# Host-side DSP checks

These standalone checks are intentionally kept outside Android's source sets.

## Stereo widener

From the repository root, compile the C++ harness against the checked-in DSP header:

```sh
g++ -std=c++17 -O2 -Wall -Wextra -Wpedantic -fno-fast-math -Iapp/src/main/cpp host_tests/widener_host_test.cpp -o /tmp/audiophile-widener-test
/tmp/audiophile-widener-test
```

## Loudness processor

With `kotlinc` installed, compile the lightweight Android/Media3 stubs together with the real processor and the loudness harness:

```sh
kotlinc host_tests/s1.kt host_tests/s2.kt host_tests/s3.kt host_tests/s4.kt app/src/main/java/com/example/audio/LoudnessProcessor.kt host_tests/loudness_host_test.kt -include-runtime -d /tmp/audiophile-loudness-test.jar
java -jar /tmp/audiophile-loudness-test.jar
```

The widener include uses a repository-relative header name; the previous environment-specific `/home/claude/...` path has been removed.