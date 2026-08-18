# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# QQ Connect OpenSDK uses Activity names and callback types from the manifest.
-keep class com.tencent.connect.** { *; }
-keep class com.tencent.open.** { *; }
-keep class com.tencent.tauth.** { *; }

# The lite QQ SDK references OkHttp APIs only from optional code paths.
-dontwarn okhttp3.**

# ML Kit discovers Firebase component registrars by the class names stored in
# manifest metadata, then creates them through their no-argument constructors.
# firebase-components keeps the class names but not those constructors under
# AGP 9/R8 full mode.
-keep class * implements com.google.firebase.components.ComponentRegistrar {
    public <init>();
}
