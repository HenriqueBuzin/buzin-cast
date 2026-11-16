# Keep JNI callback interface (called from native code)
-keep class io.github.jqssun.airplay.bridge.RaopCallbackHandler { *; }
-keep class * implements io.github.jqssun.airplay.bridge.RaopCallbackHandler { *; }
-keep class io.github.jqssun.airplay.bridge.LogListener { *; }
-keep class * implements io.github.jqssun.airplay.bridge.LogListener { *; }

# Keep NativeBridge native methods
-keep class io.github.jqssun.airplay.bridge.NativeBridge { *; }

# Appstore SDK uses reflection and annotations for its internal resources.
# https://developer.amazon.com/docs/in-app-purchasing/iap-obfuscate-the-code.html
-dontwarn com.amazon.**
-keep class com.amazon.** { *; }
-keepattributes *Annotation*
