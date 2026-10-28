# Keep stack traces readable in bug reports.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable

# The app itself is small; keep it whole so nothing reflective gets stripped.
-keep class io.github.drtron1c.cloudphotos.** { *; }

-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
