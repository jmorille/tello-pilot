# Default AGP / Hilt / Compose consumer rules are sufficient.

# ML Kit registers its detectors through ComponentRegistrar classes instantiated by reflection
# (no-arg constructor): without this, R8 full mode strips the constructors and face / pose
# detection (target tracking) is unavailable in release builds.
-keep class * implements com.google.firebase.components.ComponentRegistrar { public <init>(); }
