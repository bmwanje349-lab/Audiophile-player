#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
GRADLE_BIN="${GRADLE_BIN:-$ROOT/gradlew}"

echo "[1] Stale LiteRT/TFLite reference check"
if grep -RInE   'com\.google\.ai\.edge\.litert|ai_edge_litert|\.tflite|LiteRT|TensorBuffer|CompiledModel'   app --exclude-dir=build; then
  echo "ERROR: stale LiteRT/TFLite production reference found under app/"
  exit 1
fi
echo "Production source is ONNX-only"

echo "[2] JVM streaming regression tests"
"$GRADLE_BIN" :app:testDebugUnitTest   --console=plain   --stacktrace   --no-daemon

echo "[3] Android debug build"
"$GRADLE_BIN" :app:assembleDebug   --console=plain   --stacktrace   --no-daemon

echo "[4] Exact model metadata"
grep -RIn   'UVR_MDXNET_9482\.onnx\|f4f365207c56deb115bceedff3ad8fe98a751c745f9e370cecec6226b8b47184'   app/src/main/java/com/bmwanje/audiophile/vocalremover   | head -20

echo "LOCAL ONNX MDX CHECKS PASSED"
echo "BOUNDED STREAMING REGRESSION CHECKS PASSED"
