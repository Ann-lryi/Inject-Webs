# Hilt (generated components are already annotated, but keep entry points)
-keep class dagger.hilt.** { *; }
-keep class * extends androidx.lifecycle.ViewModel { *; }
-keep class com.aho.streambrowser.Hilt_StreamBrowserApp { *; }

# Room
-keep class * extends androidx.room.RoomDatabase { *; }
-dontwarn androidx.room.paging.**

# OkHttp / Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Kotlin coroutines
-keepclassmembernames class kotlinx.coroutines.** { volatile <fields>; }
-dontwarn kotlinx.coroutines.debug.**

# JS bridge methods (called from WebView)
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Parcelable models (StreamItem passes between activities/services)
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}
