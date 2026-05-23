# Phase 0 — minify off; rules will grow as native/protocol code lands.

# Keep JNI-bound native bridge intact regardless of minification state.
-keep class io.github.tffinder.mirrocast.NativeBridge { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
