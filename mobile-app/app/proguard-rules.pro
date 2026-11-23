# Keep kotlinx.serialization serializers
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class dev.wimmboo.omni.**$$serializer { *; }
-keepclassmembers class dev.wimmboo.omni.** {
    *** Companion;
}
-keepclasseswithmembers class dev.wimmboo.omni.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Room entities and DAOs
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao class *
-dontwarn androidx.room.paging.**

# WebView
-keepclassmembers class * extends android.webkit.WebViewClient {
    public void *(android.webkit.WebView, java.lang.String, android.graphics.Bitmap);
    public boolean *(android.webkit.WebView, android.webkit.WebResourceRequest);
    public boolean *(android.webkit.WebView, java.lang.String);
}

# OkHttp / Ktor / Coroutines
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn io.ktor.**
-dontwarn org.slf4j.**
-dontwarn kotlinx.coroutines.**
