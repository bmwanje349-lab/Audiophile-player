#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
ANDROID_JAVA="$ROOT/android/src/main/java/com/bmwanje/audiophile/vocalremover"
STUBS="$ROOT/stubs"
TEST="$ROOT/tests"
OUT="$ROOT/tests/build"
rm -rf "$OUT"
mkdir -p "$OUT"

printf '\n[1] Existing separator/chunk/streaming regression\n'
kotlinc "$ANDROID_JAVA/VocalSeparatorCore.kt" \
  "$TEST/CoreTest.kt" "$TEST/ChunkEdgeTest.kt" "$TEST/StreamingSeparatorTest.kt" \
  -include-runtime -d "$OUT/core-tests.jar"
java -cp "$OUT/core-tests.jar" test.CoreTestKt
java -cp "$OUT/core-tests.jar" test.ChunkEdgeTestKt
java -cp "$OUT/core-tests.jar" com.bmwanje.audiophile.vocalremover.StreamingSeparatorTestKt

printf '\n[2] MDX production source compile against API stubs\n'
kotlinc \
  "$STUBS/android/content/Context.kt" \
  "$STUBS/com/google/ai/edge/litert/Stubs.kt" \
  "$STUBS/org/jtransforms/fft/FloatFFT_1D.kt" \
  "$ANDROID_JAVA/MdxModelSpec.kt" \
  "$ANDROID_JAVA/MdxStft.kt" \
  "$ANDROID_JAVA/MdxLiteRtVocalModelRunner.kt" \
  "$ANDROID_JAVA/MdxModelManager.kt" \
  "$ANDROID_JAVA/MdxSeparatorCore.kt" \
  "$ANDROID_JAVA/VocalSeparatorCore.kt" \
  "$ANDROID_JAVA/NativeVocalRemover.kt" \
  "$ANDROID_JAVA/VocalRemoverPipeline.kt" \
  -d "$OUT/mdx-source.jar"
printf 'MDX Kotlin compile: PASS\n'

printf '\n[3] MDX host-DSP numerical reference\n'
python3 "$ROOT/tools/mdx_math_test.py"

printf '\n[4] CMake/native compile\n'
rm -rf "$OUT/cmake"
cmake -S "$ROOT/android/src/main/cpp" -B "$OUT/cmake" \
  -DCMAKE_CXX_FLAGS='-I/usr/lib/jvm/java-21-openjdk-amd64/include -I/usr/lib/jvm/java-21-openjdk-amd64/include/linux' >/dev/null
cmake --build "$OUT/cmake" -j2
cp "$OUT/cmake/libaudiophile_vocal_remover.so" "$TEST/libaudiophile_vocal_remover.so"

printf '\n[5] Native JNI + neural behavior + full chain + multi-rate regression\n'
kotlinc "$ANDROID_JAVA/VocalSeparatorCore.kt" "$ANDROID_JAVA/NativeVocalRemover.kt" \
  "$TEST/NativeJniTest.kt" "$TEST/NeuralBehaviorTest.kt" "$TEST/FullChainTest.kt" \
  "$TEST/NativeRegressionTest.kt" -include-runtime -d "$OUT/native-tests.jar"
java -Djava.library.path="$TEST" -cp "$OUT/native-tests.jar" com.bmwanje.audiophile.vocalremover.NativeJniTestKt
java -Djava.library.path="$TEST" -cp "$OUT/native-tests.jar" com.bmwanje.audiophile.vocalremover.NeuralBehaviorTestKt
java -Djava.library.path="$TEST" -cp "$OUT/native-tests.jar" com.bmwanje.audiophile.vocalremover.FullChainTestKt
java -Djava.library.path="$TEST" -cp "$OUT/native-tests.jar" com.bmwanje.audiophile.vocalremover.NativeRegressionTestKt

printf '\n[6] ASan/UBSan JNI regression\n'
g++ -std=c++17 -O1 -g -fno-omit-frame-pointer \
  -fsanitize=address,undefined -Wall -Wextra -Wpedantic -Wconversion -Wsign-conversion -Wshadow \
  -I/usr/lib/jvm/java-21-openjdk-amd64/include -I/usr/lib/jvm/java-21-openjdk-amd64/include/linux \
  -I"$ROOT/android/src/main/cpp" "$ROOT/android/src/main/cpp/native_vocal_remover.cpp" \
  -shared -fPIC -o "$OUT/libaudiophile_vocal_remover_san.so"
cp "$OUT/libaudiophile_vocal_remover_san.so" "$TEST/libaudiophile_vocal_remover.so"
ASAN=$(gcc -print-file-name=libasan.so)
LD_PRELOAD="$ASAN" ASAN_OPTIONS=detect_leaks=0:halt_on_error=1 UBSAN_OPTIONS=halt_on_error=1 \
  java -Djava.library.path="$TEST" -cp "$OUT/native-tests.jar" com.bmwanje.audiophile.vocalremover.NativeRegressionTestKt

printf '\n[7] ThreadSanitizer setter/process stress\n'
g++ -std=c++17 -O1 -g -fno-omit-frame-pointer -fsanitize=thread \
  -Wall -Wextra -Wpedantic -I"$ROOT/android/src/main/cpp" "$TEST/ThreadStress.cpp" \
  -o "$OUT/thread_stress_tsan"
TSAN_OPTIONS=halt_on_error=1 "$OUT/thread_stress_tsan"

printf '\nALL LOCAL MDX + EXISTING DSP TESTS PASSED\n'
