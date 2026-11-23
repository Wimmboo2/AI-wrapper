# Keep kotlinx.serialization serializers
-keepattributes *Annotation*, InnerClasses
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

# OkHttp / Ktor
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn io.ktor.**
-dontwarn org.slf4j.**
