-keep class com.aho.streambrowser.** { *; }
-keep class com.google.android.exoplayer2.** { *; }
-dontwarn com.google.android.exoplayer2.**

# Keep JS bridge methods
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
