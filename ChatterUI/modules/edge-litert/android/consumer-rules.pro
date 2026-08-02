# Release builds run ProGuard (enableProguardInReleaseBuilds in app.config.js). LiteRT-LM reaches
# its classes from native code, so nothing here may be renamed or stripped.
-keep class com.google.ai.edge.litertlm.** { *; }
-keep class com.saturnmask.edge.distilled.engine.** { *; }
-keep class com.saturnmask.gallery.data.** { *; }
-keep class com.saturnmask.gallery.runtime.** { *; }
