# Keep stack traces readable in bug reports.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable

# The app itself is small; keep it whole so nothing reflective gets stripped.
-keep class io.github.drtron1c.cloudphotos.** { *; }

-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# apksig reads its ASN.1 structures through reflection on annotated fields.
-keep class com.android.apksig.** { *; }
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault,Signature,InnerClasses,EnclosingMethod
-dontwarn com.android.apksig.**
-dontwarn javax.annotation.**

# WireGuard (JNI into the Go backend) and ONNX Runtime (JNI).
-keep class com.wireguard.** { *; }
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**
