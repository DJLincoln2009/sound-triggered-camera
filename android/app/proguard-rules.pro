# Conserve les classes TensorFlow Lite (chargées par réflexion).
-keep class org.tensorflow.** { *; }
-dontwarn org.tensorflow.**

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
