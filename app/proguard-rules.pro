# Default AGP / Hilt / Compose consumer rules are sufficient.

# ML Kit registers its detectors through ComponentRegistrar classes instantiated by reflection
# (no-arg constructor): without this, R8 full mode strips the constructors and face / pose
# detection (target tracking) is unavailable in release builds.
-keep class * implements com.google.firebase.components.ComponentRegistrar { public <init>(); }

# ML Kit pose detection runs MediaPipe, whose native code registers its JNI methods by class
# and method NAME (RegisterNatives): renamed by R8, the library aborts the whole process at load
# (SIGABRT "Failed during native method registration", seen on a release build mid-recording).
-keep class com.google.mediapipe.** { *; }
-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }
