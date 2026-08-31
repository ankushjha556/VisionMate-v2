# Add project specific ProGuard rules here.
# Keep TFLite + Porcupine native/JNI interfaces intact.
-keep class ai.picovoice.porcupine.** { *; }
-keep class org.tensorflow.lite.** { *; }
-dontwarn ai.picovoice.porcupine.**

