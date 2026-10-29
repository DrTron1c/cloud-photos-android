# Only for the "releaseTest" build that on-device tests run against: the test framework
# needs Kotlin library code the shrunk release app doesn't use itself.
-keep class kotlin.** { *; }
-dontwarn kotlin.**
