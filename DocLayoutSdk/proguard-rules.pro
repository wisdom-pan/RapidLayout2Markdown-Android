# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# Keep SDK classes
-keep class com.benjaminwan.doclayoutsdk.** { *; }

# Keep OcrLibrary classes
-keep class com.benjaminwan.ocrlibrary.** { *; }

# Keep model classes
-keep class com.benjaminwan.ocrlibrary.LayoutBox { *; }
-keep class com.benjaminwan.ocrlibrary.LayoutResult { *; }
-keep class com.benjaminwan.ocrlibrary.OcrResult { *; }

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
