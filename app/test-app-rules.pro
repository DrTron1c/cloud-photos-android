# Only for the "releaseTest" build that on-device tests run against: the test framework
# needs Kotlin library code the shrunk release app doesn't use itself.
-keep class kotlin.** { *; }
-dontwarn kotlin.**
-keep class androidx.tracing.** { *; }
-keep class androidx.concurrent.** { *; }
-keep class androidx.lifecycle.** { *; }
-keep class androidx.core.** { *; }
-keep class com.google.common.util.concurrent.** { *; }
-keep class androidx.compose.** { *; }
-keep class kotlinx.coroutines.** { *; }
-keep class androidx.activity.** { *; }
# The media tests drive Media3 Transformer directly (making a test video from a picture).
-keep class androidx.media3.** { *; }
# The video-fit test drives the zoom library directly.
-keep class me.saket.telephoto.** { *; }
-keep class pl.droidsonroids.gif.** { *; }
