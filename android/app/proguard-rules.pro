# Yunus — R8 keep rules for the release (obfuscated) build.
#
# Almost all of the app's own code may be renamed/shrunk freely. Only three things are reached by
# name, so R8 must leave them intact; everything else the app reflects on lives in the car's own
# framework (android.hardware.bydauto.*, android.* services), which is never in this APK and so is
# never renamed.

# 1) The shell helper is launched by app_process by its fully-qualified name:
#    CLASSPATH=<apk> app_process /system/bin app.yunus.launcher.ShellHelper <port> <token>
#    If R8 renamed or removed it (or its main), that launch would fail. Keep it and its nested types.
-keep class app.yunus.launcher.ShellHelper { *; }
-keep class app.yunus.launcher.ShellHelper$** { *; }

# 2) The WebView page calls the native bridge methods by name (YunusNative.*), so keep every method
#    annotated @JavascriptInterface on whatever class carries it.
-keepclasseswithmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# 3) Dynamic-proxy listeners for the BYD HAL interfaces need their signatures/annotations intact.
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod, RuntimeVisibleAnnotations

# Kotlin metadata, so reflection on Kotlin objects/companions keeps working.
-keep class kotlin.Metadata { *; }

# Android components (Activity, Service, NotificationListenerService, …) are kept via the manifest
# automatically; no rule needed here. Library consumer rules (androidx) are applied automatically.
