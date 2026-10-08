# DSP/JNI entry points are referenced by JNI names. Keep them intact.
-keep class com.example.peq.PeqNative { *; }
-keep class com.example.peq.WidenerNative { *; }
# ONNX Runtime for Android uses reflective/native bindings. Keep its Java API
# classes intact for any future minified production build; without this rule
# release shrinking can cause runtime initialization/inference failures.
-keep class ai.onnxruntime.** { *; }
