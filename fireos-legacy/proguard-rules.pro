# Appstore SDK internal resources are discovered through annotations/reflection.
# https://developer.amazon.com/docs/in-app-purchasing/iap-obfuscate-the-code.html
-dontwarn com.amazon.**
-keep class com.amazon.** { *; }
-keepattributes *Annotation*
